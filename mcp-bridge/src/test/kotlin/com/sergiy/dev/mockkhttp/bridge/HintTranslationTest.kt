package com.sergiy.dev.mockkhttp.bridge

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The control plane writes its hints for an HTTP caller. A model reaching it through this bridge
 * has no HTTP client, so a hint naming a REST route is a dead end — the one place where the
 * plugin's best idea (the error carries the next call inside it) stops working.
 */
class HintTranslationTest {

    private val client = RestClient()

    @Test
    fun `the session start route becomes the tool call that does it`() {
        val hint = "Start one with POST /v1/projects/7cf085ab/session/start with an empty body."
        val out = client.toolSpeak(hint)

        assertTrue(out.contains("""mockkhttp_session {"action":"start"}"""), out)
        assertFalse(out.contains("/v1/projects"), "no REST route may survive: $out")
    }

    @Test
    fun `set_mode keeps the mode the hint asked for`() {
        val out = client.toolSpeak("Call POST /v1/projects/abc/session/mode with mode:'MOCKK'.")

        assertTrue(out.contains("mockkhttp_session"), out)
        assertTrue(out.contains("MOCKK"), "the mode the hint named must survive: $out")
    }

    @Test
    fun `the bare session slash mode spelling is translated too`() {
        val out = client.toolSpeak("Call session/mode with 'MOCKK'")

        assertTrue(out.contains("mockkhttp_session"), out)
        assertFalse(out.contains("session/mode"), out)
    }

    @Test
    fun `devices and explain map to their own tools`() {
        assertTrue(
            client.toolSpeak("GET /v1/projects/x/session/devices").contains("""{"action":"devices"}"""))
        assertTrue(
            client.toolSpeak("POST /v1/projects/x/mocks/explain").contains("mockkhttp_match_explain"))
    }

