package com.sergiy.dev.mockkhttp.bridge

import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The wrong-window bug, asserted where it actually happened: **on the wire**.
 *
 * The bridge used to resolve `project:"Beta"` to the second IDE window, describe that window back to
 * the model, and then send the HTTP request to the window the working directory resolved to. Every
 * write landed in the wrong project and was reported as a success — the worst answer this bridge can
 * give, because nothing downstream can tell it from a real one.
 *
 * [DiscoveryTest] guards the resolution half of that fix by asserting what `RestClient.scope(...)`
 * RETURNS. It cannot guard the other half: `RestClient.targetFor` is what actually applies
 * [Scope.instancePin], and it only runs when a request is issued. So everything here drives real
 * tool calls against TWO real loopback servers standing in for two IDE windows, and asserts which
 * socket the bytes arrived on and which bearer token they carried. Deleting `scope = scope` from a
 * call site in `Tools.kt` makes these fail; nothing weaker does.
 *
 * The token is asserted separately from the URL on purpose: a "fix" that pins the endpoint but keeps
 * the cwd window's credential is a 401 in production, not a wrong write — a different bug, equally
 * invisible to a test that only checks the path.
 *
 * Nothing below touches the real `~/.mockkhttp`; [Discovery.environment] is redirected at a
 * throw-away temp tree, exactly as [DiscoveryTest] does.
 */
class WrongWindowTest {

    /** Stands in for `~/.mockkhttp`, and for `~/.mockkhttp/instances` inside it. */
    private lateinit var home: Path
    private lateinit var instances: Path

    /** Stands in for the developer's source tree: the paths the cwd is matched against. */
    private lateinit var tree: Path

    /** The window the working directory resolves to — the one the bug wrote to. */
    private lateinit var one: FakeIde

    /** The window an explicit `project:` argument points at — the one the user meant. */
    private lateinit var two: FakeIde

    private lateinit var alpha: Path
    private lateinit var beta: Path
    private lateinit var cwd: Path

    private val env = HashMap<String, String>()

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("mockkhttp-wrong-window-home")
        instances = Files.createDirectories(home.resolve("instances"))
        tree = Files.createTempDirectory("mockkhttp-wrong-window-tree")
        env["MOCKKHTTP_HOME"] = home.toString()
        Discovery.environment = { name -> env[name] }

        one = FakeIde("ide-one", "token-one")
        two = FakeIde("ide-two", "token-two")

        alpha = dir("alpha")
        // Deliberately DEEPER than alpha: the cwd-prefix rule prefers the longest base path, so if
        // the prefix test itself ever stops being applied, Beta wins and the "no project argument"
        // test below fails loudly instead of depending on directory iteration order.
        beta = dir("beta/module")
        cwd = dir("alpha/app")

