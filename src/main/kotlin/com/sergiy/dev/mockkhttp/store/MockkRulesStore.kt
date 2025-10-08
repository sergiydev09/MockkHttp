package com.sergiy.dev.mockkhttp.store

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.sergiy.dev.mockkhttp.model.ModifiedResponseData
import com.sergiy.dev.mockkhttp.model.QueryParam
import com.sergiy.dev.mockkhttp.model.StructuredUrl
import com.sergiy.dev.mockkhttp.model.MatchType

/**
 * Store for managing Mockk rules.
 * Allows adding, removing, enabling/disabling rules.
 * Rules are persisted to XML storage.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "MockkRulesStore",
    storages = [Storage("mockkHttpRules.xml")]
)
class MockkRulesStore(project: Project) : PersistentStateComponent<MockkRulesStore.State> {

    private val collections = mutableMapOf<String, com.sergiy.dev.mockkhttp.model.MockkCollection>()
    private val rules = mutableListOf<MockkRule>()
    private val ruleAddedListeners = mutableListOf<(MockkRule) -> Unit>()
    private val ruleRemovedListeners = mutableListOf<(MockkRule) -> Unit>()
    private val collectionAddedListeners = mutableListOf<(com.sergiy.dev.mockkhttp.model.MockkCollection) -> Unit>()
    private val collectionRemovedListeners = mutableListOf<(com.sergiy.dev.mockkhttp.model.MockkCollection) -> Unit>()

    companion object {
        fun getInstance(project: Project): MockkRulesStore {
            return project.getService(MockkRulesStore::class.java)
        }
    }

    /**
     * State for persistence.
     */
    data class State(
        var collections: MutableList<com.sergiy.dev.mockkhttp.model.MockkCollection> = mutableListOf(),
        var rules: MutableList<MockkRule> = mutableListOf()
    )

    override fun getState(): State {
        return State(
            collections = collections.values.toMutableList(),
            rules = rules.toMutableList()
        )
    }

    override fun loadState(state: State) {
        collections.clear()
        rules.clear()

        // Load collections
        state.collections.forEach { collection ->
            collections[collection.id] = collection
        }

        // Load rules
        rules.addAll(state.rules)

        // Migrate old rules without collectionId to "Default" collection
        migrateOldRulesToDefaultCollection()

    }

    /**
     * Migrates rules that don't have a collectionId to a "Default" collection.
     * This ensures backward compatibility with rules created before collections were introduced.
     */
    private fun migrateOldRulesToDefaultCollection() {
        val rulesWithoutCollection = rules.filter { it.collectionId.isEmpty() }

        if (rulesWithoutCollection.isNotEmpty()) {

            // Find or create "Default" collection
            val defaultCollection = collections.values.find { it.name == "Default" }
                ?: run {
                    val newDefault = com.sergiy.dev.mockkhttp.model.MockkCollection(
                        id = "collection_default_" + System.currentTimeMillis(),
                        name = "Default",
                        packageName = "",
                        description = "Migrated from previous version",
                        enabled = true
                    )
                    collections[newDefault.id] = newDefault
                    newDefault
                }

            // Assign collection to old rules
            rulesWithoutCollection.forEach { rule ->
                rule.collectionId = defaultCollection.id
            }

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
            id = "rule_" + System.currentTimeMillis(),
            name = name,
            enabled = true,
            method = method,
            collectionId = collectionId,
            scheme = structuredUrl.scheme,
            host = structuredUrl.host,
            port = structuredUrl.port,
            path = structuredUrl.path,
            queryParams = structuredUrl.queryParams,
            statusCode = mockResponse.statusCode ?: 200,
            headers = mockResponse.headers ?: emptyMap(),
            content = mockResponse.content ?: ""
        )

        rules.add(rule)

        // Notify listeners
        ruleAddedListeners.forEach { it(rule) }

        return rule
    }

    // ========== COLLECTION METHODS ==========

    /**
     * Add a new collection.
     */
    fun addCollection(
        name: String,
        packageName: String,
        description: String = ""
    ): com.sergiy.dev.mockkhttp.model.MockkCollection {
        val collection = com.sergiy.dev.mockkhttp.model.MockkCollection(
            id = "collection_" + System.currentTimeMillis(),
            name = name,
            packageName = packageName,
            description = description,
            enabled = true
        )

        collections[collection.id] = collection

        // Notify listeners
        collectionAddedListeners.forEach { it(collection) }

        return collection
    }

    /**
     * Remove a collection and optionally its rules.
     */
    fun removeCollection(collection: com.sergiy.dev.mockkhttp.model.MockkCollection, removeRules: Boolean = true) {
        collections.remove(collection.id)

        if (removeRules) {
            val rulesToRemove = rules.filter { it.collectionId == collection.id }
            rulesToRemove.forEach { rule ->
                rules.remove(rule)
                ruleRemovedListeners.forEach { listener -> listener(rule) }
            }
        }

        // Notify listeners
        collectionRemovedListeners.forEach { it(collection) }
    }

    /**
     * Get all collections.
     */
    fun getAllCollections(): List<com.sergiy.dev.mockkhttp.model.MockkCollection> {
        return collections.values.toList()
    }

    /**
     * Get collections by package name.
     */
    fun getCollectionsByPackage(packageName: String): List<com.sergiy.dev.mockkhttp.model.MockkCollection> {
        return collections.values.filter { it.packageName == packageName }
    }

    /**
     * Get a collection by ID.
     */
    fun getCollection(collectionId: String): com.sergiy.dev.mockkhttp.model.MockkCollection? {
        return collections[collectionId]
    }

    /**
     * Get rules in a specific collection.
     */
    fun getRulesInCollection(collectionId: String): List<MockkRule> {
        return rules.filter { it.collectionId == collectionId }
    }

    /**
     * Move a rule to a different collection.
     */
    fun moveRule(rule: MockkRule, targetCollectionId: String) {
        rule.collectionId = targetCollectionId
    }

    /**
     * Duplicate a rule into a target collection.
     */
    fun duplicateRule(rule: MockkRule, targetCollectionId: String): MockkRule {
        val duplicated = rule.copy(
            id = "rule_" + System.currentTimeMillis(),
            collectionId = targetCollectionId
        )
        rules.add(duplicated)

        // Notify listeners
        ruleAddedListeners.forEach { it(duplicated) }

        return duplicated
    }

    /**
     * Update collection properties.
     */
    fun updateCollection(collectionId: String, name: String? = null, description: String? = null, enabled: Boolean? = null) {
        val collection = collections[collectionId] ?: return

        name?.let { collection.name = it }
        description?.let { collection.description = it }
        enabled?.let { collection.enabled = it }

    }

    /**
     * Add listener for when a collection is added.
     */
    fun addCollectionAddedListener(listener: (com.sergiy.dev.mockkhttp.model.MockkCollection) -> Unit) {
        collectionAddedListeners.add(listener)
    }

    /**
     * Add listener for when a collection is removed.
     */
    fun addCollectionRemovedListener(listener: (com.sergiy.dev.mockkhttp.model.MockkCollection) -> Unit) {
        collectionRemovedListeners.add(listener)
    }

    /**
     * Remove a rule.
     */
    fun removeRule(rule: MockkRule) {
        if (rules.remove(rule)) {
            ruleRemovedListeners.forEach { it(rule) }
        }
    }

    /**
     * Get all rules.
     */
    fun getAllRules(): List<MockkRule> {
        return rules.toList()
    }

    /**
     * Enable or disable a rule.
     */
    fun setRuleEnabled(rule: MockkRule, enabled: Boolean) {
        rule.enabled = enabled
    }

    /**
     * Find a matching rule for a given request using structured matching.
     * Only searches in enabled collections.
     * Returns the actual MockkRule if a match is found.
     */
    fun findMatchingRuleObject(method: String, host: String, path: String, queryParams: Map<String, String>): MockkRule? {

        // Get enabled collections
        val enabledCollections = collections.values.filter { it.enabled }

        // Find matching rule without verbose logging
        for ((index, rule) in rules.withIndex()) {
            // Skip if rule not enabled
            if (!rule.enabled) {
                continue
            }

            // Skip if rule's collection is disabled
            val ruleCollection = collections[rule.collectionId]
            if (ruleCollection == null || !ruleCollection.enabled) {
                continue
            }

            // Skip if method doesn't match
            if (!rule.method.equals(method, ignoreCase = true)) {
                continue
            }

            // Evaluate match with minimal logging
            val matches = matchesStructuredQuiet(rule, host, path, queryParams)

            if (matches) {
                // Only log the winning rule
                return rule
            }
        }

        // No match found - log stats
        val eligibleRules = rules.count { rule ->
            rule.enabled &&
            rule.method.equals(method, ignoreCase = true) &&
            collections[rule.collectionId]?.enabled == true
        }
        return null
    }

    /**
     * Quiet version of matchesStructured that doesn't log intermediate steps.
     */
    private fun matchesStructuredQuiet(rule: MockkRule, host: String, path: String, queryParams: Map<String, String>): Boolean {
        // 1. Match host (case-insensitive)
        if (!rule.host.equals(host, ignoreCase = true)) {
            return false
        }

        // 2. Match path (exact match)
        if (rule.path != path) {
            return false
        }

        // 3. Match query params
        for (ruleParam in rule.queryParams) {
            if (ruleParam.required) {
                val actualValue = queryParams[ruleParam.key]
                if (actualValue == null) {
                    return false
                }

                // Check value based on match type
                when (ruleParam.matchType) {
                    MatchType.EXACT -> {
                        if (ruleParam.value != actualValue) {
                            return false
                        }
                    }
                    MatchType.WILDCARD -> {
                        // Wildcard = accept any value, just check presence
                    }
                    MatchType.REGEX -> {
                        try {
                            if (!Regex(ruleParam.value).matches(actualValue)) {
                                return false
                            }
                        } catch (_: Exception) {
                            return false
                        }
                    }
                }
            }
        }

        return true
    }

    /**
     * Check if a request matches a rule using structured matching.
     * This is the core matching logic that compares each part separately.
     */
    private fun matchesStructured(rule: MockkRule, host: String, path: String, queryParams: Map<String, String>): Boolean {
        // 1. Match host (case-insensitive)
        if (!rule.host.equals(host, ignoreCase = true)) {
            return false
        }

        // 2. Match path (exact match)
        if (rule.path != path) {
            return false
        }

        // 3. Match query params
        for (ruleParam in rule.queryParams) {
            if (ruleParam.required) {
                val actualValue = queryParams[ruleParam.key]
                if (actualValue == null) {
                    return false
                }

                // Check value based on match type
                when (ruleParam.matchType) {
                    MatchType.EXACT -> {
                        if (ruleParam.value != actualValue) {
                            return false
                        }
                    }
                    MatchType.WILDCARD -> {
                        // Wildcard = accept any value, just check presence
                    }
                    MatchType.REGEX -> {
                        // Future: regex matching
                        try {
                            if (!Regex(ruleParam.value).matches(actualValue)) {
                                return false
                            }
                        } catch (_: Exception) {
                            return false
                        }
                    }
                }
            }
        }

        // All checks passed!
        return true
    }

    /**
     * Add listener for when a rule is added.
     */
    fun addRuleAddedListener(listener: (MockkRule) -> Unit) {
        ruleAddedListeners.add(listener)
    }

    /**
     * Add listener for when a rule is removed.
     */
    fun addRuleRemovedListener(listener: (MockkRule) -> Unit) {
        ruleRemovedListeners.add(listener)
    }

    // ========== IMPORT/EXPORT METHODS ==========

    /**
     * Export a single collection to JSON string.
     */
    fun exportCollection(collection: com.sergiy.dev.mockkhttp.model.MockkCollection): String {
        return exportCollections(listOf(collection))
    }

    /**
     * Export multiple collections to JSON string.
     */
    fun exportCollections(collectionsToExport: List<com.sergiy.dev.mockkhttp.model.MockkCollection>): String {
        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()

        val collectionsData = collectionsToExport.map { collection ->
            val collectionRules = getRulesInCollection(collection.id)
            val rulesData = collectionRules.map { rule ->
                com.sergiy.dev.mockkhttp.model.MockkRuleData(
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
                    content = rule.content
                )
            }

            com.sergiy.dev.mockkhttp.model.MockkCollectionData(
                collection = collection,
                rules = rulesData
            )
        }

        val exportData = com.sergiy.dev.mockkhttp.model.MockkCollectionExport(
            collections = collectionsData
        )

        return gson.toJson(exportData)
    }

    /**
     * Import collections from JSON string.
     * Returns the list of imported collections.
     */
    fun importCollections(json: String, targetPackageName: String? = null, renameOnConflict: Boolean = true): List<com.sergiy.dev.mockkhttp.model.MockkCollection> {
        try {
            val gson = com.google.gson.Gson()
            val exportData = gson.fromJson(json, com.sergiy.dev.mockkhttp.model.MockkCollectionExport::class.java)


            val importedCollections = mutableListOf<com.sergiy.dev.mockkhttp.model.MockkCollection>()

            for (collectionData in exportData.collections) {
                var collection = collectionData.collection

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
                    collection.name = newName
                }

                // Generate new ID
                val newCollectionId = "collection_" + System.currentTimeMillis() + "_" + (Math.random() * 1000000).toInt().toString(36)
                collection.id = newCollectionId

                // Add collection
                collections[newCollectionId] = collection
                importedCollections.add(collection)

                // Import rules
                for (ruleData in collectionData.rules) {
                    val newRuleId = "rule_" + System.currentTimeMillis() + "_" + (Math.random() * 1000000).toInt().toString(36)
                    val rule = MockkRule(
                        id = newRuleId,
                        name = ruleData.name,
                        enabled = ruleData.enabled,
                        method = ruleData.method,
                        collectionId = newCollectionId,
                        scheme = ruleData.scheme,
                        host = ruleData.host,
                        port = ruleData.port,
                        path = ruleData.path,
                        queryParams = ruleData.queryParams,
                        statusCode = ruleData.statusCode,
                        headers = ruleData.headers,
                        content = ruleData.content
                    )

                    rules.add(rule)

                    // Notify listeners for each rule
                    ruleAddedListeners.forEach { listener -> listener(rule) }
                }


                // Notify listeners for collection
                collectionAddedListeners.forEach { it(collection) }
            }

            return importedCollections

        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse JSON: ${e.message}", e)
        }
    }

    /**
     * Import a single collection from JSON string.
     */
    fun importCollection(json: String, targetPackageName: String? = null, renameOnConflict: Boolean = true): com.sergiy.dev.mockkhttp.model.MockkCollection? {
        val imported = importCollections(json, targetPackageName, renameOnConflict)
        return imported.firstOrNull()
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
        var collectionId: String = "",  // NEW: Collection this rule belongs to

        // Structured URL format
        var scheme: String = "https",
        var host: String = "",
        var port: Int? = null,
        var path: String = "",
        var queryParams: MutableList<QueryParam> = mutableListOf(),

        // Response
        var statusCode: Int = 200,
        var headers: Map<String, String> = emptyMap(),
        var content: String = ""
    ) {
        /**
         * Gets the full URL (for display purposes)
         */
        fun getDisplayUrl(): String {
            return StructuredUrl(scheme, host, port, path, queryParams).toFullUrl()
        }
    }
}
