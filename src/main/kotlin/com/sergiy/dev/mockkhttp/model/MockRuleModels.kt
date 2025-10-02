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
 * Matching type for parameters
 */
enum class MatchType {
    EXACT,      // Value must be exact
    WILDCARD,   // Any value (ignores the parameter)
    REGEX       // Regular expression (future)
}

/**
 * Represents a structured URL for matching
 */
data class StructuredUrl(
    var scheme: String = "https",
    var host: String = "",
    var port: Int? = null,
    var path: String = "",
    var queryParams: MutableList<QueryParam> = mutableListOf()
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
                        params.add(QueryParam(key, value, required = false, matchType = MatchType.WILDCARD))
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
