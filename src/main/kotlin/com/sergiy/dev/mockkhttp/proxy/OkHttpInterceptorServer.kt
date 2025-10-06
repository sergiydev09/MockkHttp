package com.sergiy.dev.mockkhttp.proxy

import com.google.gson.Gson
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import com.sergiy.dev.mockkhttp.model.HttpFlowData
import com.sergiy.dev.mockkhttp.model.HttpRequestData
import com.sergiy.dev.mockkhttp.model.HttpResponseData
import com.sergiy.dev.mockkhttp.store.FlowStore
import com.sergiy.dev.mockkhttp.ui.DebugInterceptDialog
import java.io.BufferedReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

/**
 * Server that listens for connections from MockkHttpInterceptor in Android apps.
 * Handles flow interception without using external proxies (no mitmproxy).
 *
 * This is the "Interceptor Mode" architecture.
 */
@Service(Service.Level.PROJECT)
class OkHttpInterceptorServer(private val project: Project) {

    private val logger = MockkHttpLogger.getInstance(project)
    private val flowStore = FlowStore.getInstance(project)
    private val mockkRulesStore = com.sergiy.dev.mockkhttp.store.MockkRulesStore.getInstance(project)
    private val gson = Gson()

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var serverThread: Thread? = null

    @Volatile
    private var currentMode = Mode.RECORDING

    enum class Mode {
        RECORDING,  // Just capture, don't pause
        DEBUG,      // Pause and show dialog
        MOCKK       // Auto-apply mock rules
    }

    companion object {
        const val SERVER_PORT = 9876

        fun getInstance(project: Project): OkHttpInterceptorServer {
            return project.getService(OkHttpInterceptorServer::class.java)
        }
    }

    /**
     * Start the interceptor server.
     */
    fun start(mode: Mode = Mode.RECORDING): Boolean {
        if (isRunning) {
            logger.warn("⚠️  Interceptor server already running")
            return false
        }

        currentMode = mode
        logger.info("🚀 Starting OkHttp Interceptor Server on port $SERVER_PORT (mode: $mode)")

        return try {
            serverSocket = ServerSocket(SERVER_PORT)
            isRunning = true

            serverThread = thread(isDaemon = true, name = "MockkHttp-Interceptor-Server") {
                runServer()
            }

            logger.info("✅ Interceptor server listening on port $SERVER_PORT")
            logger.info("   Mode: ${mode.name}")
            logger.info("   Waiting for Android app connections...")
            true

        } catch (e: Exception) {
            logger.error("❌ Failed to start interceptor server", e)
            isRunning = false
            false
        }
    }

    /**
     * Stop the interceptor server.
     */
    fun stop() {
        if (!isRunning) {
            logger.debug("Server not running, nothing to stop")
            return
        }

        logger.info("🛑 Stopping interceptor server...")
        isRunning = false

        try {
            serverSocket?.close()
            serverThread?.interrupt()
            serverThread?.join(2000)
        } catch (e: Exception) {
            logger.error("Error stopping server", e)
        }

        serverSocket = null
        serverThread = null
        logger.info("✅ Interceptor server stopped")
    }

    /**
     * Change mode dynamically.
     */
    fun setMode(mode: Mode) {
        currentMode = mode
        logger.info("🔄 Interceptor mode changed to: ${mode.name}")
    }

