package com.sergiy.dev.mockkhttp.control

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sergiy.dev.mockkhttp.control.dto.ApiError
import com.sergiy.dev.mockkhttp.control.dto.ApiResult
import com.sergiy.dev.mockkhttp.control.dto.CollectionCreateRequest
import com.sergiy.dev.mockkhttp.control.dto.ErrorCode
import com.sergiy.dev.mockkhttp.control.dto.MODE_DEBUG
import com.sergiy.dev.mockkhttp.control.dto.ResponseSpecDto
import com.sergiy.dev.mockkhttp.control.dto.RuleCreateRequest
import com.sergiy.dev.mockkhttp.control.dto.RuleUpdateRequest
import com.sergiy.dev.mockkhttp.control.dto.SetPackageFilterRequest
import com.sergiy.dev.mockkhttp.control.dto.StartSessionRequest
import com.sergiy.dev.mockkhttp.session.CaptureSessionService
import com.sergiy.dev.mockkhttp.store.MockkRulesStore

/**
 * Session control and response-spec validation on the UI-free façade.
 *
 * **Nothing here enumerates devices.** `CaptureSessionService.listDevices()` initialises ADB and
 * shells out to `simctl`, which in a unit test means spawning an adb server and waiting on a
 * bridge — slow, machine-dependent, and testing the JDK's process API rather than this code. Every
 * assertion below is therefore about a decision the façade makes *before* it looks at hardware, or
 * about a path where no session is running and no device is touched. That is exactly the part that
 * used to answer `501 NOT_IMPLEMENTED`.
 */
class SessionControlApiTest : BasePlatformTestCase() {

    private lateinit var api: ControlApi
    private lateinit var rules: MockkRulesStore
    private lateinit var session: CaptureSessionService
    private lateinit var pid: String

    private var savedEnvironment = ControlEnvironment()

    override fun setUp() {
        super.setUp()
        api = ControlApi.getInstance()
        savedEnvironment = api.environment
        api.environment = ControlEnvironment(instanceId = "test-instance", controlPort = 0, revealSecrets = false)

        rules = MockkRulesStore.getInstance(project)
        session = CaptureSessionService.getInstance(project)
        pid = project.locationHash

        // The light project and its services are reused between test methods, so the remembered
        // package filter has to be cleared or the "requires a name" assertions read a stale target.
        session.setPackageFilter(null)
        rules.removeAllCollections()
    }

    override fun tearDown() {
        try {
            api.environment = savedEnvironment
            session.setPackageFilter(null)
            rules.removeAllCollections()
        } finally {
            super.tearDown()
        }
    }

    // ========================================================================
    // session/start — the arguments are judged before any device is
    // ========================================================================

    fun testStartRejectsAnUnknownModeWithoutTouchingADevice() {
        val error = err(api.startSession(pid, StartSessionRequest(mode = "TURBO")))

        assertEquals(ErrorCode.INVALID_ARGUMENT, error.code)
        assertTrue("the message must name the legal values", error.message.contains("MOCKK"))
        assertNotNull("a rejection must name the next call to make", error.hint)
    }

    /**
     * The footgun guard has to apply to `start` and not only to `mode`: starting straight into
     * DEBUG is the shortest path to an app frozen behind a dialog no agent can click.
     */
    fun testStartRefusesDebugUntilItIsConfirmed() {
        val error = err(api.startSession(pid, StartSessionRequest(mode = MODE_DEBUG)))

        assertEquals(ErrorCode.PAUSE_POLICY_REQUIRED, error.code)
        assertTrue(
            "the hint must offer the non-pausing alternative",
            error.hint!!.contains("confirm_pause_all")
        )
    }

