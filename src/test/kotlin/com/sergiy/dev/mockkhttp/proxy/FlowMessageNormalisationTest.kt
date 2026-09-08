package com.sergiy.dev.mockkhttp.proxy

import com.google.gson.Gson
import com.google.gson.JsonParser
import junit.framework.TestCase

/**
 * Audit round 12, AS: a FLOW with a missing or null field used to parse (Gson ignores Kotlin
 * nullability), NPE in convertToHttpFlowData, and vanish — answered with the normal {} reply,
 * never stored, never counted. The six messages of that report, and the two that cannot be flows.
 */
class FlowMessageNormalisationTest : TestCase() {

    // serializeNulls: a null header value must reach the wire as JSON null, which is what a third-party client sends.
    private val gson = com.google.gson.GsonBuilder().serializeNulls().create()

    private fun flow(vararg edits: Pair<String, Any?>): String {
        val request = mutableMapOf<String, Any?>("method" to "GET", "url" to "https://api.example.com/v1", "headers" to emptyMap<String, String>(), "body" to "")
        val response = mutableMapOf<String, Any?>("statusCode" to 200, "headers" to emptyMap<String, String>(), "body" to "{}")
        val message = mutableMapOf<String, Any?>("type" to "FLOW", "flowId" to "f1", "request" to request, "response" to response, "timestamp" to 1L, "duration" to 1L, "packageName" to "com.x")
        for ((path, value) in edits) {
            val (owner, key) = when {
                path.startsWith("request.") -> request to path.removePrefix("request.")
                path.startsWith("response.") -> response to path.removePrefix("response.")
                else -> message to path
            }
            if (value == ABSENT) owner.remove(key) else owner[key] = value
        }
        return gson.toJson(message)
    }

    private fun parse(json: String): Pair<String?, AndroidFlowData?> {
        val obj = JsonParser.parseString(json).asJsonObject
        val rejection = GlobalOkHttpInterceptorServer.normaliseFlowMessage(obj)
        return rejection to (if (rejection == null) gson.fromJson(obj, AndroidFlowData::class.java) else null)
    }

    fun testTheSixMessagesOfTheReportAllBecomeFlowsWithNonNullFields() {
        val messages = listOf(
            "well formed" to flow(),
            "request.body = null" to flow("request.body" to null),
            "request.body absent" to flow("request.body" to ABSENT),
            "response.body = null" to flow("response.body" to null),
            "request.headers absent" to flow("request.headers" to ABSENT),
            "well formed again" to flow()
        )
        for ((label, json) in messages) {
            val (rejection, data) = parse(json)
            assertNull("$label must not be rejected", rejection)
            val flow = data!!
            assertNotNull("$label: body", flow.request.body)
            assertNotNull("$label: headers", flow.request.headers)
            assertNotNull("$label: response body", flow.response.body)
            assertEquals("$label: method survives", "GET", flow.request.method)
        }
    }

    fun testAMessageThatCannotBeAFlowIsRejectedByName() {
        assertEquals("FLOW without flowId", parse(flow("flowId" to ABSENT)).first)
        assertEquals("FLOW without request", parse(flow("request" to null)).first)
        assertEquals("request without url", parse(flow("request.url" to "")).first)
        assertEquals("request without method", parse(flow("request.method" to ABSENT)).first)
    }

    fun testAMissingTimestampIsNowAndANullHeaderValueIsDropped() {
        // Audit round 13, residuals: a flow without a timestamp was dated 1970, and a header
        // whose value was null travelled inside a Map<String, String> to the REST client.
        val (rejection, data) = parse(flow("timestamp" to ABSENT, "request.headers" to mapOf("X-A" to null, "X-B" to "b")))
        assertNull(rejection)
        assertTrue("dated on arrival, not in 1970", data!!.timestamp > 1_600_000_000_000L)
        assertEquals(mapOf("X-B" to "b"), data.request.headers)

        // Audit round 14, AZ: the drop is named, never silent.
        val dropped = ArrayList<String>()
        val obj = JsonParser.parseString(flow("request.headers" to mapOf("X-A" to null, "X-N" to 42, "X-O" to mapOf("a" to 1)), "response.headers" to mapOf("X-R" to listOf("a")))).asJsonObject
        assertNull(GlobalOkHttpInterceptorServer.normaliseFlowMessage(obj, dropped))
        assertEquals(listOf("request X-A", "request X-O", "response X-R"), dropped)
        assertEquals("42", obj.getAsJsonObject("request").getAsJsonObject("headers").get("X-N").asString)
    }

    fun testAMissingResponseBecomesAnEmptyOne() {
        val (rejection, data) = parse(flow("response" to ABSENT))
        assertNull(rejection)
        assertEquals(0, data!!.response.statusCode)
        assertEquals("", data.response.body)
    }

    fun testACheckMockWithoutAUrlIsRejectedByNameAndOneWithIsNot() {
        // Adversarial review of round 12: findMockForRequest swallowed the NPE of a null URL into
        // a normal "no mock" answer, so the round-12 catch never saw it.
        val without = JsonParser.parseString("{\"type\":\"CHECK_MOCK\",\"request\":{\"method\":\"GET\",\"headers\":{},\"body\":\"\"},\"packageName\":\"com.x\"}").asJsonObject
        assertEquals("request without url", GlobalOkHttpInterceptorServer.normaliseMockCheckMessage(without))
        val noRequest = JsonParser.parseString("{\"type\":\"CHECK_MOCK\",\"packageName\":\"com.x\"}").asJsonObject
        assertEquals("CHECK_MOCK without request", GlobalOkHttpInterceptorServer.normaliseMockCheckMessage(noRequest))
        val fine = JsonParser.parseString("{\"type\":\"CHECK_MOCK\",\"request\":{\"method\":\"GET\",\"url\":\"https://api.example.com/v1\"},\"packageName\":\"com.x\"}").asJsonObject
        assertNull(GlobalOkHttpInterceptorServer.normaliseMockCheckMessage(fine))
        val parsed = gson.fromJson(fine, com.sergiy.dev.mockkhttp.model.MockCheckRequest::class.java)
        assertNotNull("headers and body get their defaults", parsed.request.headers)
        assertEquals("", parsed.request.body)
    }

    private companion object {
        val ABSENT = Any()
    }
}
