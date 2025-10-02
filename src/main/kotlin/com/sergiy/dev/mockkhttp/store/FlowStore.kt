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

        // Maximum number of flows to keep in memory
        private const val MAX_FLOWS = 1000
    }

    /**
     * Add a new flow to the store.
     */
    fun addFlow(flow: HttpFlowData) {
        logger.debug("Adding flow to store: ${flow.flowId}")

        // Check if flow already exists (update case)
        val isUpdate = flows.containsKey(flow.flowId)

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
            while (flowOrder.size > MAX_FLOWS) {
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

            // Update paused count
            val oldFlow = flows[flow.flowId]
            if (oldFlow?.paused == true && !flow.paused) {
                pausedFlowsCount--
            } else if (oldFlow?.paused == false && flow.paused) {
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
     * Register listener for when flows are cleared.
     */
    fun addFlowsClearedListener(listener: () -> Unit) {
        flowsClearedListeners.add(listener)
        logger.debug("Flows cleared listener registered (total: ${flowsClearedListeners.size})")
    }
}