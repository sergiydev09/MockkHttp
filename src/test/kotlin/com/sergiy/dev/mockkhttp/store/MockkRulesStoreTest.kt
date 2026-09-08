package com.sergiy.dev.mockkhttp.store

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sergiy.dev.mockkhttp.model.MatchType
import com.sergiy.dev.mockkhttp.model.MockBody
import com.sergiy.dev.mockkhttp.model.MockkCollection
import com.sergiy.dev.mockkhttp.model.ModifiedResponseData
import com.sergiy.dev.mockkhttp.model.QueryParam
import com.sergiy.dev.mockkhttp.model.StructuredUrl
import com.sergiy.dev.mockkhttp.model.UrlSpec
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Behaviour of the ONE matcher in the plugin, plus the store invariants whose absence caused
 * data loss in 1.7.0.
 *
 * Extends [BasePlatformTestCase] because [MockkRulesStore] is a project service: the light fixture
 * is what supplies a real [com.intellij.openapi.project.Project]. The platform test framework is
 * JUnit 3/4 based (`BasePlatformTestCase` → `UsefulTestCase` → `junit.framework.TestCase`), so
 * every test method must be named `testXxx` — a JUnit 5 `@Test` here would simply never run.
 *
 * The light project is REUSED across the methods of a class, so its services keep whatever the
 * previous method left behind: [setUp] wipes the store rather than assuming it is empty.
 */
class MockkRulesStoreTest : BasePlatformTestCase() {

    private lateinit var store: MockkRulesStore
    private lateinit var collection: MockkCollection

    override fun setUp() {
        super.setUp()
        store = MockkRulesStore.getInstance(project)
        store.removeAllCollections()
        collection = store.addCollection("Test collection", "com.acme.app")
    }

    override fun tearDown() {
        try {
            store.removeAllCollections()
        } finally {
            super.tearDown()
        }
    }

    // ========================================================================
    // Host and path matching
    // ========================================================================

    fun testExactHostIsComparedCaseInsensitively() {
        addRule(host = "API.Example.COM", path = "/v1/users")

        assertNotNull(match(host = "api.example.com", path = "/v1/users"))
        assertNotNull(match(host = "API.EXAMPLE.COM", path = "/v1/users"))
        assertNull(match(host = "api.example.org", path = "/v1/users"))
    }

    fun testExactPathIsComparedCaseSensitively() {
        addRule(host = "api.example.com", path = "/v1/users")

        assertNotNull(match(host = "api.example.com", path = "/v1/users"))
        // Paths are case-sensitive by contract; hosts are not. A store that lower-cased both would
        // start answering requests the app never routed here.
        assertNull(match(host = "api.example.com", path = "/V1/Users"))
    }

    fun testWildcardHostMatchesAnyRunOfCharacters() {
        addRule(host = "*.example.com", path = "/v1/users", hostMatch = MatchType.WILDCARD)

        assertNotNull(match(host = "api.example.com", path = "/v1/users"))
        assertNotNull(match(host = "eu.api.example.com", path = "/v1/users"))
        // `*` must not swallow the literal dot the pattern spelled out.
        assertNull(match(host = "example.com", path = "/v1/users"))
        assertNull(match(host = "api.example.org", path = "/v1/users"))
    }

    fun testWildcardPathAnchorsBothEnds() {
        addRule(host = "api.example.com", path = "/v1/*/avatar", pathMatch = MatchType.WILDCARD)

        assertNotNull(match(host = "api.example.com", path = "/v1/42/avatar"))
        // The glob is a FULL match, so extra trailing segments must not match.
        assertNull(match(host = "api.example.com", path = "/v1/42/avatar/large"))
        assertNull(match(host = "api.example.com", path = "/v2/42/avatar"))
    }

    fun testRegexHostAndPathAreFullMatches() {
        addRule(
            host = "(api|beta)\\.example\\.com",
            path = "/v1/users/\\d+",
            hostMatch = MatchType.REGEX,
            pathMatch = MatchType.REGEX
        )

        assertNotNull(match(host = "api.example.com", path = "/v1/users/42"))
        assertNotNull(match(host = "beta.example.com", path = "/v1/users/7"))
        assertNull(match(host = "cdn.example.com", path = "/v1/users/42"))
        assertNull(match(host = "api.example.com", path = "/v1/users/abc"))
        // Anchored: a longer path that merely STARTS with a match must not match.
        assertNull(match(host = "api.example.com", path = "/v1/users/42/posts"))
    }

    fun testInvalidRegexIsTreatedAsNoMatchInsteadOfFailingTheLookup() {
        addRule(host = "api.example.com", path = "[", pathMatch = MatchType.REGEX)
        addRule(name = "fallback", host = "api.example.com", path = "/v1/users")

        // The broken rule must not take the whole lookup down with it: the rule after it still wins.
        val matched = match(host = "api.example.com", path = "/v1/users")
            ?: throw AssertionError("a rule with an uncompilable sibling should still have matched")
        assertEquals("fallback", matched.name)
    }

