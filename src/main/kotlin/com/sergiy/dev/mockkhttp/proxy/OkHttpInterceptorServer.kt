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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Project-level interceptor service that registers with the global server.
 * This acts as a wrapper/proxy to the GlobalOkHttpInterceptorServer.
 *
 * Each project has its own instance, but they all share the same global server on port 9876.
 * This solves the problem of multiple projects trying to bind to the same port.
 */
@Service(Service.Level.PROJECT)
class OkHttpInterceptorServer(private val project: Project) {

    private val logger = MockkHttpLogger.getInstance(project)
    private val flowStore = FlowStore.getInstance(project)
    private val mockkRulesStore = com.sergiy.dev.mockkhttp.store.MockkRulesStore.getInstance(project)
    private val globalServer = GlobalOkHttpInterceptorServer.getInstance()

    @Volatile
    private var isRunning = false

    @Volatile
    private var currentMode = Mode.RECORDING

    enum class Mode {
        RECORDING,    // Just capture, don't pause
        DEBUG,        // Pause and show dialog
        MOCKK,        // Auto-apply mock rules
        MOCKK_DEBUG   // Apply mock THEN pause for editing
    }

    companion object {
        const val SERVER_PORT = GlobalOkHttpInterceptorServer.SERVER_PORT

        fun getInstance(project: Project): OkHttpInterceptorServer {
            return project.getService(OkHttpInterceptorServer::class.java)
        }
    }

    /**
     * Start the interceptor by registering with the global server.
     * @param mode The intercept mode (RECORDING, DEBUG, or MOCKK)
     * @param packageNameFilter Optional package name to filter flows (only receive flows from this app)
     */
    fun start(mode: Mode = Mode.RECORDING, packageNameFilter: String? = null): Boolean {
        if (isRunning) {
            logger.warn("⚠️  Interceptor already running for this project")
            return false
        }

        currentMode = mode
        logger.info("🚀 Registering project with Global Interceptor Server (mode: $mode)")
        if (packageNameFilter != null) {
            logger.info("   📦 Package filter: $packageNameFilter")
        } else {
            logger.info("   📦 Package filter: NONE (will receive ALL flows)")
        }

        // Convert mode to GlobalOkHttpInterceptorServer.InterceptMode
        val interceptMode = when (mode) {
            Mode.RECORDING -> GlobalOkHttpInterceptorServer.InterceptMode.RECORDING
            Mode.DEBUG -> GlobalOkHttpInterceptorServer.InterceptMode.DEBUG
            Mode.MOCKK -> GlobalOkHttpInterceptorServer.InterceptMode.MOCKK
            Mode.MOCKK_DEBUG -> GlobalOkHttpInterceptorServer.InterceptMode.MOCKK_DEBUG
        }

        // Register with global server WITH PACKAGE FILTER
        val success = globalServer.registerProject(
            project = project,
            mode = interceptMode,
            flowHandler = createFlowHandler(),
            packageNameFilter = packageNameFilter  // Pass the package filter!
        )

        if (success) {
            isRunning = true
            logger.info("✅ Project registered with Global Interceptor Server on port $SERVER_PORT")
            logger.info("   Project: ${project.name}")
            logger.info("   Mode: ${mode.name}")
            logger.info("   Waiting for Android app connections...")
        } else {
            logger.error("❌ Failed to register with Global Interceptor Server")
        }

        return success
    }

    /**
     * Stop the interceptor by unregistering from the global server.
     */
    fun stop() {
        if (!isRunning) {
            logger.debug("Interceptor not running for this project, nothing to stop")
            return
        }

        logger.info("🛑 Unregistering project from Global Interceptor Server...")
        globalServer.unregisterProject(project)
        isRunning = false
        logger.info("✅ Project unregistered from Global Interceptor Server")
    }

    /**
     * Change mode dynamically.
     */
    fun setMode(mode: Mode) {
        currentMode = mode
        logger.info("🔄 Interceptor mode changed to: ${mode.name}")

        // Update mode in global server if registered
        if (isRunning) {
            val interceptMode = when (mode) {
                Mode.RECORDING -> GlobalOkHttpInterceptorServer.InterceptMode.RECORDING
                Mode.DEBUG -> GlobalOkHttpInterceptorServer.InterceptMode.DEBUG
                Mode.MOCKK -> GlobalOkHttpInterceptorServer.InterceptMode.MOCKK
                Mode.MOCKK_DEBUG -> GlobalOkHttpInterceptorServer.InterceptMode.MOCKK_DEBUG
            }
            globalServer.updateProjectMode(project, interceptMode)
        }
    }