        publish(one, "p-alpha", "Alpha", alpha)
        publish(two, "p-beta", "Beta", beta)
        workingDirectory(cwd)
    }

    @AfterTest
    fun tearDown() {
        Discovery.environment = { name -> System.getenv(name) }
        Discovery.invalidate()
        one.stop()
        two.stop()
        home.toFile().deleteRecursively()
        tree.toFile().deleteRecursively()
    }

    // ── the bug ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a write addressed to a second window is sent to that window, carrying that window's token`() {
        val outcome = Tools().call(
            "mockkhttp_mocks",
            args(
                "project" to "Beta",
                "action" to "create",
                "name" to "checkout returns 500",
                "url" to "https://api.example.com/checkout"
            )
        )

        assertFalse(outcome.isError, outcome.text)

        val call = two.only("the retargeted rule creation")
        assertEquals("POST", call.method)
        assertEquals("/v1/projects/p-beta/mocks/rules", call.path)
        // Both halves of the address have to come from the pinned instance. A pinned URL with the
        // cwd window's credential authenticates against nothing and 401s in production.
        assertEquals("Bearer token-two", call.authorization)
        assertTrue(call.body.contains("checkout returns 500"), call.body)

        // The window the working directory resolves to is the one the bug wrote into. It must not
        // have seen this call at all.
        assertTrue(one.received().isEmpty(), "window one saw a call addressed to window two: ${one.received()}")

        // And the answer handed back to the model came from the window it addressed — a model that
        // reads window one's payload will keep reasoning about the wrong project.
        assertTrue(outcome.text.contains("ide-two"), outcome.text)
    }

    @Test
    fun `the same write with no project argument stays in the window the working directory resolves to`() {
        val outcome = Tools().call(
            "mockkhttp_mocks",
            args("action" to "create", "name" to "checkout returns 500", "url" to "https://api.example.com/checkout")
        )

        assertFalse(outcome.isError, outcome.text)

        val call = one.only("the ordinary cwd write")
        assertEquals("/v1/projects/p-alpha/mocks/rules", call.path)
        assertEquals("Bearer token-one", call.authorization)
        // The counterweight to the test above: over-correcting into "always re-resolve" would send
        // every unqualified call to whichever window happens to sort first.
        assertTrue(two.received().isEmpty(), "an unpinned call must not reach window two: ${two.received()}")
    }

    @Test
    fun `every retargeted call site lands on the pinned window, reads and writes alike`() {
        // One call site per tool handler is not enough: `scope = scope` is repeated at twenty of
        // them in Tools.kt, and dropping it at any one is silently the original bug again. These six
        // span all four verbs and both halves of the read/write split.
        val expected = listOf(
            Expected("mockkhttp_status", args("project" to "Beta"), "GET", "/v1/projects/p-beta/status"),
            Expected(
                "mockkhttp_flows",
                args("project" to "Beta", "action" to "list"),
                "GET",
                "/v1/projects/p-beta/flows"
            ),
            Expected(
                "mockkhttp_mocks",
                args("project" to "Beta", "action" to "list"),
                "GET",
                "/v1/projects/p-beta/mocks/rules"
            ),
            Expected(
                "mockkhttp_mocks",
                args("project" to "Beta", "action" to "delete", "rule_id" to "r-1"),
                "DELETE",
                "/v1/projects/p-beta/mocks/rules/r-1"
            ),
            Expected(
                "mockkhttp_session",
                args("project" to "Beta", "action" to "set_mode", "mode" to "MOCKK"),
                "POST",
                "/v1/projects/p-beta/session/mode"
            ),
            Expected(
                "mockkhttp_match_explain",
                args("project" to "Beta", "method" to "GET", "url" to "https://api.example.com/cart"),
                "POST",
                "/v1/projects/p-beta/mocks/explain"
            )
        )

        val tools = Tools()
        for (call in expected) {
            val outcome = tools.call(call.tool, call.args)
            assertFalse(outcome.isError, "${call.tool} (${call.method} ${call.path}): ${outcome.text}")
        }

        // Calls are issued one at a time from this thread, so arrival order is call order.
        val landed = two.received()
        assertEquals(expected.size, landed.size, "one request per tool call should have reached window two: $landed")
        expected.forEachIndexed { index, call ->
            val actual = landed[index]
            assertEquals(call.method, actual.method, "call ${index + 1}, ${call.tool}")
            assertEquals(call.path, actual.path, "call ${index + 1}, ${call.tool}")
            assertEquals("Bearer token-two", actual.authorization, "call ${index + 1}, ${call.tool}")
        }
        assertTrue(one.received().isEmpty(), "window one saw retargeted calls: ${one.received()}")
    }

    // ── the two ways the pin can stop being answerable ────────────────────────────────────────────

    @Test
    fun `two windows claiming the same project name refuse the call and name both candidates`() {
        // Two git worktrees of the same repo, open side by side: both instance files report the same
        // project name, and Files.newDirectoryStream has no defined order. Picking either one is a
        // coin toss the user cannot see, so the only safe answer is a question.
        publish(one, "p-alpha", "Twin", alpha)
        publish(two, "p-beta", "Twin", beta)
        workingDirectory(cwd)

        val outcome = Tools().call(
            "mockkhttp_mocks",
            args("project" to "Twin", "action" to "create", "name" to "checkout returns 500")
        )

        assertTrue(outcome.isError, outcome.text)
        // The message has to be actionable: the instanceId:projectId form it tells the model to use
        // is only usable if both candidates are spelled out in it.
        assertTrue(outcome.text.contains("ide-one:p-alpha"), outcome.text)
        assertTrue(outcome.text.contains("ide-two:p-beta"), outcome.text)
        assertTrue(
            one.received().isEmpty() && two.received().isEmpty(),
            "an ambiguous name must send nothing at all: one=${one.received()} two=${two.received()}"
        )
    }

    @Test
    fun `a pinned window that closes before the send refuses instead of falling back to the cwd window`() {
        val client = RestClient()
        val scope = client.scope("Beta")
        assertEquals("p-beta", scope.pid)

        // The IDE that had Beta open goes away between resolving the scope and issuing the request —
        // a restart, or agent control switched off. Reaching for the cwd window here is the bug in
        // its most damaging form, because the write would still be reported as a success.
        Files.delete(instances.resolve("ide-two.json"))
        Discovery.invalidate()

        val failure = assertFailsWith<BridgeException> {
            client.request("POST", "/projects/${scope.pid}/mocks/rules", body = JsonObject(), scope = scope)
        }

        val message = failure.message ?: fail("a refusal the model has to act on must carry a message")
        assertTrue(message.contains("NOT sent"), message)
        assertTrue(one.received().isEmpty(), "nothing may be written to the surviving window: ${one.received()}")
        assertTrue(two.received().isEmpty(), "the closed window received something: ${two.received()}")
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────

    /** One expected request: the tool call that produces it, and where it must arrive. */
    private class Expected(
        val tool: String,
        val args: JsonObject,
        val method: String,
        val path: String
    )

    /** One request as the fake IDE saw it. */
    private data class Call(
        val method: String,
        val path: String,
        val query: String?,
        val authorization: String?,
        val body: String
    )

    /**
     * A stand-in for one IDE window: a real loopback control plane on an ephemeral port, with its
     * own bearer token, that records every request instead of acting on it.
     *
     * Real sockets rather than a stubbed HttpClient because the whole bug lived in which endpoint
     * the request was built against — a stub that is handed the target has already assumed the
     * answer.
     */
    private class FakeIde(val id: String, val token: String) {

        private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        private val calls: MutableList<Call> = Collections.synchronizedList(ArrayList())

        /** The port the OS handed out; the instance file is written from it. */
        val port: Int get() = server.address.port

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                calls.add(
                    Call(
                        method = exchange.requestMethod,
                        path = exchange.requestURI.path,
                        query = exchange.requestURI.rawQuery,
                        authorization = exchange.requestHeaders.getFirst("Authorization"),
                        body = body
                    )
                )
                // served_by travels back in the payload so a test can assert that the ANSWER the
                // model reads came from the window it addressed, not merely that a request left.
                val payload = "{\"ok\":true,\"served_by\":\"$id\"}".toByteArray(StandardCharsets.UTF_8)
                try {
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.write(payload)
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        fun received(): List<Call> = synchronized(calls) { ArrayList(calls) }

        fun only(what: String): Call {
            val all = received()
            if (all.size != 1) fail("expected exactly one request at $id ($what); got ${all.size}: $all")
            return all.first()
        }

        fun stop() = server.stop(0)
    }

    /** A directory inside the fake source tree; it must exist, because both sides of the prefix test
     *  are resolved through `toRealPath` (which is what makes /var and /private/var agree on macOS). */
    private fun dir(relative: String): Path = Files.createDirectories(tree.resolve(relative))

    /** One `~/.mockkhttp/instances/<id>.json`, in the shape the plugin's InstanceRegistry writes. */
    private fun publish(ide: FakeIde, projectId: String, name: String, basePath: Path) {
        Files.writeString(
            instances.resolve("${ide.id}.json"),
            """
            {
              "instanceId": "${ide.id}",
              "pid": ${ProcessHandle.current().pid()},
              "control": {"baseUrl": "http://127.0.0.1:${ide.port}", "token": "${ide.token}", "scheme": "Bearer"},
              "ide": {"name": "Android Studio", "build": "AI-243.1", "pluginVersion": "1.8.0"},
              "agentControl": "FULL",
              "projects": [
                {"projectId": "$projectId", "name": "$name", "basePath": "${escape(basePath)}",
                 "sessionRunning": true, "mode": "MOCKK"}
              ]
            }
            """.trimIndent()
        )
        Discovery.invalidate()
    }

    private fun escape(path: Path): String = path.toString().replace("\\", "\\\\")

    private fun workingDirectory(path: Path) {
        env["MOCKKHTTP_PROJECT_DIR"] = path.toString()
        Discovery.invalidate()
    }

    /** Tool arguments. Every argument these tools take on the wire is a JSON string. */
    private fun args(vararg entries: Pair<String, String>): JsonObject {
        val json = JsonObject()
        for ((key, value) in entries) json.addProperty(key, value)
        return json
    }
}