    fun testMethodIsComparedCaseInsensitivelyButADifferentVerbNeverMatches() {
        addRule(method = "GET", host = "api.example.com", path = "/v1/users")

        assertNotNull(match(method = "get", host = "api.example.com", path = "/v1/users"))
        assertNull(match(method = "POST", host = "api.example.com", path = "/v1/users"))
        assertNull(match(method = "DELETE", host = "api.example.com", path = "/v1/users"))
    }

    // ========================================================================
    // Query parameters
    // ========================================================================

    fun testRequiredExactParamMustBePresentWithTheSameValue() {
        addRule(
            host = "api.example.com", path = "/v1/users",
            query = listOf(QueryParam("id", "7", required = true, matchType = MatchType.EXACT))
        )

        assertNotNull(match(host = "api.example.com", path = "/v1/users", query = mapOf("id" to "7")))
        assertNull(match(host = "api.example.com", path = "/v1/users", query = mapOf("id" to "8")))
        assertNull(match(host = "api.example.com", path = "/v1/users", query = emptyMap()))
    }

    fun testRequiredWildcardParamChecksPresenceOnly() {
        addRule(
            host = "api.example.com", path = "/v1/users",
            query = listOf(QueryParam("ts", "1700000000", required = true, matchType = MatchType.WILDCARD))
        )

        assertNotNull(match(host = "api.example.com", path = "/v1/users", query = mapOf("ts" to "anything")))
        assertNotNull(match(host = "api.example.com", path = "/v1/users", query = mapOf("ts" to "")))
        assertNull(match(host = "api.example.com", path = "/v1/users", query = emptyMap()))
    }

    fun testOptionalParamMayBeAbsentButIsStillComparedWhenPresent() {
        addRule(
            host = "api.example.com", path = "/v1/users",
            query = listOf(QueryParam("id", "7", required = false, matchType = MatchType.EXACT))
        )

        // `required:false` decides ONE thing: whether the request may leave the param out. It used
        // to switch the check off entirely, so the rule silently answered every value — and it made
        // this matcher disagree with the flow matcher behind await_flow, which always read the two
        // fields separately. To ignore a value, say so with WILDCARD.
        assertNotNull(match(host = "api.example.com", path = "/v1/users", query = emptyMap()))
        assertNotNull(match(host = "api.example.com", path = "/v1/users", query = mapOf("id" to "7")))
        assertNull(match(host = "api.example.com", path = "/v1/users", query = mapOf("id" to "9999")))
    }

    fun testOptionalRegexParamIsAppliedToAValueThatIsThere() {
        addRule(
            host = "api.example.com", path = "/v1/users",
            query = listOf(QueryParam("id", "\\d+", required = false, matchType = MatchType.REGEX))
        )

        // The audit's exact complaint: a REGEX that is never run is a rule that claims to constrain
        // something and does not.
        assertNotNull(match(host = "api.example.com", path = "/v1/users", query = emptyMap()))
        assertNotNull(match(host = "api.example.com", path = "/v1/users", query = mapOf("id" to "42")))
        assertNull(match(host = "api.example.com", path = "/v1/users", query = mapOf("id" to "abc")))
    }

    fun testOptionalWildcardParamIsTheWayToIgnoreAValue() {
        addRule(
            host = "api.example.com", path = "/v1/users",
            query = listOf(QueryParam("ts", "1700000000000", required = false, matchType = MatchType.WILDCARD))
        )

        // What loosen_query must produce for a cloned flow: absent is fine, and any value is fine.
        assertNotNull(match(host = "api.example.com", path = "/v1/users", query = emptyMap()))
        assertNotNull(match(host = "api.example.com", path = "/v1/users", query = mapOf("ts" to "1799999999999")))
    }

    fun testRegexParamIsAFullMatchAgainstTheValue() {
        addRule(
            host = "api.example.com", path = "/v1/users",
            query = listOf(QueryParam("id", "\\d+", required = true, matchType = MatchType.REGEX))
        )

        assertNotNull(match(host = "api.example.com", path = "/v1/users", query = mapOf("id" to "42")))
        assertNull(match(host = "api.example.com", path = "/v1/users", query = mapOf("id" to "42a")))
        assertNull(match(host = "api.example.com", path = "/v1/users", query = mapOf("id" to "abc")))
    }

    fun testParamsTheRuleDoesNotMentionAreIgnored() {
        addRule(
            host = "api.example.com", path = "/v1/users",
            query = listOf(QueryParam("id", "7", required = true, matchType = MatchType.EXACT))
        )

        assertNotNull(
            match(
                host = "api.example.com", path = "/v1/users",
                query = mapOf("id" to "7", "utm_source" to "email", "cb" to "9182")
            )
        )
    }

