package com.sergiy.dev.mockkhttp.store

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import com.sergiy.dev.mockkhttp.model.HttpFlowData
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Store for managing intercepted HTTP flows.
 * Thread-safe storage with listeners for UI updates.
 */
@Service(Service.Level.PROJECT)
class FlowStore(project: Project) {

    private val logger = MockkHttpLogger.getInstance(project)
    private val settings = SettingsStore.getInstance(project)

    // Thread-safe storage for flows
    private val flows = ConcurrentHashMap<String, HttpFlowData>()
    private val flowOrder = CopyOnWriteArrayList<String>() // Maintains insertion order

    // Listeners for flow events
    private val flowAddedListeners = CopyOnWriteArrayList<(HttpFlowData) -> Unit>()
    private val flowUpdatedListeners = CopyOnWriteArrayList<(HttpFlowData) -> Unit>()
    private val flowsClearedListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var totalFlowsReceived = 0

    @Volatile
    private var pausedFlowsCount = 0

    companion object {
        fun getInstance(project: Project): FlowStore {
            return project.getService(FlowStore::class.java)
        }

        /** Marker appended to bodies truncated at retention time. */
        const val TRUNCATION_MARKER = "[truncated by MockkHttp cache"

        /** Whether a stored body was truncated by the retention cache. */
        fun isBodyTruncated(content: String?): Boolean =
            content?.contains(TRUNCATION_MARKER) == true
    }

    /** Configurable retention limit (Settings → Cache). */
    fun maxFlows(): Int = settings.getMaxFlowsRetained()

    /**
     * Truncate oversized bodies before RETAINING a flow, so long sessions can't
     * accumulate gigabytes of response payloads in IDE memory. The live Debug
     * dialog operates on the original flow — only the stored copy is trimmed.
     */
    private fun trimForRetention(flow: HttpFlowData): HttpFlowData {
        val maxBytes = settings.getMaxStoredBodyKb() * 1024
        val requestContent = flow.request.content
        val responseContent = flow.response?.content

        val trimRequest = requestContent.length > maxBytes
        val trimResponse = responseContent != null && responseContent.length > maxBytes
        if (!trimRequest && !trimResponse) return flow

        fun truncate(content: String): String =
            content.take(maxBytes) +
                    "\n… $TRUNCATION_MARKER: kept ${maxBytes / 1024} KB of ${content.length / 1024} KB — raise the limit in Settings → Cache]"

        return flow.copy(
            request = if (trimRequest) flow.request.copy(content = truncate(requestContent)) else flow.request,
            response = if (trimResponse) flow.response!!.copy(content = truncate(responseContent!!)) else flow.response
        )
    }

    /**
     * Estimated memory used by retained flows (body + header text sizes).
     */
    fun getEstimatedMemoryBytes(): Long {
        var total = 0L
        for (flow in flows.values) {
            total += flow.request.content.length + flow.request.url.length
            total += flow.request.headers.entries.sumOf { it.key.length + it.value.length }
            flow.response?.let { response ->
                total += response.content.length
                total += response.headers.entries.sumOf { it.key.length + it.value.length }
            }
        }
        // Strings are UTF-16 in the JVM: ~2 bytes per char
        return total * 2
    }

    /** Number of retained flows. */
    fun getFlowCount(): Int = flowOrder.size

    /**
     * Add a new flow to the store.
     */
    fun addFlow(rawFlow: HttpFlowData) {
        logger.debug("Adding flow to store: ${rawFlow.flowId}")

        val flow = trimForRetention(rawFlow)

        // Check if flow already exists (update case) — capture the previous
        // value BEFORE overwriting so the paused counter stays accurate
        val previousFlow = flows[flow.flowId]
        val isUpdate = previousFlow != null

        // Add/update flow
        flows[flow.flowId] = flow

        if (!isUpdate) {
            // New flow
            flowOrder.add(flow.flowId)
            totalFlowsReceived++

            if (flow.paused) {
                pausedFlowsCount++
            }

            // Enforce max size
            val maxFlows = maxFlows()
            while (flowOrder.size > maxFlows) {
                val oldestId = flowOrder.removeAt(0)
                val removed = flows.remove(oldestId)
                logger.debug("Removed oldest flow: $oldestId")

                if (removed?.paused == true) {
                    pausedFlowsCount--
                }
            }

            logger.info("📝 Flow added: ${flow.request.method} ${flow.request.getShortUrl()} (Total: ${flowOrder.size})")

            // Notify listeners
            flowAddedListeners.forEach { listener ->
                try {
                    listener(flow)
                } catch (e: Exception) {
                    logger.error("Error in flow added listener", e)
                }
            }
        } else {
            // Updated flow
            logger.debug("Flow updated: ${flow.flowId}")

            // Update paused count (previousFlow captured before the overwrite)
            if (previousFlow?.paused == true && !flow.paused) {
                pausedFlowsCount--
            } else if (previousFlow?.paused == false && flow.paused) {
                pausedFlowsCount++
            }

            // Notify listeners
            flowUpdatedListeners.forEach { listener ->
                try {
                    listener(flow)
                } catch (e: Exception) {
                    logger.error("Error in flow updated listener", e)
                }
            }
        }
    }

    /**
     * Get all flows in insertion order.
     */
    fun getAllFlows(): List<HttpFlowData> {
        return flowOrder.mapNotNull { flows[it] }
    }

    /**
     * Clear all flows.
     */
    fun clearAllFlows() {
        logger.info("🗑️ Clearing all flows...")

        val count = flows.size
        flows.clear()
        flowOrder.clear()
        pausedFlowsCount = 0

        logger.info("✅ Cleared $count flows")

        // Notify listeners
        flowsClearedListeners.forEach { listener ->
            try {
                listener()
            } catch (e: Exception) {
                logger.error("Error in flows cleared listener", e)
            }
        }
    }

    /**
     * Register listener for when a flow is added.
     */
    fun addFlowAddedListener(listener: (HttpFlowData) -> Unit) {
        flowAddedListeners.add(listener)
        logger.debug("Flow added listener registered (total: ${flowAddedListeners.size})")
    }

    /**
     * Register listener for when a flow is updated.
     */
    fun addFlowUpdatedListener(listener: (HttpFlowData) -> Unit) {
        flowUpdatedListeners.add(listener)
        logger.debug("Flow updated listener registered (total: ${flowUpdatedListeners.size})")
    }

    /**
     * Register listener for when flows are cleared.
     */
    fun addFlowsClearedListener(listener: () -> Unit) {
        flowsClearedListeners.add(listener)
        logger.debug("Flows cleared listener registered (total: ${flowsClearedListeners.size})")
    }

    /**
     * Clear all listeners. Called before re-registering to prevent duplicates
     * when the tool window is created multiple times by the IDE.
     */
    fun clearAllListeners() {
        flowAddedListeners.clear()
        flowUpdatedListeners.clear()
        flowsClearedListeners.clear()
    }
}