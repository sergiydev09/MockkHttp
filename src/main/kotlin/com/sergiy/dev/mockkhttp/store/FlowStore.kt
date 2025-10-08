package com.sergiy.dev.mockkhttp.store

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.sergiy.dev.mockkhttp.model.HttpFlowData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Store for managing intercepted HTTP flows.
 * Thread-safe storage with listeners for UI updates.
 */
@Service(Service.Level.PROJECT)
class FlowStore(project: Project) {


    // Thread-safe storage for flows
    private val flows = ConcurrentHashMap<String, HttpFlowData>()
    private val flowOrder = CopyOnWriteArrayList<String>() // Maintains insertion order

    // Reactive UI updates with SharedFlow (Problem #4 optimization)
    // Using SharedFlow instead of listeners for better backpressure handling
    private val _flowAddedEvents = MutableSharedFlow<HttpFlowData>(
        replay = 0,  // Don't replay events to new subscribers
        extraBufferCapacity = 1000,  // Buffer up to 1000 events if consumer is slow
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST  // Drop oldest if buffer full
    )
    val flowAddedEvents: SharedFlow<HttpFlowData> = _flowAddedEvents.asSharedFlow()

    private val _flowUpdatedEvents = MutableSharedFlow<HttpFlowData>(
        replay = 0,
        extraBufferCapacity = 1000,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val flowUpdatedEvents: SharedFlow<HttpFlowData> = _flowUpdatedEvents.asSharedFlow()

    private val _flowsClearedEvents = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val flowsClearedEvents: SharedFlow<Unit> = _flowsClearedEvents.asSharedFlow()

    // Legacy listeners (kept for backward compatibility, but UI should use SharedFlow)
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

        // Memory budget optimization (Problem #3)
        // Old: 1000 flows × 5MB = 5GB potential
        // New: 200 flows max with 50MB budget
        private const val MAX_FLOWS = 200
        private const val MEMORY_BUDGET_MB = 50
        private const val MAX_AGE_MS = 3600_000L  // 1 hour

        // Estimated sizes for memory calculation
        private const val ESTIMATED_FLOW_BASE_SIZE = 2048  // 2KB per flow metadata
        private const val BYTES_PER_MB = 1024 * 1024
    }

    /**
     * Calculate estimated memory usage in MB.
     */
    private fun calculateMemoryUsage(): Double {
        var totalBytes = 0L
        flows.values.forEach { flow ->
            totalBytes += ESTIMATED_FLOW_BASE_SIZE
            totalBytes += flow.request.content?.length ?: 0
            totalBytes += flow.response?.content?.length ?: 0
        }
        return totalBytes.toDouble() / BYTES_PER_MB
    }

    /**
     * Check if we should enforce memory cleanup.
     */
    private fun shouldEnforceMemoryCleanup(): Boolean {
        return calculateMemoryUsage() > MEMORY_BUDGET_MB
    }

    /**
     * Remove old flows to free memory.
     * Removes flows older than MAX_AGE_MS, starting with oldest.
     */
    private fun enforceMemoryCleanup() {
        val now = System.currentTimeMillis()
        val threshold = now - MAX_AGE_MS

        var removed = 0
        val toRemove = mutableListOf<String>()

        // Find flows older than threshold
        for (flowId in flowOrder) {
            val flow = flows[flowId] ?: continue
            val flowAgeMs = now - (flow.timestamp * 1000).toLong()  // timestamp is in seconds
            if (flowAgeMs > MAX_AGE_MS) {
                toRemove.add(flowId)
            }
        }

        // Remove old flows
        toRemove.forEach { flowId ->
            flowOrder.remove(flowId)
            val removedFlow = flows.remove(flowId)
            if (removedFlow?.paused == true) {
                pausedFlowsCount--
            }
            removed++
        }

        if (removed > 0) {
            val memoryUsage = calculateMemoryUsage()
        }
    }

    /**
     * Add a new flow to the store.
     */
    fun addFlow(flow: HttpFlowData) {

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

            // Enforce max size (hard limit)
            while (flowOrder.size > MAX_FLOWS) {
                val oldestId = flowOrder.removeAt(0)
                val removed = flows.remove(oldestId)

                if (removed?.paused == true) {
                    pausedFlowsCount--
                }
            }

            // Enforce memory budget (soft limit based on estimated size)
            if (shouldEnforceMemoryCleanup()) {
                enforceMemoryCleanup()
            }

            val memoryUsage = calculateMemoryUsage()

            // Emit to SharedFlow (non-blocking, with backpressure)
            _flowAddedEvents.tryEmit(flow)

            // Notify legacy listeners (kept for backward compatibility)
            flowAddedListeners.forEach { listener ->
                try {
                    listener(flow)
                } catch (e: Exception) {
                }
            }
        } else {
            // Updated flow

            // Update paused count
            val oldFlow = flows[flow.flowId]
            if (oldFlow?.paused == true && !flow.paused) {
                pausedFlowsCount--
            } else if (oldFlow?.paused == false && flow.paused) {
                pausedFlowsCount++
            }

            // Emit to SharedFlow (non-blocking, with backpressure)
            _flowUpdatedEvents.tryEmit(flow)

            // Notify legacy listeners (kept for backward compatibility)
            flowUpdatedListeners.forEach { listener ->
                try {
                    listener(flow)
                } catch (e: Exception) {
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

        val count = flows.size
        flows.clear()
        flowOrder.clear()
        pausedFlowsCount = 0


        // Emit to SharedFlow (non-blocking)
        _flowsClearedEvents.tryEmit(Unit)

        // Notify legacy listeners
        flowsClearedListeners.forEach { listener ->
            try {
                listener()
            } catch (e: Exception) {
            }
        }
    }

    /**
     * Register listener for when a flow is added.
     */
    fun addFlowAddedListener(listener: (HttpFlowData) -> Unit) {
        flowAddedListeners.add(listener)
    }

    /**
     * Register listener for when a flow is updated.
     */
    fun addFlowUpdatedListener(listener: (HttpFlowData) -> Unit) {
        flowUpdatedListeners.add(listener)
    }

    /**
     * Register listener for when flows are cleared.
     */
    fun addFlowsClearedListener(listener: () -> Unit) {
        flowsClearedListeners.add(listener)
    }
}
