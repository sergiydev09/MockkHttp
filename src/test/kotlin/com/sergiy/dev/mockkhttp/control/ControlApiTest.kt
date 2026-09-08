package com.sergiy.dev.mockkhttp.control

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sergiy.dev.mockkhttp.control.dto.ApiError
import com.sergiy.dev.mockkhttp.control.dto.ApiResult
import com.sergiy.dev.mockkhttp.control.dto.AwaitFlowRequest
import com.sergiy.dev.mockkhttp.control.dto.CollectionCreateRequest
import com.sergiy.dev.mockkhttp.control.dto.ErrorCode
import com.sergiy.dev.mockkhttp.control.dto.FlowQuery
import com.sergiy.dev.mockkhttp.control.dto.INCLUDE_BODY_BOTH
import com.sergiy.dev.mockkhttp.control.dto.INCLUDE_BODY_RESPONSE
import com.sergiy.dev.mockkhttp.control.dto.MATCH_EXACT
import com.sergiy.dev.mockkhttp.control.dto.MODE_MOCKK
import com.sergiy.dev.mockkhttp.control.dto.MODE_MOCKK_DEBUG
import com.sergiy.dev.mockkhttp.control.dto.MatchExplainRequest
import com.sergiy.dev.mockkhttp.control.dto.QueryParamDto
import com.sergiy.dev.mockkhttp.control.dto.RESOLUTION_MOCKED
import com.sergiy.dev.mockkhttp.control.dto.ResponseSpecDto
import com.sergiy.dev.mockkhttp.control.dto.RuleCreateRequest
import com.sergiy.dev.mockkhttp.control.dto.RuleUpdateRequest
import com.sergiy.dev.mockkhttp.model.HttpFlowData
import com.sergiy.dev.mockkhttp.model.HttpRequestData
import com.sergiy.dev.mockkhttp.model.HttpResponseData
import com.sergiy.dev.mockkhttp.store.FlowStore
import com.sergiy.dev.mockkhttp.store.MockkRulesStore
import com.sergiy.dev.mockkhttp.store.SettingsStore
import java.net.URI
import java.util.UUID

/**
 * Behaviour of the UI-free façade every automated caller goes through.
 *
 * Extends [BasePlatformTestCase] because [ControlApi] resolves callers to a real open
 * [com.intellij.openapi.project.Project] through `ProjectManager`, and the light fixture is what
 * puts one there. The platform test framework is JUnit 3/4 based, so methods must be `testXxx`.
 *
 * [ControlApi] is an APPLICATION service and its flow cursors live for the whole JVM run, so
 * nothing here asserts an absolute sequence number — only that cursors move in the right direction.
 */
class ControlApiTest : BasePlatformTestCase() {

    private lateinit var api: ControlApi
    private lateinit var flows: FlowStore
    private lateinit var rules: MockkRulesStore
    private lateinit var settings: SettingsStore
    private lateinit var pid: String

    private var savedEnvironment = ControlEnvironment()

    override fun setUp() {
        super.setUp()
        api = ControlApi.getInstance()
        savedEnvironment = api.environment
        api.environment = ControlEnvironment(instanceId = "test-instance", controlPort = 0, revealSecrets = false)

        flows = FlowStore.getInstance(project)
        rules = MockkRulesStore.getInstance(project)
        settings = SettingsStore.getInstance(project)
        pid = project.locationHash

        // The light project (and therefore its services) is reused between test methods, and the
        // app-level ControlApi outlives all of them. Reset through the API so the flow cursor map
        // is dropped too, otherwise a reused flow id would keep its old sequence number.
        api.clearFlows(pid)
        rules.removeAllCollections()
        settings.setMaxStoredBodyKb(512)
        settings.setMaxFlowsRetained(300)
    }

    override fun tearDown() {
        try {
            api.environment = savedEnvironment
            api.clearFlows(pid)
            rules.removeAllCollections()
            settings.setMaxStoredBodyKb(512)
            settings.setMaxFlowsRetained(300)
        } finally {
            super.tearDown()
        }
    }

    // ========================================================================
    // Project resolution
    // ========================================================================

    fun testTheOpenTestProjectIsAddressableByItsLocationHash() {
        // Everything below depends on this; if the fixture project is not visible to
        // ProjectManager, fail here with a clear reason instead of failing eight vague tests.
        val listed = ok(api.listFlows(pid, FlowQuery()))
        assertEquals(0, listed.returned)
    }

    fun testUnknownProjectIdIsATypedErrorNotAnException() {
        val error = err(api.listFlows("no-such-project-id", FlowQuery()))

        assertEquals(ErrorCode.PROJECT_NOT_FOUND, error.code)
        assertEquals(404, error.code.httpStatus)
        assertFalse("a wrong project id can never fix itself by being retried", error.retryable)
        assertNotNull("every rejection must name the call that fixes it", error.hint)
    }

    fun testUnknownProjectIdIsRefusedBeforeAnyMutationIsAttempted() {
        val collection = ok(api.createCollection(pid, CollectionCreateRequest(name = "keep me")))
        val ruleId = createRule(collection.collectionId).rule.ruleId

        assertEquals(ErrorCode.PROJECT_NOT_FOUND, err(api.deleteRule("no-such-project-id", ruleId)).code)

        // The rule in the real project must be untouched.
        assertEquals(ruleId, ok(api.getRule(pid, ruleId)).ruleId)
    }

    fun testAProjectCanAlsoBeAddressedByItsName() {
        val flowId = addFlow(url = "https://api.example.com/v1/by-name")

        val byName = ok(api.listFlows(project.name, FlowQuery()))

        assertEquals(listOf(flowId), byName.flows.map { it.flowId })
    }

    fun testUnknownFlowAndRuleIdsAreTypedErrors() {
        assertEquals(ErrorCode.UNKNOWN_FLOW, err(api.getFlow(pid, "no-such-flow")).code)
        assertEquals(ErrorCode.UNKNOWN_RULE, err(api.getRule(pid, "no-such-rule")).code)
        assertEquals(
            ErrorCode.UNKNOWN_COLLECTION,
            err(api.listRules(pid, collectionId = "no-such-collection")).code
        )
    }

    // ========================================================================
    // Flow filtering
    // ========================================================================

    fun testFlowsAreFilteredByRequestAndResponseFields() {
        val users = addFlow(method = "GET", url = "https://api.example.com/v1/users", status = 200)
        val login = addFlow(
            method = "POST",
            url = "https://api.example.com/v1/login",
            status = 401,
            requestBody = """{"user":"bob"}"""
        )
        val logo = addFlow(method = "GET", url = "https://cdn.example.org/img/logo.png", status = 200)

        assertEquals(listOf(login), ids(FlowQuery(method = "POST")))
        // Hosts compare case-insensitively; paths do not.
        assertEquals(setOf(users, login), ids(FlowQuery(host = "API.EXAMPLE.COM")).toSet())
        assertEquals(listOf(login), ids(FlowQuery(path = "/v1/login")))
        assertEquals(emptyList<String>(), ids(FlowQuery(path = "/V1/LOGIN")))
        assertEquals(listOf(login), ids(FlowQuery(status = 401)))
        assertEquals(setOf(users, logo), ids(FlowQuery(statusMin = 200, statusMax = 299)).toSet())
        assertEquals(listOf(login), ids(FlowQuery(bodyContains = "bob")))
        assertEquals(listOf(logo), ids(FlowQuery(pathContains = "LOGO")))
        assertEquals(setOf(users, login), ids(FlowQuery(pathRegex = "/v1/.*")).toSet())
        assertEquals(listOf(logo), ids(FlowQuery(urlContains = "cdn.example.org")))
    }

    fun testAnExactPathIgnoresATrailingSlash() {
        val withSlash = addFlow(url = "https://api.example.com/v1/login/")

        assertEquals(listOf(withSlash), ids(FlowQuery(path = "/v1/login")))
    }

