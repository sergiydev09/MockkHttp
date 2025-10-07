package com.sergiy.dev.mockkhttp.interceptor

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * OkHttp Interceptor that captures and sends HTTP requests/responses to IntelliJ MockkHttp plugin.
 * Can be injected manually or automatically via Gradle plugin.
 *
 * Usage (manual):
 * ```kotlin
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(MockkHttpInterceptor(context))
 *     .build()
 * ```
 *
 * Or add Gradle plugin for automatic injection:
 * ```kotlin
 * plugins {
 *     id("com.sergiy.dev.mockkhttp")
 * }
 * ```
 */
class MockkHttpInterceptor @JvmOverloads constructor(
    context: Context? = null,
    private val pluginHost: String = "10.0.2.2",  // Host machine from emulator
    private val pluginPort: Int = 9876
) : Interceptor {

    private val appContext: Context? = context ?: getApplicationContextViaReflection()
    private val gson = Gson()

    companion object {
        private const val TAG = "MockkHttpInterceptor"
        private const val CONNECTION_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 60000  // 60s for user to modify
        private const val PING_TIMEOUT_MS = 500    // Fast ping timeout
        private const val PING_CACHE_DURATION_MS = 5000  // Cache ping result for 5s

        /**
         * Enable/disable interceptor globally.
         * Set to false to disable without removing interceptor.
         */
        @JvmStatic
        var isEnabled = true

        /**
         * Debug mode: pauses thread and waits for user modification.
         * Recording mode: just sends to plugin without pausing.
         */
        @JvmStatic
        var debugMode = true

        // Plugin connection state cache
        @Volatile
        private var lastPingTime: Long = 0
        @Volatile
        private var lastPingResult: Boolean = false
        @Volatile
        private var failedAttempts: Int = 0
        private const val MAX_FAILED_ATTEMPTS = 3  // After 3 fails, stop trying

        /**
         * Obtain Application context via reflection when constructor context is null.
         * This is used when Gradle plugin injects the interceptor without access to Context.
         */
        private fun getApplicationContextViaReflection(): Context? {
            return try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val method = activityThreadClass.getMethod("currentApplication")
                method.invoke(null) as? Context
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get Application context via reflection", e)
                null
            }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        // SECURITY: Double-check we're not in a release build
        // This is a fail-safe in case the Gradle plugin was bypassed
        try {
            val buildConfigClass = Class.forName("${appContext?.packageName}.BuildConfig")
            val debugField = buildConfigClass.getDeclaredField("DEBUG")
            val isDebugBuild = debugField.getBoolean(null)

            if (!isDebugBuild) {
                Log.e(TAG, "❌ SECURITY: MockkHttpInterceptor detected in RELEASE build! This should never happen.")
                Log.e(TAG, "❌ The interceptor will be disabled to prevent security issues.")
                // Pass through without intercepting
                return chain.proceed(chain.request())
            }
        } catch (e: Exception) {
            // If we can't determine build type, assume it's safe (debug)
            Log.w(TAG, "Could not determine build type, assuming debug: ${e.message}")
        }

        if (!isEnabled) {
            Log.v(TAG, "Interceptor disabled, passing through")
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()

        Log.d(TAG, "🔵 Intercepting: ${request.method} ${request.url}")

        // Proceed with request to get response
        val response: Response
        try {
            response = chain.proceed(request)
            Log.d(TAG, "✅ Got response: ${response.code} for ${request.url}")
        } catch (e: IOException) {
            // Network error, can't intercept
            Log.e(TAG, "❌ Network error for ${request.url}", e)
            throw e
        }

        val duration = System.currentTimeMillis() - startTime

        // Check if plugin is connected
        if (!isPluginConnected()) {
            Log.d(TAG, "⚠️ Plugin not connected, skipping interception for ${request.url}")
            return response
        }

        return try {
            if (debugMode) {
                // Debug mode: pause and wait for modification
                Log.d(TAG, "⏸️ DEBUG MODE: Waiting for user input for ${request.url}")
                val result = sendToPluginAndWait(request, response, duration) ?: response
                Log.d(TAG, "▶️ Continuing with response for ${request.url}")
                result
            } else {
                // Recording mode: just send async without waiting
                Log.d(TAG, "📝 RECORDING MODE: Sending async for ${request.url}")
                sendToPluginAsync(request, response, duration)
                response
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error intercepting request for ${request.url}", e)
            response
        }
    }

    /**
     * Quick check if plugin is listening on the port.
     * Uses caching to avoid repeated socket connections.
     * After MAX_FAILED_ATTEMPTS consecutive failures, stops trying to connect.
     */
    private fun isPluginConnected(): Boolean {
        // If we've failed too many times, stop trying (failsafe mode)
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            Log.d(TAG, "⚠️ Plugin connection disabled after $failedAttempts failed attempts (failsafe mode)")
            return false
        }

        // Use cached result if still valid (within PING_CACHE_DURATION_MS)
        val now = System.currentTimeMillis()
        if (now - lastPingTime < PING_CACHE_DURATION_MS) {
            Log.v(TAG, "Using cached ping result: $lastPingResult")
            return lastPingResult
        }

        // Perform actual ping with fast timeout
        val connected = try {
            Socket(pluginHost, pluginPort).use { socket ->
                socket.soTimeout = PING_TIMEOUT_MS
                socket.getOutputStream().write("PING\n".toByteArray())
                socket.getOutputStream().flush()

                val response = ByteArray(4)
                val read = socket.getInputStream().read(response)
                val success = read > 0 && String(response, 0, read).startsWith("PONG")

                if (success) {
                    Log.d(TAG, "✅ Plugin connected (ping successful)")
                    failedAttempts = 0  // Reset failure counter on success
                } else {
                    Log.d(TAG, "⚠️ Plugin ping failed (invalid response)")
                }

                success
            }
        } catch (e: Exception) {
            Log.d(TAG, "⚠️ Plugin not reachable: ${e.message}")
            failedAttempts++
            Log.d(TAG, "Failed attempts: $failedAttempts / $MAX_FAILED_ATTEMPTS")
            false
        }

        // Update cache
        lastPingTime = now
        lastPingResult = connected

        return connected
    }

    /**
     * Send flow to plugin and WAIT for modified response (blocks thread).
     * Used in Debug mode.
     */
    private fun sendToPluginAndWait(
        request: Request,
        originalResponse: Response,
        duration: Long
    ): Response? {
        return try {
            val socket = Socket(pluginHost, pluginPort)
            socket.soTimeout = READ_TIMEOUT_MS

            socket.use {
                val flowData = serializeFlow(request, originalResponse, duration)

                // Send flow data
                val json = gson.toJson(flowData) + "\n"
                it.getOutputStream().write(json.toByteArray())
                it.getOutputStream().flush()

                Log.d(TAG, "Sent flow to plugin: ${request.method} ${request.url}")

                // WAIT for modified response (blocks thread)
                val reader = it.getInputStream().bufferedReader()
                val modifiedJson = reader.readLine()

                if (modifiedJson == null || modifiedJson == "PONG") {
                    // Plugin sent PONG (ping response) or nothing, use original
                    Log.d(TAG, "📡 Received PONG or null, using original response")
                    return originalResponse
                }

                Log.d(TAG, "📥 Received from plugin: $modifiedJson")
                val modifiedData = gson.fromJson(modifiedJson, ModifiedResponseData::class.java)

                Log.d(TAG, "✅ Parsed modified response from plugin")

                // Build modified response
                buildModifiedResponse(originalResponse, modifiedData)
            }
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Timeout waiting for plugin response, using original")
            null
        } catch (e: IOException) {
            Log.e(TAG, "IO error communicating with plugin", e)
            null
        }
    }

    /**
     * Send flow to plugin async without waiting.
     * Used in Recording mode.
     */
    private fun sendToPluginAsync(
        request: Request,
        response: Response,
        duration: Long
    ) {
        Thread {
            try {
                val socket = Socket(pluginHost, pluginPort)
                socket.soTimeout = CONNECTION_TIMEOUT_MS

                socket.use {
                    val flowData = serializeFlow(request, response, duration)
                    val json = gson.toJson(flowData) + "\n"
                    it.getOutputStream().write(json.toByteArray())
                    it.getOutputStream().flush()

                    Log.d(TAG, "Sent flow to plugin (async): ${request.method} ${request.url}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending flow async", e)
            }
        }.start()
    }

    /**
     * Serialize Request and Response to FlowData.
     */
    private fun serializeFlow(
        request: Request,
        response: Response,
        duration: Long
    ): FlowData {
        // Read request body safely (if available)
        val requestBody = try {
            // Request body is already consumed at this point, we can't read it
            // This would require using a logging interceptor before this one
            // For now, we'll just capture headers and URL
            ""
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read request body", e)
            ""
        }

        // Read response body safely without consuming it
        val responseBodyString = try {
            // Use 5MB max buffer to support images and allow binary modification
            // Note: This is the MAX size, actual memory usage equals response size
            val contentLength = response.body?.contentLength() ?: 0
            val maxSize = if (contentLength > 0) {
                minOf(contentLength, 5 * 1024 * 1024) // Max 5MB
            } else {
                5 * 1024 * 1024 // Default 5MB
            }

            Log.d(TAG, "📖 Reading response body: contentLength=$contentLength, maxSize=$maxSize")
            val body = response.peekBody(maxSize).string()
            Log.d(TAG, "✅ Read ${body.length} chars from response body")
            body
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to read response body", e)
            ""
        }

        return FlowData(
            flowId = java.util.UUID.randomUUID().toString(),
            request = RequestData(
                method = request.method,
                url = request.url.toString(),
                headers = request.headers.toMap(),
                body = requestBody
            ),
            response = ResponseData(
                statusCode = response.code,
                headers = response.headers.toMap(),
                body = responseBodyString
            ),
            timestamp = System.currentTimeMillis(),
            duration = duration,
            projectId = null,  // Will be set by Gradle plugin injection
            packageName = appContext?.packageName  // Include package name for routing
        )
    }

    /**
     * Build modified Response from plugin data.
     */
    private fun buildModifiedResponse(
        original: Response,
        modified: ModifiedResponseData
    ): Response {
        Log.d(TAG, "🔧 Building response - Modified: statusCode=${modified.statusCode}, hasHeaders=${modified.headers != null}, hasBody=${modified.body != null}")

        // If nothing was modified, return original as-is
        if (modified.statusCode == null && modified.headers == null && modified.body == null) {
            Log.d(TAG, "✅ Nothing modified, returning original response")
            return original
        }

        val statusCode = modified.statusCode ?: original.code
        val originalBodySize = original.body?.contentLength() ?: 0

        Log.d(TAG, "📊 Original: statusCode=${original.code}, bodySize=${originalBodySize}")

        // If body was modified, use it. Otherwise, keep original body.
        val responseBody = if (modified.body != null) {
            val contentType = original.body?.contentType() ?: "application/json".toMediaType()
            val newBody = modified.body.toResponseBody(contentType)
            Log.d(TAG, "🔄 Using MODIFIED body (${modified.body.length} chars)")
            newBody
        } else {
            // Keep original body
            Log.d(TAG, "♻️ Keeping ORIGINAL body (${originalBodySize} bytes)")
            original.body
        }

        var builder = original.newBuilder()
            .code(statusCode)

        // Only set body if we have one
        if (responseBody != null) {
            builder = builder.body(responseBody)
            Log.d(TAG, "✅ Body set successfully")
        } else {
            Log.w(TAG, "⚠️ No body to set!")
        }

        // Apply modified headers (compatible with API 21+)
        modified.headers?.let { headers ->
            Log.d(TAG, "📝 Applying ${headers.size} modified headers")
            for ((key, value) in headers) {
                builder = builder.header(key, value)
            }
        }

        val result = builder.build()
        val resultBodySize = result.body?.contentLength() ?: 0
        Log.d(TAG, "✅ Final response: statusCode=${result.code}, bodySize=${resultBodySize}")

        return result
    }

    /**
     * Convert OkHttp Headers to Map.
     */
    private fun Headers.toMap(): Map<String, String> {
        return names().associateWith { name ->
            get(name) ?: ""
        }
    }
}
