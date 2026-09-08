package com.sergiy.dev.mockkhttp.control

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sergiy.dev.mockkhttp.agent.AgentAuditLog
import com.sergiy.dev.mockkhttp.agent.AgentFiles
import com.sergiy.dev.mockkhttp.agent.InstanceRegistry
import com.sergiy.dev.mockkhttp.control.dto.AGENT_CONTROL_FULL
import com.sergiy.dev.mockkhttp.control.dto.AGENT_CONTROL_READ_ONLY
import com.sergiy.dev.mockkhttp.control.dto.API_VERSION
import com.sergiy.dev.mockkhttp.store.AgentSettingsStore
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * The agent control plane, ASSEMBLED — socket + router + auth + handlers + instance file + audit.
 *
 * ## Why this suite exists
 *
 * The control plane once shipped in a state where every part of it was implemented, every unit test
 * passed, and the whole thing never ran: nothing called [AgentControlServer.start] and `plugin.xml`
 * registered no listener. A hundred green tests said nothing about that, because each of them tested
 * a part in isolation and the defect was in the *wiring between* the parts.
 *
 * So nothing here builds a [ControlRequest] by hand or calls a handler directly — [ControlApiTest]
 * and [ControlAuthTest] already own that ground. Every assertion below goes through a **real TCP
 * connection to the real listener**, or through the **real file on disk** that the MCP bridge reads.
 * If the socket, the router, the auth layer, a handler, the instance file or the audit hook is not
 * actually connected to the others, one of these must go red.
 *
 * ## Fixture notes
 *
 * - [BasePlatformTestCase] because the classes under test are IntelliJ **application** services and
 *   the request path resolves open projects through `ProjectManager`. The platform test framework is
 *   JUnit 3/4 based, so methods must be named `testXxx` (see `ControlApiTest`).
 * - The agent channel's root (`mockkhttp.home`, see [AgentFiles.HOME_PROPERTY]) is pointed at a temp
 *   directory for the duration of each test — and the Gradle test task roots the whole JVM under
 *   `build/`, so a refresh that runs after a test restored the property still lands there.
 *   [InstanceRegistry] publishes into `<home>/instances/`, and a test suite has no business writing
 *   a bearer token into the developer's real home directory — or pruning the files of the IDE they
 *   have open (audit round 11, AR).
 * - [tearDown] **always** calls [AgentControlServer.stop], including after a failed assertion, so a
 *   red test can never leave an ephemeral port bound for the rest of the JVM run.
 */
class AgentControlWiringTest : BasePlatformTestCase() {

    private companion object {
        /** Mandatory on every request; also what the audit trail must attribute the call to. */
        const val CLIENT_NAME: String = "mockkhttp-wiring-test/1.0"

        const val CONNECT_TIMEOUT_MS: Int = 2_000
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)

