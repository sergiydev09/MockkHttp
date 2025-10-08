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
                // Pass through without intercepting
                return chain.proceed(chain.request())
            }
        } catch (e: Exception) {
            // If we can't determine build type, assume it's safe (debug)
        }

        if (!isEnabled) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()

        // Proceed with request to get response
        val response: Response
        try {
            response = chain.proceed(request)
        } catch (e: IOException) {
            // Network error, can't intercept
            throw e
        }

        val duration = System.currentTimeMillis() - startTime

        // Check if plugin is connected
        if (!isPluginConnected()) {
            return response
        }

        return try {
            if (debugMode) {
                // Debug mode: pause and wait for modification
                val result = sendToPluginAndWait(request, response, duration) ?: response
                result
            } else {
                // Recording mode: just send async without waiting
                sendToPluginAsync(request, response, duration)
                response
            }
        } catch (e: Exception) {
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
            return false
        }

        // Use cached result if still valid (within PING_CACHE_DURATION_MS)
        val now = System.currentTimeMillis()
        if (now - lastPingTime < PING_CACHE_DURATION_MS) return lastPingResult

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
                    failedAttempts = 0  // Reset failure counter on success
                }

                success
            }
        } catch (e: Exception) {
            failedAttempts++
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


                // WAIT for modified response (blocks thread)
                val reader = it.getInputStream().bufferedReader()
                val modifiedJson = reader.readLine()

                if (modifiedJson == null || modifiedJson == "PONG") {
                    // Plugin sent PONG (ping response) or nothing, use original
                    return originalResponse
                }

                val modifiedData = gson.fromJson(modifiedJson, ModifiedResponseData::class.java)

                // Build modified response
                buildModifiedResponse(originalResponse, modifiedData)
            }
        } catch (e: SocketTimeoutException) {
            null
        } catch (e: IOException) {
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

                }
            } catch (e: Exception) {
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

            val body = response.peekBody(maxSize).string()
            body
        } catch (e: Exception) {
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

        // If nothing was modified, return original as-is
        if (modified.statusCode == null && modified.headers == null && modified.body == null) {
            return original
        }

        val statusCode = modified.statusCode ?: original.code
        val originalBodySize = original.body?.contentLength() ?: 0


        // If body was modified, use it. Otherwise, keep original body.
        val responseBody = if (modified.body != null) {
            val contentType = original.body?.contentType() ?: "application/json".toMediaType()
            val newBody = modified.body.toResponseBody(contentType)
            newBody
        } else {
            // Keep original body
            original.body
        }

        var builder = original.newBuilder()
            .code(statusCode)

        // Only set body if we have one
        if (responseBody != null) {
            builder = builder.body(responseBody)
        } else {
        }

        // Apply modified headers (compatible with API 21+)
        modified.headers?.let { headers ->
            for ((key, value) in headers) {
                builder = builder.header(key, value)
            }
        }

        val result = builder.build()
        val resultBodySize = result.body?.contentLength() ?: 0

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
