package com.sergiy.dev.mockkhttp.proxy

import com.google.gson.Gson
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.sergiy.dev.mockkhttp.model.HttpFlowData
import com.sergiy.dev.mockkhttp.model.HttpRequestData
import com.sergiy.dev.mockkhttp.model.HttpResponseData
import com.sergiy.dev.mockkhttp.store.FlowStore
import com.sergiy.dev.mockkhttp.ui.DebugInterceptDialog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import javax.swing.SwingUtilities
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-level interceptor service that registers with the global server.
 * This acts as a wrapper/proxy to the GlobalOkHttpInterceptorServer.
 *
 * Each project has its own instance, but they all share the same global server on port 9876.
 * This solves the problem of multiple projects trying to bind to the same port.
 */
@Service(Service.Level.PROJECT)
class OkHttpInterceptorServer(private val project: Project) {

    private val flowStore = FlowStore.getInstance(project)
    private val mockkRulesStore = com.sergiy.dev.mockkhttp.store.MockkRulesStore.getInstance(project)
    private val globalServer = GlobalOkHttpInterceptorServer.getInstance()

    @Volatile
    private var isRunning = false

    @Volatile
    private var currentMode = Mode.RECORDING

    // Debug mode infrastructure with Coroutines (Problem #2 optimization)
    // Stores pending debug requests waiting for user input
    private val pendingDebugRequests = MutableStateFlow<List<PendingDebugRequest>>(emptyList())
    private val debugResponses = ConcurrentHashMap<String, CompletableDeferred<Pair<ModifiedResponseData, Boolean>>>()

    /**
     * Represents a pending debug request waiting for user input.
     */
    data class PendingDebugRequest(
        val flowId: String,
        val flowData: HttpFlowData,
        val timestamp: Long,
        val responseDeferred: CompletableDeferred<Pair<ModifiedResponseData, Boolean>>
    )

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
            return false
        }

        currentMode = mode
        if (packageNameFilter != null) {
        } else {
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
        } else {
        }

        return success
    }

    /**
     * Stop the interceptor by unregistering from the global server.
     */
    fun stop() {
        if (!isRunning) {
            return
        }

        globalServer.unregisterProject(project)
        isRunning = false
    }

    /**
     * Change mode dynamically.
     */
    fun setMode(mode: Mode) {
        currentMode = mode

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
            } else {
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

        // Convert to HttpFlowData
        val httpFlowData = convertToHttpFlowData(androidFlow)

        return when (currentMode) {
            Mode.RECORDING -> {
                // Recording mode: just log, send back original
                flowStore.addFlow(httpFlowData)
                ModifiedResponseData.original()
            }

            Mode.DEBUG -> {
                // Debug mode: show dialog and wait for user
                flowStore.addFlow(httpFlowData)
                val (modifiedResponse, userModified) = showInterceptDialogAndWait(httpFlowData)

                // If user actually modified the response, update the flow in store with modified flag
                if (userModified) {
                    val modifiedFlow = httpFlowData.copy(modified = true)
                    flowStore.addFlow(modifiedFlow)
                }

                modifiedResponse
            }

            Mode.MOCKK -> {
                // Mockk mode: auto-apply mock rules without pausing
                val matchingRule = findMatchingMockRule(httpFlowData)

                if (matchingRule != null) {

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
                    modifiedResponse
                } else {
                    flowStore.addFlow(httpFlowData)
                    ModifiedResponseData.original()
                }
            }

            Mode.MOCKK_DEBUG -> {
                // Mockk Debug mode: apply mock THEN pause for editing
                val matchingRule = findMatchingMockRule(httpFlowData)

                // Create flow with mock applied (if found)
                val flowWithMock = if (matchingRule != null) {
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
                    httpFlowData
                }

                // Add flow to store (with mock if found)
                flowStore.addFlow(flowWithMock)

                // NOW pause and show dialog for user editing
                val (modifiedResponse, userModified) = showInterceptDialogAndWait(flowWithMock)

                // If user actually modified, update the flow with modified flag too
                if (userModified) {
                    val modifiedFlow = flowWithMock.copy(modified = true)
                    flowStore.addFlow(modifiedFlow)
                }

                modifiedResponse
            }
        }
    }

    /**
     * Show intercept dialog and WAIT for user response using coroutines (NON-BLOCKING).
     * Returns Pair<ModifiedResponseData, Boolean> where Boolean indicates if user manually modified the response.
     *
     * This replaces the old CountDownLatch-based approach with CompletableDeferred.
     * The thread is NOT blocked - we use runBlocking to suspend until the deferred completes.
     */
    private fun showInterceptDialogAndWait(flowData: HttpFlowData): Pair<ModifiedResponseData, Boolean> {
        // Create deferred for this request
        val deferred = CompletableDeferred<Pair<ModifiedResponseData, Boolean>>()

        // Store it for tracking
        debugResponses[flowData.flowId] = deferred

        // Add to pending requests (for UI observation if needed)
        val pendingRequest = PendingDebugRequest(
            flowId = flowData.flowId,
            flowData = flowData,
            timestamp = System.currentTimeMillis(),
            responseDeferred = deferred
        )
        pendingDebugRequests.value = pendingDebugRequests.value + pendingRequest

        // Show dialog on EDT
        SwingUtilities.invokeLater {
            try {
                val dialog = DebugInterceptDialog(project, flowData)
                if (dialog.showAndGet()) {
                    val modified = dialog.getModifiedResponse()
                    val (resultData, userModified) = when {
                        modified != null -> {
                            // User explicitly modified the response
                            Pair(
                                ModifiedResponseData(
                                    statusCode = modified.statusCode,
                                    headers = modified.headers,
                                    body = modified.content
                                ),
                                true
                            )
                        }
                        flowData.mockApplied -> {
                            // User pressed "Continue with Mockk Response" WITHOUT editing
                            Pair(
                                ModifiedResponseData(
                                    statusCode = flowData.response?.statusCode,
                                    headers = flowData.response?.headers,
                                    body = flowData.response?.content
                                ),
                                false
                            )
                        }
                        else -> {
                            // User pressed "Continue with Remote Response"
                            Pair(ModifiedResponseData.original(), false)
                        }
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
                        }
                    }

                    // Complete the deferred with result
                    deferred.complete(Pair(resultData, userModified))
                } else {
                    // User cancelled, use original
                    deferred.complete(Pair(ModifiedResponseData.original(), false))
                }
            } catch (e: Exception) {
                deferred.complete(Pair(ModifiedResponseData.original(), false))
            } finally {
                // Remove from pending requests
                pendingDebugRequests.value = pendingDebugRequests.value.filter { it.flowId != flowData.flowId }
                debugResponses.remove(flowData.flowId)
            }
        }

        // Wait for user response with timeout (suspends, doesn't block!)
        // runBlocking is OK here because this is already on a worker coroutine from GlobalServer
        return runBlocking {
            withTimeoutOrNull(30_000) {  // 30 seconds timeout
                deferred.await()
            } ?: run {
                Pair(ModifiedResponseData.original(), false)
            }
        }
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

        } catch (e: Exception) {
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
            val parsedUrl = java.net.URI.create(url).toURL()

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
            return false
        }
    }

    /**
     * Convert Android flow data to HttpFlowData.
     */
    private fun convertToHttpFlowData(androidFlow: AndroidFlowData): HttpFlowData {
        val url = androidFlow.request.url
        val parsedUrl = try {
            java.net.URI.create(url).toURL()
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
