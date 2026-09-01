package com.sergiy.dev.mockkhttp.model

/**
 * Represents a query string parameter with matching options
 */
data class QueryParam(
    var key: String = "",
    var value: String = "",
    var required: Boolean = true,
    var matchType: MatchType = MatchType.EXACT
)

/**
 * Matching type for a single piece of a rule (query param value, host or path).
 *
 * The meaning of [WILDCARD] differs by field on purpose: a query param has no useful
 * pattern to glob against (the rule only cares that the key is present), while a host
 * or a path is exactly where `*` patterns are worth having.
 */
enum class MatchType {
    EXACT,      // Query param: value must be equal. Host/path: literal comparison (host is case-insensitive)
    WILDCARD,   // Query param: any value (only presence is checked). Host/path: `*` matches any run of characters
    REGEX       // Full-match regular expression against the value
}

/**
 * Represents a collection of mock rules grouped by app/environment
 */
data class MockkCollection(
    var id: String = "",
    var name: String = "",
    var packageName: String = "",  // Associated app package
    var description: String = "",  // Optional description
    var enabled: Boolean = true,
    var createdAt: Long = System.currentTimeMillis(),
    var version: String = "1.7.1"  // Plugin version when created
)

/**
 * Format for exporting/importing collections
 */
data class MockkCollectionExport(
    val pluginVersion: String = "1.7.1",
    val exportDate: Long = System.currentTimeMillis(),
    val collections: List<MockkCollectionData> = emptyList()
)

/**
 * Collection data for export (includes rules)
 */
data class MockkCollectionData(
    val collection: MockkCollection,
    val rules: List<MockkRuleData>
)

/**
 * Rule data for export (simplified from MockkRulesStore.MockkRule)
 */
data class MockkRuleData(
    var id: String = "",
    var name: String = "",
    var enabled: Boolean = true,
    var method: String = "",
    var scheme: String = "https",
    var host: String = "",
    var port: Int? = null,
    var path: String = "",
    var queryParams: MutableList<QueryParam> = mutableListOf(),
    var statusCode: Int = 200,
    var headers: Map<String, String> = emptyMap(),
    var content: String = "",
    // Export files written before per-field match modes existed have no such keys; Gson keeps
    // these defaults, which is the same EXACT behaviour those rules had in the store.
    var hostMatch: MatchType = MatchType.EXACT,
    var pathMatch: MatchType = MatchType.EXACT
)

/**
 * Represents a structured URL for matching
 */
data class StructuredUrl(
    var scheme: String = "https",
    var host: String = "",
    var port: Int? = null,
    var path: String = "",
    var queryParams: MutableList<QueryParam> = mutableListOf(),
    // How the host/path above should be compared against a real request. Trailing with
    // defaults so every existing positional construction keeps compiling.
    var hostMatch: MatchType = MatchType.EXACT,
    var pathMatch: MatchType = MatchType.EXACT
) {
    /**
     * Converts the structured URL to a complete string
     */
    fun toFullUrl(): String {
        val portStr = if (port != null && port != 80 && port != 443) ":$port" else ""
        val queryStr = if (queryParams.isNotEmpty()) {
            "?" + queryParams.joinToString("&") { "${it.key}=${it.value}" }
        } else ""
        return "$scheme://$host$portStr$path$queryStr"
    }

    companion object {
        /**
         * Parses a URL string to StructuredUrl
         */
        fun fromUrl(url: String): StructuredUrl {
            try {
                // Use URI.create().toURL() instead of deprecated URL(String) constructor
                val javaUrl = java.net.URI.create(url).toURL()
                val params = mutableListOf<QueryParam>()

                javaUrl.query?.split("&")?.forEach { param ->
                    val parts = param.split("=", limit = 2)
                    if (parts.isNotEmpty()) {
                        val key = parts[0]
                        val value = if (parts.size > 1) parts[1] else ""
                        // Mark as required=true and EXACT match when creating from captured flow
                        params.add(QueryParam(key, value, required = true, matchType = MatchType.EXACT))
                    }
                }

                return StructuredUrl(
                    scheme = javaUrl.protocol,
                    host = javaUrl.host,
                    port = if (javaUrl.port != -1) javaUrl.port else null,
                    path = javaUrl.path ?: "",
                    queryParams = params
                )
            } catch (_: Exception) {
                // If parsing fails, return empty structure
                return StructuredUrl()
            }
        }
    }
}
