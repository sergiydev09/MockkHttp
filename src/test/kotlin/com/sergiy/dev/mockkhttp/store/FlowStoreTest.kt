package com.sergiy.dev.mockkhttp.store

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sergiy.dev.mockkhttp.model.HttpFlowData
import com.sergiy.dev.mockkhttp.model.HttpRequestData
import com.sergiy.dev.mockkhttp.model.HttpResponseData
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The store's cap and its eviction counter, which `since_clear.comparable` relies on (audit round
 * 11, AQ, and the adversarial review of its fix).
 */
class FlowStoreTest : BasePlatformTestCase() {

    private lateinit var store: FlowStore
    private lateinit var settings: SettingsStore

    override fun setUp() {
        super.setUp()
        store = FlowStore.getInstance(project)
        settings = SettingsStore.getInstance(project)
        settings.setMaxFlowsRetained(300)
        store.clearAllFlows()
    }

    override fun tearDown() {
        try {
            settings.setMaxFlowsRetained(300)
            store.clearAllFlows()
        } finally {
            super.tearDown()
        }
    }

    fun `test the eviction counter is exact under concurrent adds`() {
        // Flows arrive on one pooled thread per client connection. A bare increment on a volatile
        // lost updates, and a lost eviction is a "comparable: true" that is false.
        settings.setMaxFlowsRetained(10)
        val threads = 8
        val perThread = 400
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                repeat(perThread) { store.addFlow(flow()) }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(60, TimeUnit.SECONDS))
        pool.shutdownNow()

        val added = threads * perThread
        assertEquals("the cap held", 10, store.getFlowCount())
        assertEquals("every eviction counted, none lost", added - store.getFlowCount(), store.getEvictedSinceClear())
    }

    fun `test lowering the cap evicts on the next read and counts it`() {
        // The limit is lowered in Settings with no traffic to run the loop in addFlow; until the
        // next flow the store held more than flows.capacity said, with nothing evicted.
        repeat(20) { store.addFlow(flow()) }
        assertEquals(20, store.getFlowCount())
        assertEquals(0, store.getEvictedSinceClear())

        settings.setMaxFlowsRetained(10)

        assertEquals("the cap is applied on read", 10, store.getFlowCount())
        assertEquals(10, store.getAllFlows().size)
        assertEquals("and what it evicted is counted", 10, store.getEvictedSinceClear())

        store.clearAllFlows()
        assertEquals(0, store.getEvictedSinceClear())
    }

    private fun flow(): HttpFlowData = HttpFlowData(
        flowId = UUID.randomUUID().toString(),
        paused = false,
        request = HttpRequestData("GET", "https://api.example.com/v1/x", "api.example.com", "/v1/x", emptyMap(), ""),
        response = HttpResponseData(200, "OK", emptyMap(), "{}"),
        timestamp = 0.0,
        duration = 0.0
    )
}
