package com.sergiy.dev.mockkhttp.store

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
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

    private val logger = MockkHttpLogger.getInstance(project)
    private val rules = mutableListOf<MockkRule>()
    private val ruleAddedListeners = mutableListOf<(MockkRule) -> Unit>()
    private val ruleRemovedListeners = mutableListOf<(MockkRule) -> Unit>()

    companion object {
        fun getInstance(project: Project): MockkRulesStore {
            return project.getService(MockkRulesStore::class.java)
        }
    }

    /**
     * State for persistence.
     */
    data class State(
        var rules: MutableList<MockkRule> = mutableListOf()
    )

    override fun getState(): State {
        return State(rules = rules.toMutableList())
    }

    override fun loadState(state: State) {
        rules.clear()
        rules.addAll(state.rules)
        logger.info("📚 Loaded ${rules.size} mock rules from storage")
    }

    /**
     * Add a new mock rule with structured URL format.
     */
    fun addRule(
        name: String,
        method: String,
        structuredUrl: StructuredUrl,
        mockResponse: ModifiedResponseData
    ): MockkRule {
        val rule = MockkRule(
            id = System.currentTimeMillis().toString(),
            name = name,
            enabled = true,
            method = method,
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
        logger.info("➕ Added mock rule: $name")

        // Notify listeners
        ruleAddedListeners.forEach { it(rule) }

        return rule
    }

    /**
     * Remove a rule.
     */
    fun removeRule(rule: MockkRule) {
        if (rules.remove(rule)) {
            logger.info("➖ Removed mock rule: ${rule.name}")
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
        logger.info("${if (enabled) "✅" else "⏸"} Rule ${rule.name} ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Find a matching rule for a given request using structured matching.
     * Returns the actual MockkRule if a match is found.
     */
    fun findMatchingRuleObject(method: String, host: String, path: String, queryParams: Map<String, String>): MockkRule? {
        logger.debug("🔍 Looking for match:")
        logger.debug("   Method: $method")
        logger.debug("   Host: $host")
        logger.debug("   Path: $path")
        logger.debug("   Query Params: $queryParams")
        logger.debug("🔍 Available rules: ${rules.size}")

        // Find matching rule without verbose logging
        for ((index, rule) in rules.withIndex()) {
            if (!rule.enabled) {
                continue
            }

            if (!rule.method.equals(method, ignoreCase = true)) {
                continue
            }

            // Evaluate match with minimal logging
            val matches = matchesStructuredQuiet(rule, host, path, queryParams)

            if (matches) {
                // Only log the winning rule
                logger.debug("  ✅ Rule $index: ${rule.name}")
                logger.debug("     Method: ${rule.method}, Host: ${rule.host}, Path: ${rule.path}")
                logger.debug("     Query Params: ${rule.queryParams.map { "${it.key}=${it.value}" }}")
                logger.debug("✅ MATCHED!")
                return rule
            }
        }

        // No match found - log all checked rules
        logger.debug("❌ No matching rule found")
        logger.debug("   Checked ${rules.count { it.enabled && it.method.equals(method, ignoreCase = true) }} enabled rule(s)")
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
            logger.debug("      ❌ Host mismatch: rule=${rule.host} actual=$host")
            return false
        }

        // 2. Match path (exact match)
        if (rule.path != path) {
            logger.debug("      ❌ Path mismatch: rule=${rule.path} actual=$path")
            return false
        }

        // 3. Match query params
        for (ruleParam in rule.queryParams) {
            if (ruleParam.required) {
                val actualValue = queryParams[ruleParam.key]
                if (actualValue == null) {
                    logger.debug("      ❌ Missing required param: ${ruleParam.key}")
                    return false
                }

                // Check value based on match type
                when (ruleParam.matchType) {
                    MatchType.EXACT -> {
                        if (ruleParam.value != actualValue) {
                            logger.debug("      ❌ Param value mismatch: ${ruleParam.key}")
                            logger.debug("         Expected: ${ruleParam.value}")
                            logger.debug("         Actual: $actualValue")
                            return false
                        }
                    }
                    MatchType.WILDCARD -> {
                        // Wildcard = accept any value, just check presence
                        logger.debug("      ✅ Param ${ruleParam.key} matched (wildcard)")
                    }
                    MatchType.REGEX -> {
                        // Future: regex matching
                        try {
                            if (!Regex(ruleParam.value).matches(actualValue)) {
                                logger.debug("      ❌ Param regex mismatch: ${ruleParam.key}")
                                return false
                            }
                        } catch (_: Exception) {
                            logger.debug("      ❌ Invalid regex for param: ${ruleParam.key}")
                            return false
                        }
                    }
                }
            }
        }

        // All checks passed!
        logger.debug("      ✅ All checks passed")
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

    /**
     * Data class representing a Mockk rule.
     * All properties are var with defaults for XML serialization.
     */
    data class MockkRule(
        var id: String = "",
        var name: String = "",
        var enabled: Boolean = true,
        var method: String = "",

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