    /**
     * Main server loop.
     */
    private fun runServer() {
        try {
            while (isRunning) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    logger.debug("📱 Client connected: ${clientSocket.inetAddress.hostAddress}")

                    // Handle each client in separate thread
                    thread(isDaemon = true) {
                        handleClient(clientSocket)
                    }
                } catch (e: SocketException) {
                    if (isRunning) {
                        logger.error("Socket error", e)
                    }
                    // Socket closed, exit loop
                    break
                } catch (e: Exception) {
                    if (isRunning) {
                        logger.error("Error accepting client connection", e)
                    }
                }
            }
        } finally {
            logger.debug("Server loop ended")
        }
    }

    /**
     * Handle a single client connection.
     */
    private fun handleClient(socket: Socket) {
        socket.use { clientSocket ->
            try {
                val reader = BufferedReader(clientSocket.getInputStream().reader())
                val writer = PrintWriter(clientSocket.getOutputStream(), true)

                // Read flow data (one line JSON)
                val json = reader.readLine()

                if (json == null) {
                    logger.debug("Client disconnected without sending data")
                    return
                }

                // Handle PING
                if (json == "PING") {
                    writer.println("PONG")
                    logger.debug("📡 PING received, sent PONG")
                    return
                }

                // Parse flow data
                val flowData = try {
                    gson.fromJson(json, AndroidFlowData::class.java)
                } catch (e: Exception) {
                    logger.error("Failed to parse flow data", e)
                    writer.println(gson.toJson(ModifiedResponseData.original()))
                    return
                }

                logger.info("🔴 INTERCEPTED: ${flowData.request.method} ${flowData.request.url}")

                // Convert to HttpFlowData
                val httpFlowData = convertToHttpFlowData(flowData)

                when (currentMode) {
                    Mode.RECORDING -> {
                        // Recording mode: just log, send back original
                        logger.debug("📝 Flow recorded (not paused)")
                        flowStore.addFlow(httpFlowData)
                        writer.println(gson.toJson(ModifiedResponseData.original()))
                    }

                    Mode.DEBUG -> {
                        // Debug mode: show dialog and wait for user
                        logger.info("⏸️  Flow paused, waiting for user input...")
                        flowStore.addFlow(httpFlowData)
                        val modifiedResponse = showInterceptDialogAndWait(httpFlowData)

                        // Check if response was actually modified
                        val wasModified = modifiedResponse.statusCode != null ||
                                        modifiedResponse.headers != null ||
                                        modifiedResponse.body != null

                        // If modified, update the flow in store with modified flag
                        if (wasModified) {
                            val modifiedFlow = httpFlowData.copy(modified = true)
                            flowStore.addFlow(modifiedFlow)
                            logger.info("✏️  Response was modified by user")
                        }

                        val responseJson = gson.toJson(modifiedResponse)
                        writer.println(responseJson)
                        logger.info("✅ Response sent back to app")
                    }

                    Mode.MOCKK -> {
                        // Mockk mode: auto-apply mock rules without pausing
                        logger.debug("🎭 Mockk mode: checking for matching rules...")
                        val matchingRule = findMatchingMockRule(httpFlowData)

                        if (matchingRule != null) {
                            logger.info("✅ Found matching mock rule: ${matchingRule.name}")

                            // Mark flow as mocked and update in store
                            val mockedFlow = httpFlowData.copy(
                                mockApplied = true,
                                mockRuleName = matchingRule.name,
                                mockRuleId = matchingRule.id
                            )
                            flowStore.addFlow(mockedFlow)

                            val modifiedResponse = ModifiedResponseData(
                                statusCode = matchingRule.statusCode,
                                headers = matchingRule.headers,
                                body = matchingRule.content
                            )
                            writer.println(gson.toJson(modifiedResponse))
                            logger.info("📋 Applied mock: ${matchingRule.name}")
                        } else {
                            logger.debug("📝 No matching mock rule, using original")
                            flowStore.addFlow(httpFlowData)
                            writer.println(gson.toJson(ModifiedResponseData.original()))
                        }
                    }
                }

            } catch (e: Exception) {
                logger.error("Error handling client", e)
            }
        }
    }

    /**
     * Show intercept dialog and WAIT for user response (blocks thread).
     */
    private fun showInterceptDialogAndWait(flowData: HttpFlowData): ModifiedResponseData {
        val latch = CountDownLatch(1)
        var result: ModifiedResponseData? = null

        SwingUtilities.invokeLater {
            try {
                val dialog = DebugInterceptDialog(project, flowData)
                if (dialog.showAndGet()) {
                    val modified = dialog.getModifiedResponse()
                    if (modified != null) {
                        result = ModifiedResponseData(
                            statusCode = modified.statusCode,
                            headers = modified.headers,
                            body = modified.content
                        )
                        logger.debug("User modified response")
                    } else {
                        result = ModifiedResponseData.original()
                        logger.debug("No modifications, using original response")
                    }
                } else {
                    // User cancelled, use original
                    result = ModifiedResponseData.original()
                    logger.debug("User cancelled, using original response")
                }
            } catch (e: Exception) {
                logger.error("Error in intercept dialog", e)
                result = ModifiedResponseData.original()
            } finally {
                latch.countDown()
            }
        }

        // BLOCK until user responds (with timeout)
        val completed = latch.await(5, TimeUnit.MINUTES)
        if (!completed) {
            logger.warn("⚠️  Timeout waiting for user input, using original response")
            return ModifiedResponseData.original()
        }

        return result ?: ModifiedResponseData.original()
    }

    /**
     * Find matching mock rule for a flow.
     */
    private fun findMatchingMockRule(flowData: HttpFlowData): com.sergiy.dev.mockkhttp.store.MockkRulesStore.MockkRule? {
        val allRules = mockkRulesStore.getAllRules()

        for (rule in allRules) {
            if (!rule.enabled) continue

            // Match method
            if (rule.method != flowData.request.method) continue

            // Match URL pattern
            if (!matchesUrlPattern(flowData.request.url, rule)) continue

            // Found a match!
            return rule
        }

        return null
    }

    /**
     * Check if URL matches the pattern.
     */
    private fun matchesUrlPattern(url: String, rule: com.sergiy.dev.mockkhttp.store.MockkRulesStore.MockkRule): Boolean {
        try {
            val parsedUrl = java.net.URL(url)

            // Match scheme
            if (rule.scheme.isNotEmpty() && parsedUrl.protocol != rule.scheme) {
                return false
            }

            // Match host
            if (rule.host.isNotEmpty() && !parsedUrl.host.matches(Regex(rule.host))) {
                return false
            }

            // Match path
            if (rule.path.isNotEmpty() && !parsedUrl.path.matches(Regex(rule.path))) {
                return false
            }

            // Match query parameters if specified
            // (Simple implementation - just check if all required params exist)
            if (rule.queryParams.isNotEmpty()) {
                val query = parsedUrl.query ?: ""
                for (param in rule.queryParams) {
                    if (!query.contains("${param.key}=${param.value}")) {
                        return false
                    }
                }
            }

            return true
        } catch (e: Exception) {
            logger.warn("Failed to parse URL for matching: $url", e)
            return false
        }
    }

    /**
     * Convert Android flow data to HttpFlowData.
     */
    private fun convertToHttpFlowData(androidFlow: AndroidFlowData): HttpFlowData {
        val url = androidFlow.request.url
        val parsedUrl = try {
            java.net.URL(url)
        } catch (e: Exception) {
            null
        }

        return HttpFlowData(
            flowId = androidFlow.flowId,
            paused = (currentMode == Mode.DEBUG),
            request = HttpRequestData(
                method = androidFlow.request.method,
                url = url,
                host = parsedUrl?.host ?: "",
                path = parsedUrl?.path ?: "/",
                headers = androidFlow.request.headers,
                content = androidFlow.request.body
            ),
            response = HttpResponseData(
                statusCode = androidFlow.response.statusCode,
                reason = "",
                headers = androidFlow.response.headers,
                content = androidFlow.response.body
            ),
            timestamp = androidFlow.timestamp / 1000.0,  // Convert ms to seconds
            duration = androidFlow.duration / 1000.0,     // Convert ms to seconds
            mockApplied = false,
            mockRuleName = null,
            mockRuleId = null
        )
    }
}

/**
 * Flow data from Android interceptor.
 * Must match Models.kt in android-library module.
 */
data class AndroidFlowData(
    val flowId: String,
    val request: AndroidRequestData,
    val response: AndroidResponseData,
    val timestamp: Long,
    val duration: Long
)

data class AndroidRequestData(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String
)

data class AndroidResponseData(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String
)

data class ModifiedResponseData(
    val statusCode: Int?,
    val headers: Map<String, String>?,
    val body: String?
) {
    companion object {
        fun original() = ModifiedResponseData(null, null, null)
    }
}
