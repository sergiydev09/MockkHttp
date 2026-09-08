package com.sergiy.dev.mockkhttp.bridge

import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Audit finding P, asserted on the path it leaked through: a **successful** tool call.
 *
 * [HintTranslationTest] proves `toolSpeak` can rewrite a route. It could not catch this bug, because
 * the bug was never in the function — it was in who called it: only the error branch did. So this
 * drives real tool calls against a loopback stand-in for the IDE that answers 200 with the exact
 * `hint` and `warnings` captured from the plugin, and reads what the model would read.
 */
class SuccessHintTranslationTest {

    private lateinit var home: Path
    private lateinit var tree: Path
    private lateinit var ide: HttpServer
    private val env = HashMap<String, String>()

    /** Path and body of the last request the fake IDE saw. */
    @Volatile private var lastPath: String = ""
    @Volatile private var lastBody: String = ""

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("mockkhttp-success-hint-home")
        tree = Files.createTempDirectory("mockkhttp-success-hint-tree")
        val instances = Files.createDirectories(home.resolve("instances"))
        env["MOCKKHTTP_HOME"] = home.toString()
        Discovery.environment = { name -> env[name] }

        ide = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        ide.createContext("/") { exchange ->
            lastPath = exchange.requestURI.path
            lastBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            // Verbatim from the third audit round: what status, devices, mocks create and
            // session start actually said on a 200.
            val payload = """
                {
                  "session": {"running": true, "mode": "RECORDING"},
                  "hint": "Capturing in RECORDING. Call POST /v1/projects/p-one/session/mode with mode:'MOCKK' to make them fire.",
                  "warnings": [
                    "Mode is RECORDING: mock rules are inert. Call session/mode with 'MOCKK'.",
                    "Apps that never announced themselves need GET /v1/projects/p-one/devices?scan=true."
                  ]
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            try {
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.write(payload)
            } finally {
                exchange.close()
            }
        }
        ide.start()

        val projectDir = Files.createDirectories(tree.resolve("one"))
        Files.writeString(
            instances.resolve("ide-one.json"),
            """
            {
              "instanceId": "ide-one",
              "pid": ${ProcessHandle.current().pid()},
              "control": {"baseUrl": "http://127.0.0.1:${ide.address.port}", "token": "token-one", "scheme": "Bearer"},
              "ide": {"name": "Android Studio", "build": "AI-243.1", "pluginVersion": "1.8.0"},
              "agentControl": "FULL",
              "projects": [
                {"projectId": "p-one", "name": "One", "basePath": "${projectDir.toString().replace("\\", "\\\\")}",
                 "sessionRunning": true, "mode": "RECORDING"}
              ]
            }
            """.trimIndent()
        )
        env["MOCKKHTTP_PROJECT_DIR"] = projectDir.toString()
        Discovery.invalidate()
    }

    @AfterTest
    fun tearDown() {
        Discovery.environment = { name -> System.getenv(name) }
        Discovery.invalidate()
        ide.stop(0)
        home.toFile().deleteRecursively()
        tree.toFile().deleteRecursively()
    }

    @Test
    fun `advice on a successful answer reaches the model as tool calls, not REST routes`() {
        val outcome = Tools().call("mockkhttp_session", JsonObject().apply { addProperty("action", "start") })

        assertFalse(outcome.isError, outcome.text)
        assertFalse(outcome.text.contains("/v1/projects"), "a REST route survived a 200: ${outcome.text}")
        assertFalse(outcome.text.contains("session/mode"), outcome.text)
        // The tool result is pretty-printed JSON, so the quotes inside a string value are escaped.
        val text = outcome.text.replace("\\\"", "\"")
        assertTrue(text.contains("""mockkhttp_session {"action":"set_mode"} with mode:'MOCKK'"""), text)
        assertTrue(text.contains("""mockkhttp_session {"action":"devices","scan":true}."""), text)
        // The prose the plugin wrote around the routes is what makes the advice readable; it stays.
        assertTrue(text.contains("mock rules are inert"), text)
    }

    @Test
    fun `restart forwards confirm_pause_all exactly like start does`() {
        val args = JsonObject().apply {
            addProperty("action", "restart")
            addProperty("mode", "debug")
            addProperty("confirm_pause_all", true)
        }

        val outcome = Tools().call("mockkhttp_session", args)

        assertFalse(outcome.isError, outcome.text)
        assertTrue(lastPath.endsWith("/session/restart"), lastPath)
        val body = com.google.gson.JsonParser.parseString(lastBody).asJsonObject
        assertTrue(body.get("confirm_pause_all")?.asBoolean == true, "the flag must reach the control plane on restart: $lastBody")
        assertTrue(body.get("mode")?.asString == "DEBUG", "mode is upper-cased on the wire: $lastBody")
    }

    @Test
    fun `the same translation applies to status, the call every agent makes first`() {
        val outcome = Tools().call("mockkhttp_status", JsonObject())

        assertFalse(outcome.isError, outcome.text)
        assertFalse(outcome.text.contains("/v1/projects"), outcome.text)
        assertTrue(outcome.text.contains("mockkhttp_session"), outcome.text)
    }
}
