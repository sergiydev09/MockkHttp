package com.sergiy.dev.mockkhttp.interceptor

/**
 * Flow data sent from Android app to IntelliJ plugin.
 */
data class FlowData(
    val flowId: String,
    val request: RequestData,
    val response: ResponseData,
    val timestamp: Long,
    val duration: Long
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
