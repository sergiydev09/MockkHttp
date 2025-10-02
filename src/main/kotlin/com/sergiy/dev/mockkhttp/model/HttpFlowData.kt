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
    val mockRuleId: String? = null
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