package com.sergiy.dev.mockkhttp.store

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import com.sergiy.dev.mockkhttp.model.MatchType
import com.sergiy.dev.mockkhttp.model.MockkCollection
import com.sergiy.dev.mockkhttp.model.MockkCollectionData
import com.sergiy.dev.mockkhttp.model.MockkCollectionExport
import com.sergiy.dev.mockkhttp.model.MockkRuleData
import com.sergiy.dev.mockkhttp.model.ModifiedResponseData
import com.sergiy.dev.mockkhttp.model.QueryParam
import com.sergiy.dev.mockkhttp.model.StructuredUrl
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Store for managing Mockk rules.
 * Allows adding, removing, enabling/disabling rules.
 * Rules are persisted to XML storage.
 *
 * [findMatchingRuleObject] is the single matching implementation in the plugin: the pre-flight
 * mock lookup and any flow-side "was a mock applied?" label must go through it (directly or via
 * [findMatchingRuleForUrl]), otherwise the UI can name a rule that was never applied.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "MockkRulesStore",
    storages = [Storage("mockkHttpRules.xml")]
)
class MockkRulesStore(project: Project) : PersistentStateComponent<MockkRulesStore.State> {

    private val logger = MockkHttpLogger.getInstance(project)

    // Guards [collections] and [rules]. Matching runs on interceptor socket threads while the
    // EDT adds/edits/removes rules, so reads take a snapshot under the read lock and every
    // mutation happens under the write lock.
    private val stateLock = ReentrantReadWriteLock()
    private val collections = mutableMapOf<String, MockkCollection>()
    private val rules = mutableListOf<MockkRule>()

    // Copy-on-write: listeners are notified outside the state lock and a listener is allowed to
    // register or dispose another listener while the notification loop is running.
    private val ruleAddedListeners = CopyOnWriteArrayList<(MockkRule) -> Unit>()
    private val ruleRemovedListeners = CopyOnWriteArrayList<(MockkRule) -> Unit>()
    private val ruleUpdatedListeners = CopyOnWriteArrayList<(MockkRule) -> Unit>()
    private val collectionAddedListeners = CopyOnWriteArrayList<(MockkCollection) -> Unit>()
    private val collectionRemovedListeners = CopyOnWriteArrayList<(MockkCollection) -> Unit>()

    companion object {
        fun getInstance(project: Project): MockkRulesStore {
            return project.getService(MockkRulesStore::class.java)
        }

        /**
         * Characters that turn a host/path into a pattern when it is interpreted as a regex.
         * Used by [rulesNeedingRegexMigration] to spot rules whose literal text used to be
         * compiled as a regex by the old flow-side matcher.
         */
        private const val REGEX_METACHARACTERS = ".*+?[](){}^\$|\\"

        private fun newId(): String = UUID.randomUUID().toString()
    }

    /**
     * State for persistence.
     */
    data class State(
        var collections: MutableList<MockkCollection> = mutableListOf(),
        var rules: MutableList<MockkRule> = mutableListOf()
    )

    override fun getState(): State = stateLock.read {
        State(
            collections = collections.values.toMutableList(),
            rules = rules.toMutableList()
        )
    }

    override fun loadState(state: State) {
        val (collectionCount, ruleCount) = stateLock.write {
            collections.clear()
            rules.clear()

            // Ids first: everything below (and the collections map itself) keys off them.
            normalizeCollectionIds(state.collections)
            state.collections.forEach { collection ->
                collections[collection.id] = collection
            }

            rules.addAll(state.rules)
            normalizeRuleIds()

            // hostMatch/pathMatch need no conversion here: rules persisted before those fields
            // existed deserialize with the EXACT default, which is what they always did on the
            // pre-flight mock path. Rules that used to be read as regex by the flow-side matcher
            // are reported by rulesNeedingRegexMigration() so the user can convert them
            // deliberately - auto-converting a literal dot into a wildcard is what made the two
            // matchers disagree in the first place.
            migrateOldRulesToDefaultCollection()
            recoverOrphanedRules()

            collections.size to rules.size
        }

        logger.info("📚 Loaded $collectionCount collection(s) and $ruleCount mock rule(s) from storage")
    }

    /**
     * Collections used to be identified by "collection_<millis>", so two collections created in
     * the same millisecond shared an id and the second silently replaced the first when the map
     * was rebuilt on load. Rewrites empty/duplicate ids, keeping the first occurrence's id so the
     * rules already pointing at it stay attached.
     */
    private fun normalizeCollectionIds(loaded: MutableList<MockkCollection>) {
        val seenIds = mutableSetOf<String>()

        loaded.forEach { collection ->
            when {
                collection.id.isEmpty() -> {
                    // An id-less collection was never addressable, so no rule can be pointing at
                    // it: give it an id without touching any collectionId.
                    collection.id = newId()
                    seenIds.add(collection.id)
                    logger.warn("⚠️ Collection '${collection.name}' had no id - assigned a new one")
                }
                !seenIds.add(collection.id) -> {
                    val duplicatedId = collection.id
                    collection.id = newId()
                    seenIds.add(collection.id)
                    // Rules keep pointing at the first collection that owned the shared id;
                    // there is no way to tell which duplicate they really belonged to.
                    logger.warn("⚠️ Collection '${collection.name}' reused id $duplicatedId - assigned a new one")
                }
            }
        }
    }

