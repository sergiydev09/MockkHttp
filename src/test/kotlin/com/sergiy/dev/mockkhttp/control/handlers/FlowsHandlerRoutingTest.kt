package com.sergiy.dev.mockkhttp.control.handlers

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sergiy.dev.mockkhttp.control.ControlApi
import com.sergiy.dev.mockkhttp.control.ControlRequest

/**
 * Audit finding T: `…/flows/clear` was a closed loop. A POST answered "Use GET …/flows/clear"
 * (a verb invented from the method table without checking the route existed), and obeying it
 * treated `clear` as a flow id and 404'd. Neither answer named `DELETE …/flows`, which works.
 *
 * These go through the handler exactly as the router does, with a real [ControlApi] behind it, so
 * a regression in the routing (not the façade) is what they catch.
 */
class FlowsHandlerRoutingTest : BasePlatformTestCase() {

    private lateinit var handler: FlowsHandler
    private lateinit var pid: String

    override fun setUp() {
        super.setUp()
        handler = FlowsHandler(ControlApi.getInstance())
        pid = project.locationHash
    }

    fun testPostToFlowsClearNamesTheCallThatClears() {
        val response = handler.handle(request("POST", "clear"))

        assertEquals(400, response.status)
        assertTrue("the hint must name the route that works: ${response.body}", response.body.contains("DELETE /v1/projects/$pid/flows"))
        assertFalse("the hint must not send the caller to a route that 404s: ${response.body}", response.body.contains("GET /v1/projects/$pid/flows/clear"))
    }

    fun testGetToFlowsClearIsNotTreatedAsAFlowId() {
        val response = handler.handle(request("GET", "clear"))

        assertEquals("a verb that is not a route is a 400, not a missing flow", 400, response.status)
        assertFalse(response.body.contains("UNKNOWN_FLOW"))
        assertTrue(response.body.contains("DELETE /v1/projects/$pid/flows"))
    }

    fun testAWriteToAFlowIdListsTheRealRoutesInsteadOfInventingAVerb() {
        val response = handler.handle(request("POST", "abc123"))

        assertEquals(400, response.status)
        assertFalse("wrongMethod must not name a route nothing answers: ${response.body}", response.body.contains("Use GET"))
        assertTrue("the answer lists what exists: ${response.body}", response.body.contains("flows/{flow_id}"))
        assertTrue(response.body.contains("flows/await"))
    }

    fun testDeleteOnTheCollectionStillClears() {
        val response = handler.handle(request("DELETE"))

        assertEquals(200, response.status)
        assertTrue(response.body.contains("\"cleared\""))
    }

    private fun request(method: String, vararg tail: String): ControlRequest = ControlRequest(
        method = method,
        path = "/v1/projects/$pid/flows" + tail.joinToString("") { "/$it" },
        resource = "flows",
        projectId = pid,
        tail = tail.toList(),
        query = emptyMap(),
        body = "",
        clientName = "test",
        longPollBudgetMs = 0,
        readOnly = false
    )
}