    /**
     * The whole point of the change: these routes used to answer NOT_IMPLEMENTED naming a
     * milestone. Whatever they answer now, it must not be that.
     */
    fun testSessionControlIsNoLongerDeclaredUnimplemented() {
        val meta = api.meta()

        assertTrue(meta.capabilities.contains("session_start_stop"))
        assertTrue(meta.capabilities.contains("devices"))
        assertTrue(
            "nothing may be advertised and disclaimed at once",
            meta.notImplementedYet.none { it.startsWith("session_start_stop") || it.startsWith("devices") }
        )
    }

    // ========================================================================
    // session/stop
    // ========================================================================

    fun testStoppingNothingSaysSoInsteadOfFailing() {
        val stopped = ok(api.stopSession(pid))

        assertFalse("nothing was running, so nothing was stopped", stopped.stopped)
        assertFalse(stopped.wasRunning)
        assertFalse("the session must still report itself stopped", stopped.session.running)
        assertNotNull("a no-op must say what would have made it do something", stopped.hint)
    }

    // ========================================================================
    // session/app — the package filter
    // ========================================================================

    fun testPackageFilterRequiresANameUnlessClearingIsExplicit() {
        val missing = err(api.setPackageFilter(pid, SetPackageFilterRequest()))
        assertEquals(ErrorCode.INVALID_ARGUMENT, missing.code)
        assertTrue("the hint must mention the explicit way to clear it", missing.hint!!.contains("clear"))

        val contradictory = err(
            api.setPackageFilter(pid, SetPackageFilterRequest(packageName = "com.acme.app", clear = true))
        )
        assertEquals(ErrorCode.INVALID_ARGUMENT, contradictory.code)
    }

    fun testAPackageNameThatCannotBeOneIsRefused() {
        val error = err(api.setPackageFilter(pid, SetPackageFilterRequest(packageName = "com.acme app")))

        assertEquals(ErrorCode.INVALID_ARGUMENT, error.code)
        assertNotNull(error.hint)
    }

    /**
     * Choosing the app while nothing is capturing is not an error: it is how an agent sets the
     * target before starting. It must be reported as remembered rather than as applied.
     */
    fun testPackageFilterIsRememberedWhileStopped() {
        val applied = ok(api.setPackageFilter(pid, SetPackageFilterRequest(packageName = "com.acme.app")))

        assertEquals("com.acme.app", applied.packageFilter)
        assertFalse("nothing was running, so nothing could be applied to it", applied.appliedToLiveSession)
        assertTrue(
            "an app that never announced itself must be flagged, not silently accepted",
            applied.warnings.any { it.contains("announced") }
        )

        val session = ok(api.getSession(pid))
        assertEquals("com.acme.app", session.packageFilter)
        assertFalse(session.running)
    }

    // ========================================================================
    // status
    // ========================================================================

    /**
     * FINDING H: an agent that starts at `status` should learn what exists without a second call
     * and without reading prose out of the docs route.
     */
    fun testStatusCarriesTheSameCapabilityListsAsMeta() {
        val meta = api.meta()
        val status = ok(api.status(pid))

        assertEquals(meta.capabilities, status.capabilities)
        assertEquals(meta.notImplementedYet, status.notImplementedYet)
    }

    fun testStatusPointsAtTheCallThatStartsCapturing() {
        val status = ok(api.status(pid))

        assertFalse(status.session.running)
        assertTrue(
            "the warning must name the call, not a tool-window button",
            status.warnings.any { it.contains("session/start") }
        )
    }

    /**
     * FINDING S: port 9876 is released on purpose when the last session stops, so "unbound" is the
     * resting state. Calling it a fault — in the same words as a real bind failure — taught the
     * caller to ignore the warning that matters.
     */
    fun testAnIdlePortIsNotReportedAsAFaultWhenNoSessionIsRunning() {
        val status = ok(api.status(pid))

        assertFalse(status.session.running)
        assertNull("nothing failed to bind, so there is nothing to report", status.interceptor.bindError)
        assertFalse(
            "an unbound port with no session and no bind error is rest, not a fault: ${status.warnings}",
            status.warnings.any { it.contains("not listening") }
        )
        // The one warning that applies still names the fix.
        assertTrue(status.warnings.any { it.contains("session/start") })
    }