    /**
     * Same collision problem as [normalizeCollectionIds], but for rules: duplicate ids made
     * lookups like "find the rule I just edited" return a different rule.
     */
    private fun normalizeRuleIds() {
        val seenIds = mutableSetOf<String>()
        var rewritten = 0

        rules.forEach { rule ->
            if (rule.id.isEmpty() || !seenIds.add(rule.id)) {
                rule.id = newId()
                seenIds.add(rule.id)
                rewritten++
            }
        }

        if (rewritten > 0) {
            logger.warn("⚠️ Rewrote $rewritten rule id(s) that were empty or duplicated")
        }
    }

    /**
     * Migrates rules that don't have a collectionId to a "Default" collection.
     * This ensures backward compatibility with rules created before collections were introduced.
     */
    private fun migrateOldRulesToDefaultCollection() {
        val rulesWithoutCollection = rules.filter { it.collectionId.isEmpty() }
        if (rulesWithoutCollection.isEmpty()) return

        logger.info("🔄 Migrating ${rulesWithoutCollection.size} old rule(s) to Default collection")

        val defaultCollection = findOrCreateDefaultCollection()
        rulesWithoutCollection.forEach { rule ->
            rule.collectionId = defaultCollection.id
        }

        logger.info("✅ Migration complete: ${rulesWithoutCollection.size} rule(s) migrated")
    }

    /**
     * Re-attaches rules whose collection no longer exists (a collection removed without its rules,
     * or an id collision in an older build). Such a rule was invisible in the UI and could never
     * match, so it is recovered disabled: re-enabling it must stay the user's decision.
     */
    private fun recoverOrphanedRules() {
        val orphaned = rules.filter { it.collectionId.isNotEmpty() && !collections.containsKey(it.collectionId) }
        if (orphaned.isEmpty()) return

        val defaultCollection = findOrCreateDefaultCollection()
        orphaned.forEach { rule ->
            rule.collectionId = defaultCollection.id
            rule.enabled = false
        }

        logger.warn("⚠️ Recovered ${orphaned.size} orphaned rule(s) into '${defaultCollection.name}' (left disabled)")
    }

    private fun findOrCreateDefaultCollection(): MockkCollection {
        return collections.values.find { it.name == "Default" }
            ?: MockkCollection(
                id = newId(),
                name = "Default",
                packageName = "",
                description = "Migrated from previous version",
                enabled = true
            ).also { created ->
                collections[created.id] = created
                logger.info("✨ Created Default collection for migrated rules")
            }
    }

    /**
     * Add a new mock rule with structured URL format.
     */
    fun addRule(
        name: String,
        method: String,
        structuredUrl: StructuredUrl,
        mockResponse: ModifiedResponseData,
        collectionId: String = ""
    ): MockkRule {
        val rule = MockkRule(
            id = newId(),
            name = name,
            enabled = true,
            method = method,
            collectionId = collectionId,
            scheme = structuredUrl.scheme,
            host = structuredUrl.host,
            hostMatch = structuredUrl.hostMatch,
            port = structuredUrl.port,
            path = structuredUrl.path,
            pathMatch = structuredUrl.pathMatch,
            // Copied: the caller (a dialog) keeps editing its own StructuredUrl afterwards.
            queryParams = structuredUrl.queryParams.map { it.copy() }.toMutableList(),
            statusCode = mockResponse.statusCode ?: 200,
            headers = mockResponse.headers ?: emptyMap(),
            content = mockResponse.content ?: ""
        )

        stateLock.write { rules.add(rule) }
        logger.info("➕ Added mock rule: $name to collection: $collectionId")

        notifyRuleListeners(ruleAddedListeners, rule)
        return rule
    }

    /**
     * Update an existing rule in place, preserving its id and collection. Every parameter is
     * optional: only the ones that are passed are changed.
     *
     * [structuredUrl] deliberately does NOT carry hostMatch/pathMatch over - a caller rebuilding a
     * StructuredUrl from the rule's own fields would otherwise reset a REGEX rule back to EXACT.
     * Pass [hostMatch]/[pathMatch] explicitly to change the match modes.
     *
     * Returns the updated rule, or null if no rule has that id.
     */
    fun updateRule(
        ruleId: String,
        name: String? = null,
        enabled: Boolean? = null,
        method: String? = null,
        structuredUrl: StructuredUrl? = null,
        mockResponse: ModifiedResponseData? = null
    ): MockkRule? {
        val updated = stateLock.write {
            val rule = rules.find { it.id == ruleId } ?: return@write null

            name?.let { rule.name = it }
            enabled?.let { rule.enabled = it }
            method?.let { rule.method = it }

            structuredUrl?.let { url ->
                rule.scheme = url.scheme
                rule.host = url.host
                rule.hostMatch = url.hostMatch
                rule.port = url.port
                rule.path = url.path
                rule.pathMatch = url.pathMatch
                rule.queryParams = url.queryParams.map { it.copy() }.toMutableList()
            }

            mockResponse?.let { response ->
                response.statusCode?.let { rule.statusCode = it }
                response.headers?.let { rule.headers = it }
                response.content?.let { rule.content = it }
            }

            rule
        }

        if (updated == null) {
            logger.warn("⚠️ Cannot update rule: no rule with id $ruleId")
            return null
        }

        logger.info("🔄 Updated mock rule: ${updated.name}")
        notifyRuleListeners(ruleUpdatedListeners, updated)
        return updated
    }

