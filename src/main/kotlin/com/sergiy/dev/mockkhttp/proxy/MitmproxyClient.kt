package com.sergiy.dev.mockkhttp.proxy

import com.google.gson.Gson
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import com.sergiy.dev.mockkhttp.model.ModifiedResponseData
import com.sergiy.dev.mockkhttp.model.ResumeFlowRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HTTP client for communicating with mitmproxy addon's control API.
 * Sends resume commands to continue paused flows.
 */
class MitmproxyClient(
    private val logger: MockkHttpLogger,
    private val controlPort: Int = 9999
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    /**
     * Resume a paused flow with optional modifications.
     */
    fun resumeFlow(flowId: String, modifiedResponse: ModifiedResponseData? = null): Boolean {
        logger.info("Resuming flow: $flowId${if (modifiedResponse != null) " (modified)" else " (original)"}")

        return try {
            val resumeRequest = ResumeFlowRequest(
                flowId = flowId,
                modifiedResponse = modifiedResponse
            )

            val json = gson.toJson(resumeRequest)
            logger.debug("Resume request JSON: $json")

            val requestBody = json.toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("http://localhost:$controlPort/resume")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    logger.info("✅ Flow resumed successfully")
                    true
                } else {
                    logger.error("❌ Failed to resume flow: ${response.code} ${response.message}")
                    false
                }
            }

        } catch (e: Exception) {
            logger.error("Failed to resume flow", e)
            false
        }
    }
}