    // ========================================================================
    // Enablement
    // ========================================================================

    fun testDisabledRuleNeverMatches() {
        val rule = addRule(host = "api.example.com", path = "/v1/users")
        assertNotNull(match(host = "api.example.com", path = "/v1/users"))

        store.setRuleEnabled(rule, false)
        assertNull(match(host = "api.example.com", path = "/v1/users"))

        store.setRuleEnabled(rule, true)
        assertNotNull(match(host = "api.example.com", path = "/v1/users"))
    }

    fun testRuleInADisabledCollectionNeverMatches() {
        addRule(host = "api.example.com", path = "/v1/users")
        assertNotNull(match(host = "api.example.com", path = "/v1/users"))

        store.updateCollection(collection.id, enabled = false)
        assertNull(match(host = "api.example.com", path = "/v1/users"))

        store.updateCollection(collection.id, enabled = true)
        assertNotNull(match(host = "api.example.com", path = "/v1/users"))
    }

    fun testRuleWithNoCollectionNeverMatches() {
        // An orphan is invisible in the UI; serving it would mock traffic the user cannot see or stop.
        store.addRule(
            name = "orphan",
            method = "GET",
            structuredUrl = StructuredUrl(host = "api.example.com", path = "/v1/users"),
            mockResponse = ModifiedResponseData(statusCode = 200, headers = emptyMap(), content = "{}"),
            collectionId = ""
        )

        assertNull(match(host = "api.example.com", path = "/v1/users"))
    }

    // ========================================================================
    // The two entry points must agree
    // ========================================================================

    fun testUrlEntryPointAgreesWithTheStructuredMatcher() {
        val rule = addRule(
            host = "api.example.com", path = "/v1/users",
            query = listOf(QueryParam("id", "7", required = true, matchType = MatchType.EXACT))
        )

        val url = "https://api.example.com/v1/users?id=7&extra=x"
        val structured = store.findMatchingRuleObject(
            "GET", "api.example.com", "/v1/users", mapOf("id" to "7", "extra" to "x")
        )
        val fromUrl = store.findMatchingRuleForUrl("GET", url)

        assertEquals(rule.id, structured?.id)
        assertEquals(structured?.id, fromUrl?.id)

        // …and they must agree on the negative answer too, which is the case that used to make the
        // Inspector label a flow with a rule that was never applied.
        store.setRuleEnabled(rule, false)
        assertNull(store.findMatchingRuleObject("GET", "api.example.com", "/v1/users", mapOf("id" to "7")))
        assertNull(store.findMatchingRuleForUrl("GET", url))
    }

    fun testSchemeIsNotPartOfMatching() {
        addRule(host = "api.example.com", path = "/v1/users")

        // The rule stores scheme=https; the pre-flight lookup never compared it, so neither may this.
        assertNotNull(store.findMatchingRuleForUrl("GET", "http://api.example.com/v1/users"))
    }

    fun testEmptyUrlPathIsMatchedAsRoot() {
        addRule(host = "api.example.com", path = "/")

        assertNotNull(store.findMatchingRuleForUrl("GET", "https://api.example.com"))
    }

    fun testUnparseableUrlReturnsNullInsteadOfThrowing() {
        addRule(host = "api.example.com", path = "/v1/users")

        assertNull(store.findMatchingRuleForUrl("GET", "not a url at all"))
        assertNull(store.findMatchingRuleForUrl("GET", ""))
    }

    // ========================================================================
    // Matching hands out a detached copy
    // ========================================================================

    fun testMatchedRuleIsDetachedFromTheStore() {
        val rule = addRule(host = "api.example.com", path = "/v1/users")
        val matched = match(host = "api.example.com", path = "/v1/users")
            ?: throw AssertionError("the rule should have matched")

        store.updateRule(
            ruleId = rule.id,
            name = "renamed",
            mockResponse = ModifiedResponseData(statusCode = 500, headers = mapOf("X" to "y"), content = "changed")
        )

        // The caller reads statusCode/headers/content outside the store's lock. Handing out the live
        // object let a concurrent edit serve a new body with the old status code.
        assertEquals(200, matched.statusCode)
        assertEquals("{}", matched.content)
        assertTrue(matched.headers.isEmpty())
        assertNotSame(rule.queryParams, matched.queryParams)
    }

    // ========================================================================
    // updateRule
    // ========================================================================