    // ========== COLLECTION METHODS ==========

    /**
     * Add a new collection.
     */
    fun addCollection(
        name: String,
        packageName: String,
        description: String = ""
    ): MockkCollection {
        val collection = MockkCollection(
            id = newId(),
            name = name,
            packageName = packageName,
            description = description,
            enabled = true
        )

        stateLock.write { collections[collection.id] = collection }
        logger.info("📁 Added collection: $name (package: $packageName)")

        notifyCollectionListeners(collectionAddedListeners, collection)
        return collection
    }

    /**
     * Remove ALL collections and their rules. Useful to recover from accidental duplicate imports.
     * Returns the number of collections and rules removed.
     */
    fun removeAllCollections(): Pair<Int, Int> {
        val removed = stateLock.write {
            val counts = collections.size to rules.size
            collections.clear()
            rules.clear()
            counts
        }

        logger.info("🗑️ Removed ALL collections (${removed.first}) and rules (${removed.second})")
        return removed
    }

    /**
     * Remove a collection and optionally its rules.
     */
    fun removeCollection(collection: MockkCollection, removeRules: Boolean = true) {
        val removedRules = stateLock.write {
            collections.remove(collection.id)
            if (removeRules) {
                val toRemove = rules.filter { it.collectionId == collection.id }
                rules.removeAll { it.collectionId == collection.id }
                toRemove
            } else {
                emptyList<MockkRule>()
            }
        }

        logger.info("🗑️ Removed collection: ${collection.name}")
        if (removeRules) {
            logger.info("   Also removed ${removedRules.size} rule(s)")
        }

        removedRules.forEach { rule -> notifyRuleListeners(ruleRemovedListeners, rule) }
        notifyCollectionListeners(collectionRemovedListeners, collection)
    }

    /**
     * Get all collections (snapshot: the returned list never changes under the caller).
     */
    fun getAllCollections(): List<MockkCollection> = stateLock.read { collections.values.toList() }

    /**
     * Get collections by package name.
     */
    fun getCollectionsByPackage(packageName: String): List<MockkCollection> =
        stateLock.read { collections.values.filter { it.packageName == packageName } }

    /**
     * Get a collection by ID.
     */
    fun getCollection(collectionId: String): MockkCollection? = stateLock.read { collections[collectionId] }

    /**
     * Get rules in a specific collection (snapshot).
     */
    fun getRulesInCollection(collectionId: String): List<MockkRule> =
        stateLock.read { rules.filter { it.collectionId == collectionId } }

    /**
     * Move a rule to a different collection.
     */
    fun moveRule(rule: MockkRule, targetCollectionId: String) {
        stateLock.write { rule.collectionId = targetCollectionId }
        logger.info("📦 Moved rule '${rule.name}' to collection: $targetCollectionId")
    }

    /**
     * Duplicate a rule into a target collection.
     */
    fun duplicateRule(rule: MockkRule, targetCollectionId: String): MockkRule {
        val duplicated = stateLock.write {
            val copy = rule.copy(
                id = newId(),
                collectionId = targetCollectionId,
                // data class copy() would share the very same MutableList, so editing the
                // duplicate's query params would silently edit the original's too.
                queryParams = rule.queryParams.map { it.copy() }.toMutableList()
            )
            rules.add(copy)
            copy
        }

        logger.info("📋 Duplicated rule '${rule.name}' to collection: $targetCollectionId")

        notifyRuleListeners(ruleAddedListeners, duplicated)
        return duplicated
    }

    /**
     * Update collection properties.
     */
    fun updateCollection(collectionId: String, name: String? = null, description: String? = null, enabled: Boolean? = null) {
        val updated = stateLock.write {
            val collection = collections[collectionId] ?: return@write null

            name?.let { collection.name = it }
            description?.let { collection.description = it }
            enabled?.let { collection.enabled = it }
            collection
        } ?: return

        logger.info("🔄 Updated collection: ${updated.name}")
    }

    /**
     * Remove a rule.
     */
    fun removeRule(rule: MockkRule) {
        val removed = stateLock.write { rules.remove(rule) }
        if (removed) {
            logger.info("➖ Removed mock rule: ${rule.name}")
            notifyRuleListeners(ruleRemovedListeners, rule)
        }
    }

