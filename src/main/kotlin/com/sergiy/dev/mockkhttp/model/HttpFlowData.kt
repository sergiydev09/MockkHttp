package com.sergiy.dev.mockkhttp.model

/**
 * Data classes for HTTP flow information received from mitmproxy addon.
 */

/**
 * Complete HTTP flow data from mitmproxy.
 */
data class HttpFlowData(
    val flowId: String,
    val paused: Boolean,
    val request: HttpRequestData,
    val response: HttpResponseData?,
    val timestamp: Double,
    val duration: Double,
    val mockApplied: Boolean = false,
    val mockRuleName: String? = null,
    val mockRuleId: String? = null,
    val modified: Boolean = false  // True if response was modified in Debug mode
)

/**
 * HTTP request data.
 */
data class HttpRequestData(
    val method: String,
    val url: String,
    val host: String,
    val path: String,
    val headers: Map<String, String>,
    val content: String
) {
    fun getShortUrl(): String {
        val maxLength = 60
        return if (url.length > maxLength) {
            url.take(maxLength) + "..."
        } else {
            url
        }
    }
}

/**
 * HTTP response data.
 */
data class HttpResponseData(
    val statusCode: Int,
    val reason: String,
    val headers: Map<String, String>,
    val content: String
) {
    fun getDisplayStatus(): String = "$statusCode $reason"

    fun getContentType(): String? = headers["Content-Type"] ?: headers["content-type"]
}

/**
 * Modified response data to send back to mitmproxy.
 */
data class ModifiedResponseData(
    val statusCode: Int? = null,
    val headers: Map<String, String>? = null,
    val content: String? = null
)

/**
 * Request to resume a paused flow.
 */
data class ResumeFlowRequest(
    @com.google.gson.annotations.SerializedName("flow_id")
    val flowId: String,
    @com.google.gson.annotations.SerializedName("modified_response")
    val modifiedResponse: ModifiedResponseData? = null
)

/**
 * Mock check request from Android app - sent BEFORE making real network call.
 * This allows the plugin to respond with a mock if available, skipping the real network call.
 */
data class MockCheckRequest(
    val type: String,  // "CHECK_MOCK"
    val request: MockRequestData,
    val projectId: String? = null,
    val packageName: String? = null
)

/**
 * Simplified request data for mock checking (no response needed).
 */
data class MockRequestData(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String
)

/**
 * Mock check response - plugin responds with mock data if available.
 */
data class MockCheckResponse(
    val hasMock: Boolean,
    val mode: String? = null,  // Current plugin mode: "RECORDING", "DEBUG", "MOCKK", "MOCKK_DEBUG"
    val statusCode: Int? = null,
    val headers: Map<String, String>? = null,
    val body: String? = null,
    val mockRuleName: String? = null
) {
    companion object {
        fun noMock(mode: String) = MockCheckResponse(hasMock = false, mode = mode)
        fun noMockUnknown() = MockCheckResponse(hasMock = false, mode = "RECORDING")
    }
}