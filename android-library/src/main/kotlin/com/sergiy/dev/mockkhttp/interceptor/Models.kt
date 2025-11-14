package com.sergiy.dev.mockkhttp.interceptor

/**
 * Flow data sent from Android app to IntelliJ plugin.
 */
data class FlowData(
    val type: String = "FLOW",          // Message type: "FLOW" or "CHECK_MOCK"
    val flowId: String,
    val request: RequestData,
    val response: ResponseData,
    val timestamp: Long,
    val duration: Long,
    val projectId: String? = null,      // Optional: helps route to correct project
    val packageName: String? = null     // Optional: app package name for routing
)

/**
 * HTTP Request data.
 */
data class RequestData(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String
)

/**
 * HTTP Response data.
 */
data class ResponseData(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String
)

/**
 * Modified response data received from IntelliJ plugin.
 */
data class ModifiedResponseData(
    val statusCode: Int?,
    val headers: Map<String, String>?,
    val body: String?
) {
    companion object {
        /**
         * Returns empty modification (use original).
         */
        fun original() = ModifiedResponseData(null, null, null)
    }
}

/**
 * Mock check request - sent BEFORE making real network call to check if mock exists.
 * This allows skipping the real network call when in MOCKK mode.
 */
data class MockCheckRequest(
    val type: String = "CHECK_MOCK",  // Message type identifier
    val request: RequestData,
    val projectId: String? = null,
    val packageName: String? = null
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
        /**
         * No mock available, proceed with real request.
         */
        fun noMock(mode: String) = MockCheckResponse(hasMock = false, mode = mode)
        fun noMockUnknown() = MockCheckResponse(hasMock = false, mode = "RECORDING")
    }
}
