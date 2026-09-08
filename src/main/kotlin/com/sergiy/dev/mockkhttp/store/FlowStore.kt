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

    /** Flows the cap pushed out since the last clear: what makes `flows.count` a window, not a total. */
    @Volatile
    private var evictedSinceClear = 0

    @Volatile
    private var evictedTotal = 0

    /**
     * Guards every mutation of the map, the order list and the counters together. Flows arrive on
     * one pooled thread per client connection, and a bare `x++` on a volatile loses increments
     * under that — which for the eviction counter meant `comparable` could say true after an
     * eviction it never counted (adversarial review of audit round 11). Listeners are called
     * outside the lock.
     */
    private val mutation = Any()

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

    /** How many flows the cap has evicted since the last clear (audit round 11, AQ). */
    fun getEvictedSinceClear(): Int = evictedSinceClear

    /** How many flows the cap has evicted in this project's lifetime. */
    fun getEvictedTotal(): Int = evictedTotal

    /**
     * What the last clear remembered about the outside world — every client's report and the
     * rejections so far — taken here, on every clear, whoever asked for it: the agent's
     * `clear_flows` or the Inspector's button. `since_clear` subtracts from it. Null until the
     * first clear.
     */
    @Volatile
    private var clearSnapshot: com.sergiy.dev.mockkhttp.proxy.GlobalOkHttpInterceptorServer.ClearSnapshot? = null

    fun lastClearSnapshot(): com.sergiy.dev.mockkhttp.proxy.GlobalOkHttpInterceptorServer.ClearSnapshot? = clearSnapshot

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

    /** Number of retained flows — with the cap applied first, so it never exceeds [maxFlows]. */
    fun getFlowCount(): Int {
        enforceCap()
        return flowOrder.size
    }

    /**
     * Apply the cap now. The limit is lowered in Settings with no traffic to run the loop in
     * [addFlow], and until the next flow the store would hold more than `flows.capacity` says
     * with nothing evicted; every read applies it instead, and counts what it evicts.
     */
    fun enforceCap() {
        synchronized(mutation) { evictLocked() }
    }

    private fun evictLocked() {
        val maxFlows = maxFlows()
        while (flowOrder.size > maxFlows) {
            val oldestId = flowOrder.removeAt(0)
            val removed = flows.remove(oldestId)
            evictedSinceClear++
            evictedTotal++
            logger.debug("Removed oldest flow: $oldestId")

            if (removed?.paused == true) {
                pausedFlowsCount--
            }
        }
    }

    /**
     * Add a new flow to the store.
     */
    fun addFlow(rawFlow: HttpFlowData) {
        logger.debug("Adding flow to store: ${rawFlow.flowId}")

        val flow = trimForRetention(rawFlow)

        val previousFlow: HttpFlowData?
        val isUpdate: Boolean
        synchronized(mutation) {
            // Check if flow already exists (update case) — capture the previous
            // value BEFORE overwriting so the paused counter stays accurate
            previousFlow = flows[flow.flowId]
            isUpdate = previousFlow != null

            // Add/update flow
            flows[flow.flowId] = flow

            if (!isUpdate) {
                flowOrder.add(flow.flowId)
                totalFlowsReceived++
                if (flow.paused) {
                    pausedFlowsCount++
                }
                evictLocked()
            } else {
                // Update paused count (previousFlow captured before the overwrite)
                if (previousFlow?.paused == true && !flow.paused) {
                    pausedFlowsCount--
                } else if (previousFlow?.paused == false && flow.paused) {
                    pausedFlowsCount++
                }
            }
        }

        if (!isUpdate) {
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
            logger.debug("Flow updated: ${flow.flowId}")

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
        enforceCap()
        return flowOrder.mapNotNull { flows[it] }
    }

    /**
     * Clear all flows.
     */
    fun clearAllFlows() {
        logger.info("🗑️ Clearing all flows...")

        val count: Int
        synchronized(mutation) {
            count = flows.size
            flows.clear()
            flowOrder.clear()
            pausedFlowsCount = 0
            evictedSinceClear = 0
            clearSnapshot = try {
                com.sergiy.dev.mockkhttp.proxy.GlobalOkHttpInterceptorServer.getInstance().clearSnapshot()
            } catch (e: Exception) {
                logger.warn("Could not snapshot the client reports at clear", e)
                null
            }
        }

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