    fun testResolutionReportsHowAFlowWasAnswered() {
        val mocked = addFlow(url = "https://api.example.com/v1/users", mockApplied = true, mockRuleName = "users 200")
        addFlow(url = "https://api.example.com/v1/other")

        val listed = ok(api.listFlows(pid, FlowQuery(resolution = RESOLUTION_MOCKED)))

        assertEquals(listOf(mocked), listed.flows.map { it.flowId })
        assertEquals(RESOLUTION_MOCKED, listed.flows.single().resolution)
        assertEquals("users 200", listed.flows.single().mockRuleName)
        // Never guessed: only the pause path can prove the app consumed our reply.
        assertNull(listed.flows.single().appNotified)
    }

    fun testFlowsComeBackNewestFirst() {
        val first = addFlow(url = "https://api.example.com/1")
        val second = addFlow(url = "https://api.example.com/2")
        val third = addFlow(url = "https://api.example.com/3")

        assertEquals(listOf(third, second, first), ids(FlowQuery()))
    }

    fun testMutuallyExclusiveAndInvalidMatchersAreRejected() {
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            err(api.listFlows(pid, FlowQuery(path = "/x", pathRegex = "/x"))).code
        )
        assertEquals(ErrorCode.INVALID_REGEX, err(api.listFlows(pid, FlowQuery(pathRegex = "["))).code)
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            err(api.listFlows(pid, FlowQuery(includeBody = "everything"))).code
        )
    }

    // ========================================================================
    // The since_seq cursor
    // ========================================================================

    fun testSinceSeqReturnsOnlyWhatArrivedAfterTheCursor() {
        addFlow(url = "https://api.example.com/1")
        addFlow(url = "https://api.example.com/2")

        val firstPage = ok(api.listFlows(pid, FlowQuery()))
        assertEquals(2, firstPage.returned)
        val cursor = firstPage.nextSeq

        val third = addFlow(url = "https://api.example.com/3")

        val secondPage = ok(api.listFlows(pid, FlowQuery(sinceSeq = cursor)))
        assertEquals(listOf(third), secondPage.flows.map { it.flowId })
        assertTrue("the cursor must keep moving forward", secondPage.nextSeq > cursor)

        // Re-reading with the same cursor is idempotent — nothing new arrived in between.
        assertEquals(listOf(third), ok(api.listFlows(pid, FlowQuery(sinceSeq = cursor))).flows.map { it.flowId })
    }

    fun testTheCursorNeverRewindsAcrossAClear() {
        addFlow(url = "https://api.example.com/1")
        val cursor = ok(api.listFlows(pid, FlowQuery())).nextSeq

        val cleared = ok(api.clearFlows(pid))
        assertEquals(1, cleared.cleared)
        assertTrue(
            "a cursor an agent is holding must never start matching different flows",
            cleared.nextSeq >= cursor
        )

        val afterClear = addFlow(url = "https://api.example.com/2")
        assertEquals(listOf(afterClear), ok(api.listFlows(pid, FlowQuery(sinceSeq = cursor))).flows.map { it.flowId })
    }

    fun testAwaitWithoutACursorWaitsForSomethingNewRatherThanReturningHistory() {
        addFlow(url = "https://api.example.com/already-here")

        // No since_seq means "wait for something new", so the flow captured a moment ago must not
        // be handed back as if it had just arrived.
        val fresh = ok(api.awaitFlow(pid, AwaitFlowRequest(count = 1, waitMs = 0L)))
        assertFalse(fresh.satisfied)
        assertEquals(0, fresh.actual)
        assertNotNull("a timed-out await must explain what to change", fresh.hint)

        // …while an explicit cursor of 0 does mean "everything you have".
        val historical = ok(api.awaitFlow(pid, AwaitFlowRequest(count = 1, waitMs = 0L, sinceSeq = 0L)))
        assertTrue(historical.satisfied)
        assertEquals(1, historical.actual)
    }

    // ========================================================================
    // Truncation and redaction are reported, never silent
    // ========================================================================

    fun testTruncatingABodyToMaxBodyCharsIsReported() {
        val body = "x".repeat(5_000)
        val flowId = addFlow(responseBody = body)

        val detail = ok(api.getFlow(pid, flowId, maxBodyChars = 100))
        val response = detail.response!!

        assertEquals(100, response.body!!.length)
        assertTrue("the caller must be told the body was cut", response.bodyTruncated)
        // The reported size is the TRUE size, not the size of what was handed over.
        assertEquals(5_000, response.bodyBytes)
        assertFalse(response.bodyTruncatedByRetention)
        assertFalse(detail.storedBodyTruncatedByRetention)
    }

    fun testABodyWithinTheBudgetIsNotFlaggedAsTruncated() {
        val flowId = addFlow(responseBody = "x".repeat(50))

        val response = ok(api.getFlow(pid, flowId, maxBodyChars = 100)).response!!

        assertFalse(response.bodyTruncated)
        assertEquals(50, response.bodyBytes)
    }

    fun testRetentionTruncationIsReportedSeparatelyFromResponseTruncation() {
        // 16 KB is the smallest retention budget the settings allow.
        settings.setMaxStoredBodyKb(16)
        val flowId = addFlow(responseBody = "y".repeat(40_000))

        val detail = ok(api.getFlow(pid, flowId))
        val response = detail.response!!

        assertTrue(
            "bytes discarded on the way into the cache are gone for good and must be flagged",
            response.bodyTruncatedByRetention
        )
        assertTrue(detail.storedBodyTruncatedByRetention)
        assertFalse(
            "what survived retention fits the default budget, so this response cut nothing",
            response.bodyTruncated
        )
        assertTrue(
            "the warning must say the missing bytes cannot be recovered",
            detail.warnings.any { it.contains("Cache") }
        )
    }

    fun testAnOversizedMaxBodyCharsIsClampedAndTheClampIsReported() {
        val flowId = addFlow(responseBody = "z".repeat(100))

        val detail = ok(api.getFlow(pid, flowId, maxBodyChars = 10_000_000))
        val clamp = detail.clamped?.get("max_body_chars")
            ?: throw AssertionError("no parameter may be silently ignored: max_body_chars was clamped without saying so")

        assertEquals(10_000_000L, clamp.requested)
        assertEquals(ControlApi.HARD_MAX_BODY_CHARS.toLong(), clamp.applied)
        assertTrue(clamp.reason.isNotEmpty())
    }

    fun testAnOversizedFlowLimitIsClampedAndTheClampIsReported() {
        addFlow(url = "https://api.example.com/1")

        val listed = ok(api.listFlows(pid, FlowQuery(limit = 9_999)))
        val clamp = listed.clamped?.get("limit")
            ?: throw AssertionError("an over-large limit was clamped without saying so")

        assertEquals(9_999L, clamp.requested)
        assertEquals(ControlApi.MAX_FLOW_LIMIT.toLong(), clamp.applied)
    }

    fun testAPageThatDoesNotShowEverythingSaysSo() {
        addFlow(url = "https://api.example.com/1")
        addFlow(url = "https://api.example.com/2")
        addFlow(url = "https://api.example.com/3")

        val page = ok(api.listFlows(pid, FlowQuery(limit = 1)))

        assertEquals(1, page.returned)
        assertEquals(3, page.totalMatching)
        assertTrue(page.warnings.any { it.contains("more flows match") })
    }

    fun testSecretHeadersAreRedactedWithTheirTrueLengthUnlessRevealingIsAllowed() {
        val secret = "Bearer super-secret-token"
        val flowId = addFlow(requestHeaders = mapOf("Authorization" to secret, "Accept" to "application/json"))

        val redactedView = ok(api.getFlow(pid, flowId)).request
        assertEquals(listOf("authorization"), redactedView.redactedHeaders)
        assertEquals(
            "<redacted:${secret.toByteArray(Charsets.UTF_8).size}b>",
            redactedView.headers["Authorization"]
        )
        assertEquals("application/json", redactedView.headers["Accept"])

        // Asking for secrets while the IDE forbids it is a typed refusal, not a silent redaction.
        val refused = err(api.getFlow(pid, flowId, includeSecrets = true))
        assertEquals(ErrorCode.REVEAL_DISABLED, refused.code)

        api.environment = api.environment.copy(revealSecrets = true)
        val revealed = ok(api.getFlow(pid, flowId, includeSecrets = true)).request
        assertEquals(secret, revealed.headers["Authorization"])
        assertTrue(revealed.redactedHeaders.isEmpty())
    }

    fun testIncludeBodyControlsWhichHalvesComeBackInAListing() {
        val flowId = addFlow(requestBody = "REQUEST", responseBody = "RESPONSE")

        assertNull("bodies must be opt-in", ok(api.listFlows(pid, FlowQuery())).bodies)

        val responseOnly = ok(api.listFlows(pid, FlowQuery(includeBody = INCLUDE_BODY_RESPONSE))).bodies!![flowId]!!
        assertNull(responseOnly.request)
        assertEquals("RESPONSE", responseOnly.response!!.body)

        val both = ok(api.listFlows(pid, FlowQuery(includeBody = INCLUDE_BODY_BOTH))).bodies!![flowId]!!
        assertEquals("REQUEST", both.request!!.body)
        assertEquals("RESPONSE", both.response!!.body)
    }

    // ========================================================================
    // Rule CRUD
    // ========================================================================

    fun testRuleCrudRoundTrip() {
        val collection = ok(api.createCollection(pid, CollectionCreateRequest(name = "Agent rules", packageName = "com.acme.app")))

        val created = ok(
            api.createRule(
                pid,
                RuleCreateRequest(
                    name = "login 503",
                    method = "get",
                    url = "https://api.example.com/v1/login",
                    response = ResponseSpecDto(
                        statusCode = 503,
                        headers = mapOf("Content-Type" to "application/json"),
                        body = """{"error":"down"}"""
                    ),
                    collectionId = collection.collectionId
                )
            )
        )
        val ruleId = created.rule.ruleId

        assertEquals("GET", created.rule.method)
        assertEquals(collection.collectionId, created.rule.collectionId)
        assertEquals("api.example.com", created.willMatch.host)
        assertEquals("/v1/login", created.willMatch.path)
        assertEquals(MATCH_EXACT, created.willMatch.hostMatch)
        assertEquals(listOf(MODE_MOCKK, MODE_MOCKK_DEBUG), created.willFireInModes)
        assertNull("no session is running in a unit test", created.currentMode)

        val read = ok(api.getRule(pid, ruleId))
        assertEquals(503, read.response.statusCode)
        assertEquals("""{"error":"down"}""", read.response.body)
        assertEquals("api.example.com", read.host)
        assertEquals("/v1/login", read.path)
        assertTrue(read.enabled)

        val updated = ok(api.updateRule(pid, RuleUpdateRequest(ruleId = ruleId, name = "login 500", response = ResponseSpecDto(statusCode = 500))))
        assertEquals(ruleId, updated.rule.ruleId)
        assertEquals(collection.collectionId, updated.rule.collectionId)

        val afterUpdate = ok(api.getRule(pid, ruleId))
        assertEquals("login 500", afterUpdate.name)
        assertEquals(500, afterUpdate.response.statusCode)
        // The body was not part of the update, so it must be left exactly as it was.
        assertEquals("""{"error":"down"}""", afterUpdate.response.body)

        val listed = ok(api.listRules(pid, collectionId = collection.collectionId, includeBodies = true))
        assertEquals(1, listed.total)
        assertEquals(ruleId, listed.rules.single().ruleId)

        assertTrue(ok(api.deleteRule(pid, ruleId)).deleted)
        assertEquals(ErrorCode.UNKNOWN_RULE, err(api.getRule(pid, ruleId)).code)
        assertEquals(0, ok(api.listRules(pid, collectionId = collection.collectionId)).total)
    }

    fun testARuleReadIsASnapshotNotTheLiveMutableRule() {
        val collection = ok(api.createCollection(pid, CollectionCreateRequest(name = "snapshots")))
        val ruleId = ok(
            api.createRule(
                pid,
                RuleCreateRequest(
                    name = "original",
                    method = "GET",
                    url = "https://api.example.com/v1/users",
                    response = ResponseSpecDto(statusCode = 200, headers = mapOf("X-Env" to "stage"), body = "original body"),
                    collectionId = collection.collectionId
                )
            )
        ).rule.ruleId

        val before = ok(api.getRule(pid, ruleId))
        assertTrue(before.query.isEmpty())

        ok(
            api.updateRule(
                pid,
                RuleUpdateRequest(
                    ruleId = ruleId,
                    name = "changed",
                    response = ResponseSpecDto(statusCode = 503, headers = mapOf("X-Env" to "prod"), body = "changed body"),
                    query = listOf(QueryParamDto(key = "id", value = "7"))
                )
            )
        )

        // The store hands out its live, mutable MockkRule objects; a view built from one must copy,
        // or a value an agent already read changes under it after the fact.
        assertEquals("original", before.name)
        assertEquals(200, before.response.statusCode)
        assertEquals("original body", before.response.body)
        assertEquals(mapOf("X-Env" to "stage"), before.response.headers)
        assertTrue("the earlier read shared the rule's live query list", before.query.isEmpty())

        val after = ok(api.getRule(pid, ruleId))
        assertEquals("changed", after.name)
        assertEquals(503, after.response.statusCode)
        assertEquals(1, after.query.size)
    }

    fun testCreatingARuleWithoutAUrlOrAFlowIsRejected() {
        val collection = ok(api.createCollection(pid, CollectionCreateRequest(name = "bad input")))

        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            err(api.createRule(pid, RuleCreateRequest(method = "GET", collectionId = collection.collectionId))).code
        )
        assertEquals(
            ErrorCode.INVALID_URL,
            err(api.createRule(pid, RuleCreateRequest(method = "GET", url = "/v1/users", collectionId = collection.collectionId))).code
        )
        assertEquals(
            ErrorCode.UNKNOWN_FLOW,
            err(api.createRule(pid, RuleCreateRequest(fromFlowId = "no-such-flow", collectionId = collection.collectionId))).code
        )
        assertEquals(
            ErrorCode.UNKNOWN_COLLECTION,
            err(api.createRule(pid, RuleCreateRequest(method = "GET", url = "https://a.example.com/x", collectionId = "nope"))).code
        )
    }

    fun testARuleClonedFromAFlowLoosensTheParamsThatWouldMakeItMatchOnlyOnce() {
        val flowId = addFlow(url = "https://api.example.com/v1/users?id=7&ts=1700000000000")
        val collection = ok(api.createCollection(pid, CollectionCreateRequest(name = "cloned")))

        val created = ok(
            api.createRule(pid, RuleCreateRequest(fromFlowId = flowId, collectionId = collection.collectionId))
        )

        // `ts` changes on every call, so a rule that requires its captured value matches exactly the
        // call it was cloned from and nothing afterwards. Loosening is reported, never silent.
        assertTrue(created.loosenedParams.contains("ts"))
        assertEquals(listOf("id"), created.willMatch.requiredParams)
        assertEquals("GET", created.rule.method)
    }

    // ========================================================================
    // Explain
    // ========================================================================

    fun `test a credential in the query is redacted like a header`() {
        // Audit round 16, BG: an API key travelling as ?appid= came back in clear on every surface
        // that shows a URL, and redacted_headers said nothing because it is not a header.
        val flowId = addFlow(url = "https://api.example.com/v1/weather?appid=SECRET123&q=Madrid")
        val listed = ok(api.listFlows(pid, FlowQuery())).flows.single { it.flowId == flowId }
        assertFalse("the key must not be in the summary", listed.url.contains("SECRET123"))
        assertTrue(listed.url.contains("appid=<redacted:9b>") && listed.url.contains("q=Madrid"))
        assertEquals(listOf("appid"), listed.redactedQuery)
        val detail = ok(api.getFlow(pid, flowId))
        assertEquals(listOf("appid"), detail.request.redactedQuery)
        assertFalse(detail.flow.url.contains("SECRET123"))

        api.environment = api.environment.copy(revealSecrets = true)
        val revealed = ok(api.getFlow(pid, flowId, includeSecrets = true))
        assertTrue("revealed under the same gate as headers", revealed.flow.url.contains("appid=SECRET123"))
        assertEquals(listOf("appid"), revealed.request.revealedQuery)
        assertTrue("and named as revealed", revealed.warnings.any { it.contains("query appid") })

        // Adversarial review: the listing and await reveal the URL in their summaries whatever
        // include_body says, so the warning must come from the summaries too.
        val listedInClear = ok(api.listFlows(pid, FlowQuery(includeSecrets = true)))
        assertTrue(listedInClear.flows.single { it.flowId == flowId }.url.contains("SECRET123"))
        assertTrue("a listing that reveals says so", listedInClear.warnings.any { it.contains("query appid") })
        val awaited = ok(api.awaitFlow(pid, AwaitFlowRequest(count = 1, waitMs = 200, includeSecrets = true, sinceSeq = 0L)))
        assertTrue("and so does await", awaited.warnings.any { it.contains("query appid") })
        api.environment = api.environment.copy(revealSecrets = false)

        // ';' as a pair separator, and spellings: hyphens, underscores, case, and an OAuth code.
        val odd = addFlow(url = "https://api.example.com/cb?a=1;Access-Token=SECRET456&code=AUTHCODE&plain=x")
        val oddListed = ok(api.listFlows(pid, FlowQuery())).flows.single { it.flowId == odd }
        assertFalse(oddListed.url.contains("SECRET456") || oddListed.url.contains("AUTHCODE"))
        assertTrue(oddListed.url.contains("a=1;Access-Token=<redacted:9b>&code=<redacted:8b>&plain=x"))
        assertEquals(listOf("access-token", "code"), oddListed.redactedQuery)
    }

    fun `test a rule cloned from a flow never keeps a credential`() {
        // Audit round 16, BG: from_flow_id copied the key into the rule as a required EXACT value,
        // and rules persist under .idea/, which the test bench had committed.
        val flowId = addFlow(url = "https://api.example.com/v1/weather?appid=SECRET123&q=Madrid")
        val collection = ok(api.createCollection(pid, CollectionCreateRequest(name = "credential")))
        val created = ok(api.createRule(pid, RuleCreateRequest(fromFlowId = flowId, collectionId = collection.collectionId)))
        val appid = created.rule.query.single { it.key == "appid" }
        assertEquals("WILDCARD", appid.match)
        assertEquals("the value is not stored", "", appid.value)
        assertEquals(true, appid.required)
        assertTrue(created.loosenedParams.contains("appid"))
        assertTrue(created.warnings.any { it.contains("credential") })
        assertFalse(created.rule.url.contains("SECRET123"))

        // A rule written with the value by hand is the caller's own words and is stored — but the
        // views still redact it, and so does match_explain.
        val explicit = createRule(collection.collectionId, url = "https://api.example.com/v1/other?token=SECRET456")
        assertEquals("<redacted:9b>", explicit.rule.query.single { it.key == "token" }.value)
        assertFalse(explicit.rule.url.contains("SECRET456"))
        val explained = ok(api.explainMatch(pid, MatchExplainRequest(method = "GET", url = "https://api.example.com/v1/other?token=OTHER")))
        val reason = explained.candidates.single { it.id == explicit.rule.ruleId }.rejectedBecause!!
        assertTrue(reason, reason.contains("<redacted>") && !reason.contains("SECRET456") && !reason.contains("OTHER"))
    }

    fun testExplainNamesTheWinnerAndWhyEveryOtherRuleWasRejected() {
        val collection = ok(api.createCollection(pid, CollectionCreateRequest(name = "explain")))
        val winnerId = createRule(collection.collectionId, url = "https://api.example.com/v1/users").rule.ruleId
        val wrongPathId = createRule(collection.collectionId, url = "https://api.example.com/v1/orders").rule.ruleId
        val disabledId = createRule(collection.collectionId, url = "https://api.example.com/v1/users", enabled = false).rule.ruleId

        val explained = ok(
            api.explainMatch(pid, MatchExplainRequest(method = "GET", url = "https://api.example.com/v1/users"))
        )

        assertEquals(winnerId, explained.winner?.id)
        assertTrue(explained.winner!!.matched)
        assertNull(explained.winner!!.rejectedBecause)

        val rejected = explained.candidates.associateBy { it.id }
        assertTrue(rejected.getValue(wrongPathId).rejectedBecause!!.contains("path mismatch"))
        assertTrue(rejected.getValue(disabledId).rejectedBecause!!.contains("disabled"))

        // A winner is not enough: with no capture session nothing answers CHECK_MOCK, so claiming
        // the network would be skipped would be a lie.
        assertFalse(explained.wouldSkipNetwork)
        assertTrue(explained.warnings.any { it.contains("capture session") })
    }

    // ========================================================================
    // Meta
    // ========================================================================

    fun testMetaDoesNotAdvertiseWhatItAlsoDeclaresUnimplemented() {
        val meta = api.meta()

        val overlap = meta.capabilities.toSet().intersect(
            meta.notImplementedYet.map { it.substringBefore(" ") }.toSet()
        )
        assertTrue("meta advertises and disclaims the same verbs: $overlap", overlap.isEmpty())
        assertTrue(meta.limits.maxBodyCharsDefault <= meta.limits.maxBodyCharsHard)
        assertTrue(meta.limits.flowLimitDefault <= meta.limits.flowLimitMax)
        assertTrue(meta.limits.redactedHeaders.contains("authorization"))
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun <T : Any> ok(result: ApiResult<T>): T = when (result) {
        is ApiResult.Ok -> result.value
        is ApiResult.Err -> throw AssertionError("expected success, got ${result.error.code}: ${result.error.message}")
    }

    private fun err(result: ApiResult<*>): ApiError {
        val failure = result as? ApiResult.Err
            ?: throw AssertionError("expected a typed error, got a successful $result")
        return failure.error
    }

    private fun ids(query: FlowQuery): List<String> = ok(api.listFlows(pid, query)).flows.map { it.flowId }

    private fun createRule(
        collectionId: String,
        url: String = "https://api.example.com/v1/users",
        enabled: Boolean? = null
    ) = ok(
        api.createRule(
            pid,
            RuleCreateRequest(
                name = "rule for $url",
                method = "GET",
                url = url,
                response = ResponseSpecDto(statusCode = 200, body = "{}"),
                collectionId = collectionId,
                enabled = enabled
            )
        )
    )

    private fun addFlow(
        method: String = "GET",
        url: String = "https://api.example.com/v1/users",
        status: Int = 200,
        requestBody: String = "",
        responseBody: String = "{}",
        requestHeaders: Map<String, String> = emptyMap(),
        responseHeaders: Map<String, String> = emptyMap(),
        mockApplied: Boolean = false,
        mockRuleName: String? = null,
        clientRunId: String? = null
    ): String {
        val uri = URI.create(url)
        // A fresh id every time: ControlApi's cursor map outlives the fixture, so reusing an id
        // would resurrect the sequence number it had in an earlier test.
        val flowId = UUID.randomUUID().toString()
        flows.addFlow(
            HttpFlowData(
                flowId = flowId,
                paused = false,
                request = HttpRequestData(
                    method = method,
                    url = url,
                    host = uri.host,
                    path = uri.path.ifEmpty { "/" },
                    headers = requestHeaders,
                    content = requestBody
                ),
                response = HttpResponseData(
                    statusCode = status,
                    reason = "OK",
                    headers = responseHeaders,
                    content = responseBody
                ),
                timestamp = System.currentTimeMillis() / 1000.0,
                duration = 0.012,
                mockApplied = mockApplied,
                mockRuleName = mockRuleName,
                clientRunId = clientRunId
            )
        )
        return flowId
    }
    // ------------------------------------------------------------------
    // include_secrets is refused eagerly — reported from a real session,
    // where `include_secrets: true` came back 200 with an empty flow list.
    // ------------------------------------------------------------------

    fun `test include_secrets is refused even when there are no flows to redact`() {
        api.environment = ControlEnvironment(revealSecrets = false)
        flows.clearAllFlows()

        val result = api.listFlows(pid, FlowQuery(includeSecrets = true))

        // The permission question must be answered BEFORE the store is consulted. Deciding it while
        // walking the flows would make an empty capture look like consent, so the very first call of
        // a session — when nothing has been captured yet — would be the one that silently succeeds.
        assertTrue(
            "include_secrets:true must fail with REVEAL_DISABLED regardless of how many flows exist",
            result is ApiResult.Err
        )
        assertEquals(ErrorCode.REVEAL_DISABLED, (result as ApiResult.Err).error.code)
        assertNotNull("the refusal must say how to enable it", result.error.hint)
    }

    fun `test include_secrets is allowed once the IDE setting permits it`() {
        api.environment = ControlEnvironment(revealSecrets = true)
        flows.clearAllFlows()

        val result = api.listFlows(pid, FlowQuery(includeSecrets = true))

        assertTrue("with consent granted the same call must succeed", result is ApiResult.Ok)
    }

    fun `test omitting include_secrets never trips the permission check`() {
        api.environment = ControlEnvironment(revealSecrets = false)
        flows.clearAllFlows()

        val result = api.listFlows(pid, FlowQuery())

        assertTrue("the default path must not require consent", result is ApiResult.Ok)
    }

    // ------------------------------------------------------------------
    // Revealing secrets must leave a trace (audit finding Q), and a
    // loosen_query that loosened nothing must say so (finding R).
    // ------------------------------------------------------------------

    fun `test revealed headers are named when include_secrets hands them over`() {
        api.environment = ControlEnvironment(revealSecrets = true)
        flows.clearAllFlows()
        flows.addFlow(flowWithAuthHeader())

        val result = api.listFlows(
            pid,
            FlowQuery(includeSecrets = true, includeBody = INCLUDE_BODY_BOTH)
        )

        assertTrue(result is ApiResult.Ok)
        val view = (result as ApiResult.Ok).value.bodies.orEmpty().values.first().request!!

        // The header really is in clear...
        assertEquals("Bearer SUPERSECRET", view.headers["Authorization"])
        // ...and the answer says so, which an empty redacted_headers alone could not.
        assertTrue(
            "a revealed credential must be named, not left indistinguishable from 'nothing sensitive here'",
            view.revealedHeaders.any { it.equals("authorization", ignoreCase = true) }
        )
        assertTrue("nothing was redacted, so that list stays empty", view.redactedHeaders.isEmpty())

        val warning = (result.value.warnings).joinToString(" ")
        assertTrue(
            "a payload carrying live credentials must warn: these end up in test reports. Got: $warning",
            warning.contains("include_secrets") && warning.contains("CLEAR")
        )
    }

    fun `test the default path redacts and warns about nothing`() {
        api.environment = ControlEnvironment(revealSecrets = true)
        flows.clearAllFlows()
        flows.addFlow(flowWithAuthHeader())

        val result = api.listFlows(pid, FlowQuery(includeBody = INCLUDE_BODY_BOTH))

        assertTrue(result is ApiResult.Ok)
        val view = (result as ApiResult.Ok).value.bodies.orEmpty().values.first().request!!

        assertTrue("the value must be hidden", view.headers["Authorization"]!!.startsWith("<redacted:"))
        assertTrue(view.redactedHeaders.isNotEmpty())
        assertTrue("nothing was revealed, so no warning", view.revealedHeaders.isEmpty())
        assertFalse(
            "the secrets warning must not fire when nothing was revealed",
            result.value.warnings.any { it.contains("include_secrets") }
        )
    }

    fun `test loosen_query says so when it loosened nothing`() {
        val collection = rules.addCollection("loosen-test", "com.example.app", "")
        val created = api.createRule(
            pid,
            RuleCreateRequest(
                name = "weather",
                method = "GET",
                // Coordinates: they look like ordinary values, the heuristic does not know them,
                // and they change the moment the device moves.
                url = "https://api.example.com/data/2.5/weather?lat=40.4168&lon=-3.7038&units=metric",
                collectionId = collection.id,
                loosenQuery = true,
                response = ResponseSpecDto(statusCode = 200, body = "{}")
            )
        )

        assertTrue(created is ApiResult.Ok)
        val response = (created as ApiResult.Ok).value
        assertTrue("nothing matched the heuristic", response.loosenedParams.isEmpty())

        val warning = response.warnings.joinToString(" ")
        assertTrue(
            "asking to loosen and loosening nothing must not be silent — the rule fires once and never again. Got: $warning",
            warning.contains("loosen_query") && warning.contains("lat")
        )
    }

    // ------------------------------------------------------------------
    // Finding Q, third round: the warning existed on ONE of the three paths
    // that hand credentials over. One test per path, not per function.
    // ------------------------------------------------------------------

    fun `test get_flow warns when include_secrets hands a credential over`() {
        api.environment = ControlEnvironment(revealSecrets = true)
        flows.clearAllFlows()
        flows.addFlow(flowWithAuthHeader())

        val detail = ok(api.getFlow(pid, "secret-flow", includeSecrets = true))

        assertEquals("Bearer SUPERSECRET", detail.request.headers["Authorization"])
        assertTrue(detail.request.revealedHeaders.any { it.equals("authorization", ignoreCase = true) })
        val warning = detail.warnings.joinToString(" ")
        assertTrue(
            "the detail route is the one an agent reads once it knows the flow; it must warn like the listing does. Got: $warning",
            warning.contains("include_secrets") && warning.contains("CLEAR")
        )

        // And the same route stays quiet when nothing was revealed.
        val redacted = ok(api.getFlow(pid, "secret-flow"))
        assertTrue(redacted.request.headers["Authorization"]!!.startsWith("<redacted:"))
        assertFalse(redacted.warnings.any { it.contains("include_secrets") })
    }

    fun `test await_flow warns when include_secrets hands a credential over`() {
        api.environment = ControlEnvironment(revealSecrets = true)
        flows.clearAllFlows()
        flows.addFlow(flowWithAuthHeader())

        // since_seq 0 makes the already-stored flow satisfy the wait at once; no listener, no sleep.
        val answer = ok(
            api.awaitFlow(
                pid,
                AwaitFlowRequest(sinceSeq = 0, waitMs = 0, includeBody = INCLUDE_BODY_BOTH, includeSecrets = true)
            )
        )

        assertTrue("the stored flow must satisfy the wait", answer.satisfied)
        val view = answer.bodies!!.values.first().request!!
        assertEquals("Bearer SUPERSECRET", view.headers["Authorization"])
        val warning = answer.warnings.joinToString(" ")
        assertTrue(
            "await_flow with include_secrets is the third path that reveals; it must warn too. Got: $warning",
            warning.contains("include_secrets") && warning.contains("CLEAR")
        )

        val quiet = ok(api.awaitFlow(pid, AwaitFlowRequest(sinceSeq = 0, waitMs = 0, includeBody = INCLUDE_BODY_BOTH)))
        assertFalse(quiet.warnings.any { it.contains("include_secrets") })
    }

    /**
     * The fourth path that could hand a captured credential over, found while closing Q: a rule
     * cloned with from_flow_id copied the captured Set-Cookie into the store, and `mocks get` and
     * `export` return rule headers without any include_secrets gate.
     */
    fun `test cloning a rule from a flow never copies captured credentials into it`() {
        api.environment = ControlEnvironment(revealSecrets = false)
        val collection = ok(api.createCollection(pid, CollectionCreateRequest(name = "clones")))
        val flowId = addFlow(
            url = "https://api.example.com/v1/login",
            responseHeaders = mapOf(
                "Set-Cookie" to "session=SUPERSECRET; HttpOnly",
                "Content-Type" to "application/json"
            )
        )

        val created = ok(api.createRule(pid, RuleCreateRequest(fromFlowId = flowId, collectionId = collection.collectionId)))

        val headers = created.rule.response.headers
        assertFalse("the captured cookie must not enter the rule: $headers", headers.keys.any { it.equals("Set-Cookie", ignoreCase = true) })
        assertEquals("application/json", headers["Content-Type"])
        assertTrue(
            "dropping a header silently would read as 'the response had none': ${created.warnings}",
            created.warnings.any { it.contains("set-cookie", ignoreCase = true) && it.contains("response.headers") }
        )
        // What the store hands back later agrees — this is the path that leaked.
        val fetched = ok(api.getRule(pid, created.rule.ruleId))
        assertFalse(fetched.response.headers.keys.any { it.equals("Set-Cookie", ignoreCase = true) })

        // The caller's own explicit headers are kept as written: those are its words, not a capture.
        val explicit = ok(
            api.createRule(
                pid,
                RuleCreateRequest(
                    fromFlowId = flowId,
                    collectionId = collection.collectionId,
                    response = ResponseSpecDto(headers = mapOf("Set-Cookie" to "session=fake"))
                )
            )
        )
        assertEquals("session=fake", explicit.rule.response.headers["Set-Cookie"])
    }

    // ------------------------------------------------------------------
    // Release review of 1.8.0: more guards that lived on one path only.
    // ------------------------------------------------------------------

    fun `test listing and awaiting warn about retention-truncated bodies like get does`() {
        settings.setMaxStoredBodyKb(16)
        val flowId = addFlow(responseBody = "y".repeat(40_000))

        val listed = ok(api.listFlows(pid, FlowQuery(includeBody = INCLUDE_BODY_RESPONSE)))
        assertTrue(
            "a listing that renders a cut body must say so at the top level, naming the flow: ${listed.warnings}",
            listed.warnings.any { it.contains("Cache") && it.contains(flowId) }
        )

        val awaited = ok(api.awaitFlow(pid, AwaitFlowRequest(sinceSeq = 0, waitMs = 0, includeBody = INCLUDE_BODY_RESPONSE)))
        assertTrue("await renders the same bodies and must warn the same way: ${awaited.warnings}", awaited.warnings.any { it.contains("Cache") })

        val bare = ok(api.listFlows(pid, FlowQuery()))
        assertFalse("nothing rendered, nothing to warn about", bare.warnings.any { it.contains("Cache") })
    }

    fun `test await never moves the cursor past matches it had no room to return`() {
        val ids = setOf(
            addFlow(url = "https://api.example.com/v1/a"),
            addFlow(url = "https://api.example.com/v1/b"),
            addFlow(url = "https://api.example.com/v1/c")
        )

        // The documented loop: count=1, then since_seq = next_seq, three times over.
        val seen = mutableListOf<String>()
        var cursor = 0L
        repeat(3) {
            val page = ok(api.awaitFlow(pid, AwaitFlowRequest(sinceSeq = cursor, waitMs = 0, count = 1)))
            assertEquals("one flow per page", 1, page.flows.size)
            seen += page.flows.single().flowId
            cursor = page.nextSeq
        }

        assertEquals("no flow may be skipped or repeated by the cursor", ids, seen.toSet())
        assertEquals(3, seen.size)

        // And the page that left flows behind said so.
        val first = ok(api.awaitFlow(pid, AwaitFlowRequest(sinceSeq = 0, waitMs = 0, count = 1)))
        assertTrue(first.warnings.any { it.contains("more flow(s) matched") && it.contains("since_seq") })
    }

    fun `test await validates include_body exactly like list does`() {
        val error = err(api.awaitFlow(pid, AwaitFlowRequest(waitMs = 0, includeBody = "everything")))

        assertEquals(ErrorCode.INVALID_ARGUMENT, error.code)
        assertTrue(error.message.contains("include_body"))
        assertEquals(ErrorCode.INVALID_ARGUMENT, err(api.listFlows(pid, FlowQuery(includeBody = "everything"))).code)
    }

    fun `test a regex that does not compile is refused on create, not stored as a rule that never fires`() {
        val collection = ok(api.createCollection(pid, CollectionCreateRequest(name = "regex")))

        val error = err(
            api.createRule(
                pid,
                RuleCreateRequest(
                    method = "GET",
                    host = "api.example.com",
                    path = "/v1/orders/[0-9+",
                    pathMatch = "REGEX",
                    collectionId = collection.collectionId,
                    response = ResponseSpecDto(statusCode = 200, body = "{}")
                )
            )
        )

        assertEquals(ErrorCode.INVALID_REGEX, error.code)
        assertEquals("nothing may have been stored", 0, ok(api.listRules(pid, collectionId = collection.collectionId)).total)
    }

    fun `test update honours host and path as first-class fields`() {
        val collection = ok(api.createCollection(pid, CollectionCreateRequest(name = "update")))
        val ruleId = createRule(collection.collectionId, url = "https://api.example.com/v1/users").rule.ruleId

        val updated = ok(
            api.updateRule(
                pid,
                RuleUpdateRequest(
                    ruleId = ruleId,
                    host = "api\\.acme\\.test",
                    hostMatch = "REGEX",
                    path = "/v1/orders/[0-9]+",
                    pathMatch = "REGEX"
                )
            )
        )

        assertEquals("api\\.acme\\.test", updated.rule.host)
        assertEquals("/v1/orders/[0-9]+", updated.rule.path)
        val fetched = ok(api.getRule(pid, ruleId))
        assertEquals("the store must hold what the answer claimed", "api\\.acme\\.test", fetched.host)
        assertEquals("/v1/orders/[0-9]+", fetched.path)
        assertEquals("REGEX", fetched.hostMatch)
    }

    /**
     * X-b, asked for in four audit rounds: the numbers that let "one request, one flow" be checked
     * from outside. The client sends them with every message; the control plane shows the latest
     * on status and on the listing.
     */
    fun `test the client's own report is exposed on status and on the flow listing`() {
        val global = com.sergiy.dev.mockkhttp.proxy.GlobalOkHttpInterceptorServer.getInstance()
        global.recordClientReport(
            "com.acme.weather",
            com.sergiy.dev.mockkhttp.model.ClientReport(
                library = "mockk_http",
                version = "1.8.0",
                platform = "Android",
                dedup = com.sergiy.dev.mockkhttp.model.ClientReport.Dedup(enabled = true, windowMs = 0, controllable = false),
                caps = listOf("identityClaims", "lineFramed", "idle"),
                stats = mapOf("flows_sent" to 3L, "passes_yielded" to 1L, "claims_made" to 1L)
            )
        )

        val status = ok(api.status(pid))
        val client = status.client ?: throw AssertionError("status must carry the last client report")
        assertEquals("mockk_http", client.library)
        assertEquals("1.8.0", client.version)
        assertEquals("com.acme.weather", client.packageName)
        assertEquals(3L, client.stats!!["flows_sent"])
        assertEquals(1L, client.stats!!["passes_yielded"])
        assertEquals(0, client.dedup!!.windowMs)
        assertNotNull(client.seenAt)

        val listed = ok(api.listFlows(pid, FlowQuery()))
        assertEquals("the listing carries the same report", "1.8.0", listed.client?.version)
    }

    /** Audit round 8, AH: with a package filter, another app's numbers must never appear as ours. */
    fun `test a project with a package filter shows its own app's report or nothing`() {
        val global = com.sergiy.dev.mockkhttp.proxy.GlobalOkHttpInterceptorServer.getInstance()
        val session = com.sergiy.dev.mockkhttp.session.CaptureSessionService.getInstance(project)
        try {
            session.setPackageFilter("com.acme.r8.filtered")
            global.recordClientReport("com.impostor.otherapp", com.sergiy.dev.mockkhttp.model.ClientReport(library = "mockk_http", version = "9.9.9", stats = mapOf("flows_sent" to 12345L)))

            assertNull("an app that is not the filtered one must not be shown as it", ok(api.status(pid)).client)
            assertNull(ok(api.listFlows(pid, FlowQuery())).client)

            global.recordClientReport("com.acme.r8.filtered", com.sergiy.dev.mockkhttp.model.ClientReport(library = "mockk_http", version = "1.8.0", stats = mapOf("flows_sent" to 2L)))
            assertEquals("com.acme.r8.filtered", ok(api.status(pid)).client!!.packageName)

            session.setPackageFilter(null)
            assertNotNull("without a filter, whoever spoke last", ok(api.status(pid)).client)
        } finally {
            session.setPackageFilter(null)
        }
    }

    /** Audit round 8, AI: the client's counters must be comparable with flows.count after a clear and across app restarts. */
    fun `test since_clear anchors the client's count to the last clear and to the app run`() {
        val global = com.sergiy.dev.mockkhttp.proxy.GlobalOkHttpInterceptorServer.getInstance()
        fun report(run: String, sent: Long) = global.recordClientReport(
            null,
            com.sergiy.dev.mockkhttp.model.ClientReport(library = "mockk_http", version = "1.8.0", runId = run, startedAt = 1_000L, stats = mapOf("flows_sent" to sent))
        )

        // Run ids unique to this test: the global report map outlives the fixture.
        report("ak-run-1", 10L)
        val fresh = ok(api.status(pid)).client!!.sinceClear!!
        assertEquals("nothing cleared yet: the whole count", 10L, fresh.flowsSent)
        assertTrue("and nothing in the store this run did not send: exact", fresh.comparable)
        assertEquals(0, fresh.flowsNotFromThisRun)

        // Flows from an older client (no run stamp) make the count incomparable, and say so.
        addFlow(url = "https://api.example.com/old", clientRunId = null)
        val mixed = ok(api.status(pid)).client!!.sinceClear!!
        assertFalse("one flow in the store is not this run's", mixed.comparable)
        assertEquals(1, mixed.flowsNotFromThisRun)
        // (Whether the reason opens with "nothing cleared" or "restarted" depends on what earlier
        // tests left in the app-level report map; the count in it is what this test fixes.)
        assertTrue(mixed.reason!!.contains("1 of the flows"))

        ok(api.clearFlows(pid))
        report("ak-run-1", 13L)
        val anchored = ok(api.status(pid)).client!!.sinceClear!!
        assertEquals("three flows since the clear", 3L, anchored.flowsSent)
        assertTrue("cleared in this run: exact", anchored.comparable)

        // Audit round 9, AK: the app restarts without a clear while a flow of the earlier run is
        // still in the store. The number is published but flagged, the runs named, the flow counted.
        addFlow(url = "https://api.example.com/before-restart", clientRunId = "ak-run-1")
        report("ak-run-2", 2L)
        val restarted = ok(api.status(pid)).client!!
        assertEquals("a new run counts from zero", 2L, restarted.sinceClear!!.flowsSent)
        assertFalse("a restart since the clear must be said, not squared by luck", restarted.sinceClear!!.comparable)
        assertEquals(1, restarted.sinceClear!!.flowsNotFromThisRun)
        assertTrue(restarted.sinceClear!!.reason!!.contains("ak-run-1 → ak-run-2") && restarted.sinceClear!!.reason!!.contains("1 of the flows"))
        assertEquals("ak-run-2", restarted.runId)
        assertEquals(1_000L, restarted.startedAt)

        ok(api.clearFlows(pid))
        report("ak-run-2", 5L)
        val reanchored = ok(api.status(pid)).client!!.sinceClear!!
        assertEquals(3L, reanchored.flowsSent)
        assertTrue("a clear in the new run anchors again", reanchored.comparable)
    }

    fun `test the store's cap makes the count incomparable and says so`() {
        // Audit round 11, AQ: past the cap the store evicts the oldest flows. An eviction changes
        // nobody's provenance, so the run check alone kept saying "comparable" while flows.count
        // had a floor — and an agent doing the documented subtraction concluded requests were lost.
        val global = com.sergiy.dev.mockkhttp.proxy.GlobalOkHttpInterceptorServer.getInstance()
        fun report(run: String, sent: Long) = global.recordClientReport(
            null,
            com.sergiy.dev.mockkhttp.model.ClientReport(library = "mockk_http", version = "1.8.0", runId = run, startedAt = 1_000L, stats = mapOf("flows_sent" to sent))
        )
        settings.setMaxFlowsRetained(10)
        ok(api.clearFlows(pid))
        repeat(12) { addFlow(url = "https://api.example.com/burst/$it", clientRunId = "aq-run-1") }
        report("aq-run-1", 12L)

        val status = ok(api.status(pid))
        assertEquals("the cap held", 10, status.flows.count)
        assertEquals(10, status.flows.capacity)
        assertEquals("two went out the bottom", 2, status.flows.evictedSinceClear)
        val since = status.client!!.sinceClear!!
        assertEquals("the client's count is untouched by the cap", 12L, since.flowsSent)
        assertFalse("flows.count is a window now: not comparable, and said", since.comparable)
        assertEquals(2, since.flowsEvictedSinceClear)
        assertEquals("every flow in the window is this run's; that was never the problem", 0, since.flowsNotFromThisRun)
        assertTrue(since.reason!!.contains("evicted") && since.reason!!.contains("at most 10"))

        ok(api.clearFlows(pid))
        report("aq-run-1", 12L)
        val cleared = ok(api.status(pid))
        assertEquals(0, cleared.flows.evictedSinceClear)
        assertTrue("a clear resets the window", cleared.client!!.sinceClear!!.comparable)
    }

    fun `test a rejected message makes the count incomparable and says so`() {
        // Audit round 12, AS: a message the plugin could not turn into a flow was answered with the
        // normal reply, never stored, and never counted — the app's flows_sent kept it, the store
        // did not, and `comparable` stayed true. Now every rejection is counted where the verdict looks.
        val global = com.sergiy.dev.mockkhttp.proxy.GlobalOkHttpInterceptorServer.getInstance()
        fun report(run: String, sent: Long) = global.recordClientReport(
            null,
            com.sergiy.dev.mockkhttp.model.ClientReport(library = "mockk_http", version = "1.8.0", runId = run, startedAt = 1_000L, stats = mapOf("flows_sent" to sent))
        )
        ok(api.clearFlows(pid))
        addFlow(url = "https://api.example.com/ok", clientRunId = "as-run-1")
        global.noteRejected(null, "request without url") // no app could be named: could be ours
        report("as-run-1", 2L)

        val status = ok(api.status(pid))
        assertEquals(1L, status.flows.rejectedSinceClear)
        val since = status.client!!.sinceClear!!
        assertEquals(2L, since.flowsSent)
        assertFalse("one message never became a flow: the subtraction is short by it", since.comparable)
        assertEquals(1L, since.flowsRejectedSinceClear)
        assertTrue(since.reason!!.contains("could not be turned into a flow"))

        ok(api.clearFlows(pid))
        report("as-run-1", 2L)
        val cleared = ok(api.status(pid))
        assertEquals(0L, cleared.flows.rejectedSinceClear)
        assertTrue("a clear anchors past the rejection", cleared.client!!.sinceClear!!.comparable)
    }

    fun `test another app's rejected message is never this project's`() {
        // Adversarial review of round 12: one process-wide total flipped every project's verdict
        // at once. A project with a filter carries its own app's rejections and nobody else's.
        val global = com.sergiy.dev.mockkhttp.proxy.GlobalOkHttpInterceptorServer.getInstance()
        val session = com.sergiy.dev.mockkhttp.session.CaptureSessionService.getInstance(project)
        session.setPackageFilter("com.acme.as.mine")
        try {
            ok(api.clearFlows(pid))
            global.recordClientReport(
                "com.acme.as.mine",
                com.sergiy.dev.mockkhttp.model.ClientReport(library = "mockk_http", version = "1.8.0", runId = "as-mine-1", startedAt = 1_000L, stats = mapOf("flows_sent" to 0L))
            )
            global.noteRejected("com.acme.as.other", "request without url")
            val untouched = ok(api.status(pid))
            assertEquals("another NAMED app's: not this filtered project's", 0L, untouched.flows.rejectedSinceClear)
            assertTrue(untouched.client!!.sinceClear!!.comparable)

            // Audit round 13, AW: a message cut short on the wire carries its package inside and
            // cannot say it. It counts for this project too, filter or no filter.
            global.noteRejected(null, "FLOW is not a JSON object")
            val unnamed = ok(api.status(pid))
            assertEquals("an unnamed one may be ours", 1L, unnamed.flows.rejectedSinceClear)
            assertFalse(unnamed.client!!.sinceClear!!.comparable)

            global.noteRejected("com.acme.as.mine", "request without url")
            val own = ok(api.status(pid))
            assertEquals(2L, own.flows.rejectedSinceClear)
            assertFalse(own.client!!.sinceClear!!.comparable)
        } finally {
            session.setPackageFilter(null)
        }
    }

    fun `test with no filter and no app resolved only unnamed rejections count`() {
        // Second adversarial pass of round 12: with no filter and no package resolvable for the
        // project, the count fell back to the process-wide total — another app's named rejection
        // was this project's again. Only the rejections no app could be named for may count then.
        val global = com.sergiy.dev.mockkhttp.proxy.GlobalOkHttpInterceptorServer.getInstance()
        global.recordClientReport(
            null,
            com.sergiy.dev.mockkhttp.model.ClientReport(library = "mockk_http", version = "1.8.0", runId = "as-nopkg-1", startedAt = 1_000L, stats = mapOf("flows_sent" to 0L))
        )
        ok(api.clearFlows(pid))
        global.noteRejected("com.totally.unrelated.app", "request without url")
        assertEquals("a named app's rejection is that app's, not this project's", 0L, ok(api.status(pid)).flows.rejectedSinceClear)
        global.noteRejected(null, "FLOW without flowId")
        assertEquals("an unnamed one may be ours", 1L, ok(api.status(pid)).flows.rejectedSinceClear)
    }

    fun `test dropped headers are counted where the agent reads`() {
        // Audit round 15, AZ: the log named them; the control plane said nothing. The flow is stored,
        // so comparable stays true — the count says the flows are shorter than sent.
        val global = com.sergiy.dev.mockkhttp.proxy.GlobalOkHttpInterceptorServer.getInstance()
        ok(api.clearFlows(pid))
        global.recordClientReport(
            null,
            com.sergiy.dev.mockkhttp.model.ClientReport(library = "mockk_http", version = "1.8.0", runId = "az-run-1", startedAt = 1_000L, stats = mapOf("flows_sent" to 1L))
        )
        addFlow(url = "https://api.example.com/short", clientRunId = "az-run-1")
        global.noteHeadersDropped(null, 3)
        val status = ok(api.status(pid))
        assertEquals(3L, status.flows.headersDroppedSinceClear)
        val since = status.client!!.sinceClear!!
        assertEquals(3L, since.headersDropped)
        assertTrue("the flow is stored: still comparable", since.comparable)

        ok(api.clearFlows(pid))
        assertEquals(0L, ok(api.status(pid)).flows.headersDroppedSinceClear)
    }

    fun `test a clear from the Inspector anchors the rejection count too`() {
        // Adversarial review of round 12: the baseline used to be taken by a listener ControlApi
        // attached on its first call for a project; a clear from the UI before that call kept
        // an older rejection "since the clear". The store takes the snapshot itself now.
        val global = com.sergiy.dev.mockkhttp.proxy.GlobalOkHttpInterceptorServer.getInstance()
        global.noteRejected(null, "FLOW without request")
        FlowStore.getInstance(project).clearAllFlows() // the Inspector's button, not the agent
        global.recordClientReport(
            null,
            com.sergiy.dev.mockkhttp.model.ClientReport(library = "mockk_http", version = "1.8.0", runId = "as-ui-1", startedAt = 1_000L, stats = mapOf("flows_sent" to 0L))
        )
        val status = ok(api.status(pid))
        assertEquals("rejected before the UI clear: not since it", 0L, status.flows.rejectedSinceClear)
        assertTrue(status.client!!.sinceClear!!.comparable)
    }

    fun `test a report overtaken on the wire cannot roll the count back`() {
        // Adversarial review of round 11: CHECK_MOCK and FLOW travel on separate sockets and are
        // served on separate threads, so a report built earlier can be recorded later. The
        // client's own seq decides which is fresher; without one, flows_sent does.
        val global = com.sergiy.dev.mockkhttp.proxy.GlobalOkHttpInterceptorServer.getInstance()
        fun report(run: String, sent: Long, seq: Long? = null) = global.recordClientReport(
            null,
            com.sergiy.dev.mockkhttp.model.ClientReport(library = "mockk_http", version = "1.8.0", runId = run, startedAt = 1_000L, seq = seq, stats = mapOf("flows_sent" to sent))
        )
        report("ov-run-1", 20L, seq = 1L)
        ok(api.clearFlows(pid))
        report("ov-run-1", 22L, seq = 3L)
        report("ov-run-1", 21L, seq = 2L) // built before the previous one, served after it
        val kept = ok(api.status(pid)).client!!
        assertEquals("the freshest report stays", 22L, kept.stats!!["flows_sent"])
        assertEquals(2L, kept.sinceClear!!.flowsSent)

        // A client that sends no seq: the higher flows_sent is the later snapshot.
        report("ov-run-2", 7L)
        report("ov-run-2", 5L)
        assertEquals(7L, ok(api.status(pid)).client!!.stats!!["flows_sent"])
        // A new run always replaces, whatever its numbers.
        report("ov-run-3", 1L, seq = 1L)
        assertEquals("ov-run-3", ok(api.status(pid)).client!!.runId)
    }

    fun `test a restart right after a clear keeps the count comparable`() {
        // Audit round 10, AO: clear, restart the app, let it make its first requests. Nothing from
        // the earlier run is in the store — every flow there is the new run's — so the subtraction
        // is exact and must say so, instead of asserting a retention that did not happen.
        val global = com.sergiy.dev.mockkhttp.proxy.GlobalOkHttpInterceptorServer.getInstance()
        fun report(run: String, sent: Long) = global.recordClientReport(
            null,
            com.sergiy.dev.mockkhttp.model.ClientReport(library = "mockk_http", version = "1.8.0", runId = run, startedAt = 1_000L, stats = mapOf("flows_sent" to sent))
        )
        report("ao-run-1", 4L)
        ok(api.clearFlows(pid))
        repeat(3) { addFlow(url = "https://api.example.com/startup/$it", clientRunId = "ao-run-2") }
        report("ao-run-2", 3L)
        val after = ok(api.status(pid)).client!!.sinceClear!!
        assertEquals("the new run's whole count: it had sent nothing before the clear", 3L, after.flowsSent)
        assertTrue("nothing in flows.count predates this run: exact", after.comparable)
        assertEquals(0, after.flowsNotFromThisRun)
        assertNull(after.reason)
        assertEquals(3, ok(api.status(pid)).flows.count)
    }

    private fun flowWithAuthHeader(): HttpFlowData = HttpFlowData(
        flowId = "secret-flow",
        paused = false,
        request = HttpRequestData(
            method = "GET",
            url = "https://api.example.com/v1/me",
            host = "api.example.com",
            path = "/v1/me",
            headers = mapOf("Authorization" to "Bearer SUPERSECRET", "Accept" to "application/json"),
            content = ""
        ),
        response = HttpResponseData(200, "OK", emptyMap(), "{}"),
        timestamp = 0.0,
        duration = 0.0
    )

}