    /**
     * Get all rules (snapshot: the returned list never changes under the caller).
     */
    fun getAllRules(): List<MockkRule> = stateLock.read { rules.toList() }

    /**
     * Enable or disable a rule.
     */
    fun setRuleEnabled(rule: MockkRule, enabled: Boolean) {
        stateLock.write { rule.enabled = enabled }
        logger.info("${if (enabled) "✅" else "⏸"} Rule ${rule.name} ${if (enabled) "enabled" else "disabled"}")
    }

    // ========== MATCHING (single implementation) ==========

    /**
     * Find a matching rule for a given request using structured matching.
     * Only searches in enabled collections.
     * Returns the actual MockkRule if a match is found.
     *
     * This is THE matcher: the pre-flight mock lookup and the Inspector's "mock applied" label
     * must both come from here, or the label ends up naming a rule that was never applied.
     */
    fun findMatchingRuleObject(method: String, host: String, path: String, queryParams: Map<String, String>): MockkRule? {
        logger.debug("🔍 Looking for match:")
        logger.debug("   Method: $method")
        logger.debug("   Host: $host")
        logger.debug("   Path: $path")
        logger.debug("   Query Params: $queryParams")

        // Snapshot under the read lock: this runs on socket threads and must never walk a list
        // the EDT is mutating. Matching itself is done outside the lock.
        val (candidates, enabledCollectionIds) = stateLock.read {
            rules.toList() to collections.values.filter { it.enabled }.map { it.id }.toSet()
        }
        logger.debug("🔍 Searching in ${enabledCollectionIds.size} enabled collection(s)")

        for ((index, rule) in candidates.withIndex()) {
            // Skip if rule not enabled
            if (!rule.enabled) {
                logger.debug("  ⏭️  Rule $index: ${rule.name} - DISABLED")
                continue
            }

            // Skip if rule's collection is disabled (or gone)
            if (rule.collectionId !in enabledCollectionIds) {
                logger.debug("  ⏭️  Rule $index: ${rule.name} - Collection disabled")
                continue
            }

            // Skip if method doesn't match
            if (!rule.method.equals(method, ignoreCase = true)) {
                logger.debug("  ⏭️  Rule $index: ${rule.name} - Method mismatch (${rule.method} != $method)")
                continue
            }

            logger.debug("  🔍 Checking Rule $index: ${rule.name}")
            logger.debug("     Rule: ${rule.method} ${rule.host}${rule.path}")
            logger.debug("     Rule Params: ${rule.queryParams.map { "${it.key}=${it.value} (required=${it.required}, type=${it.matchType})" }}")

            if (matchesStructured(rule, host, path, queryParams)) {
                logger.debug("  ✅ MATCHED Rule $index: ${rule.name}")
                // A copy, not the live rule: MockkRule is mutable and the caller reads
                // statusCode/headers/content outside the read lock. Handing out the live
                // object let a concurrent edit serve a half-updated response — a new body
                // with the old status code.
                return rule.copy(queryParams = rule.queryParams.map { it.copy() }.toMutableList())
            }
        }

        val eligibleRules = candidates.count { rule ->
            rule.enabled &&
                    rule.method.equals(method, ignoreCase = true) &&
                    rule.collectionId in enabledCollectionIds
        }
        logger.debug("❌ No matching rule found")
        logger.debug("   Checked $eligibleRules eligible rule(s)")
        return null
    }