    @Test
    fun `the more specific route wins over the shorter one`() {
        // /session/start must not be rewritten by the plain /session rule, which is listed after it
        // precisely so the specific match happens first.
        val out = client.toolSpeak("POST /v1/projects/x/session/start")

        assertTrue(out.contains(""""action":"start""""), out)
        assertFalse(out.contains(""""action":"get""""), out)
    }

    @Test
    fun `a hint with no route is left alone`() {
        val hint = "Send the payload in response.body as plain text if it is text."

        assertEquals(hint, client.toolSpeak(hint))
    }

    // ── audit finding P: the routes that leaked through, and the arguments they carried ──

    @Test
    fun `the bare devices route is translated and its scan flag becomes an argument`() {
        val out = client.toolSpeak("GET /v1/projects/7cf085ab/devices?scan=true reads the installed APKs instead (minutes).")

        assertTrue(out.contains("""mockkhttp_session {"action":"devices","scan":true}"""), out)
        assertFalse(out.contains("/v1/projects"), "no REST route may survive: $out")
        assertFalse(out.contains("?scan"), "the query string must not dangle off the tool call: $out")
        assertTrue(out.endsWith("reads the installed APKs instead (minutes)."), "the prose around the route stays: $out")
    }

    @Test
    fun `a flows listing keeps its query parameters as typed arguments`() {
        val out = client.toolSpeak("Try `GET /v1/projects/{pid}/flows?limit=25&path_contains=/v1/login` first.")

        assertTrue(out.contains("""mockkhttp_flows {"action":"list","limit":25,"path_contains":"/v1/login"}"""), out)
    }

    @Test
    fun `await_flow gets its arguments even though the bare call has no object`() {
        val out = client.toolSpeak("GET /v1/projects/{pid}/flows/await?path=/v1/login&count=1&wait_ms=25000. Never sleep.")

        assertTrue(out.contains("""mockkhttp_await_flow {"path":"/v1/login","count":1,"wait_ms":25000}"""), out)
        assertTrue(out.endsWith(". Never sleep."), out)
    }

    @Test
    fun `a rule id travels into the tool call, for every verb`() {
        val id = "3f2a9c1e-7b4d-4e0a-9a1b-2c3d4e5f6a7b"

        assertTrue(client.toolSpeak("PUT /v1/projects/x/mocks/$id to change it")
            .contains("""mockkhttp_mocks {"action":"update","rule_id":"$id"}"""))
        assertTrue(client.toolSpeak("GET /v1/projects/x/mocks/rules/$id.")
            .contains("""mockkhttp_mocks {"action":"get","rule_id":"$id"}."""))
        assertTrue(client.toolSpeak("DELETE /v1/projects/x/mocks/rules/$id")
            .contains("""mockkhttp_mocks {"action":"delete","rule_id":"$id"}"""))
        assertTrue(client.toolSpeak("POST /v1/projects/{pid}/mocks/{rule_id}/enable")
            .contains("""mockkhttp_mocks {"action":"set_enabled","rule_id":"{rule_id}"}"""))
    }

    @Test
    fun `the fixed mocks sub-routes are never mistaken for a rule id`() {
        val list = client.toolSpeak("GET /v1/projects/x/mocks/rules")
        assertTrue(list.contains(""""action":"list""""), list)
        assertFalse(list.contains("rule_id"), list)

        assertTrue(client.toolSpeak("POST /v1/projects/x/mocks").contains(""""action":"create""""))
        assertTrue(client.toolSpeak("GET /v1/projects/x/mocks/collections").contains(""""action":"list_collections""""))
        assertTrue(client.toolSpeak("GET /v1/projects/x/mocks/export").contains(""""action":"export""""))
        assertTrue(client.toolSpeak("DELETE /v1/projects/x/flows").contains(""""action":"clear_flows""""))
        assertTrue(client.toolSpeak("GET /v1/projects/x/flows/{flow_id}").contains(""""action":"get","flow_id":"{flow_id}""""))
    }

    @Test
    fun `every bare session spelling is translated`() {
        for (route in listOf("session/stop", "session/restart", "session/devices", "session/app")) {
            val out = client.toolSpeak("Call $route next.")
            assertTrue(out.contains("mockkhttp_session"), "$route -> $out")
            assertFalse(out.contains(route), "$route -> $out")
        }
    }

    // ── the seam: a 200's advice is translated too, and only its advice ──

    @Test
    fun `hint, warnings and note are translated at any depth of a successful payload`() {
        val payload = JsonParser.parseString(
            """
            {
              "hint": "POST /v1/projects/7cf085ab/session/start with an empty body starts capturing.",
              "warnings": ["Mode is RECORDING: mock rules are inert. Call session/mode with 'MOCKK'.", 7],
              "session": {"note": "Call GET /v1/projects/7cf085ab/status to see it."},
              "flows": [{"flow_id": "f1", "note": "Repeat with GET /v1/projects/7cf085ab/flows/f1"}]
            }
            """.trimIndent()
        ).asJsonObject

        val out = client.toolSpeakPayload(payload).asJsonObject

        assertFalse(out.toString().contains("/v1/projects"), "no REST route may survive anywhere: $out")
        assertFalse(out.toString().contains("session/mode"), out.toString())
        assertTrue(out.get("hint").asString.contains("""mockkhttp_session {"action":"start"}"""))
        assertTrue(out.getAsJsonArray("warnings").get(0).asString.contains("""mockkhttp_session {"action":"set_mode"}"""))
        assertEquals(7, out.getAsJsonArray("warnings").get(1).asInt, "non-string entries are left alone")
        assertTrue(out.getAsJsonObject("session").get("note").asString.contains("mockkhttp_status"))
        assertTrue(out.getAsJsonArray("flows").get(0).asJsonObject.get("note").asString
            .contains("""mockkhttp_flows {"action":"get","flow_id":"f1"}"""))
    }

    @Test
    fun `captured data is never rewritten, even when it looks like a route`() {
        val body = "POST /v1/projects/abc/session/start was in the app's own log line"
        val payload = JsonParser.parseString(
            """{"request": {"body": "$body", "headers": {"hint": "GET /v1/projects/abc/status"}}, "message": "$body"}"""
        ).asJsonObject

        val out = client.toolSpeakPayload(payload).asJsonObject

        assertEquals(body, out.getAsJsonObject("request").get("body").asString, "a body is data")
        assertEquals(body, out.get("message").asString, "message is not an advisory field")
        // A header the app named "hint" is still a header: the name is the app's, not ours.
        assertEquals(
            "GET /v1/projects/abc/status",
            out.getAsJsonObject("request").getAsJsonObject("headers").get("hint").asString,
            "captured header values must never be rewritten, whatever the header is called"
        )
    }

    // ── audit round four: U (the abbreviated route form) and V (routes without a project) ──

    @Test
    fun `the abbreviated route form is translated whole, verb and argument included`() {
        val cases = mapOf(
            "Capturing in RECORDING. Call POST …/session/mode with mode:'MOCKK' to make them fire."
                to """mockkhttp_session {"action":"set_mode"} with mode:'MOCKK'""",
            "Stop it first (POST …/session/stop) if you need a different device."
                to """(mockkhttp_session {"action":"stop"})""",
            "Nothing was capturing. POST …/session/start begins a session."
                to """mockkhttp_session {"action":"start"}""",
            "DELETE …/flows clears the whole journal." to """mockkhttp_session {"action":"clear_flows"}""",
            "Try GET …/flows/{flow_id} for one flow." to """mockkhttp_flows {"action":"get","flow_id":"{flow_id}"}""",
            "POST …/mocks/{rule_id}/enable flips it." to """mockkhttp_mocks {"action":"set_enabled","rule_id":"{rule_id}"}"""
        )
        for ((hint, expected) in cases) {
            val out = client.toolSpeak(hint)
            assertTrue(out.contains(expected), "$hint -> $out")
            assertFalse(out.contains("…/"), "the abbreviated verb must not survive glued to the call: $out")
            assertFalse(Regex("""(?:POST|GET|PUT|DELETE)\s+mockkhttp_""").containsMatchIn(out), out)
        }
    }

    @Test
    fun `the routes without a project are translated too`() {
        val docs = client.toolSpeak("Call GET /v1/docs?topic=quickstart first. Every topic states plainly what it does.")
        assertTrue(docs.contains("""mockkhttp_docs {"topic":"quickstart"}"""), docs)
        assertFalse(docs.contains("/v1/"), docs)

        val projects = client.toolSpeak("Call GET /v1/projects and use one of the returned project_id values.")
        assertTrue(projects.contains("mockkhttp_status"), projects)
        assertFalse(projects.contains("/v1/"), projects)

        val meta = client.toolSpeak("Call GET /v1/meta first — it reports api_version.")
        assertTrue(meta.contains("mockkhttp_status"), meta)
        assertFalse(meta.contains("/v1/"), meta)

        // The project-scoped routes still win over the bare /v1/projects entry.
        val scoped = client.toolSpeak("GET /v1/projects/abc/status")
        assertEquals("mockkhttp_status", scoped)
    }

    @Test
    fun `a docs page is advice too, so its routes become tool calls`() {
        val payload = JsonParser.parseString(
            """{"topic":"flows","markdown":"3. `GET /v1/projects/{pid}/flows?limit=25` — summaries only.","related_routes":["GET /v1/projects/{pid}/flows"]}"""
        ).asJsonObject

        val out = client.toolSpeakPayload(payload).asJsonObject

        assertTrue(out.get("markdown").asString.contains("""mockkhttp_flows {"action":"list","limit":25}"""), out.toString())
        assertEquals("GET /v1/projects/{pid}/flows", out.getAsJsonArray("related_routes").get(0).asString, "related_routes is data about the REST API and stays as written")
    }
}