    /**
     * The restart path used to stop the running session and only then ask startSession, whose pause
     * gate refused DEBUG — a refusal that had already destroyed the session on the way out. The
     * gate must answer before anything is touched, exactly as it does on start.
     */
    fun testRestartRefusesDebugBeforeTouchingTheSession() {
        val error = err(api.restartSession(pid, StartSessionRequest(mode = MODE_DEBUG)))

        assertEquals(ErrorCode.PAUSE_POLICY_REQUIRED, error.code)
        assertTrue(error.hint!!.contains("confirm_pause_all"))
        assertEquals(ErrorCode.INVALID_ARGUMENT, err(api.restartSession(pid, StartSessionRequest(mode = "TURBO"))).code)
    }

    // ========================================================================
    // FINDING D: status_code is validated like delay_ms
    // ========================================================================

    fun testAStatusCodeOutsideTheHttpRangeIsRefusedOnCreate() {
        val collectionId = ok(api.createCollection(pid, CollectionCreateRequest(name = "Bad codes"))).collectionId

        for (bogus in listOf(9999, -1, 0, 99, 600)) {
            val error = err(
                api.createRule(
                    pid,
                    RuleCreateRequest(
                        method = "GET",
                        url = "https://api.example.com/v1/thing",
                        response = ResponseSpecDto(statusCode = bogus),
                        collectionId = collectionId
                    )
                )
            )
            assertEquals("status_code $bogus must be refused", ErrorCode.INVALID_ARGUMENT, error.code)
            assertTrue("the message must name the field", error.message.contains("status_code"))
            assertNotNull("the refusal must say what to send instead", error.hint)
        }

        assertEquals("no rule may have been stored", 0, rules.getAllRules().size)
    }

    fun testTheEdgesOfTheHttpRangeAreStillAccepted() {
        val collectionId = ok(api.createCollection(pid, CollectionCreateRequest(name = "Edges"))).collectionId

        for (legal in listOf(100, 200, 599)) {
            val created = ok(
                api.createRule(
                    pid,
                    RuleCreateRequest(
                        method = "GET",
                        url = "https://api.example.com/v1/thing-$legal",
                        response = ResponseSpecDto(statusCode = legal),
                        collectionId = collectionId
                    )
                )
            )
            assertEquals(legal, created.rule.response.statusCode)
        }
    }

    fun testAStatusCodeOutsideTheHttpRangeIsRefusedOnUpdateAndChangesNothing() {
        val collectionId = ok(api.createCollection(pid, CollectionCreateRequest(name = "Update codes"))).collectionId
        val ruleId = ok(
            api.createRule(
                pid,
                RuleCreateRequest(
                    method = "GET",
                    url = "https://api.example.com/v1/thing",
                    response = ResponseSpecDto(statusCode = 503, body = "down"),
                    collectionId = collectionId
                )
            )
        ).rule.ruleId

        val error = err(
            api.updateRule(pid, RuleUpdateRequest(ruleId = ruleId, response = ResponseSpecDto(statusCode = 9999)))
        )
        assertEquals(ErrorCode.INVALID_ARGUMENT, error.code)

        val unchanged = ok(api.getRule(pid, ruleId))
        assertEquals("a refused update must not have written anything", 503, unchanged.response.statusCode)
        assertEquals("down", unchanged.response.body)
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun <T : Any> ok(result: ApiResult<T>): T = when (result) {
        is ApiResult.Ok -> result.value
        is ApiResult.Err -> throw AssertionError("expected success, got ${result.error.code}: ${result.error.message}")
    }

    private fun err(result: ApiResult<*>): ApiError = when (result) {
        is ApiResult.Err -> result.error
        is ApiResult.Ok -> throw AssertionError("expected a typed failure, got ${result.value}")
    }
}
