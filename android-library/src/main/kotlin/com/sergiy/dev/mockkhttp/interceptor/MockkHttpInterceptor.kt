package com.sergiy.dev.mockkhttp.interceptor

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer
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

        /**
         * Mode the plugin reports when NO capture session owns this app's traffic —
         * nobody pressed Start, or a running session's package filter excludes us.
         *
         * It is also the fallback when the plugin answers without naming a mode. That
         * used to be "RECORDING": an idle IDE therefore cost every single request a
         * full response body read plus a second socket to ship a flow the plugin then
         * discarded. Assuming IDLE when we do not know is the cheap reading of the same
         * uncertainty, and the very next request asks again.
         */
        const val MODE_IDLE = "IDLE"

        /**
         * Reported to the plugin in every message. Keep equal to the `version` in
         * android-library/build.gradle.kts when the AAR is published.
         */
        const val LIBRARY_VERSION = "1.8.0"

        // ── the numbers ──
        // Nothing here drops a request any more; these exist so that "one request, one flow"
        // can be checked from outside. Sent with every message, shown by the plugin as `client`.
        private val flowsSent = java.util.concurrent.atomic.AtomicLong(0)
        private val passesYielded = java.util.concurrent.atomic.AtomicLong(0)

        /** New on every process start, so a reader can tell a restart from silence. */
        private val runId: String = java.util.UUID.randomUUID().toString().take(8)
        private val startedAt: Long = System.currentTimeMillis()

        /** What this library says about itself; attached to every CHECK_MOCK and FLOW. */
        @JvmStatic
        private val reportSeq = java.util.concurrent.atomic.AtomicLong()

        fun clientReport(): ClientReport = ClientReport(
            library = "android-okhttp",
            version = LIBRARY_VERSION,
            platform = "Android",
            runId = runId,
            startedAt = startedAt,
            seq = reportSeq.incrementAndGet(),
            dedup = mapOf("enabled" to enableDeduplication, "window_ms" to 0, "controllable" to false),
            caps = listOf("identityTag", "idle"),
            stats = mapOf(
                "flows_sent" to flowsSent.get(),
                "passes_yielded" to passesYielded.get()
            )
        )

        /** Test seam: zero the counters. */
        internal fun resetStatsForTests() {
            flowsSent.set(0)
            passesYielded.set(0)
        }
        private const val CONNECTION_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 60000  // 60s for user to modify
        private const val PING_TIMEOUT_MS = 500    // Fast ping timeout
        private const val PING_CACHE_DURATION_MS = 5000  // Cache ping result for 5s
        private const val MAX_REQUEST_BODY_SIZE = 5L * 1024 * 1024  // 5MB cap, mirrors the response cap

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
         * Whether a second copy of this interceptor in the same chain steps aside for a request the
         * first copy already captured.
         *
         * Until 1.8 this was a 500 ms window keyed on method + URL: a second identical request
         * inside it was dropped inside the app — never captured, never mockable, with no counter
         * anywhere. Two screens asking for the same forecast 12 ms apart is normal app behaviour,
         * and so is an immediate retry, which is exactly what `await_flow count:2` exists to
         * observe. What the window was really for is ONE request seen by TWO copies of this
         * interceptor (a builder built twice, a client derived with `newBuilder()`, a manual add
         * next to the injected one). That is a question of identity, not of time, so it is now
         * answered with a tag on the request itself — see [Captured]. Off, every copy captures.
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

        /**
         * Add the interceptor to [builder] unless it already carries one, and return [builder].
         *
         * This is what the Gradle plugin's bytecode transform calls in front of every
         * `OkHttpClient.Builder.build()`. Before it, every `build()` gained a fresh copy: a builder
         * built twice, or one derived from an existing client with `newBuilder()`, ended up with
         * two, and the same request was captured twice. Also the right call for a hand-written
         * setup that may run more than once.
         */
        @JvmStatic
        fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder {
            if (builder.interceptors().none { it is MockkHttpInterceptor }) {
                builder.addInterceptor(MockkHttpInterceptor(null))
            }
            return builder
        }

        /** Test seam: the ping cache is static, so one test's answer would otherwise leak into the next. */
        internal fun resetConnectionStateForTests() {
            lastPingTime = 0
            lastPingResult = false
            failedAttempts = 0
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
            // Platform types: a device always fills these in, the android.jar stubs of a JVM unit
            // test do not. Treating them as empty there costs nothing on a device.
            val fingerprint = Build.FINGERPRINT ?: ""
            val model = Build.MODEL ?: ""
            val hardware = Build.HARDWARE ?: ""
            val product = Build.PRODUCT ?: ""
            return fingerprint.startsWith("generic") ||
                    fingerprint.startsWith("unknown") ||
                    model.contains("google_sdk") ||
                    model.contains("Emulator") ||
                    model.contains("Android SDK built for") ||
                    hardware.contains("goldfish") ||
                    hardware.contains("ranchu") ||
                    product.contains("sdk") ||
                    product.contains("emulator")
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

    /**
     * The tag this interceptor leaves on a request it has captured, so a second copy of itself
     * further down the same chain lets that request through. Typed tags are invisible to the app:
     * `Request.tag()` without a class still returns whatever the app set.
     */
    object Captured

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

        val original = chain.request()
        val startTime = System.currentTimeMillis()

        // Another copy of this interceptor earlier in the chain has already captured this request
        // and tagged it. Identity, not a clock: a second, genuine request to the same URL carries
        // no tag and is captured on its own, however close behind it comes.
        if (enableDeduplication && original.tag(Captured::class.java) != null) {
            passesYielded.incrementAndGet()
            return chain.proceed(original)
        }

        // Check if plugin is connected
        if (!isPluginConnected()) {
            return chain.proceed(original)
        }

        // From here on this copy owns the capture: every copy after it sees the tag.
        val request = original.newBuilder().tag(Captured::class.java, Captured).build()

        // STEP 1: Check plugin mode and mock availability
        val mockCheckResponse = checkForMock(request)
        val pluginMode = mockCheckResponse?.mode ?: MODE_IDLE

        Log.d(TAG, "🎯 Plugin mode: $pluginMode, Has mock: ${mockCheckResponse?.hasMock ?: false}")

        // STEP 2: Decide flow based on mode
        return when (pluginMode) {
            MODE_IDLE -> {
                // Nothing is listening: capturing would read the whole response body and open a
                // second socket for a flow the plugin drops on arrival. Pass through untouched.
                val response = chain.proceed(request)
                response
            }

            "RECORDING" -> {
                // RECORDING: Make real call, send async, don't block
                val response = chain.proceed(request)
                val duration = System.currentTimeMillis() - startTime
                sendToPluginAsync(request, response, duration)
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
                modifiedResponse
            }

            "MOCKK" -> {
                // MOCKK: Use mock if available (no network), NO dialog
                val response: Response
                if (mockCheckResponse?.hasMock == true) {
                    Log.d(TAG, "⚡ Mock available! Skipping network call, NO dialog")
                    response = buildMockResponse(request, mockCheckResponse)
                } else {
                    // No mock, make real call
                    response = chain.proceed(request)
                }
                val duration = System.currentTimeMillis() - startTime
                sendToPluginAsync(request, response, duration)
                response
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
                modifiedResponse
            }

            else -> {
                // Unknown mode, fallback to simple pass-through
                Log.w(TAG, "Unknown mode: $pluginMode, using pass-through")
                val response = chain.proceed(request)
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

                // Announce the package, not just "PING": the plugin records the
                // name in knownMockkHttpPackages and AppManager uses that set to
                // list interceptable apps. Sending a bare PING (as this did until
                // now) meant native Android apps never registered, so only Flutter
                // apps — which have always sent PING:<package> — showed up.
                val packageName = appContext?.packageName
                val ping = if (packageName.isNullOrBlank()) "PING" else "PING:$packageName"
                socket.getOutputStream().write("$ping\n".toByteArray())
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
                    client = clientReport(),
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
     * Read the request body as text when it is safe to do so.
     *
     * Because this is an application interceptor, the body has not been transmitted yet, so
     * buffering it here does NOT consume it for the real network call. One-shot and duplex
     * bodies are skipped (reading them would consume the only available stream), and clearly
     * binary payloads are reported as a placeholder instead of garbage text.
     */
    private fun readRequestBody(request: Request): String {
        val body = request.body ?: return ""
        return try {
            if (body.isDuplex() || body.isOneShot()) {
                return ""  // Cannot be read without consuming the stream
            }

            // Buffer through a size-bounded Sink so a large upload (file/multipart) cannot be
            // fully materialized in memory and OOM the device. Memory stays capped even when
            // the body has an unknown content length. Mirrors the 5MB cap on the response side.
            val collector = Buffer()
            try {
                val boundedSink = object : Sink {
                    override fun write(source: Buffer, byteCount: Long) {
                        if (collector.size + byteCount > MAX_REQUEST_BODY_SIZE) {
                            throw BodyTooLargeException()
                        }
                        collector.write(source, byteCount)
                    }
                    override fun flush() {}
                    override fun timeout(): Timeout = Timeout.NONE
                    override fun close() {}
                }
                boundedSink.buffer().use { sink ->
                    body.writeTo(sink)
                }
            } catch (e: BodyTooLargeException) {
                val size = try { body.contentLength() } catch (ex: Exception) { -1L }
                return if (size >= 0) "<body too large to capture: $size bytes>" else "<body too large to capture>"
            }

            if (!collector.isProbablyUtf8()) {
                val size = try { body.contentLength() } catch (e: Exception) { collector.size }
                return if (size >= 0) "<binary body: $size bytes>" else "<binary body>"
            }

            val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            collector.readString(charset)
        } catch (e: Exception) {
            Log.d(TAG, "Could not read request body: ${e.message}")
            ""
        }
    }

    /**
     * Heuristic to detect whether a buffer holds UTF-8 text (mirrors OkHttp's logging interceptor).
     * Inspects the first 16 characters; non-whitespace control characters indicate binary content.
     */
    private fun Buffer.isProbablyUtf8(): Boolean {
        return try {
            val prefix = Buffer()
            val byteCount = if (size < 64) size else 64
            copyTo(prefix, 0, byteCount)
            for (i in 0 until 16) {
                if (prefix.exhausted()) break
                val codePoint = prefix.readUtf8CodePoint()
                if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) {
                    return false
                }
            }
            true
        } catch (e: Exception) {
            false  // Truncated UTF-8 sequence or other decode error => treat as binary
        }
    }

    /**
     * Serialize Request and Response to FlowData.
     */
    private fun serializeFlow(
        request: Request,
        response: Response,
        duration: Long
    ): FlowData {
        // Read request body safely.
        // NOTE: This is injected as an APPLICATION interceptor (.addInterceptor), so at this
        // point the body has NOT been sent yet and can be buffered without consuming it —
        // OkHttp re-invokes RequestBody.writeTo() when it actually transmits the request.
        val requestBody = readRequestBody(request)

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

        flowsSent.incrementAndGet()
        return FlowData(
            client = clientReport(),
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

/**
 * Signals that a request body exceeded the maximum capture size while being buffered, so reading
 * is aborted instead of materializing the whole payload in memory.
 */
private class BodyTooLargeException : IOException()