    fun testUpdateRulePreservesIdAndCollection() {
        val rule = addRule(host = "api.example.com", path = "/v1/users")

        val updated = store.updateRule(
            ruleId = rule.id,
            name = "renamed",
            method = "POST",
            structuredUrl = StructuredUrl(host = "other.example.com", path = "/v2/things"),
            mockResponse = ModifiedResponseData(statusCode = 503, headers = emptyMap(), content = "down")
        ) ?: throw AssertionError("updateRule returned null for an id that exists")

        assertEquals(rule.id, updated.id)
        assertEquals(collection.id, updated.collectionId)
        assertEquals("renamed", updated.name)
        assertEquals("POST", updated.method)
        assertEquals(503, updated.statusCode)

        val stored = store.getAllRules().single { it.id == rule.id }
        assertEquals(collection.id, stored.collectionId)
        assertEquals("other.example.com", stored.host)
    }

    fun testUpdateRuleLeavesUntouchedFieldsAlone() {
        val rule = addRule(
            host = "api\\.example\\.com",
            path = "/v1/users/\\d+",
            hostMatch = MatchType.REGEX,
            pathMatch = MatchType.REGEX
        )

        store.updateRule(ruleId = rule.id, name = "renamed only")

        val stored = store.getAllRules().single { it.id == rule.id }
        // A caller that rebuilds a StructuredUrl from the rule's own fields used to reset a REGEX
        // rule back to EXACT, silently killing it.
        assertEquals(MatchType.REGEX, stored.hostMatch)
        assertEquals(MatchType.REGEX, stored.pathMatch)
        assertEquals("renamed only", stored.name)
        assertNotNull(match(host = "api.example.com", path = "/v1/users/42"))
    }

    fun testUpdateRuleWithAnUnknownIdReturnsNull() {
        assertNull(store.updateRule(ruleId = "no-such-rule", name = "x"))
    }

    // ========================================================================
    // Identity
    // ========================================================================

    fun testIdsAreUniqueForRulesCreatedInATightLoop() {
        // Ids used to be derived from the millisecond clock, so a batch create produced duplicates
        // and "find the rule I just edited" returned somebody else's rule.
        val ids = (1..500).map { addRule(name = "rule $it", host = "h$it.example.com", path = "/p").id }

        assertEquals(500, ids.size)
        assertEquals(500, ids.toSet().size)
        assertFalse(ids.any { it.isEmpty() })
    }

    fun testIdsAreUniqueForCollectionsCreatedInATightLoop() {
        val ids = (1..200).map { store.addCollection("collection $it", "com.acme.app").id }

        assertEquals(200, ids.toSet().size)
        assertEquals(201, store.getAllCollections().size) // + the one created in setUp
    }

    fun testLoadStateRecoversCollectionsThatSharedAnId() {
        val shared = "collection_1700000000000"
        store.loadState(
            MockkRulesStore.State(
                collections = mutableListOf(
                    MockkCollection(id = shared, name = "First"),
                    MockkCollection(id = shared, name = "Second")
                ),
                rules = mutableListOf(
                    MockkRulesStore.MockkRule(
                        id = "dup", name = "a", method = "GET", collectionId = shared,
                        host = "a.example.com", path = "/a"
                    ),
                    MockkRulesStore.MockkRule(
                        id = "dup", name = "b", method = "GET", collectionId = shared,
                        host = "b.example.com", path = "/b"
                    )
                )
            )
        )

        // The duplicate used to overwrite the first entry when the map was rebuilt, destroying a
        // whole collection on load.
        val collections = store.getAllCollections()
        assertEquals(2, collections.size)
        assertEquals(setOf("First", "Second"), collections.map { it.name }.toSet())
        assertEquals(2, collections.map { it.id }.toSet().size)

        val rules = store.getAllRules()
        assertEquals(2, rules.size)
        assertEquals(2, rules.map { it.id }.toSet().size)
    }

    fun testLoadStateRecoversOrphanedRulesDisabled() {
        store.loadState(
            MockkRulesStore.State(
                collections = mutableListOf(MockkCollection(id = "kept", name = "Kept")),
                rules = mutableListOf(
                    MockkRulesStore.MockkRule(
                        id = "r1", name = "orphan", enabled = true, method = "GET",
                        collectionId = "gone", host = "api.example.com", path = "/v1/users"
                    )
                )
            )
        )

        val recovered = store.getAllRules().single()
        assertEquals("Default", store.getCollection(recovered.collectionId)?.name)
        // Recovered DISABLED on purpose: re-enabling a rule that starts mocking live traffic must
        // stay the user's decision.
        assertFalse(recovered.enabled)
        assertNull(match(host = "api.example.com", path = "/v1/users"))
    }

    // ========================================================================
    // Concurrency
    // ========================================================================

