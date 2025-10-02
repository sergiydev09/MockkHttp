package com.sergiy.dev.mockkhttp.proxy

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import com.sergiy.dev.mockkhttp.model.HttpFlowData
import com.sergiy.dev.mockkhttp.store.MockkRulesStore
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * HTTP server that receives intercepted flows from mitmproxy addon.
 * Listens on port 8765 by default for POST /intercept requests.
 */
@Service(Service.Level.PROJECT)
class PluginHttpServer(private val project: Project) {

    private val logger = MockkHttpLogger.getInstance(project)
    private val gson = Gson()

    private var server: HttpServer? = null
    private var isRunning = false

    // Callbacks for handling received flows
    private val flowCallbacks = ConcurrentHashMap<String, (HttpFlowData) -> Unit>()

    companion object {
        fun getInstance(project: Project): PluginHttpServer {
            return project.getService(PluginHttpServer::class.java)
        }

        const val DEFAULT_PORT = 8765
    }

    /**
     * Start the HTTP server.
     */
    fun start(port: Int = DEFAULT_PORT): Boolean {
        logger.info("🚀 Starting plugin HTTP server on port $port...")

        if (isRunning) {
            logger.warn("⚠️ HTTP server is already running")
            return false
        }

        try {
            // Create HTTP server
            server = HttpServer.create(InetSocketAddress(port), 0)

            // Register endpoints
            server?.createContext("/intercept") { exchange ->
                handleIntercept(exchange)
            }

            server?.createContext("/status") { exchange ->
                handleStatus(exchange)
            }

            server?.createContext("/mock-match") { exchange ->
                handleMockMatch(exchange)
            }

            // Start server with thread pool
            server?.executor = Executors.newFixedThreadPool(4)
            server?.start()

            isRunning = true

            logger.info("✅ Plugin HTTP server started successfully")
            logger.info("   Listening on: http://localhost:$port")
            logger.info("   Endpoints: /intercept, /status, /mock-match")

            return true

        } catch (e: IOException) {
            logger.error("❌ Failed to start HTTP server", e)
            return false
        } catch (e: Exception) {
            logger.error("❌ Unexpected error starting HTTP server", e)
            return false
        }
    }

    /**
     * Stop the HTTP server.
     */
    fun stop(): Boolean {
        logger.info("🛑 Stopping plugin HTTP server...")

        if (!isRunning || server == null) {
            logger.warn("⚠️ HTTP server is not running")
            return false
        }

        try {
            server?.stop(2) // 2 seconds delay
            server = null
            isRunning = false

            logger.info("✅ Plugin HTTP server stopped successfully")
            return true

        } catch (e: Exception) {
            logger.error("❌ Failed to stop HTTP server", e)
            return false
        }
    }

    /**
     * Register a callback for handling received flows.
     *
     * @param id Unique identifier for the callback
     * @param callback Function to call when a flow is received
     */
    fun registerFlowCallback(id: String, callback: (HttpFlowData) -> Unit) {
        flowCallbacks[id] = callback
        logger.debug("Registered flow callback: $id")
    }

    /**
     * Handle POST /intercept endpoint.
     * Receives flow data from mitmproxy addon.
     */
    private fun handleIntercept(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            sendResponse(exchange, 405, """{"error": "Method not allowed"}""")
            return
        }

