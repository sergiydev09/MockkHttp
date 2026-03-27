package com.sergiy.dev.mockkhttp.interceptor

import android.content.Context
import android.os.Build
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
    private val pluginHost: String = detectPluginHost(),
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
        private const val DEDUP_WINDOW_MS = 500  // 500ms window to detect duplicate requests

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

        /**
         * Enable/disable request deduplication.
         * When enabled, requests with same method+URL within 500ms will only be captured once.
         * This prevents duplicate flows from multiple OkHttpClient instances.
         * Set to false to capture ALL requests (useful for detecting app-level duplicate calls).
         */
        @JvmStatic
        var enableDeduplication = true

        // Plugin connection state cache
        @Volatile
        private var lastPingTime: Long = 0
        @Volatile
        private var lastPingResult: Boolean = false
        @Volatile
        private var failedAttempts: Int = 0
        private const val MAX_FAILED_ATTEMPTS = 3  // After 3 fails, stop trying

        // Request deduplication: Track active requests to prevent duplicates from multiple OkHttp clients
        private val activeRequests = java.util.concurrent.ConcurrentHashMap<String, Long>()
        @Volatile
        private var lastCleanupTime: Long = 0
        private const val CLEANUP_INTERVAL_MS = 10000  // Cleanup old entries every 10s

        /**
         * Check if this request is already being captured by another OkHttpClient instance.
         * Returns true if this is a duplicate within the deduplication window.
         */
        private fun isDuplicateRequest(request: Request): Boolean {
            if (!enableDeduplication) return false

            val now = System.currentTimeMillis()

            // Periodic cleanup of old entries to prevent memory leak
            if (now - lastCleanupTime > CLEANUP_INTERVAL_MS) {
                cleanupOldRequests(now)
                lastCleanupTime = now
            }

            // Create key: method + URL (ignore query params differences for dedup)
            val key = "${request.method}:${request.url.toUrl().run { "$protocol://$host$path" }}"

            // Try to register this request
            val existingTimestamp = activeRequests.putIfAbsent(key, now)

            if (existingTimestamp != null) {
                // Request already exists, check if within dedup window
                val timeSinceFirst = now - existingTimestamp
                if (timeSinceFirst < DEDUP_WINDOW_MS) {
                    // Duplicate detected within window
                    Log.d(TAG, "🔄 Duplicate request detected (${timeSinceFirst}ms ago): ${request.method} ${request.url}")
                    return true
                } else {
                    // Outside window, update timestamp and allow
                    activeRequests[key] = now
                }
            }

            return false
        }

        /**
         * Mark a request as completed (for cleanup).
         */
        private fun markRequestCompleted(request: Request) {
            if (!enableDeduplication) return

            val key = "${request.method}:${request.url.toUrl().run { "$protocol://$host$path" }}"
            activeRequests.remove(key)
        }

        /**
         * Remove old entries from activeRequests map to prevent memory leak.
         */
        private fun cleanupOldRequests(now: Long) {
            val iterator = activeRequests.entries.iterator()
            var removed = 0
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > DEDUP_WINDOW_MS * 2) {
                    iterator.remove()
                    removed++
                }
            }
            if (removed > 0) {
                Log.d(TAG, "🧹 Cleaned up $removed old request entries")
            }
        }

        /**
         * Detect the correct host address to reach the IntelliJ plugin.
         * - Emulator: 10.0.2.2 (special alias for host loopback)
         * - Physical device: 127.0.0.1 (via adb reverse port forwarding)
         */
        private fun detectPluginHost(): String {
            val host = if (isRunningOnEmulator()) "10.0.2.2" else "127.0.0.1"
            Log.d(TAG, "🔌 Plugin host: $host (emulator=${isRunningOnEmulator()})")
            return host
        }

        /**
         * Detect if running on an Android emulator vs a physical device.
         */
        private fun isRunningOnEmulator(): Boolean {
            return Build.FINGERPRINT.startsWith("generic") ||
                    Build.FINGERPRINT.startsWith("unknown") ||
                    Build.MODEL.contains("google_sdk") ||
                    Build.MODEL.contains("Emulator") ||
                    Build.MODEL.contains("Android SDK built for") ||
                    Build.HARDWARE.contains("goldfish") ||
                    Build.HARDWARE.contains("ranchu") ||
                    Build.PRODUCT.contains("sdk") ||
                    Build.PRODUCT.contains("emulator")
        }

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

        // Check if this is a duplicate request from another OkHttpClient instance
        if (isDuplicateRequest(request)) {
            // Skip capturing, another client already captured this request
            return chain.proceed(request)
        }

        // Check if plugin is connected
        if (!isPluginConnected()) {
            markRequestCompleted(request)
            return chain.proceed(request)
        }

        // STEP 1: Check plugin mode and mock availability
        val mockCheckResponse = checkForMock(request)
        val pluginMode = mockCheckResponse?.mode ?: "RECORDING"

        Log.d(TAG, "🎯 Plugin mode: $pluginMode, Has mock: ${mockCheckResponse?.hasMock ?: false}")

        // STEP 2: Decide flow based on mode
        return when (pluginMode) {
            "RECORDING" -> {
                // RECORDING: Make real call, send async, don't block
                val response = chain.proceed(request)
                val duration = System.currentTimeMillis() - startTime
                sendToPluginAsync(request, response, duration)
                markRequestCompleted(request)
                response
            }

            "DEBUG" -> {
                // DEBUG: Use mock if available (no network), always open dialog (blocking)
                val response: Response
                val duration: Long

                if (mockCheckResponse?.hasMock == true) {
                    Log.d(TAG, "⚡ Mock available! Skipping network call")
                    response = buildMockResponse(request, mockCheckResponse)
                    duration = System.currentTimeMillis() - startTime
                } else {
                    response = chain.proceed(request)
                    duration = System.currentTimeMillis() - startTime
                }

                // ALWAYS show dialog in DEBUG mode
                val modifiedResponse = sendToPluginAndWait(request, response, duration) ?: response
                markRequestCompleted(request)
                modifiedResponse
            }

            "MOCKK" -> {
                // MOCKK: Use mock if available (no network), NO dialog
                if (mockCheckResponse?.hasMock == true) {
                    Log.d(TAG, "⚡ Mock available! Skipping network call, NO dialog")
                    markRequestCompleted(request)
                    buildMockResponse(request, mockCheckResponse)
                } else {
                    // No mock, make real call
                    val response = chain.proceed(request)
                    markRequestCompleted(request)
                    response
                }
            }

            "MOCKK_DEBUG" -> {
                // MOCKK_DEBUG: Use mock if available (no network), ALWAYS show dialog (blocking)
                val response: Response
                val duration: Long

                if (mockCheckResponse?.hasMock == true) {
                    Log.d(TAG, "⚡ Mock available! Skipping network call, will show dialog with mock")
                    response = buildMockResponse(request, mockCheckResponse)
                    duration = System.currentTimeMillis() - startTime
                } else {
                    response = chain.proceed(request)
                    duration = System.currentTimeMillis() - startTime
                }

                // ALWAYS show dialog in MOCKK_DEBUG mode
                val modifiedResponse = sendToPluginAndWait(request, response, duration) ?: response
                markRequestCompleted(request)
                modifiedResponse
            }

            else -> {
                // Unknown mode, fallback to simple pass-through
                Log.w(TAG, "Unknown mode: $pluginMode, using pass-through")
                val response = chain.proceed(request)
                markRequestCompleted(request)
                response
            }
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
     * Check if a mock exists for this request BEFORE making the real network call.
     * Returns MockCheckResponse if mock available, null otherwise.
     * This allows skipping the expensive network call when in Mockk mode.
     */
    private fun checkForMock(request: Request): MockCheckResponse? {
        return try {
            val socket = Socket(pluginHost, pluginPort)
            socket.soTimeout = CONNECTION_TIMEOUT_MS  // Fast timeout for mock check

            socket.use {
                // Create mock check request (no response data, just request info)
                val mockCheckRequest = MockCheckRequest(
                    type = "CHECK_MOCK",
                    request = RequestData(
                        method = request.method,
                        url = request.url.toString(),
                        headers = request.headers.toMap(),
                        body = ""
                    ),
                    projectId = null,
                    packageName = appContext?.packageName
                )

                // Send check request
                val json = gson.toJson(mockCheckRequest) + "\n"
                it.getOutputStream().write(json.toByteArray())
                it.getOutputStream().flush()

                // Wait for response
                val reader = it.getInputStream().bufferedReader()
                val responseJson = reader.readLine()

                if (responseJson == null || responseJson == "PONG") {
                    return null
                }

                gson.fromJson(responseJson, MockCheckResponse::class.java)
            }
        } catch (e: SocketTimeoutException) {
            Log.d(TAG, "Mock check timeout for ${request.url}")
            null
        } catch (e: IOException) {
            Log.d(TAG, "Mock check failed: ${e.message}")
            null
        }
    }

    /**
     * Build an OkHttp Response from mock data WITHOUT making a real network call.
     */
    private fun buildMockResponse(request: Request, mockData: MockCheckResponse): Response {
        val statusCode = mockData.statusCode ?: 200
        val body = mockData.body ?: ""
        val headers = mockData.headers ?: emptyMap()

        val contentType = headers["Content-Type"] ?: "application/json"
        val responseBody = body.toResponseBody(contentType.toMediaType())

        var builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(statusCode)
            .message(getHttpMessage(statusCode))
            .body(responseBody)

        // Add headers
        for ((key, value) in headers) {
            builder = builder.header(key, value)
        }

        Log.d(TAG, "✅ Built mock response (${mockData.mockRuleName ?: "unnamed"}): $statusCode")
        return builder.build()
    }

    /**
     * Get HTTP status message for code.
     */
    private fun getHttpMessage(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> "Unknown"
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