    /**
     * Snapshot reads must never throw while the EDT mutates the store — this is the
     * `ConcurrentModificationException` that used to drop a request on the interceptor socket
     * thread — and every snapshot must be internally consistent.
     *
     * Readers deliberately use only the lock-protected snapshot accessors. `findMatchingRuleObject`
     * is exercised single-threaded above instead, because it logs on every call and
     * `MockkHttpLogger` formats its timestamps through one shared `SimpleDateFormat`; hammering
     * that from several threads would make this test fail for a reason that has nothing to do with
     * the rule store.
     */
    fun testConcurrentSnapshotReadsDuringWritesNeitherThrowNorTear() {
        repeat(30) { addRule(name = "seed $it", host = "seed$it.example.com", path = "/p") }

        val stop = AtomicBoolean(false)
        val failures = CopyOnWriteArrayList<Throwable>()
        val reads = AtomicLong()

        val readers = (1..4).map {
            Thread {
                try {
                    while (!stop.get()) {
                        val rules = store.getAllRules()
                        // The returned list is a snapshot: iterating it must be safe, and its size
                        // must not change under the caller.
                        val size = rules.size
                        rules.forEach { rule -> check(rule.id.isNotEmpty()) { "rule with no id" } }
                        check(rules.size == size) { "the snapshot changed under the caller" }

                        store.getAllCollections().forEach { c -> check(c.id.isNotEmpty()) { "collection with no id" } }
                        store.getRulesInCollection(collection.id).forEach { rule ->
                            check(rule.collectionId == collection.id) { "wrong collection in the filtered snapshot" }
                        }
                        store.rulesNeedingRegexMigration()
                        store.getState()
                        reads.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    failures += t
                }
            }
        }

        val writer = Thread {
            try {
                repeat(300) { i ->
                    val added = addRule(name = "churn $i", host = "churn$i.example.com", path = "/p")
                    store.updateRule(
                        ruleId = added.id,
                        name = "churn $i renamed",
                        mockResponse = ModifiedResponseData(statusCode = 200 + (i % 100), headers = emptyMap(), content = "$i")
                    )
                    store.removeRule(added)
                }
            } catch (t: Throwable) {
                failures += t
            } finally {
                stop.set(true)
            }
        }

        readers.forEach { it.start() }
        writer.start()

        writer.join(60_000)
        stop.set(true)
        readers.forEach { it.join(30_000) }

        assertFalse("the writer thread did not finish in time", writer.isAlive)
        assertTrue("no reader ever completed a pass, so this test proved nothing", reads.get() > 0)
        assertTrue(
            "concurrent access failed: " + failures.joinToString("\n") { it.toString() },
            failures.isEmpty()
        )
        // Every churned rule was removed again, so only the seeds survive.
        assertEquals(30, store.getAllRules().size)
    }

    // ========================================================================
    // Optional params written by an older build
    // ========================================================================

    fun testLegacyOptionalExactParamsAreConvertedToWildcardOnLoad() {
        store.loadState(
            MockkRulesStore.State(
                collections = mutableListOf(MockkCollection(id = "legacy", name = "Legacy", enabled = true)),
                rules = mutableListOf(
                    MockkRulesStore.MockkRule(
                        id = "r1", name = "cloned from a flow", method = "GET", collectionId = "legacy",
                        host = "api.example.com", path = "/v1/users",
                        queryParams = mutableListOf(
                            QueryParam("ts", "1700000000000", required = false, matchType = MatchType.EXACT)
                        )
                    )
                )
            )
        )

        // `required:false` + EXACT could only ever have meant "ignore this value" — it is what the
        // Required checkbox and loosen_query produced — so the load rewrites it to the mode that
        // says so. Without this, every rule cloned from a flow would start rejecting the next
        // request the moment the timestamp changed.
        val migrated = store.getAllRules().single()
        assertEquals(MatchType.WILDCARD, migrated.queryParams.single().matchType)
        assertFalse(migrated.queryParams.single().required)
        assertNotNull(
            match(host = "api.example.com", path = "/v1/users", query = mapOf("ts" to "1799999999999"))
        )
    }

    fun testLegacyOptionalRegexParamsAreLeftAloneSoTheyStartBeingApplied() {
        store.loadState(
            MockkRulesStore.State(
                collections = mutableListOf(MockkCollection(id = "legacy", name = "Legacy", enabled = true)),
                rules = mutableListOf(
                    MockkRulesStore.MockkRule(
                        id = "r1", name = "numeric id", method = "GET", collectionId = "legacy",
                        host = "api.example.com", path = "/v1/users",
                        queryParams = mutableListOf(
                            QueryParam("id", "\\d+", required = false, matchType = MatchType.REGEX)
                        )
                    )
                )
            )
        )

        // A pattern is never converted: an optional REGEX that is finally applied is the fix, and
        // turning it into a WILDCARD would delete the constraint the user wrote.
        assertEquals(MatchType.REGEX, store.getAllRules().single().queryParams.single().matchType)
        assertNotNull(match(host = "api.example.com", path = "/v1/users", query = mapOf("id" to "42")))
        assertNull(match(host = "api.example.com", path = "/v1/users", query = mapOf("id" to "abc")))
    }

    // ========================================================================
    // Writing a rule that is a PATTERN (StructuredUrl.fromSpec)
    // ========================================================================
    //
    // These live next to the matcher on purpose: fromSpec exists only to build something this
    // matcher can use, and a spec that parses but matches nothing is the failure worth catching.

    fun testARegexPathCanBeWrittenAsAFirstClassField() {
        val spec = StructuredUrl.fromSpec(
            url = null, host = "api.example.com", path = "/v1/orders/[0-9]+",
            hostMatch = MatchType.EXACT, pathMatch = MatchType.REGEX
        )

        addRule(spec)

        assertNotNull(match(host = "api.example.com", path = "/v1/orders/42"))
        assertNull(match(host = "api.example.com", path = "/v1/orders/abc"))
        // Anchored, like every other pattern in this store.
        assertNull(match(host = "api.example.com", path = "/v1/orders/42/items"))
    }

    fun testAPatternInsideTheUrlIsNoLongerParsedAsAUrl() {
        // The whole finding: `java.net.URI` refuses `[0-9]+`, so this used to come back INVALID_URL
        // and `.*` was the only pattern anybody could write.
        val spec = StructuredUrl.fromSpec(
            url = "https://api.example.com/v1/orders/[0-9]+", host = null, path = null,
            hostMatch = MatchType.EXACT, pathMatch = MatchType.REGEX
        )
        val built = okSpec(spec)

        assertEquals("api.example.com", built.host)
        assertEquals("/v1/orders/[0-9]+", built.path)

        addRule(spec)
        assertNotNull(match(host = "api.example.com", path = "/v1/orders/42"))
        assertNull(match(host = "api.example.com", path = "/v1/orders/abc"))
    }

    fun testAPatternPathIsNotSplitOnAQuestionMark() {
        // `/?` is a legal quantifier; cutting the pattern there would silently store half of it.
        val built = okSpec(
            StructuredUrl.fromSpec(
                url = "https://api.example.com/v1/orders/[0-9]+/?", host = null, path = null,
                hostMatch = MatchType.EXACT, pathMatch = MatchType.REGEX
            )
        )

        assertEquals("/v1/orders/[0-9]+/?", built.path)
        assertTrue(built.queryParams.isEmpty())
    }

    fun testALiteralPathIsStillSplitOffItsQueryWhenOnlyTheHostIsAPattern() {
        val spec = StructuredUrl.fromSpec(
            url = "https://*.example.com/v1/users?id=7", host = null, path = null,
            hostMatch = MatchType.WILDCARD, pathMatch = MatchType.EXACT
        )
        val built = okSpec(spec)

        // Only the HOST was a pattern here, so `?` is the ordinary delimiter it has always been.
        // Leaving `?id=7` glued to an EXACT path would build a rule that matches nothing at all.
        assertEquals("/v1/users", built.path)
        assertEquals("id", built.queryParams.single().key)

        addRule(spec)
        assertNotNull(match(host = "eu.example.com", path = "/v1/users", query = mapOf("id" to "7")))
    }

    fun testARegexHostKeepsItsEscapedDots() {
        val spec = StructuredUrl.fromSpec(
            url = null, host = "(api|beta)\\.example\\.com", path = "/v1/users",
            hostMatch = MatchType.REGEX, pathMatch = MatchType.EXACT
        )

        addRule(spec)

        assertNotNull(match(host = "api.example.com", path = "/v1/users"))
        assertNotNull(match(host = "beta.example.com", path = "/v1/users"))
        // An unescaped dot matches any character: `.*.example.test` also matches `XexampleXtest`,
        // which is why the escapes have to survive being sent as a first-class field.
        assertNull(match(host = "apiXexample.com", path = "/v1/users"))
    }

    fun testPortAndUserInfoAreStrippedFromAPatternUrl() {
        val built = okSpec(
            StructuredUrl.fromSpec(
                url = "https://someone@localhost:8080/v1/orders/[0-9]+", host = null, path = null,
                hostMatch = MatchType.EXACT, pathMatch = MatchType.REGEX
            )
        )

        // Hand-splitting must not leave `someone@localhost:8080` to be compared against a host.
        assertEquals("localhost", built.host)
        assertEquals(8080, built.port)
        assertEquals("/v1/orders/[0-9]+", built.path)
    }

    fun testALiteralUrlMustStillBeAUrlAndTheErrorNamesTheWayOut() {
        val error = errSpec(
            StructuredUrl.fromSpec(
                url = "/v1/users", host = null, path = null,
                hostMatch = MatchType.EXACT, pathMatch = MatchType.EXACT
            )
        )

        assertEquals("url", error.field)
        // The error a caller sees has to name the field that fixes it, or the answer is "guess".
        assertTrue(error.hint, error.hint.contains("path_match"))
    }

    fun testAPatternPathModeWithoutAPathIsRefused() {
        val error = errSpec(
            StructuredUrl.fromSpec(
                url = null, host = "api.example.com", path = null,
                hostMatch = MatchType.EXACT, pathMatch = MatchType.REGEX
            )
        )

        // Defaulting to "/" would build a rule that answers only the root and looks like it works.
        assertEquals("path", error.field)
    }

    fun testASpecWithNoHostAnywhereIsRefused() {
        val error = errSpec(
            StructuredUrl.fromSpec(
                url = null, host = null, path = "/v1/users",
                hostMatch = MatchType.EXACT, pathMatch = MatchType.EXACT
            )
        )

        assertEquals("host", error.field)
    }

    fun testUpdatingOnlyThePathKeepsTheRestOfTheRule() {
        val base = okSpec(
            StructuredUrl.fromSpec(
                url = "https://api.example.com/v1/users?id=7", host = null, path = null,
                hostMatch = MatchType.EXACT, pathMatch = MatchType.EXACT
            )
        )

        val updated = okSpec(
            StructuredUrl.fromSpec(
                url = null, host = null, path = "/v1/users/[0-9]+",
                hostMatch = MatchType.EXACT, pathMatch = MatchType.REGEX, base = base
            )
        )

        assertEquals("api.example.com", updated.host)
        assertEquals("/v1/users/[0-9]+", updated.path)
        assertEquals(MatchType.REGEX, updated.pathMatch)
        // The params the caller did not touch are carried over — and copied, not shared.
        assertEquals(1, updated.queryParams.size)
        assertNotSame(base.queryParams, updated.queryParams)
    }

    // ========================================================================
    // Response bodies (a rule body is text, all the way to the device)
    // ========================================================================

    fun testABinaryBodyIsRefusedInsteadOfBeingCorrupted() {
        // A 1x1 PNG: the signature alone is already not valid UTF-8.
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06,
            0x00, 0x00, 0x00, 0x1F.toByte(), 0x15, 0xC4.toByte(), 0x89.toByte(), 0x00
        )
        val encoded = java.util.Base64.getEncoder().encodeToString(png)

        // What the store would have kept: every malformed byte replaced by U+FFFD, so more bytes come
        // out than went in. The payload is destroyed, and body_bytes reports the wrong number too.
        assertTrue(
            "a lossy decode has to be measurably lossy, or this test proves nothing",
            String(png, Charsets.UTF_8).toByteArray(Charsets.UTF_8).size != png.size
        )

        val decoded = MockBody.decodeBase64(encoded)
        assertTrue("expected a refusal, got $decoded", decoded is MockBody.Decoded.NotText)
        val refused = decoded as MockBody.Decoded.NotText
        assertEquals(png.size, refused.byteCount)
        // The caller has to be able to recognise what it sent.
        assertTrue(refused.firstBytesHex, refused.firstBytesHex.startsWith("89 50 4E 47"))
    }

