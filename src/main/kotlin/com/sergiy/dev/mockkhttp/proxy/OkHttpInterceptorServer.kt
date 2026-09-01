package com.sergiy.dev.mockkhttp.proxy

import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
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
class OkHttpInterceptorServer(private val project: Project) : Disposable {

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
     * Release the project's registration when the project closes.
     *
     * Nothing did this before: `stop()` is only reached from the Inspector's Stop button, so
     * closing a project mid-capture left its registration in the application-level server —
     * and with it a hard reference to the Project and to the whole plugin object graph, which
     * the IDE could then never collect.
     */
    override fun dispose() {
        if (isRunning) {
            logger.info("🛑 Project closing while capture is running — unregistering")
        }
        globalServer.unregisterProject(project)
        isRunning = false
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
                // Mockk mode: the mock was already served by the app from the
                // CHECK_MOCK reply, and this FLOW message is fire-and-forget —
                // MockkHttpInterceptor.sendToPluginAsync writes and closes without
                // reading. So nothing returned here can substitute a response; the
                // only job left is labelling the flow with the rule that fired.
                logger.debug("🔍 Mockk mode: resolving which rule the app served...")
                val appliedRule = findAppliedMockRule(httpFlowData)

                if (appliedRule != null) {
                    logger.info("✅ Flow served from mock rule: ${appliedRule.name}")
                    flowStore.addFlow(
                        httpFlowData.copy(
                            mockApplied = true,
                            mockRuleName = appliedRule.name,
                            mockRuleId = appliedRule.id
                        )
                    )
                } else {
                    logger.debug("📝 No matching mock rule, flow came from the network")
                    flowStore.addFlow(httpFlowData)
                }

                // Written into a stream the app never reads — see above.
                ModifiedResponseData.original()
            }

            Mode.MOCKK_DEBUG -> {
                // Mockk Debug mode: apply mock THEN pause for editing
                logger.debug("🎭 Mockk Debug mode: checking for matching rules...")
                val matchingRule = findAppliedMockRule(httpFlowData)

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

        // Published so the timeout path can dismiss the dialog. Without it a
        // stale modal stays on screen after the app has already moved on, and the
        // next paused flow stacks another one on top of it.
        val dialogRef = java.util.concurrent.atomic.AtomicReference<DebugInterceptDialog?>()

        SwingUtilities.invokeLater {
            try {
                val dialog = DebugInterceptDialog(project, flowData)
                dialogRef.set(dialog)
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

        // BLOCK until the user responds — but never longer than the app is
        // willing to wait. See GlobalOkHttpInterceptorServer.DEBUG_DECISION_TIMEOUT_MS.
        val completed = latch.await(
            GlobalOkHttpInterceptorServer.DEBUG_DECISION_TIMEOUT_MS,
            TimeUnit.MILLISECONDS
        )
        if (!completed) {
            val seconds = GlobalOkHttpInterceptorServer.DEBUG_DECISION_TIMEOUT_MS / 1000
            logger.warn(
                "⚠️  No decision after ${seconds}s for ${flowData.request.method} ${flowData.request.getShortUrl()} — " +
                        "the app is about to give up, so the original response is being used. " +
                        "Any edit made in the dialog from now on would be discarded."
            )
            // Dismiss the orphaned dialog: there is no longer a flow to answer.
            SwingUtilities.invokeLater {
                dialogRef.get()?.takeIf { it.isShowing }?.close(DialogWrapper.CANCEL_EXIT_CODE)
            }
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
     * Resolve which mock rule the app actually served, so the Inspector labels a
     * flow with the rule that really fired.
     *
     * Delegates to the same structured matcher that answered the app's CHECK_MOCK
     * message (see GlobalOkHttpInterceptorServer.findMockForRequest). A second
     * implementation used to live here and treated host/path as regex, so it could
     * disagree with the decision the app had already acted on.
     */
    private fun findAppliedMockRule(flowData: HttpFlowData): com.sergiy.dev.mockkhttp.store.MockkRulesStore.MockkRule? =
        mockkRulesStore.findMatchingRuleForUrl(flowData.request.method, flowData.request.url)

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

/**
 * WIRE format of a modified response — the JSON actually sent back to the app.
 *
 * Careful: [com.sergiy.dev.mockkhttp.model.ModifiedResponseData] is a DIFFERENT
 * class with the same name, used inside the plugin, whose body field is called
 * `content`. This one must stay `body`, because that is the name the Android and
 * Flutter interceptors deserialise. Anything writing to the socket uses this one.
 *
 * All-null means "keep the original response".
 */
data class ModifiedResponseData(
    val statusCode: Int?,
    val headers: Map<String, String>?,
    val body: String?
) {
    companion object {
        fun original() = ModifiedResponseData(null, null, null)
    }
}