    /**
     * URL-string entry point for [findMatchingRuleObject], for callers that only hold the full URL
     * of a captured flow.
     *
     * The scheme is deliberately not part of matching: the pre-flight lookup never compared it, and
     * a second matcher with extra conditions is exactly how the plugin ended up labelling flows
     * with rules that were never applied.
     */
    fun findMatchingRuleForUrl(method: String, url: String): MockkRule? {
        val parsedUrl = try {
            java.net.URI.create(url).toURL()
        } catch (e: Exception) {
            logger.warn("⚠️ Failed to parse URL for mock matching: $url", e)
            return null
        }

        val queryParams = parsedUrl.query?.split("&")?.associate { param ->
            val parts = param.split("=", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        } ?: emptyMap()

        return findMatchingRuleObject(
            method = method,
            host = parsedUrl.host ?: "",
            path = (parsedUrl.path ?: "").ifEmpty { "/" },
            queryParams = queryParams
        )
    }

    /**
     * Rules whose host or path contains a regex metacharacter while still matching literally
     * (EXACT). Before per-field match modes existed, the flow-side matcher compiled those strings
     * as regexes, so these are the rules whose intent may have been a pattern.
     *
     * Nothing is converted automatically: a literal `.` in "api.example.com" also counts here, and
     * silently turning it into a wildcard is what made the two matchers disagree. The UI is meant
     * to offer a per-rule "convert to REGEX" action using [updateRule].
     */
    fun rulesNeedingRegexMigration(): List<MockkRule> = stateLock.read {
        rules.filter { rule ->
            (rule.hostMatch == MatchType.EXACT && containsRegexMetacharacter(rule.host)) ||
                    (rule.pathMatch == MatchType.EXACT && containsRegexMetacharacter(rule.path))
        }
    }

    private fun containsRegexMetacharacter(value: String): Boolean = value.any { it in REGEX_METACHARACTERS }

    /**
     * Check if a request matches a rule using structured matching.
     * This is the core matching logic that compares each part separately.
     */
    private fun matchesStructured(rule: MockkRule, host: String, path: String, queryParams: Map<String, String>): Boolean {
        // 1. Match host (case-insensitive: hosts are)
        if (!matchesUrlPart(rule.host, rule.hostMatch, host, ignoreCase = true)) {
            logger.debug("   ❌ Host mismatch: rule='${rule.host}' (${rule.hostMatch}) vs actual='$host'")
            return false
        }

        // 2. Match path (case-sensitive: paths are)
        if (!matchesUrlPart(rule.path, rule.pathMatch, path, ignoreCase = false)) {
            logger.debug("   ❌ Path mismatch: rule='${rule.path}' (${rule.pathMatch}) vs actual='$path'")
            return false
        }

        // 3. Match query params
        for (ruleParam in rule.queryParams) {
            if (!ruleParam.required) {
                logger.debug("   ⏭️  Param '${ruleParam.key}' skipped (not required)")
                continue
            }

            val actualValue = queryParams[ruleParam.key]
            if (actualValue == null) {
                logger.debug("   ❌ Required param '${ruleParam.key}' not found in request")
                return false
            }

            when (ruleParam.matchType) {
                MatchType.EXACT -> {
                    if (ruleParam.value != actualValue) {
                        logger.debug("   ❌ Param '${ruleParam.key}' value mismatch: rule='${ruleParam.value}' vs actual='$actualValue'")
                        return false
                    }
                }
                MatchType.WILDCARD -> {
                    // Wildcard = accept any value, just check presence
                    logger.debug("   ✅ Param '${ruleParam.key}' matched (wildcard)")
                }
                MatchType.REGEX -> {
                    val regex = compilePattern(ruleParam.value, ignoreCase = false)
                    if (regex == null || !regex.matches(actualValue)) {
                        logger.debug("   ❌ Param '${ruleParam.key}' regex mismatch: pattern='${ruleParam.value}' vs actual='$actualValue'")
                        return false
                    }
                }
            }
        }

        logger.debug("   ✅ All checks passed!")
        return true
    }

    /**
     * Compare one URL part (host or path) against a rule's pattern using its explicit match mode.
     */
    private fun matchesUrlPart(pattern: String, matchType: MatchType, actual: String, ignoreCase: Boolean): Boolean {
        return when (matchType) {
            MatchType.EXACT -> pattern.equals(actual, ignoreCase = ignoreCase)
            MatchType.WILDCARD -> compilePattern(globToRegexPattern(pattern), ignoreCase)?.matches(actual) ?: false
            MatchType.REGEX -> compilePattern(pattern, ignoreCase)?.matches(actual) ?: false
        }
    }

    /** `*` matches any run of characters; everything else in the glob is literal. */
    private fun globToRegexPattern(glob: String): String =
        glob.split("*").joinToString(".*") { Regex.escape(it) }

    /** Returns null (= no match) for an invalid pattern instead of failing the whole lookup. */
    private fun compilePattern(pattern: String, ignoreCase: Boolean): Regex? {
        return try {
            if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
        } catch (e: Exception) {
            logger.warn("⚠️ Invalid pattern in mock rule: '$pattern' - treated as no match", e)
            null
        }
    }

    // ========== LISTENERS ==========

    private fun notifyRuleListeners(listeners: List<(MockkRule) -> Unit>, rule: MockkRule) {
        for (listener in listeners) {
            listener(rule)
        }
    }

    private fun notifyCollectionListeners(listeners: List<(MockkCollection) -> Unit>, collection: MockkCollection) {
        for (listener in listeners) {
            listener(collection)
        }
    }

    /**
     * Add listener for when a rule is added.
     */
    fun addRuleAddedListener(listener: (MockkRule) -> Unit) {
        ruleAddedListeners.add(listener)
    }

    /**
     * Add listener for when a rule is added, unregistered automatically when [parentDisposable]
     * is disposed. Preferred over [clearAllListeners]: a panel that scopes its listeners to itself
     * cannot leak them, nor wipe another panel's.
     */
    fun addRuleAddedListener(parentDisposable: Disposable, listener: (MockkRule) -> Unit) {
        ruleAddedListeners.add(listener)
        Disposer.register(parentDisposable, Disposable { ruleAddedListeners.remove(listener) })
    }

    /**
     * Add listener for when a rule is removed.
     */
    fun addRuleRemovedListener(listener: (MockkRule) -> Unit) {
        ruleRemovedListeners.add(listener)
    }

    /**
     * Add listener for when a rule is removed, scoped to [parentDisposable].
     */
    fun addRuleRemovedListener(parentDisposable: Disposable, listener: (MockkRule) -> Unit) {
        ruleRemovedListeners.add(listener)
        Disposer.register(parentDisposable, Disposable { ruleRemovedListeners.remove(listener) })
    }

    /**
     * Add listener for when an existing rule is edited in place via [updateRule].
     */
    fun addRuleUpdatedListener(listener: (MockkRule) -> Unit) {
        ruleUpdatedListeners.add(listener)
    }

    /**
     * Add listener for when an existing rule is edited in place, scoped to [parentDisposable].
     */
    fun addRuleUpdatedListener(parentDisposable: Disposable, listener: (MockkRule) -> Unit) {
        ruleUpdatedListeners.add(listener)
        Disposer.register(parentDisposable, Disposable { ruleUpdatedListeners.remove(listener) })
    }

    /**
     * Add listener for when a collection is added.
     */
    fun addCollectionAddedListener(listener: (MockkCollection) -> Unit) {
        collectionAddedListeners.add(listener)
    }

    /**
     * Add listener for when a collection is added, scoped to [parentDisposable].
     */
    fun addCollectionAddedListener(parentDisposable: Disposable, listener: (MockkCollection) -> Unit) {
        collectionAddedListeners.add(listener)
        Disposer.register(parentDisposable, Disposable { collectionAddedListeners.remove(listener) })
    }

    /**
     * Add listener for when a collection is removed.
     */
    fun addCollectionRemovedListener(listener: (MockkCollection) -> Unit) {
        collectionRemovedListeners.add(listener)
    }

    /**
     * Add listener for when a collection is removed, scoped to [parentDisposable].
     */
    fun addCollectionRemovedListener(parentDisposable: Disposable, listener: (MockkCollection) -> Unit) {
        collectionRemovedListeners.add(listener)
        Disposer.register(parentDisposable, Disposable { collectionRemovedListeners.remove(listener) })
    }

    /**
     * Clear all listeners. Called before re-registering to prevent duplicates
     * when the tool window is created multiple times by the IDE.
     *
     * Prefer the overloads taking a parent Disposable: they unregister exactly the listeners of
     * the component that goes away, instead of every listener in the project.
     */
    fun clearAllListeners() {
        ruleAddedListeners.clear()
        ruleRemovedListeners.clear()
        ruleUpdatedListeners.clear()
        collectionAddedListeners.clear()
        collectionRemovedListeners.clear()
    }

    // ========== IMPORT/EXPORT METHODS ==========

    /**
     * Export a single collection to JSON string.
     */
    fun exportCollection(collection: MockkCollection): String {
        return exportCollections(listOf(collection))
    }

    /**
     * Export multiple collections to JSON string.
     */
    fun exportCollections(collectionsToExport: List<MockkCollection>): String {
        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()

        val collectionsData = collectionsToExport.map { collection ->
            val collectionRules = getRulesInCollection(collection.id)
            val rulesData = collectionRules.map { rule ->
                MockkRuleData(
                    id = rule.id,
                    name = rule.name,
                    enabled = rule.enabled,
                    method = rule.method,
                    scheme = rule.scheme,
                    host = rule.host,
                    port = rule.port,
                    path = rule.path,
                    queryParams = rule.queryParams.toMutableList(),
                    statusCode = rule.statusCode,
                    headers = rule.headers,
                    content = rule.content,
                    hostMatch = rule.hostMatch,
                    pathMatch = rule.pathMatch
                )
            }

            MockkCollectionData(
                collection = collection,
                rules = rulesData
            )
        }

        val exportData = MockkCollectionExport(
            collections = collectionsData
        )

        logger.info("📤 Exported ${collectionsToExport.size} collection(s)")
        return gson.toJson(exportData)
    }

    /**
     * Import collections from JSON string.
     * Returns the list of imported collections.
     */
    fun importCollections(json: String, targetPackageName: String? = null, renameOnConflict: Boolean = true): List<MockkCollection> {
        try {
            val gson = com.google.gson.Gson()
            val exportData = gson.fromJson(json, MockkCollectionExport::class.java)

            logger.info("📥 Importing ${exportData.collections.size} collection(s) from JSON")
            logger.info("   Plugin version: ${exportData.pluginVersion}")
            logger.info("   Export date: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(exportData.exportDate))}")

            val importedCollections = mutableListOf<MockkCollection>()
            // Collected while holding the write lock, fired once it is released.
            val addedRules = mutableListOf<MockkRule>()

            stateLock.write {
                for (collectionData in exportData.collections) {
                    val collection = collectionData.collection

                    // Override package name if specified
                    if (targetPackageName != null) {
                        collection.packageName = targetPackageName
                    }

                    // Check for name conflicts
                    val existingCollection = collections.values.find { it.name == collection.name }
                    if (existingCollection != null && renameOnConflict) {
                        var counter = 2
                        var newName = "${collection.name} (Imported)"
                        while (collections.values.any { it.name == newName }) {
                            newName = "${collection.name} (Imported $counter)"
                            counter++
                        }
                        logger.info("   ⚠️  Name conflict detected: renaming '${collection.name}' to '$newName'")
                        collection.name = newName
                    }

                    // Generate new ID
                    collection.id = newId()

                    // Add collection
                    collections[collection.id] = collection
                    importedCollections.add(collection)

                    // Import rules
                    for (ruleData in collectionData.rules) {
                        val rule = ruleData.toRule(collection.id)
                        rules.add(rule)
                        addedRules.add(rule)
                    }

                    logger.info("   ✅ Imported collection '${collection.name}' with ${collectionData.rules.size} rule(s)")
                }
            }

            addedRules.forEach { rule -> notifyRuleListeners(ruleAddedListeners, rule) }
            importedCollections.forEach { collection -> notifyCollectionListeners(collectionAddedListeners, collection) }

            logger.info("✅ Import complete: ${importedCollections.size} collection(s), total ${exportData.collections.sumOf { it.rules.size }} rule(s)")
            return importedCollections

        } catch (e: Exception) {
            logger.error("Failed to import collections from JSON", e)
            throw IllegalArgumentException("Failed to parse JSON: ${e.message}", e)
        }
    }

    /**
     * Import a single collection from JSON string.
     */
    fun importCollection(json: String, targetPackageName: String? = null, renameOnConflict: Boolean = true): MockkCollection? {
        val imported = importCollections(json, targetPackageName, renameOnConflict)
        return imported.firstOrNull()
    }

    /** Build a store rule out of imported data, always with a fresh id. */
    private fun MockkRuleData.toRule(
        collectionId: String,
        nameSuffix: String = "",
        enabledOverride: Boolean? = null
    ): MockkRule = MockkRule(
        id = newId(),
        name = name + nameSuffix,
        enabled = enabledOverride ?: enabled,
        method = method,
        collectionId = collectionId,
        scheme = scheme,
        host = host,
        hostMatch = hostMatch,
        port = port,
        path = path,
        pathMatch = pathMatch,
        queryParams = queryParams.map { it.copy() }.toMutableList(),
        statusCode = statusCode,
        headers = headers,
        content = content
    )

    // ========== SMART IMPORT (merge with existing collections) ==========

    /** What to do with an imported rule whose endpoint already exists but whose response changed. */
    enum class ChangedRuleStrategy {
        REPLACE,    // update the existing rule with the imported response
        KEEP_BOTH,  // keep the existing rule and add the imported one as a new rule
        SKIP        // leave the existing rule untouched
    }

    /** Per-collection diff between an import file and the current store. */
    data class CollectionDiff(
        val incoming: MockkCollectionData,
        val existing: MockkCollection?, // matched by name; null = new collection
        val newRules: List<MockkRuleData>,
        val changedRules: List<Pair<MockkRuleData, MockkRule>>,
        val identicalRules: List<MockkRuleData>
    )

    /** Result of [analyzeImport]: the parsed export plus one diff per collection. */
    data class ImportAnalysis(
        val diffs: List<CollectionDiff>
    ) {
        val hasExistingCollections: Boolean get() = diffs.any { it.existing != null }
    }

    private fun endpointSignature(
        method: String, scheme: String, host: String, hostMatch: MatchType,
        path: String, pathMatch: MatchType, queryParams: List<QueryParam>
    ): String {
        val params = queryParams.sortedBy { it.key }
            .joinToString(",") { "${it.key}=${it.value}:${it.required}:${it.matchType}" }
        return "$method:$scheme:$host:$hostMatch:$path:$pathMatch:$params"
    }

    private fun signatureOf(rule: MockkRule) =
        endpointSignature(rule.method, rule.scheme, rule.host, rule.hostMatch, rule.path, rule.pathMatch, rule.queryParams)

    private fun signatureOf(rule: MockkRuleData) =
        endpointSignature(rule.method, rule.scheme, rule.host, rule.hostMatch, rule.path, rule.pathMatch, rule.queryParams)

    private fun sameResponse(imported: MockkRuleData, existing: MockkRule): Boolean =
        imported.statusCode == existing.statusCode &&
                imported.headers == existing.headers &&
                imported.content == existing.content

    /**
     * Parse an export JSON and diff it against the current store, matching
     * collections by name and rules by endpoint signature.
     */
    fun analyzeImport(json: String): ImportAnalysis {
        val gson = com.google.gson.Gson()
        val exportData = gson.fromJson(json, MockkCollectionExport::class.java)

        // One snapshot for the whole diff, so every collection is compared against the same store.
        val (collectionsSnapshot, rulesSnapshot) = stateLock.read {
            collections.values.toList() to rules.toList()
        }

        val diffs = exportData.collections.map { collectionData ->
            val existing = collectionsSnapshot.find { it.name == collectionData.collection.name }
            if (existing == null) {
                CollectionDiff(collectionData, null, collectionData.rules, emptyList(), emptyList())
            } else {
                val existingBySignature = rulesSnapshot
                    .filter { it.collectionId == existing.id }
                    .associateBy { signatureOf(it) }
                val newRules = mutableListOf<MockkRuleData>()
                val changedRules = mutableListOf<Pair<MockkRuleData, MockkRule>>()
                val identicalRules = mutableListOf<MockkRuleData>()

                for (incomingRule in collectionData.rules) {
                    val match = existingBySignature[signatureOf(incomingRule)]
                    when {
                        match == null -> newRules.add(incomingRule)
                        sameResponse(incomingRule, match) -> identicalRules.add(incomingRule)
                        else -> changedRules.add(incomingRule to match)
                    }
                }
                CollectionDiff(collectionData, existing, newRules, changedRules, identicalRules)
            }
        }

        return ImportAnalysis(diffs)
    }

    /** Summary of what a merge import actually did. */
    data class MergeResult(
        var collectionsCreated: Int = 0,
        var rulesAdded: Int = 0,
        var rulesReplaced: Int = 0,
        var rulesKeptBoth: Int = 0,
        var rulesSkipped: Int = 0
    )

    /**
     * Apply a merge import: missing rules are added into the matched existing
     * collections, changed rules follow [strategy], identical rules are skipped,
     * and collections with no match are created whole.
     */
    fun applyMergeImport(analysis: ImportAnalysis, strategy: ChangedRuleStrategy): MergeResult {
        val result = MergeResult()
        // Notifications are collected here and fired once the write lock is released.
        val addedRules = mutableListOf<MockkRule>()
        val addedCollections = mutableListOf<MockkCollection>()
        val updatedRules = mutableListOf<MockkRule>()

        stateLock.write {
            fun addRuleTo(
                collectionId: String,
                data: MockkRuleData,
                nameSuffix: String = "",
                enabledOverride: Boolean? = null
            ) {
                val rule = data.toRule(collectionId, nameSuffix, enabledOverride)
                rules.add(rule)
                addedRules.add(rule)
            }

            for (diff in analysis.diffs) {
                if (diff.existing == null) {
                    // Brand-new collection: create it whole
                    val collection = diff.incoming.collection
                    collection.id = newId()
                    collections[collection.id] = collection
                    addedCollections.add(collection)
                    result.collectionsCreated++
                    diff.incoming.rules.forEach { addRuleTo(collection.id, it) }
                    result.rulesAdded += diff.incoming.rules.size
                    continue
                }

                // Merge into the existing collection
                diff.newRules.forEach { addRuleTo(diff.existing.id, it) }
                result.rulesAdded += diff.newRules.size

                for ((incoming, existing) in diff.changedRules) {
                    when (strategy) {
                        ChangedRuleStrategy.REPLACE -> {
                            existing.name = incoming.name
                            existing.statusCode = incoming.statusCode
                            existing.headers = incoming.headers
                            existing.content = incoming.content
                            updatedRules.add(existing)
                            result.rulesReplaced++
                        }
                        ChangedRuleStrategy.KEEP_BOTH -> {
                            // Added DISABLED: two enabled rules with the same endpoint would
                            // leave the imported one enabled-but-never-matching (first enabled
                            // rule wins) and it would be silently auto-disabled on next load.
                            addRuleTo(diff.existing.id, incoming, nameSuffix = " (imported)", enabledOverride = false)
                            result.rulesKeptBoth++
                        }
                        ChangedRuleStrategy.SKIP -> result.rulesSkipped++
                    }
                }
                result.rulesSkipped += diff.identicalRules.size
            }
        }

        addedRules.forEach { rule -> notifyRuleListeners(ruleAddedListeners, rule) }
        addedCollections.forEach { collection -> notifyCollectionListeners(collectionAddedListeners, collection) }
        updatedRules.forEach { rule -> notifyRuleListeners(ruleUpdatedListeners, rule) }

        logger.info(
            "📥 Merge import: +${result.collectionsCreated} collection(s), +${result.rulesAdded} rule(s), " +
                    "${result.rulesReplaced} replaced, ${result.rulesKeptBoth} kept-both, ${result.rulesSkipped} skipped"
        )
        return result
    }

    /**
     * Data class representing a Mockk rule.
     * All properties are var with defaults for XML serialization.
     */
    data class MockkRule(
        var id: String = "",
        var name: String = "",
        var enabled: Boolean = true,
        var method: String = "",
        var collectionId: String = "",  // Collection this rule belongs to

        // Structured URL format
        var scheme: String = "https",
        var host: String = "",
        var port: Int? = null,
        var path: String = "",
        var queryParams: MutableList<QueryParam> = mutableListOf(),

        // How host/path are compared. Rules stored before these existed load as EXACT, which is
        // how the pre-flight mock lookup has always treated them.
        var hostMatch: MatchType = MatchType.EXACT,
        var pathMatch: MatchType = MatchType.EXACT,

        // Response
        var statusCode: Int = 200,
        var headers: Map<String, String> = emptyMap(),
        var content: String = ""
    ) {
        /**
         * Gets the full URL (for display purposes)
         */
        fun getDisplayUrl(): String {
            return StructuredUrl(scheme, host, port, path, queryParams, hostMatch, pathMatch).toFullUrl()
        }
    }
}