    fun testABase64TextBodyStillDecodesByteForByte() {
        val body = "{\"café\":\"ok — yes\"}"
        val encoded = java.util.Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))

        val decoded = MockBody.decodeBase64(encoded)
        assertTrue("expected text, got $decoded", decoded is MockBody.Decoded.Text)
        val text = (decoded as MockBody.Decoded.Text).value
        assertEquals(body, text)
        // The invariant that makes refusing safe: text that decodes re-encodes to the same bytes.
        assertEquals(body.toByteArray(Charsets.UTF_8).size, MockBody.utf8Length(text))
    }

    fun testSomethingThatIsNotBase64IsReportedAsSuch() {
        assertEquals(MockBody.Decoded.NotBase64, MockBody.decodeBase64("this is not base64!!"))
    }

    fun testStoredBodyBytesAreCountedInUtf8NotCharacters() {
        val rule = store.addRule(
            name = "accented",
            method = "GET",
            structuredUrl = StructuredUrl(host = "api.example.com", path = "/v1/users"),
            mockResponse = ModifiedResponseData(statusCode = 200, headers = emptyMap(), content = "café"),
            collectionId = collection.id
        )

        // 4 characters, 5 bytes on the socket. body_bytes has to be the second number.
        assertEquals(4, rule.content.length)
        assertEquals(5, rule.contentBytes())
    }

    fun testReimportingAnOlderExportRecognisesTheRuleItAlreadyHas() {
        val json = """
            {"pluginVersion":"1.7.1","exportDate":0,"collections":[
              {"collection":{"id":"c","name":"Old export","packageName":"com.acme.app","description":"",
                "enabled":true,"createdAt":0,"version":"1.7.1"},
               "rules":[{"id":"r1","name":"users","enabled":true,"method":"GET","scheme":"https",
                 "host":"api.example.com","path":"/v1/users",
                 "queryParams":[{"key":"ts","value":"1700000000000","required":false,"matchType":"EXACT"}],
                 "statusCode":200,"headers":{},"content":"{}","hostMatch":"EXACT","pathMatch":"EXACT"}]}
            ]}
        """.trimIndent()

        store.importCollections(json)

        val imported = store.getAllRules().single()
        assertEquals(MatchType.WILDCARD, imported.queryParams.single().matchType)
        assertNotNull(match(host = "api.example.com", path = "/v1/users", query = mapOf("ts" to "1799999999999")))

        // The endpoint signature contains the match mode, so normalising the file at the door is
        // what stops the SAME file from being imported as a second copy of every rule.
        val diff = store.analyzeImport(json).diffs.single()
        assertNotNull("the collection should have been recognised by name", diff.existing)
        assertEquals(1, diff.identicalRules.size)
        assertTrue("an already-present rule must not come back as new", diff.newRules.isEmpty())
    }

    fun testAStatusCodeNoClientCanUseIsCalledOutWhenItIsStored() {
        val logger = com.sergiy.dev.mockkhttp.logging.MockkHttpLogger.getInstance(project)
        logger.clear()

        store.addRule(
            name = "impossible",
            method = "GET",
            structuredUrl = StructuredUrl(host = "api.example.com", path = "/v1/users"),
            mockResponse = ModifiedResponseData(statusCode = 9999, headers = emptyMap(), content = "{}"),
            collectionId = collection.id
        )

        // 9999 fails inside the app, minutes later and nowhere near the call that stored it. The
        // control plane refuses it; the store is the last place that can still name it.
        val warned = logger.getAllLogs().any {
            it.level == com.sergiy.dev.mockkhttp.logging.MockkHttpLogger.LogLevel.WARN && it.message.contains("9999")
        }
        assertTrue("storing status 9999 must be reported: " + logger.getLogsAsString(), warned)
    }

    // ========================================================================
    // The match vocabulary is generated, not hand-copied
    // ========================================================================

    fun testEveryMatchModeIsPublishedUnderItsOwnName() {
        // Whatever publishes this list (the REST constants, the MCP schema) has to be able to name
        // all of it: a subset is what made WILDCARD a value the server accepted and the schema
        // called invalid.
        assertEquals(listOf("EXACT", "WILDCARD", "REGEX"), MatchType.WIRE_NAMES)
        MatchType.entries.forEach { mode -> assertEquals(mode, MatchType.parse(mode.name)) }
        assertEquals(MatchType.REGEX, MatchType.parse(" regex "))
        assertNull(MatchType.parse("almost"))
        assertNull(MatchType.parse(null))
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun addRule(
        name: String = "rule",
        method: String = "GET",
        host: String,
        path: String,
        hostMatch: MatchType = MatchType.EXACT,
        pathMatch: MatchType = MatchType.EXACT,
        query: List<QueryParam> = emptyList()
    ): MockkRulesStore.MockkRule = store.addRule(
        name = name,
        method = method,
        structuredUrl = StructuredUrl(
            scheme = "https",
            host = host,
            port = null,
            path = path,
            queryParams = query.map { it.copy() }.toMutableList(),
            hostMatch = hostMatch,
            pathMatch = pathMatch
        ),
        mockResponse = ModifiedResponseData(statusCode = 200, headers = emptyMap(), content = "{}"),
        collectionId = collection.id
    )

    private fun match(
        method: String = "GET",
        host: String,
        path: String,
        query: Map<String, String> = emptyMap()
    ): MockkRulesStore.MockkRule? = store.findMatchingRuleObject(method, host, path, query)

    /** Adds a rule straight from a spec, so a test can prove the spec is usable and not just legal. */
    private fun addRule(spec: UrlSpec, name: String = "rule", method: String = "GET"): MockkRulesStore.MockkRule =
        store.addRule(
            name = name,
            method = method,
            structuredUrl = okSpec(spec),
            mockResponse = ModifiedResponseData(statusCode = 200, headers = emptyMap(), content = "{}"),
            collectionId = collection.id
        )

    private fun okSpec(spec: UrlSpec): StructuredUrl = when (spec) {
        is UrlSpec.Ok -> spec.url
        is UrlSpec.Err -> throw AssertionError("expected a usable URL spec, got ${spec.field}: ${spec.message}")
    }

    private fun errSpec(spec: UrlSpec): UrlSpec.Err = when (spec) {
        is UrlSpec.Err -> spec
        is UrlSpec.Ok -> throw AssertionError("expected a refusal, got ${spec.url}")
    }
}