        /** [ControlRouter] notifies its call listeners AFTER the response is written; see [awaitAuditEntry]. */
        const val AUDIT_WAIT_MS: Long = 5_000L
    }

    private lateinit var server: AgentControlServer
    private lateinit var registry: InstanceRegistry
    private lateinit var http: HttpClient

    private lateinit var fakeHome: Path
    private var savedHomeProperty: String? = null
    private var savedAgentControl: String = AgentSettingsStore.FULL

    /**
     * Non-null when this JVM cannot open a loopback listener at all, which is the only environment
     * in which skipping is honest. Proved with a plain [ServerSocket] rather than assumed from a
     * failed [AgentControlServer.start] — otherwise "the control plane is broken" and "this machine
     * forbids sockets" would be the same outcome, and the suite would quietly stop testing anything.
     */
    private var loopbackRefusal: String? = null

    override fun setUp() {
        super.setUp()

        savedHomeProperty = System.getProperty(AgentFiles.HOME_PROPERTY)
        fakeHome = Files.createTempDirectory("mockkhttp-wiring-home")
        System.setProperty(AgentFiles.HOME_PROPERTY, fakeHome.toString())

        server = AgentControlServer.getInstance()
        registry = InstanceRegistry.getInstance()
        savedAgentControl = AgentSettingsStore.getInstance().getAgentControl()

        // The service is application-level and outlives every test method, so start from a known
        // unbound state: each test then binds its own port and owns the instance file it publishes.
        server.stop()
        server.setAgentControl(AGENT_CONTROL_FULL)
        AgentAuditLog.getInstance().clear()
        AgentAuditLog.getInstance().forgetClients()

        loopbackRefusal = probeLoopback()

        // HTTP/1.1 explicitly: the default client would try an h2c upgrade against a JDK HttpServer
        // that only speaks 1.1, adding Upgrade/HTTP2-Settings headers to every assertion below for
        // no benefit. The bridge talks 1.1 to this port too.
        http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build()
    }

    override fun tearDown() {
        try {
            // Guarded on initialisation because tearDown also runs when setUp itself failed, and a
            // lateinit access here would replace the real failure with an unrelated one.
            if (::server.isInitialized) {
                try {
                    // Restoring the mode can itself bind (setAgentControl re-starts a plane it
                    // turned off), so stop() has to be the last thing that touches the server.
                    server.setAgentControl(savedAgentControl)
                } finally {
                    // Unconditional: a failed assertion must never leave an ephemeral port bound
                    // for the rest of the JVM run.
                    server.stop()
                }
                AgentAuditLog.getInstance().clear()
                AgentAuditLog.getInstance().forgetClients()
            }
            if (::http.isInitialized) shutDownHttpClient()
        } finally {
            // Back to the task-level root (build/…), never to the real home: a refresh still queued
            // on the pool lands there.
            val saved = savedHomeProperty
            if (saved != null) System.setProperty(AgentFiles.HOME_PROPERTY, saved) else System.clearProperty(AgentFiles.HOME_PROPERTY)
            if (::fakeHome.isInitialized) fakeHome.toFile().deleteRecursively()
            super.tearDown()
        }
    }

    // ========================================================================
    // 1. The socket, the router, the auth layer and a handler are one working whole
    // ========================================================================

    fun testStartBindsAndTheBoundSocketServesARealHttpRequest() {
        val binding = startOrSkip() ?: return

        assertTrue(
            "the bridge appends its paths to control.baseUrl, so the /${ControlRouter.API_PREFIX} " +
                    "prefix must already be in it — got ${binding.baseUrl}",
            binding.baseUrl.endsWith("/${ControlRouter.API_PREFIX}")
        )
        assertTrue("a bound control plane must have issued a bearer token", binding.token.isNotEmpty())
        assertTrue("an ephemeral port was never read back: ${binding.port}", binding.port > 0)
        assertTrue("start() returned a binding but the server does not consider itself bound", server.isBound())
        assertNull("a successful bind must clear the error the Agent tab shows", server.getBindError())

        // THE assertion of this suite: a real TCP request, over the real listener, through the real
        // router and the real auth layer, answered by a real handler.
        val response = request(binding)

        assertEquals(
            "GET ${binding.baseUrl}/meta was not served — the socket is bound but nothing behind " +
                    "it answered. Body: ${response.body().take(400)}",
            200,
            response.statusCode()
        )
        assertEquals(
            "every response must carry the api-version header the router sets",
            API_VERSION,
            response.headers().firstValue("X-MockkHttp-Api-Version").orElse(null)
        )

        val meta = JsonParser.parseString(response.body()).asJsonObject
        assertEquals("the body must be the meta payload, not an error envelope", API_VERSION, meta.get("api_version").asString)

        // instance_id in the payload comes from ControlApi.environment, which only start() fills in.
        // Equal to the binding's id means the process that answered is the process that published.
        val reported = meta.get("instance_id")
        assertTrue("meta answered without an instance_id: the control environment was never published", reported != null && !reported.isJsonNull)
        assertEquals(binding.instanceId, reported.asString)
    }

    // ========================================================================
    // 2. The instance file describes the server that is actually running
    // ========================================================================

    fun testTheInstanceFileLivesUnderTheRelocatedHomeAndNeverInTheRealOne() {
        // Audit round 11, AR: running the suite left a dead IDE's file — endpoint, token and all —
        // in the developer's ~/.mockkhttp/instances, next to the file of the IDE they had open.
        val realInstances = Path.of(System.getProperty("user.home"), ".mockkhttp", "instances")
        val realBefore = listInstanceFiles(realInstances)
        val binding = startOrSkip() ?: return

        val file = registry.instanceFile()
        assertTrue("the file must be rooted at ${AgentFiles.HOME_PROPERTY} ($fakeHome), not at user.home: $file", file.startsWith(fakeHome))
        assertTrue("published under the relocated root", Files.exists(file))
        assertEquals("${binding.instanceId}.json", file.fileName.toString())
        assertEquals(
            "nothing may appear in the developer's real instances directory",
            realBefore, listInstanceFiles(realInstances)
        )
    }

    private fun listInstanceFiles(dir: Path): Set<String> =
        if (Files.isDirectory(dir)) Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList().toSet() } else emptySet()

    fun testAStaleInstanceFileIsSweptAtStartupWithoutPublishingAnything() {
        // Audit round 11, AR: pruning ran only when a live IDE rewrote its own file. The registry
        // now sweeps on its own, endpoint or not, and a dead pid's file goes.
        val dir = InstanceRegistry.instancesDirectory()
        Files.createDirectories(dir)
        val stale = dir.resolve("ij-999999-deadbeef.json")
        // A pid no process on this machine has: the largest the kernel hands out is far below it.
        Files.writeString(stale, "{\"schema\":1,\"instanceId\":\"ij-999999-deadbeef\",\"pid\":2147483000}")

        val pruned = registry.sweepStale()

        assertEquals("the one stale file", 1, pruned)
        assertFalse("swept", Files.exists(stale))
        assertFalse("a sweep publishes nothing of its own", Files.exists(registry.instanceFile()))
    }

    fun testAFreshRegistrySweepsADeadPeerOnItsOwnWithoutBeingAsked() {
        // Audit round 12: the sweep test above calls sweepStale() by hand on a singleton built long
        // before. The claim is that CONSTRUCTION sweeps — so build one and call nothing.
        val dir = InstanceRegistry.instancesDirectory()
        Files.createDirectories(dir)
        val dead = dir.resolve("ij-999999-r12dead.json")
        val live = dir.resolve("ij-000001-r12live.json")
        Files.writeString(dead, "{\"schema\":1,\"instanceId\":\"ij-999999-r12dead\",\"pid\":2147483000}")
        Files.writeString(live, "{\"schema\":1,\"instanceId\":\"ij-000001-r12live\",\"pid\":${ProcessHandle.current().pid()}}")

        val fresh = InstanceRegistry()
        try {
            val deadline = System.currentTimeMillis() + 5_000
            while (Files.exists(dead) && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertFalse("a dead pid's file must be gone in the first seconds of the process", Files.exists(dead))
            assertTrue("a live pid's file stays", Files.exists(live))
            assertFalse("a sweep publishes nothing of its own", Files.exists(fresh.instanceFile()))
        } finally {
            fresh.dispose()
            Files.deleteIfExists(live)
        }
    }

    fun testTheInstanceFileDescribesTheServerThatIsActuallyRunning() {
        val binding = startOrSkip() ?: return
        val file = registry.instanceFile()

        assertTrue(
            "start() bound ${binding.baseUrl} but published no file at $file — this file is the ONLY " +
                    "channel through which an agent learns the port and the token",
            Files.exists(file)
        )
        // The file NAME comes from InstanceRegistry.instanceId and the binding from the server: two
        // independently generated ids meant the file on disk described a different server than the
        // one running, and every discovery attempt resolved nothing.
        assertEquals(
            "the instance file must be named after the id the control plane reports",
            "${binding.instanceId}.json",
            file.fileName.toString()
        )

        val published = JsonParser.parseString(Files.readString(file)).asJsonObject
        val control = published.getAsJsonObject("control")

        assertEquals(binding.instanceId, published.get("instanceId").asString)
        assertEquals(binding.agentControl, published.get("agentControl").asString)
        assertEquals("the advertised base URL must be the socket that is listening", binding.baseUrl, control.get("baseUrl").asString)
        assertEquals("the advertised token must be the one the auth layer accepts", binding.token, control.get("token").asString)

        // Equality is not enough: what is on disk has to actually open the door. This is exactly the
        // call the MCP bridge makes, with nothing but the file to go on.
        val asABridgeWould = send(
            baseUrl = control.get("baseUrl").asString,
            path = "/meta",
            token = control.get("token").asString,
            client = CLIENT_NAME
        )
        assertEquals(
            "the endpoint published in $file did not answer — a bridge reading it would fail. Body: ${asABridgeWould.body().take(400)}",
            200,
            asABridgeWould.statusCode()
        )
    }

    // ========================================================================
    // 3. The auth layer is in the request path, not merely present in the build
    // ========================================================================

    fun testARequestWithoutAValidBearerTokenIsRefused() {
        val binding = startOrSkip() ?: return

        val noToken = request(binding, token = null)
        assertEquals("a request with no Authorization header was served", 401, noToken.statusCode())
        assertEquals("UNAUTHORIZED", errorCodeOf(noToken))

        // Change the last character rather than truncating, so the forgery can never collide with
        // the real token, and keep the length equal so the constant-time compare is what decides.
        val forged = binding.token.dropLast(1) + (if (binding.token.last() == 'A') 'B' else 'A')
        assertFalse("the forged token must differ from the issued one", forged == binding.token)

        val wrongToken = request(binding, token = forged)
        assertEquals("a request with someone else's token was served", 401, wrongToken.statusCode())
        assertEquals("UNAUTHORIZED", errorCodeOf(wrongToken))

        // The client header is what a web page cannot send without a preflight, so a valid token
        // alone must not be enough.
        val unidentified = request(binding, client = null)
        assertEquals("a request with no ${ControlAuth.CLIENT_HEADER} header was served", 403, unidentified.statusCode())
        assertEquals("FORBIDDEN", errorCodeOf(unidentified))

        // And the door still opens for a well-formed call, so the three refusals above are the auth
        // layer deciding and not the route being broken.
        assertEquals(200, request(binding).statusCode())
    }

    // ========================================================================
    // 4. Stopping retracts the advertisement as well as the socket
    // ========================================================================

    fun testAfterStopTheInstanceFileNoLongerAdvertisesAControlEndpoint() {
        val binding = startOrSkip() ?: return
        val file = registry.instanceFile()
        assertTrue("nothing was published, so this test would prove nothing", Files.exists(file))

        server.stop()

        assertFalse("stop() left the server believing it is bound", server.isBound())

        // Withdrawal may be a deleted file or an emptied endpoint; what must never survive stop() is
        // a readable baseUrl/token pointing at a dead port, which a bridge would dial and then
        // report as a connection error instead of "no live MockkHttp instance".
        if (Files.exists(file)) {
            val control = JsonParser.parseString(Files.readString(file)).asJsonObject.getAsJsonObject("control")
            assertEquals("$file still advertises a control endpoint after stop()", "", control.get("baseUrl").asString)
            assertEquals("$file still advertises a token after stop()", "", control.get("token").asString)
        }

        // The socket is gone too, not merely unadvertised: a leaked listener is how a plugin update
        // ends up with two control planes and a bridge talking to the corpse.
        try {
            Socket().use { it.connect(InetSocketAddress(AgentControlServer.BIND_HOST, binding.port), CONNECT_TIMEOUT_MS) }
            fail("port ${binding.port} still accepts connections after stop() — the listener was never closed")
        } catch (nothingIsListening: IOException) {
            // ConnectException (or a connect timeout) is the PASS: the listener really is gone.
            // fail() above throws an Error, so it can never be swallowed by this catch.
        }
    }

    // ========================================================================
    // 5. The audit hook is subscribed by start(), not by whoever opens the Agent tab
    // ========================================================================

    fun testAServedCallIsRecordedInTheAgentAuditLog() {
        val binding = startOrSkip() ?: return
        val audit = AgentAuditLog.getInstance()
        audit.clear()

        assertEquals(200, request(binding).statusCode())

        val entry = awaitAuditEntry { it.path == "/${ControlRouter.API_PREFIX}/meta" }
            ?: throw AssertionError(
                "a call was served but nothing reached AgentAuditLog: start() is not subscribing the " +
                        "audit listener, so the agent channel would be driving this IDE with no trail at all"
            )

        assertEquals("GET", entry.method)
        assertEquals(200, entry.statusCode)
        assertEquals(AgentAuditLog.Outcome.OK, entry.outcome)
        assertEquals("every call must be attributable to the client that made it", CLIENT_NAME, entry.client)
        assertFalse("GET /meta changes nothing and must not be logged as a mutation", entry.mutating)
    }

    // ========================================================================
    // 6. One identity, shared
    // ========================================================================

    fun testTheServerAndTheInstanceRegistryAgreeOnOneInstanceId() {
        val serverId = server.instanceId
        val registryId = registry.instanceId

        assertTrue("the instance id must not be blank", serverId.isNotBlank())
        assertEquals(
            "the control plane and the discovery file must share ONE identity — when each generated " +
                    "its own, the id an agent read from the file never matched the id /v1/meta " +
                    "reported, and MOCKKHTTP_PROJECT=instanceId:projectId could never resolve",
            registryId,
            serverId
        )
        assertEquals("the id must be stable for the life of the process", serverId, server.instanceId)
        assertEquals(
            "the file an agent globs is named after the registry's id, so the server's id must name it too",
            "$serverId.json",
            registry.instanceFile().fileName.toString()
        )
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Start the control plane, or report a genuinely impossible environment.
     *
     * @return the live binding, or null when — and only when — this JVM was already proved unable to
     *         open any loopback listener. A [AgentControlServer.start] that fails on a machine where
     *         a plain [ServerSocket] works is the defect this suite is for, so it fails loudly.
     */
    private fun startOrSkip(): ControlBinding? {
        loopbackRefusal?.let {
            announceSkip(it)
            return null
        }
        return server.start() ?: throw AssertionError(
            "AgentControlServer.start() produced no binding even though 127.0.0.1 accepts listeners " +
                    "in this JVM. Reported reason: ${server.getBindError() ?: "none — start() returned null silently"}"
        )
    }

    /** @return null when a loopback listener can be opened, or the reason it cannot. */
    private fun probeLoopback(): String? = try {
        ServerSocket(0, 1, InetAddress.getByName(AgentControlServer.BIND_HOST)).close()
        null
    } catch (e: IOException) {
        "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
    } catch (e: SecurityException) {
        "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
    }

    /** A skip here means the wiring went unverified, so it is printed where nobody can miss it. */
    private fun announceSkip(reason: String) {
        System.err.println(
            """

            ################################################################################
            # SKIPPED: AgentControlWiringTest could not bind a loopback listener.
            # Reason: $reason
            #
            # NOTHING about the agent control plane was verified by this run: not the socket,
            # not the router, not the auth layer, not the instance file, not the audit trail.
            # This suite exists because that whole feature once shipped fully implemented and
            # never executed. A skip here is a hole, not a pass.
            ################################################################################

            """.trimIndent()
        )
        System.err.flush()
    }

    /**
     * Audit round five, Z: the discovery file published each project's session once, at IDE
     * start, and never again — an agent reading it saw "nothing is capturing" for the whole life
     * of a MOCKK session. A session change must reach the file, through the service's own
     * listener seam, without anyone remembering to call refresh.
     */
    fun testTheInstanceFileFollowsASessionChange() {
        val binding = startOrSkip() ?: return
        val session = com.sergiy.dev.mockkhttp.session.CaptureSessionService.getInstance(project)
        val marker = "com.acme.round5.${System.nanoTime()}"
        try {
            session.setPackageFilter(marker)

            val deadline = System.currentTimeMillis() + 5_000
            var published: String? = null
            while (System.currentTimeMillis() < deadline) {
                published = projectEntry(binding)?.get("packageFilter")?.takeIf { !it.isJsonNull }?.asString
                if (published == marker) break
                Thread.sleep(50)
            }
            assertEquals("the discovery file must follow the session it describes", marker, published)
        } finally {
            session.setPackageFilter(null)
        }
    }

    /** This project's entry in the published instance file, or null while it is being rewritten. */
    private fun projectEntry(binding: ControlBinding): JsonObject? = try {
        val root = JsonParser.parseString(Files.readString(registry.instanceFile())).asJsonObject
        root.getAsJsonArray("projects").map { it.asJsonObject }
            .firstOrNull { it.get("projectId").asString == project.locationHash }
    } catch (e: Exception) {
        null
    }

    /**
     * The explain handler takes PUT as a synonym of POST. The router used to classify that PUT as
     * mutating, so READ_ONLY refused a read — the one mode whose whole point is that reads work.
     */
    fun testReadOnlyStillServesAPutToExplainWhileRefusingARealWrite() {
        val binding = startOrSkip() ?: return
        server.setAgentControl(AGENT_CONTROL_READ_ONLY)
        try {
            val pid = project.locationHash
            val explain = sendJson(binding, "PUT", "/${ControlRouter.RESOURCE_PROJECTS}/$pid/${ControlRouter.RESOURCE_MOCKS}/explain",
                """{"method":"GET","url":"https://api.example.com/v1/users"}""")
            assertNotSame("a PUT explain is a read and must not be refused as a write", 403, explain.statusCode())

            val write = sendJson(binding, "PUT", "/${ControlRouter.RESOURCE_PROJECTS}/$pid/${ControlRouter.RESOURCE_MOCKS}/rules/no-such-rule", """{"name":"x"}""")
            assertEquals("a real write is still refused", 403, write.statusCode())
            assertEquals("READ_ONLY", errorCodeOf(write))
        } finally {
            server.setAgentControl(AGENT_CONTROL_FULL)
        }
    }

    private fun sendJson(binding: ControlBinding, method: String, path: String, body: String): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(binding.baseUrl + path))
            .timeout(REQUEST_TIMEOUT)
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${binding.token}")
            .header(ControlAuth.CLIENT_HEADER, CLIENT_NAME)
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun request(
        binding: ControlBinding,
        path: String = "/${ControlRouter.RESOURCE_META}",
        token: String? = binding.token,
        client: String? = CLIENT_NAME
    ): HttpResponse<String> = send(binding.baseUrl, path, token, client)

    private fun send(baseUrl: String, path: String, token: String?, client: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(REQUEST_TIMEOUT)
            .GET()
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (client != null) builder.header(ControlAuth.CLIENT_HEADER, client)
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** The `code` out of the `{"error":{…}}` envelope every non-2xx must carry. */
    private fun errorCodeOf(response: HttpResponse<String>): String {
        val body = JsonParser.parseString(response.body()).asJsonObject
        val error = body.getAsJsonObject("error")
            ?: throw AssertionError("a refusal must be a typed error envelope, got: ${response.body().take(400)}")
        return error.get("code").asString
    }

    /**
     * Poll for a matching audit entry.
     *
     * The router notifies its listeners in a `finally`, after the response bytes are on the wire, so
     * the client can legitimately be back here before the entry exists. Polling keeps this test
     * about the wiring instead of about a sleep that is always either flaky or slow.
     */
    private fun awaitAuditEntry(predicate: (AgentAuditLog.Entry) -> Boolean): AgentAuditLog.Entry? {
        val deadline = System.currentTimeMillis() + AUDIT_WAIT_MS
        while (true) {
            AgentAuditLog.getInstance().entries().lastOrNull(predicate)?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(10L)
        }
    }

    /**
     * Release the client's selector thread deterministically instead of leaving it for the garbage
     * collector, so it cannot be reported as a leak by whatever runs after this suite.
     */
    private fun shutDownHttpClient() {
        try {
            http.shutdownNow()
            http.awaitTermination(Duration.ofSeconds(2))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
