package com.sergiy.dev.mockkhttp.bridge

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Discovery is the one part of the bridge that can fail SILENTLY.
 *
 * Everything else answers with a message a model can read; picking the wrong IDE window instead
 * produces a confident, wrong answer about a project nobody asked about — and, once a write is
 * involved, a mock rule created in somebody else's session. So every test here asserts WHICH
 * instance was chosen — its base URL and its token — never merely that one was.
 *
 * Nothing below touches the real `~/.mockkhttp`: [Discovery.environment] is redirected at a
 * throw-away temp tree, which is also the only way to exercise the env overrides from inside a JVM
 * that cannot change its own environment.
 */
class DiscoveryTest {

    /** Stands in for `~/.mockkhttp`. */
    private lateinit var home: Path

    /** Stands in for `~/.mockkhttp/instances`. */
    private lateinit var instances: Path

    /** Stands in for the developer's source tree: the paths the cwd is matched against. */
    private lateinit var tree: Path

    private val env = HashMap<String, String>()

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("mockkhttp-home")
        instances = Files.createDirectories(home.resolve("instances"))
        tree = Files.createTempDirectory("mockkhttp-tree")
        env["MOCKKHTTP_HOME"] = home.toString()
        Discovery.environment = { name -> env[name] }
        Discovery.invalidate()
    }

    @AfterTest
    fun tearDown() {
        Discovery.environment = { name -> System.getenv(name) }
        Discovery.invalidate()
        home.toFile().deleteRecursively()
        tree.toFile().deleteRecursively()
    }

    // ── the cwd match ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the deepest matching project wins, not the first one read`() {
        val repo = dir("repo")
        val module = dir("repo/app/feature")
        instance(
            "ide-a",
            port = 51000,
            projects = listOf(
                Triple("p-repo", "Repo", repo),
                Triple("p-module", "Feature", module)
            )
        )

        val target = target(cwd = dir("repo/app/feature/src/main"))

        // A nested module project has to beat the repo that contains it, or every call from inside
        // the module lands on the wrong FlowStore.
        assertEquals("p-module", target.project?.projectId)
        assertEquals("cwd-prefix", target.matchedBy)
        // The /v1 prefix is added by discovery, never by the caller.
        assertEquals("http://127.0.0.1:51000/v1", target.baseUrl)
        assertEquals("token-ide-a", target.token)
    }

    @Test
    fun `a second IDE window next door is never chosen`() {
        instance("ide-a", port = 51001, projects = listOf(Triple("p-alpha", "Alpha", dir("alpha"))))
        instance("ide-b", port = 51002, projects = listOf(Triple("p-beta", "Beta", dir("beta"))))

        val target = target(cwd = dir("beta/lib"))

        assertEquals("p-beta", target.project?.projectId)
        assertEquals("http://127.0.0.1:51002/v1", target.baseUrl)
        assertEquals("token-ide-b", target.token)
        assertEquals(2, target.liveInstances)
    }

    @Test
    fun `one IDE with one project is used from a sibling directory, and says so`() {
        instance("ide-a", port = 51003, projects = listOf(Triple("p-alpha", "Alpha", dir("alpha"))))

        val target = target(cwd = dir("somewhere-else"))

        // Running the agent from next door is a common, harmless mistake — but matched_by has to
        // admit it, because that string is what mockkhttp_status shows the model.
        assertEquals("sole-open-project", target.matchedBy)
        assertEquals("p-alpha", target.project?.projectId)
    }

    @Test
    fun `two windows and no cwd match is reported as a question, not a guess`() {
        instance("ide-a", port = 51004, projects = listOf(Triple("p-alpha", "Alpha", dir("alpha"))))
        instance("ide-b", port = 51005, projects = listOf(Triple("p-beta", "Beta", dir("beta"))))

        val target = target(cwd = dir("elsewhere"))

        assertEquals("unresolved", target.matchedBy)
        assertNull(target.project)
        val problem = target.projectProblem ?: fail("an unresolved target must explain itself")
        assertTrue(problem.contains("project:"), problem)
    }

    // ── explicit re-targeting: the regression this file exists for ────────────────────────────────

    @Test
    fun `an explicit project re-points at the window that owns it, endpoint included`() {
        instance("ide-a", port = 51006, projects = listOf(Triple("p-alpha", "Alpha", dir("alpha"))))
        instance("ide-b", port = 51007, projects = listOf(Triple("p-beta", "Beta", dir("beta"))))
        env["MOCKKHTTP_PROJECT_DIR"] = dir("alpha/app").toString()
        Discovery.invalidate()

        val retargeted = Discovery.forProject("Beta") ?: fail("Beta is named in an instance file and must resolve")
        assertEquals("http://127.0.0.1:51007/v1", retargeted.baseUrl)
        assertEquals("token-ide-b", retargeted.token)
        assertEquals("explicit-arg", retargeted.matchedBy)

        // The Scope a tool call is actually built from must carry that window too. The bug this
        // guards against computed the retargeted instance, described it, and then sent the request
        // to the cwd window anyway — a write applied to the wrong project, reported as a success.
        val elsewhere = RestClient().scope("Beta")
        assertEquals("p-beta", elsewhere.pid)
        assertEquals("http://127.0.0.1:51007/v1", elsewhere.baseUrl)
        assertEquals("Beta", elsewhere.instancePin)

        // …while an ordinary call still resolves from the working directory and pins nothing.
        val here = RestClient().scope(null)
        assertEquals("p-alpha", here.pid)
        assertEquals("http://127.0.0.1:51006/v1", here.baseUrl)
        assertNull(here.instancePin)
    }

    @Test
    fun `a project name no instance file knows is passed through for the control plane to resolve`() {
        instance("ide-a", port = 51008, projects = listOf(Triple("p-alpha", "Alpha", dir("alpha"))))
        env["MOCKKHTTP_PROJECT_DIR"] = dir("alpha").toString()
        Discovery.invalidate()

        // Opened one second ago: the instance file has not been rewritten yet, so the name goes over
        // the wire as-is. Nothing local matched, so nothing local is claimed about it either.
        val scope = RestClient().scope("OpenedJustNow")
        assertEquals("OpenedJustNow", scope.pid)
        assertEquals("explicit-arg", scope.matchedBy)
        assertNull(scope.projectName)
        assertNull(scope.basePath)
        assertNull(scope.instancePin)
    }

    // ── stale and unreadable files ────────────────────────────────────────────────────────────────

    @Test
    fun `an instance file whose process has exited is skipped, even when it matches better`() {
        instance("ide-live", port = 51009, projects = listOf(Triple("p-repo", "Repo", dir("repo"))))
        instance(
            "ide-dead",
            port = 51010,
            pid = exitedPid(),
            projects = listOf(Triple("p-module", "Module", dir("repo/app")))
        )

        val target = target(cwd = dir("repo/app/src"))

        // The dead file has the LONGER prefix, so a scan that trusted the file would pick it — and
        // then talk to a recycled port owned by something else entirely.
        assertEquals("p-repo", target.project?.projectId)
        assertEquals("token-ide-live", target.token)
        assertEquals(1, target.liveInstances)
    }

    @Test
    fun `when every instance file is stale the message says to reopen the IDE`() {
        instance("ide-dead", port = 51011, pid = exitedPid(), projects = listOf(Triple("p", "Gone", dir("gone"))))

        val message = missing(cwd = dir("gone"))

        assertTrue(message.contains("No live MockkHttp instance found"), message)
        assertTrue(message.contains("Reopen the IDE"), message)
    }

    @Test
    fun `a malformed instance file is ignored, never thrown`() {
        Files.writeString(instances.resolve("truncated.json"), "{ \"control\": { \"baseUrl\":")
        Files.writeString(instances.resolve("no-control.json"), "{\"instanceId\":\"x\",\"pid\":1}")
        Files.writeString(instances.resolve("not-an-object.json"), "[1, 2, 3]")
        instance("ide-a", port = 51012, projects = listOf(Triple("p-alpha", "Alpha", dir("alpha"))))

        val target = target(cwd = dir("alpha"))

        assertEquals("token-ide-a", target.token)
        assertEquals(1, target.liveInstances)
    }

    @Test
    fun `nothing but unparseable files still produces an actionable message`() {
        Files.writeString(instances.resolve("broken.json"), "<html>not json at all</html>")

        val message = missing(cwd = dir("alpha"))

        assertTrue(message.contains("could not parse"), message)
    }

    @Test
    fun `an empty instances directory reads as no IDE, not as a crash`() {
        val message = missing(cwd = dir("alpha"))

        assertTrue(message.contains("is empty"), message)
    }

    @Test
    fun `a machine where the plugin has never run is told how to get one`() {
        instances.toFile().deleteRecursively()

        val message = missing(cwd = dir("alpha"))

        assertTrue(message.contains("does not exist"), message)
        assertTrue(message.contains("1.8.0 or newer"), message)
    }

    // ── environment overrides ─────────────────────────────────────────────────────────────────────

    @Test
    fun `MOCKKHTTP_PROJECT pins the project regardless of the working directory`() {
        instance("ide-a", port = 51013, projects = listOf(Triple("p-alpha", "Alpha", dir("alpha"))))
        instance("ide-b", port = 51014, projects = listOf(Triple("p-beta", "Beta", dir("beta"))))
        env["MOCKKHTTP_PROJECT"] = "p-beta"

        val target = target(cwd = dir("alpha/app"))

        assertEquals("p-beta", target.project?.projectId)
        assertEquals("env-pin", target.matchedBy)
        assertEquals("token-ide-b", target.token)
    }

    @Test
    fun `a pin that matches nothing is refused with the open projects listed`() {
        instance("ide-a", port = 51015, projects = listOf(Triple("p-alpha", "Alpha", dir("alpha"))))
        env["MOCKKHTTP_PROJECT"] = "Gamma"

        val message = missing(cwd = dir("alpha"))

        assertTrue(message.contains("MOCKKHTTP_PROJECT"), message)
        assertTrue(message.contains("Alpha"), message)
    }

    @Test
    fun `MOCKKHTTP_BASE_URL and MOCKKHTTP_TOKEN bypass discovery entirely`() {
        // A live instance file that would otherwise win, to prove the override is not just a default.
        instance("ide-a", port = 51016, projects = listOf(Triple("p-alpha", "Alpha", dir("alpha"))))
        env["MOCKKHTTP_BASE_URL"] = "http://127.0.0.1:9999"
        env["MOCKKHTTP_TOKEN"] = "ci-token"

        val target = target(cwd = dir("alpha"))

        assertEquals("http://127.0.0.1:9999/v1", target.baseUrl)
        assertEquals("ci-token", target.token)
        assertEquals("base-url-override", target.matchedBy)
        // The override says nothing about which projects that IDE has open; RestClient asks it.
        assertNull(target.project)
        assertEquals(0, target.liveInstances)
    }

    @Test
    fun `an override already carrying the version prefix is not given a second one`() {
        env["MOCKKHTTP_BASE_URL"] = "http://127.0.0.1:9999/v1/"
        env["MOCKKHTTP_TOKEN"] = "ci-token"

        assertEquals("http://127.0.0.1:9999/v1", target(cwd = dir("alpha")).baseUrl)
    }

    @Test
    fun `half an override is refused, naming the variable that is set`() {
        instance("ide-a", port = 51017, projects = listOf(Triple("p-alpha", "Alpha", dir("alpha"))))
        env["MOCKKHTTP_BASE_URL"] = "http://127.0.0.1:9999"

        val message = missing(cwd = dir("alpha"))

        // Silently falling back to discovery here would answer from the local IDE while the user
        // believes they are pointed at a container.
        assertTrue(message.contains("only MOCKKHTTP_BASE_URL is set"), message)
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────

    /** A directory inside the fake source tree; it must exist, since both sides of the prefix test
     *  are resolved through `toRealPath` (which is what makes /var vs /private/var agree on macOS). */
    private fun dir(relative: String): Path = Files.createDirectories(tree.resolve(relative))

    /** One `~/.mockkhttp/instances/<id>.json`, in the shape the plugin's InstanceRegistry writes. */
    private fun instance(
        id: String,
        port: Int,
        pid: Long = ProcessHandle.current().pid(),
        projects: List<Triple<String, String, Path?>> = emptyList(),
        baseUrl: String = "http://127.0.0.1:$port"
    ) {
        val entries = projects.joinToString(",") { (projectId, name, base) ->
            "{\"projectId\":\"$projectId\",\"name\":\"$name\",\"basePath\":${quote(base)}," +
                "\"sessionRunning\":true,\"mode\":\"MOCKK\"}"
        }
        Files.writeString(
            instances.resolve("$id.json"),
            """
            {
              "instanceId": "$id",
              "pid": $pid,
              "control": {"baseUrl": "$baseUrl", "token": "token-$id", "scheme": "Bearer"},
              "ide": {"name": "Android Studio", "build": "AI-243.1", "pluginVersion": "1.8.0"},
              "agentControl": "FULL",
              "projects": [$entries]
            }
            """.trimIndent()
        )
    }

    private fun quote(path: Path?): String =
        if (path == null) "null" else "\"" + path.toString().replace("\\", "\\\\") + "\""

    private fun resolution(cwd: Path?): Discovery.Resolution {
        if (cwd != null) env["MOCKKHTTP_PROJECT_DIR"] = cwd.toString()
        Discovery.invalidate()
        return Discovery.current()
    }

    private fun target(cwd: Path?): Discovery.Target = when (val resolved = resolution(cwd)) {
        is Discovery.Resolution.Found -> resolved.target
        is Discovery.Resolution.Missing -> fail("expected a live instance; discovery said: ${resolved.message}")
    }

    private fun missing(cwd: Path?): String = when (val resolved = resolution(cwd)) {
        is Discovery.Resolution.Found -> fail("expected no usable instance; discovery chose ${resolved.target.baseUrl}")
        is Discovery.Resolution.Missing -> resolved.message
    }

    /**
     * A pid that is certainly not running: a child process started and immediately reaped, so the
     * OS has already dropped it. A hard-coded number could be anything by the time this runs.
     */
    private fun exitedPid(): Long = runCatching {
        val launcher = ProcessHandle.current().info().command()
            .orElse(System.getProperty("java.home") + "/bin/java")
        val process = ProcessBuilder(launcher, "-version")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.waitFor()
        process.pid()
    }.getOrDefault(NEVER_A_PID)

    private companion object {
        /** Above every OS pid ceiling, for the case where this JVM may not start processes. */
        const val NEVER_A_PID: Long = 4_000_000_000L
    }
}
