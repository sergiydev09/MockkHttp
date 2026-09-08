package com.sergiy.dev.mockkhttp.bridge

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The JSON-RPC surface, and the one property that outranks every feature in this module: **the
 * bridge must never die**. An MCP server that exits during start-up is not a degraded server — the
 * client marks it broken and every `mockkhttp_*` tool disappears from the session, with a message
 * the user cannot act on. So "the IDE is not running" has to arrive as a tools/call RESULT carrying
 * `isError`, and garbage on stdin has to be answered rather than propagated.
 *
 * Discovery is pointed at an empty temp directory throughout, which is exactly the "no IDE" state
 * these tests assert against — and never the developer's real `~/.mockkhttp`.
 */
class McpProtocolTest {

    private lateinit var home: Path
    private val env = HashMap<String, String>()
    private val protocol = McpProtocol()

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("mockkhttp-protocol-home")
        Files.createDirectories(home.resolve("instances"))
        env["MOCKKHTTP_HOME"] = home.toString()
        env["MOCKKHTTP_PROJECT_DIR"] = home.toString()
        Discovery.environment = { name -> env[name] }
        Discovery.invalidate()
    }

    @AfterTest
    fun tearDown() {
        Discovery.environment = { name -> System.getenv(name) }
        Discovery.invalidate()
        home.toFile().deleteRecursively()
    }

    // ── the two protocol eras ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the 2025-11-25 handshake is answered in full`() {
        val result = resultOf(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{" +
                "\"protocolVersion\":\"$PROTOCOL_2025_11_25\",\"capabilities\":{}," +
                "\"clientInfo\":{\"name\":\"claude-code\",\"version\":\"1.0\"}}}"
        )

        assertEquals(PROTOCOL_2025_11_25, result.get("protocolVersion").asString)
        assertEquals(BRIDGE_NAME, result.getAsJsonObject("serverInfo").get("name").asString)
        assertEquals(false, result.getAsJsonObject("capabilities").getAsJsonObject("tools").get("listChanged").asBoolean)
        // The instructions are the only thing a model reads before it sees a tool, so they have to
        // name the first call to make.
        assertTrue(result.get("instructions").asString.contains("mockkhttp_status"))
        // Mandatory from 2026-07-28; earlier revisions treat it as pass-through data.
        assertEquals("complete", result.get("resultType").asString)
    }

    @Test
    fun `initialize without a protocolVersion falls back to the last stateful revision`() {
        val result = resultOf("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{}}")

        assertEquals(PROTOCOL_2025_11_25, result.get("protocolVersion").asString)
    }

    @Test
    fun `the 2026-07-28 era gets its capabilities with no handshake at all`() {
        val result = resultOf(
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"server/discover\",\"params\":{" +
                "\"_meta\":{\"$META_PROTOCOL_VERSION\":\"$PROTOCOL_2026_07_28\"}}}"
        )

        val versions = result.getAsJsonArray("supportedVersions").map { it.asString }
        assertEquals(SUPPORTED_PROTOCOL_VERSIONS, versions)
        assertEquals(PROTOCOL_2026_07_28, versions.first())
        assertTrue(versions.contains(PROTOCOL_2025_11_25), versions.toString())
        assertEquals(BRIDGE_NAME, result.getAsJsonObject("serverInfo").get("name").asString)
        // The SEP puts serverInfo at the top level, the architecture guide under _meta. Both are
        // emitted, so either reader finds it.
        assertEquals(
            BRIDGE_NAME,
            result.getAsJsonObject("_meta").getAsJsonObject(META_SERVER_INFO).get("name").asString
        )
        assertEquals("complete", result.get("resultType").asString)
    }

    @Test
    fun `a protocol revision this bridge has never heard of still gets the tools`() {
        // Answering -32022 here would take the whole server down for a revision whose tool surface,
        // on every past evidence, is identical.
        val initialized = resultOf(
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2099-01-01\"}}"
        )
        assertEquals("2099-01-01", initialized.get("protocolVersion").asString)

        val listed = resultOf(
            "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\",\"params\":{" +
                "\"_meta\":{\"$META_PROTOCOL_VERSION\":\"2099-01-01\"}}}"
        )
        assertEquals(TOOL_COUNT, listed.getAsJsonArray("tools").size())
    }

    @Test
    fun `notifications are acknowledged with silence`() {
        // Answering a notification is a protocol violation some clients abort the connection on.
        assertNull(send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
        assertNull(send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":7}}"))
        assertNull(send("{\"jsonrpc\":\"2.0\",\"params\":{}}"))
    }

    // ── the tool surface ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `tools list is a complete set of MCP tool definitions`() {
        val tools = resultOf("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/list\"}").getAsJsonArray("tools")

        assertEquals(TOOL_COUNT, tools.size())
        for (element in tools) {
            val tool = element.asJsonObject
            val name = tool.get("name").asString
            assertTrue(name.startsWith("mockkhttp_"), "every tool is namespaced: $name")
            // A one-line label is not a description: the model picks the tool from this text alone.
            assertTrue(tool.get("description").asString.length > 200, "$name has a stub description")
            assertEquals("object", tool.getAsJsonObject("inputSchema").get("type").asString, name)
        }
    }

    @Test
    fun `the agent-facing argument surface matches the golden`() {
        val tools = resultOf("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}").getAsJsonArray("tools")

        // Every argument here is something a model will pass verbatim, and a rename is invisible
        // until a session fails at run time. Changing this golden must be a deliberate edit; print
        // renderSurface(tools) to regenerate it.
        assertEquals(TOOL_SURFACE_GOLDEN, renderSurface(tools))
    }

    // ── failures a model has to be able to act on ─────────────────────────────────────────────────

    @Test
    fun `a tool call with no IDE running is a result, not a JSON-RPC error, and the server keeps serving`() {
        val response = send(
            "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\"," +
                "\"params\":{\"name\":\"mockkhttp_status\",\"arguments\":{}}}"
        ) ?: fail("a request must be answered")

        assertTrue(response.has("result"), "a model cannot recover from a JSON-RPC error: $response")
        val result = response.getAsJsonObject("result")
        assertEquals(true, result.get("isError").asBoolean)

        val text = result.getAsJsonArray("content").first().asJsonObject.get("text").asString
        assertTrue(text.contains("No live MockkHttp instance found"), text)
        // Actionable means it names what to do next in both possible worlds: a human at an IDE, and
        // an agent in a container that can only be given an endpoint.
        assertTrue(text.contains("Android Studio"), text)
        assertTrue(text.contains("MOCKKHTTP_BASE_URL"), text)

        // The whole point of answering with isError: the session survives it intact.
        val stillThere = resultOf("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/list\"}")
        assertEquals(TOOL_COUNT, stillThere.getAsJsonArray("tools").size())
    }

    @Test
    fun `an unknown method is method-not-found and an unknown tool is invalid-params`() {
        // This bridge serves tools only; a client probing for resources or prompts must be told so
        // rather than handed a tool result it will try to parse.
        assertEquals(METHOD_NOT_FOUND, errorOf("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"resources/list\"}").get("code").asInt)
        assertEquals(METHOD_NOT_FOUND, errorOf("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"prompts/get\",\"params\":{}}").get("code").asInt)

        val unknownTool = errorOf(
            "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"tools/call\",\"params\":{\"name\":\"mockkhttp_teleport\"}}"
        )
        assertEquals(INVALID_PARAMS, unknownTool.get("code").asInt)
        // Naming the alternatives saves the model a round trip it would otherwise spend guessing.
        assertTrue(unknownTool.get("message").asString.contains("mockkhttp_status"), unknownTool.toString())

        assertEquals(INVALID_PARAMS, errorOf("{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"tools/call\",\"params\":{}}").get("code").asInt)
        assertEquals(
            INVALID_PARAMS,
            errorOf(
                "{\"jsonrpc\":\"2.0\",\"id\":14,\"method\":\"tools/call\"," +
                    "\"params\":{\"name\":\"mockkhttp_status\",\"arguments\":\"not an object\"}}"
            ).get("code").asInt
        )
    }

    @Test
    fun `a message without a method is refused as an invalid request`() {
        assertEquals(INVALID_REQUEST, errorOf("{\"jsonrpc\":\"2.0\",\"id\":15,\"params\":{}}").get("code").asInt)
    }

    @Test
    fun `a parse error carries the literal null id the spec requires`() {
        val response = parseErrorResponse("JsonSyntaxException: unterminated object")

        assertEquals(PARSE_ERROR, response.getAsJsonObject("error").get("code").asInt)
        assertTrue(response.get("id").isJsonNull)
        // serializeNulls is what keeps the key in the frame; several clients reject one without it.
        assertTrue(WIRE.toJson(response).contains("\"id\":null"), WIRE.toJson(response))
    }

    // ── the real process ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the bridge survives garbage on stdin and keeps answering afterwards`() {
        val run = runBridge(
            listOf(
                "not json at all",
                "",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"$PROTOCOL_2025_11_25\"}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "\"a bare string is valid JSON and not a JSON-RPC message\"",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"mockkhttp_status\",\"arguments\":{}}}",
                "[{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}]"
            )
        )

        assertTrue(run.exited, "the bridge must exit when stdin closes, not hang: ${run.lines}")
        assertEquals(0, run.exitCode, "a non-zero exit is shown to the user as a broken MCP server")

        val initialize = run.byId(1) ?: fail("no answer to initialize; stdout was ${run.lines}")
        assertEquals(
            BRIDGE_NAME,
            initialize.getAsJsonObject("result").getAsJsonObject("serverInfo").get("name").asString
        )

        val listed = run.byId(2) ?: fail("garbage on stdin killed the read loop; stdout was ${run.lines}")
        assertEquals(TOOL_COUNT, listed.getAsJsonObject("result").getAsJsonArray("tools").size())

        val called = run.byId(3) ?: fail("no answer to tools/call; stdout was ${run.lines}")
        assertEquals(true, called.getAsJsonObject("result").get("isError").asBoolean)

        // Batches only exist up to 2025-03-26, but a client that sends one still gets an answer.
        val pinged = run.byId(4) ?: fail("no answer to the batched ping; stdout was ${run.lines}")
        assertTrue(pinged.has("result"))

        // Both unparseable lines are answered with a parse error rather than swallowed or fatal.
        assertEquals(2, run.frames.count { it.has("error") && it.get("id").isJsonNull }, run.lines.toString())
    }

    @Test
    fun `a broken environment cannot stop the bridge from serving its tools`() {
        // Three misconfigurations at once — half an endpoint override, a home that is a FILE, and a
        // project pin nothing can match. Every one of them must be reported per CALL: validating any
        // of it at start-up is what removes the tools from the session entirely.
        val notADirectory = Files.createFile(home.resolve("this-is-a-file"))
        val run = runBridge(
            listOf(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"mockkhttp_docs\",\"arguments\":{}}}"
            ),
            extraEnv = mapOf(
                "MOCKKHTTP_BASE_URL" to "http://127.0.0.1:1",
                "MOCKKHTTP_HOME" to notADirectory.toString(),
                "MOCKKHTTP_PROJECT" to "a project that is not open"
            )
        )

        assertEquals(0, run.exitCode, "stdout was ${run.lines}")
        val listed = run.byId(1) ?: fail("a broken environment must not cost the client its tools: ${run.lines}")
        assertEquals(TOOL_COUNT, listed.getAsJsonObject("result").getAsJsonArray("tools").size())

        val docs = (run.byId(2) ?: fail("no answer to mockkhttp_docs; stdout was ${run.lines}"))
            .getAsJsonObject("result")
        assertEquals(true, docs.get("isError").asBoolean)
        // mockkhttp_docs is the one tool that still answers usefully with no IDE: the model that
        // cannot reach the plugin is exactly the one that needs to know what to ask the user for.
        val text = docs.getAsJsonArray("content").first().asJsonObject.get("text").asString
        assertTrue(text.contains("offline fallback"), text)
        assertTrue(text.contains("open the project in Android Studio"), text)
    }

    @Test
    fun `the version flag answers and exits cleanly`() {
        val run = runBridge(emptyList(), args = listOf("--version"))

        assertEquals(0, run.exitCode)
        assertTrue(run.lines.any { it.startsWith("$BRIDGE_NAME-mcp ") }, run.lines.toString())
    }

    // ── plumbing ──────────────────────────────────────────────────────────────────────────────────

    private fun send(json: String): JsonObject? =
        protocol.handle(JsonParser.parseString(json).asJsonObject)?.asJsonObject

    private fun resultOf(json: String): JsonObject {
        val response = send(json) ?: fail("a request must be answered: $json")
        assertEquals("2.0", response.get("jsonrpc").asString)
        if (response.has("error")) fail("expected a result, got error ${response.get("error")}")
        return response.getAsJsonObject("result")
    }

    private fun errorOf(json: String): JsonObject {
        val response = send(json) ?: fail("a request must be answered: $json")
        if (!response.has("error")) fail("expected an error, got result ${response.get("result")}")
        return response.getAsJsonObject("error")
    }

    /** The tool surface as one comparable block: name, then `argument: type [enum|enum]` in order. */
    private fun renderSurface(tools: JsonArray): String = buildString {
        for (element in tools) {
            val tool = element.asJsonObject
            append(tool.get("name").asString).append('\n')
            val schema = tool.getAsJsonObject("inputSchema")
            for ((name, value) in schema.getAsJsonObject("properties").entrySet()) {
                val property = value.asJsonObject
                append("  ").append(name).append(": ").append(property.get("type").asString)
                property.getAsJsonArray("enum")?.let { values ->
                    append(" [").append(values.joinToString("|") { it.asString }).append(']')
                }
                append('\n')
            }
            schema.getAsJsonArray("required")?.let { required ->
                append("  required: ").append(required.joinToString(", ") { it.asString }).append('\n')
            }
        }
    }.trimEnd('\n')

    /**
     * Runs the bridge the way an MCP client does: a separate JVM, JSON-RPC on its stdin and stdout.
     *
     * Nothing else can prove the properties this file is about — `main` writes to the real stdout
     * file descriptor, not to `System.out`, precisely so no stray print can corrupt the stream.
     */
    private fun runBridge(
        lines: List<String>,
        extraEnv: Map<String, String> = emptyMap(),
        args: List<String> = emptyList()
    ): BridgeRun {
        val launcher = ProcessHandle.current().info().command()
            .orElse(System.getProperty("java.home") + "/bin/java")
        val command = listOf(launcher, "-cp", bridgeClasspath(), BRIDGE_MAIN_CLASS) + args
        val builder = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD)
        builder.environment().let { childEnv ->
            // Never inherit the developer's own agent configuration: a live IDE on this machine
            // would make "no instance is running" pass or fail depending on who runs the build.
            childEnv.keys.removeIf { it.startsWith("MOCKKHTTP_") }
            childEnv["MOCKKHTTP_HOME"] = home.toString()
            childEnv["MOCKKHTTP_PROJECT_DIR"] = home.toString()
            childEnv.putAll(extraEnv)
        }
        val process = builder.start()

        // stdout is drained on its own thread WHILE stdin is written: one tools/list answer is
        // larger than a pipe buffer, so writing everything first would deadlock both processes.
        val out = ArrayList<String>()
        val drain = Thread {
            BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { reader ->
                while (true) out += (reader.readLine() ?: break)
            }
        }
        drain.isDaemon = true
        drain.start()

        process.outputStream.use { stdin ->
            if (lines.isNotEmpty()) {
                stdin.write(lines.joinToString("\n", postfix = "\n").toByteArray(StandardCharsets.UTF_8))
            }
            stdin.flush()
        }

        val exited = process.waitFor(60, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }
        drain.join(10_000)
        return BridgeRun(exited, if (exited) process.exitValue() else -1, out)
    }

    /**
     * Where the three things the bridge needs actually live: its own classes, the Kotlin stdlib and
     * Gson. Asked of the classes themselves rather than read out of `java.class.path`, because a
     * Gradle test worker runs with nothing but `gradle-worker.jar` on that property and loads the
     * test classpath through its own classloader.
     */
    private fun bridgeClasspath(): String {
        val roots = listOf(McpProtocol::class.java, Unit::class.java, com.google.gson.Gson::class.java)
            .mapNotNull { type -> type.protectionDomain?.codeSource?.location }
            .map { location -> File(location.toURI()).absolutePath }
            .distinct()
        if (roots.size < 3) fail("could not locate the bridge classes, the Kotlin stdlib and Gson: $roots")
        return roots.joinToString(File.pathSeparator)
    }

    private class BridgeRun(val exited: Boolean, val exitCode: Int, val lines: List<String>) {

        /** Every JSON-RPC frame on stdout, batches flattened into their members. */
        val frames: List<JsonObject> = lines
            .mapNotNull { line -> runCatching { JsonParser.parseString(line) }.getOrNull() }
            .flatMap { element -> if (element.isJsonArray) element.asJsonArray.toList() else listOf(element) }
            .filter { it.isJsonObject }
            .map { it.asJsonObject }

        /** Answers may arrive out of order: the bridge dispatches requests to a worker pool. */
        fun byId(id: Int): JsonObject? = frames.firstOrNull { frame ->
            val value = frame.get("id")
            value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asInt == id
        }
    }

    private companion object {
        /** `Main.kt`'s top-level `main`, as the fat jar's Main-Class attribute spells it. */
        const val BRIDGE_MAIN_CLASS: String = "com.sergiy.dev.mockkhttp.bridge.MainKt"

        /** M1 ships exactly seven tools; an eighth arriving unannounced is a bug, not a feature. */
        const val TOOL_COUNT: Int = 7

        /**
         * The whole agent-facing argument surface. Regenerate with `renderSurface(tools)` — but only
         * after deciding that the change is one an already-running agent should have to relearn.
         */
        val TOOL_SURFACE_GOLDEN: String = """
            mockkhttp_status
              project: string
            mockkhttp_flows
              project: string
              action: string [list|get|clear]
              flow_id: string
              method: string
              host: string
              path: string
              path_contains: string
              path_regex: string
              url_contains: string
              body_contains: string
              status: integer
              status_min: integer
              status_max: integer
              resolution: string [STUBBED|MOCKED|PASSTHROUGH|MODIFIED|TIMEOUT|UNCONFIRMED]
              since_seq: integer
              limit: integer
              include_body: string [none|response|both]
              max_body_chars: integer
              include_secrets: boolean
            mockkhttp_await_flow
              project: string
              match: object
              count: integer
              since_seq: integer
              wait_ms: integer
              include_body: string [none|response|both]
              max_body_chars: integer
            mockkhttp_mocks
              project: string
              action: string [list|get|create|update|set_enabled|delete|list_collections|create_collection|delete_collection|export|import]
              rule_id: string
              collection_id: string
              collection_ids: array
              name: string
              method: string
              url: string
              host: string
              path: string
              host_match: string [EXACT|WILDCARD|REGEX]
              path_match: string [EXACT|WILDCARD|REGEX]
              query: array
              response: object
              from_flow_id: string
              loosen_query: boolean
              enabled: boolean
              exclusive: boolean
              new_collection: object
              package_name: string
              description: string
              remove_rules: boolean
              include_bodies: boolean
              limit: integer
              json: string
              strategy: string [REPLACE|KEEP_BOTH|SKIP]
              dry_run: boolean
            mockkhttp_match_explain
              project: string
              method: string
              url: string
              required: method, url
            mockkhttp_session
              project: string
              action: string [get|start|stop|restart|set_app|devices|set_mode|clear_flows]
              serial: string
              package_name: string
              scan: boolean
              mode: string [RECORDING|MOCKK|DEBUG|MOCKK_DEBUG]
              confirm_pause_all: boolean
            mockkhttp_docs
              topic: string [quickstart|modes|mocking|matching|flows|automated_test|arms_and_ordering|debug_intercept|troubleshooting|limits]
        """.trimIndent()
    }
}