    /**
     * Change package name filter dynamically.
     */
    fun setPackageNameFilter(packageNameFilter: String?) {
        if (isRunning) {
            globalServer.updateProjectPackageFilter(project, packageNameFilter)
            if (packageNameFilter != null) {
                logger.info("🔄 Package filter changed to: $packageNameFilter")
            } else {
                logger.info("🔄 Package filter removed (will receive ALL flows)")
            }
        }
    }

    /**
     * Create a flow handler for this project.
     * This is called by the global server when a flow is intercepted.
     */
    private fun createFlowHandler(): GlobalOkHttpInterceptorServer.FlowHandler {
        return GlobalOkHttpInterceptorServer.FlowHandler { androidFlow ->
            handleFlow(androidFlow)
        }
    }

    /**
     * Handle an intercepted flow (called from global server).
     */
    private fun handleFlow(androidFlow: AndroidFlowData): ModifiedResponseData {
        logger.info("🔴 FLOW RECEIVED: ${androidFlow.request.method} ${androidFlow.request.url}")

        // Convert to HttpFlowData
        val httpFlowData = convertToHttpFlowData(androidFlow)

        return when (currentMode) {
            Mode.RECORDING -> {
                // Recording mode: just log, send back original
                logger.debug("📝 Flow recorded (not paused)")
                flowStore.addFlow(httpFlowData)
                ModifiedResponseData.original()
            }

            Mode.DEBUG -> {
                // Debug mode: show dialog and wait for user
                logger.info("⏸️  Flow paused, waiting for user input...")
                flowStore.addFlow(httpFlowData)
                val (modifiedResponse, userModified) = showInterceptDialogAndWait(httpFlowData)

                // If user actually modified the response, update the flow in store with modified flag
                if (userModified) {
                    val modifiedFlow = httpFlowData.copy(modified = true)
                    flowStore.addFlow(modifiedFlow)
                    logger.info("✏️  Response was modified by user")
                }

                logger.info("✅ Response sent back to app")
                modifiedResponse
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
                    logger.info("📋 Applied mock: ${matchingRule.name}")
                    modifiedResponse
                } else {
                    logger.debug("📝 No matching mock rule, using original")
                    flowStore.addFlow(httpFlowData)
                    ModifiedResponseData.original()
                }
            }

            Mode.MOCKK_DEBUG -> {
                // Mockk Debug mode: apply mock THEN pause for editing
                logger.debug("🎭 Mockk Debug mode: checking for matching rules...")
                val matchingRule = findMatchingMockRule(httpFlowData)

                // Create flow with mock applied (if found)
                val flowWithMock = if (matchingRule != null) {
                    logger.info("✅ Found matching mock rule: ${matchingRule.name}")
                    // Update the flow's response to show the mocked response
                    httpFlowData.copy(
                        response = httpFlowData.response?.copy(
                            statusCode = matchingRule.statusCode,
                            headers = matchingRule.headers,
                            content = matchingRule.content
                        ),
                        mockApplied = true,
                        mockRuleName = matchingRule.name,
                        mockRuleId = matchingRule.id
                    )
                } else {
                    logger.debug("📝 No matching mock rule, will pause with original")
                    httpFlowData
                }

                // Add flow to store (with mock if found)
                flowStore.addFlow(flowWithMock)

                // NOW pause and show dialog for user editing
                logger.info("⏸️  Flow paused (with mock applied), waiting for user input...")
                val (modifiedResponse, userModified) = showInterceptDialogAndWait(flowWithMock)

                // If user actually modified, update the flow with modified flag too
                if (userModified) {
                    val modifiedFlow = flowWithMock.copy(modified = true)
                    flowStore.addFlow(modifiedFlow)
                    logger.info("✏️  Response was further modified by user")
                }

                logger.info("✅ Response sent back to app")
                modifiedResponse
            }
        }
    }

    /**
     * Show intercept dialog and WAIT for user response (blocks thread).
     * Returns Pair<ModifiedResponseData, Boolean> where Boolean indicates if user manually modified the response.
     */
    private fun showInterceptDialogAndWait(flowData: HttpFlowData): Pair<ModifiedResponseData, Boolean> {
        val latch = CountDownLatch(1)
        var result: ModifiedResponseData? = null
        var userModified = false

        SwingUtilities.invokeLater {
            try {
                val dialog = DebugInterceptDialog(project, flowData)
                if (dialog.showAndGet()) {
                    val modified = dialog.getModifiedResponse()
                    if (modified != null) {
                        // User explicitly modified the response (pressed "Continue with Modified Response")
                        result = ModifiedResponseData(
                            statusCode = modified.statusCode,
                            headers = modified.headers,
                            body = modified.content
                        )
                        userModified = true  // USER MADE CHANGES
                        logger.debug("User modified response")
                    } else if (flowData.mockApplied) {
                        // User pressed "Continue with Mockk Response" WITHOUT editing - return the mocked response
                        result = ModifiedResponseData(
                            statusCode = flowData.response?.statusCode,
                            headers = flowData.response?.headers,
                            body = flowData.response?.content
                        )
                        userModified = false  // NO USER CHANGES, just using mock
                        logger.debug("Continuing with mocked response (not edited)")
                    } else {
                        // User pressed "Continue with Remote Response" - return original
                        result = ModifiedResponseData.original()
                        userModified = false  // NO USER CHANGES
                        logger.debug("No modifications, using original response")
                    }

                    // Check if user wants to save as mock rule
                    if (dialog.shouldSaveAsMock()) {
                        val collection = dialog.getSelectedCollection()
                        if (collection != null) {
                            val responseToSave = if (modified != null) {
                                modified
                            } else {
                                com.sergiy.dev.mockkhttp.model.ModifiedResponseData(
                                    statusCode = flowData.response?.statusCode,
                                    headers = flowData.response?.headers,
                                    content = flowData.response?.content
                                )
                            }
                            saveMockRuleFromDialog(flowData, dialog, responseToSave, collection)
                        } else {
                            logger.warn("⚠️  Cannot save mock: no collection selected")
                        }
                    }
                } else {
                    // User cancelled, use original
                    result = ModifiedResponseData.original()
                    userModified = false
                    logger.debug("User cancelled, using original response")
                }
            } catch (e: Exception) {
                logger.error("Error in intercept dialog", e)
                result = ModifiedResponseData.original()
                userModified = false
            } finally {
                latch.countDown()
            }
        }

        // BLOCK until user responds (with timeout)
        val completed = latch.await(5, TimeUnit.MINUTES)
        if (!completed) {
            logger.warn("⚠️  Timeout waiting for user input, using original response")
            return Pair(ModifiedResponseData.original(), false)
        }

        return Pair(result ?: ModifiedResponseData.original(), userModified)
    }

    /**
     * Save a mock rule from the debug intercept dialog.
     */
    private fun saveMockRuleFromDialog(
        flowData: HttpFlowData,
        dialog: DebugInterceptDialog,
        response: com.sergiy.dev.mockkhttp.model.ModifiedResponseData,
        collection: com.sergiy.dev.mockkhttp.model.MockkCollection
    ) {
        try {
            val ruleName = dialog.getMockRuleName()
            val structuredUrl = com.sergiy.dev.mockkhttp.model.StructuredUrl.fromUrl(flowData.request.url)

            mockkRulesStore.addRule(
                name = ruleName,
                method = flowData.request.method,
                structuredUrl = structuredUrl,
                mockResponse = com.sergiy.dev.mockkhttp.model.ModifiedResponseData(
                    statusCode = response.statusCode ?: flowData.response?.statusCode ?: 200,
                    headers = response.headers ?: flowData.response?.headers ?: emptyMap(),
                    content = response.content ?: flowData.response?.content ?: ""
                ),
                collectionId = collection.id
            )

            logger.info("✅ Saved mock rule '$ruleName' to collection '${collection.name}'")
        } catch (e: Exception) {
            logger.error("Failed to save mock rule", e)
        }
    }

    /**
     * Find matching mock rule for a flow.
     */
    private fun findMatchingMockRule(flowData: HttpFlowData): com.sergiy.dev.mockkhttp.store.MockkRulesStore.MockkRule? {
        val allRules = mockkRulesStore.getAllRules()

        for (rule in allRules) {
            if (!rule.enabled) continue

            // Skip if rule's collection is disabled
            val ruleCollection = mockkRulesStore.getCollection(rule.collectionId)
            if (ruleCollection == null || !ruleCollection.enabled) {
                continue
            }

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
            paused = (currentMode == Mode.DEBUG || currentMode == Mode.MOCKK_DEBUG),
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
    val duration: Long,
    val projectId: String? = null,      // Optional: helps route to correct project
    val packageName: String? = null     // Optional: app package name for routing
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