        try {
            // Read request body
            val requestBody = exchange.requestBody.bufferedReader().use { it.readText() }

            logger.debug("Received intercept request: ${requestBody.take(200)}...")

            // Parse JSON
            val flowData = try {
                // Parse with custom field names matching Python snake_case
                val jsonObject = gson.fromJson(requestBody, Map::class.java)

                HttpFlowData(
                    flowId = jsonObject["flow_id"] as String,
                    paused = jsonObject["paused"] as Boolean,
                    request = parseRequest(jsonObject["request"] as Map<*, *>),
                    response = jsonObject["response"]?.let { parseResponse(it as Map<*, *>) },
                    timestamp = (jsonObject["timestamp"] as Number).toDouble(),
                    duration = (jsonObject["duration"] as Number).toDouble(),
                    mockApplied = jsonObject["mock_applied"] as? Boolean ?: false,
                    mockRuleName = jsonObject["mock_rule_name"] as? String,
                    mockRuleId = jsonObject["mock_rule_id"] as? String
                )
            } catch (e: JsonSyntaxException) {
                logger.error("Failed to parse flow JSON", e)
                sendResponse(exchange, 400, """{"error": "Invalid JSON"}""")
                return
            }

            val mockPrefix = if (flowData.mockApplied) "[MOCK: ${flowData.mockRuleName}] " else ""
            logger.info("📥 Received flow: $mockPrefix${flowData.request.method} ${flowData.request.getShortUrl()}")
            logger.debug("   Flow ID: ${flowData.flowId}")
            logger.debug("   Paused: ${flowData.paused}")
            logger.debug("   Response: ${flowData.response?.getDisplayStatus()}")
            if (flowData.mockApplied) {
                logger.debug("   Mock Applied: ${flowData.mockRuleName} (${flowData.mockRuleId})")
            }

            // Notify callbacks
            flowCallbacks.values.forEach { callback ->
                try {
                    callback(flowData)
                } catch (e: Exception) {
                    logger.error("Error in flow callback", e)
                }
            }

            // Send success response
            sendResponse(exchange, 200, """{"status": "received", "flow_id": "${flowData.flowId}"}""")

        } catch (e: Exception) {
            logger.error("Error handling intercept request", e)
            sendResponse(exchange, 500, """{"error": "${e.message}"}""")
        }
    }

    /**
     * Handle GET /mock-match endpoint.
     * Checks if there's a mock rule that matches the request.
     * Query params: method, host, path, query_* (for each query param)
     */
    private fun handleMockMatch(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            sendResponse(exchange, 405, """{"error": "Method not allowed"}""")
            return
        }

        try {
            // Parse query parameters
            val query = exchange.requestURI.query ?: ""
            logger.debug("Raw query string: $query")

            val params = parseQueryParams(query)
            logger.debug("Parsed params: ${params.keys}")

            val method = params["method"] ?: ""
            val host = params["host"] ?: ""
            val path = params["path"] ?: ""

            logger.debug("Parsed method: $method")
            logger.debug("Parsed host: $host")
            logger.debug("Parsed path: $path")

            // Extract query parameters (all params starting with "query_")
            val queryParams = params.filterKeys { it.startsWith("query_") }
                .mapKeys { it.key.removePrefix("query_") }
            logger.debug("Parsed query params: $queryParams")

            if (method.isEmpty() || host.isEmpty()) {
                sendResponse(exchange, 400, """{"error": "Missing method or host parameter"}""")
                return
            }

            // Check if there's a mock rule using structured matching
            val mockkRulesStore = MockkRulesStore.getInstance(project)
            val mockRule = mockkRulesStore.findMatchingRuleObject(method, host, path, queryParams)

            if (mockRule != null) {
                // Return mock data
                logger.debug("✅ Found matching mock rule: ${mockRule.name}")

                val response = mapOf(
                    "rule_id" to mockRule.id,
                    "rule_name" to mockRule.name,
                    "status_code" to mockRule.statusCode,
                    "headers" to mockRule.headers,
                    "content" to mockRule.content
                )

                sendResponse(exchange, 200, gson.toJson(response))
            } else {
                // No mock found
                logger.debug("No matching mock rule found")
                sendResponse(exchange, 404, "{}")
            }

        } catch (e: Exception) {
            logger.error("Error handling mock-match request", e)
            sendResponse(exchange, 500, """{"error": "${e.message}"}""")
        }
    }

    /**
     * Handle GET /status endpoint.
     */
    private fun handleStatus(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            sendResponse(exchange, 405, """{"error": "Method not allowed"}""")
            return
        }

        try {
            val status = """
                {
                    "status": "running",
                    "port": ${server?.address?.port},
                    "callbacks_registered": ${flowCallbacks.size}
                }
            """.trimIndent()

            sendResponse(exchange, 200, status)

        } catch (e: Exception) {
            logger.error("Error handling status request", e)
            sendResponse(exchange, 500, """{"error": "${e.message}"}""")
        }
    }

    /**
     * Parse request data from JSON map.
     */
    private fun parseRequest(map: Map<*, *>): com.sergiy.dev.mockkhttp.model.HttpRequestData {
        return com.sergiy.dev.mockkhttp.model.HttpRequestData(
            method = map["method"] as String,
            url = map["url"] as String,
            host = map["host"] as String,
            path = map["path"] as String,
            headers = (map["headers"] as Map<*, *>).mapKeys { it.key.toString() }
                .mapValues { it.value.toString() },
            content = map["content"] as? String ?: ""
        )
    }

    /**
     * Parse response data from JSON map.
     */
    private fun parseResponse(map: Map<*, *>): com.sergiy.dev.mockkhttp.model.HttpResponseData {
        return com.sergiy.dev.mockkhttp.model.HttpResponseData(
            statusCode = (map["status_code"] as Number).toInt(),
            reason = map["reason"] as String,
            headers = (map["headers"] as Map<*, *>).mapKeys { it.key.toString() }
                .mapValues { it.value.toString() },
            content = map["content"] as? String ?: ""
        )
    }

    /**
     * Parse query parameters from URL query string.
     */
    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()

        return query.split("&")
            .mapNotNull { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = URLDecoder.decode(parts[0], "UTF-8")
                    val value = URLDecoder.decode(parts[1], "UTF-8")
                    key to value
                } else {
                    null
                }
            }
            .toMap()
    }

    /**
     * Send HTTP response.
     */
    private fun sendResponse(exchange: HttpExchange, statusCode: Int, body: String) {
        try {
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(statusCode, body.toByteArray().size.toLong())
            exchange.responseBody.use { os ->
                os.write(body.toByteArray())
            }
        } catch (e: Exception) {
            logger.error("Error sending response", e)
        } finally {
            exchange.close()
        }
    }
}