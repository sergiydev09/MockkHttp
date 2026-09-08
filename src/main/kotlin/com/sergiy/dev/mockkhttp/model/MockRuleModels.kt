package com.sergiy.dev.mockkhttp.model

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * Represents a query string parameter with matching options
 */
data class QueryParam(
    var key: String = "",
    var value: String = "",
    /**
     * Whether the request must carry this parameter at all.
     *
     * `false` means **absent is OK** — not "never looked at". When the parameter IS present its
     * value still goes through [matchType], which is how the flow matcher behind `await_flow` has
     * always treated it. Use [MatchType.WILDCARD] to accept any value.
     */
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
    REGEX;      // Full-match regular expression against the value

    /**
     * True when the stored text is a PATTERN rather than a literal URL component.
     *
     * A pattern is not a URL: `/v1/orders/[0-9]+` makes `java.net.URI` throw, and `api\.acme\.com`
     * must survive verbatim. Everything that parses a rule's URL has to ask this first.
     */
    val isPattern: Boolean get() = this != EXACT

    companion object {
        /**
         * The exact spellings the control plane accepts, straight from the enum.
         *
         * This is the single source of truth for the wire vocabulary: a value that exists here is
         * accepted, and anything that publishes the list (the REST DTO constants, the MCP schema)
         * must be able to name all of them. Hand-copied subsets are what made `WILDCARD` a value
         * the server accepted and the schema called invalid.
         */
        val WIRE_NAMES: List<String> get() = MatchType.entries.map { it.name }

        /** Case- and whitespace-insensitive, because the wire value comes from a model. */
        fun parse(raw: String?): MatchType? {
            val normalized = raw?.trim() ?: return null
            return MatchType.entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
        }
    }
}

/**
 * The body of a mock response, on the one path where bytes and text can disagree.
 *
 * Everything downstream of a rule is a `String`: the store persists `content` in XML through
 * `PersistentStateComponent`, the CHECK_MOCK reply carries it as a JSON string, and both clients
 * turn it back into bytes with UTF-8 (`MockkHttpInterceptor.buildMockResponse` →
 * `String.toResponseBody`, and `_BufferedResponse.fromMock` → `utf8.encode`). A payload that is not
 * valid UTF-8 therefore CANNOT reach the app whatever the store does with it — so it is refused at
 * the door instead of being decoded lossily and served as a different sequence of bytes.
 */
object MockBody {

    /** Result of reading a `body_base64` argument. */
    sealed interface Decoded {
        /** The payload survives the String round trip byte for byte. */
        data class Text(val value: String) : Decoded

        /** Not base64 at all. */
        data object NotBase64 : Decoded

        /**
         * Valid base64, but the bytes are not UTF-8 text — a PNG, a protobuf, gzip.
         * [firstBytesHex] is there so the caller can recognise the payload in the error.
         */
        data class NotText(val byteCount: Int, val firstBytesHex: String) : Decoded
    }

    /**
     * Decode a base64 body, refusing anything a `String` would silently mangle.
     *
     * `String(bytes, UTF_8)` replaces every malformed byte with U+FFFD, so a 70-byte PNG came back
     * out of the store as 94 different bytes and the corruption was invisible in the export. A
     * strict decoder turns that into an error the caller can act on.
     */
    fun decodeBase64(encoded: String): Decoded {
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return Decoded.NotBase64
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            Decoded.Text(decoder.decode(ByteBuffer.wrap(bytes)).toString())
        } catch (_: CharacterCodingException) {
            Decoded.NotText(bytes.size, hexPreview(bytes))
        }
    }

    /**
     * The number of bytes the app will actually receive for [text].
     *
     * The one honest answer for a `body_bytes` field: the store holds characters, the socket carries
     * UTF-8, and the two only agree if you measure the encoding.
     */
    fun utf8Length(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /**
     * Whether [text] carries U+FFFD, the fingerprint of a payload that was already destroyed before
     * it reached us — a binary response body captured over the wire protocol, typically. Not proof
     * (a document may legitimately contain the character), which is why it only ever warns.
     */
    fun looksLossy(text: String): Boolean = text.any { it == REPLACEMENT_CHAR }

    /** U+FFFD, written as an escape so the source file's own encoding cannot be the bug. */
    private const val REPLACEMENT_CHAR: Char = '\uFFFD'

    private fun hexPreview(bytes: ByteArray): String =
        bytes.take(8).joinToString(" ") { byte -> "%02X".format(byte.toInt() and 0xFF) }
}

/** Whether [code] is a status an HTTP client can be handed. RFC 9110 defines 100..599. */
object HttpStatus {
    val VALID_RANGE: IntRange = 100..599
    fun isValid(code: Int): Boolean = code in VALID_RANGE
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
    var version: String = com.sergiy.dev.mockkhttp.MockkHttpBuild.VERSION  // Plugin version when created
)

/**
 * Format for exporting/importing collections
 */
data class MockkCollectionExport(
    val pluginVersion: String = com.sergiy.dev.mockkhttp.MockkHttpBuild.VERSION,
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
 * The outcome of building the matching half of a rule out of what a caller sent.
 *
 * An [Err] names the wire field to fix, because "INVALID_URL" on its own is what left a caller
 * guessing which of `url`, `host` and `path_match` it should have written differently.
 */
sealed interface UrlSpec {
    data class Ok(val url: StructuredUrl) : UrlSpec
    data class Err(val field: String, val message: String, val hint: String) : UrlSpec
}

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

        /**
         * Build the matching half of a rule from any combination of `url`, `host` and `path`.
         *
         * The reason this exists: a REGEX rule could only ever be written by hiding the pattern
         * inside `url`, which was parsed as an absolute URL *before* the match modes were read. So
         * `https://api.acme.com/v1/orders/[0-9]+` was rejected as INVALID_URL and the only pattern
         * that survived parsing was `.*` — the documented "one rule for /v1/orders/<any id>" was
         * unwritable, and a host pattern was forced to leave its dots unescaped (`.*.acme.test`
         * happily matches `XacmeXtest`).
         *
         * Two rules make it work:
         *  · [host] and [path] are first-class and are taken VERBATIM — never parsed, never decoded;
         *  · a `url` is only parsed strictly while both comparisons are literal. As soon as either
         *    side is a pattern the URL is split by hand, so `[`, `+` and `?` reach the matcher intact.
         *
         * [base] supplies whatever the caller did not send (the rule being updated); pass null when
         * creating.
         */
        fun fromSpec(
            url: String?,
            host: String?,
            path: String?,
            hostMatch: MatchType,
            pathMatch: MatchType,
            base: StructuredUrl? = null
        ): UrlSpec {
            val explicitHost = host?.trim()?.takeIf { it.isNotEmpty() }
            val explicitPath = path?.trim()?.takeIf { it.isNotEmpty() }
            val rawUrl = url?.trim()?.takeIf { it.isNotEmpty() }

            // Strict parsing is reserved for the case it is actually correct for: everything
            // compared literally, so the string really is a URL and its query string really is a
            // list of parameters.
            val literal = !hostMatch.isPattern && !pathMatch.isPattern

            var parsed: StructuredUrl? = null
            if (rawUrl != null) {
                if (literal) {
                    val strict = fromUrl(rawUrl)
                    if (strict.host.isEmpty()) {
                        return UrlSpec.Err(
                            field = "url",
                            message = "Could not parse a host out of '$rawUrl'.",
                            hint = "Pass an absolute URL including the scheme, e.g. " +
                                    "https://api.example.com/v1/login. If this is a PATTERN, send it as " +
                                    "host/path instead — path:'/v1/orders/[0-9]+' with path_match:'REGEX' — " +
                                    "because a pattern is not a URL and cannot be parsed as one."
                        )
                    }
                    parsed = strict
                } else {
                    parsed = splitVerbatim(rawUrl, pathMatch.isPattern)
                }
            }

            val resolvedHost = explicitHost
                ?: parsed?.host?.takeIf { it.isNotEmpty() }
                ?: base?.host?.takeIf { it.isNotEmpty() }
                ?: return UrlSpec.Err(
                    field = "host",
                    message = "A rule needs a host: neither url nor host carried one.",
                    hint = "Send host:'api.example.com' (with host_match:'REGEX' if it is a pattern), " +
                            "or url:'https://api.example.com/v1/login'."
                )

            val knownPath = explicitPath
                ?: parsed?.path?.takeIf { it.isNotEmpty() }
                ?: base?.path?.takeIf { it.isNotEmpty() }
            // A pattern mode with nothing to pattern-match on is never what the caller meant:
            // defaulting to "/" would silently build a rule that only ever answers the root.
            if (knownPath == null && pathMatch.isPattern) {
                return UrlSpec.Err(
                    field = "path",
                    message = "path is required when path_match is ${pathMatch.name}.",
                    hint = "Send path:'/v1/orders/[0-9]+' with path_match:'REGEX', or drop path_match " +
                            "to compare the path literally."
                )
            }
            val resolvedPath = knownPath ?: "/"

            return UrlSpec.Ok(
                StructuredUrl(
                    scheme = parsed?.scheme ?: base?.scheme ?: "https",
                    host = resolvedHost,
                    port = parsed?.port ?: base?.port,
                    path = resolvedPath,
                    // Only a strict parse can honestly claim to have found query parameters; see
                    // splitVerbatim for why a pattern URL is never split on '?'.
                    queryParams = (parsed?.queryParams ?: base?.queryParams ?: mutableListOf())
                        .map { it.copy() }.toMutableList(),
                    hostMatch = hostMatch,
                    pathMatch = pathMatch
                )
            )
        }

        /**
         * Split a URL by hand, keeping every character of the pattern.
         *
         * `java.net.URI` refuses `[0-9]+` in a path, and even when it parses it would decode escapes
         * that a regex needs to keep.
         *
         * [pathIsPattern] decides what `?` means. In a pattern it is a quantifier —
         * `/orders/[0-9]+/?` is legal, and cutting the string there would silently store half of it,
         * so the query goes through the `query` list instead. With a literal path it is the ordinary
         * delimiter, and leaving `?id=7` glued to the path would build a rule that matches nothing:
         * only the HOST was a pattern in that call.
         */
        private fun splitVerbatim(url: String, pathIsPattern: Boolean): StructuredUrl {
            val schemeEnd = url.indexOf("://")
            val scheme = if (schemeEnd > 0) url.substring(0, schemeEnd) else "https"
            val afterScheme = if (schemeEnd > 0) url.substring(schemeEnd + 3) else url

            val slash = afterScheme.indexOf('/')
            var authority = if (slash >= 0) afterScheme.substring(0, slash) else afterScheme
            var path = if (slash >= 0) afterScheme.substring(slash) else ""

            val params = mutableListOf<QueryParam>()
            if (!pathIsPattern) {
                val question = path.indexOf('?')
                if (question >= 0) {
                    parseQueryInto(path.substring(question + 1), params)
                    path = path.substring(0, question)
                }
            }

            // user:pass@ is not part of a host pattern.
            authority.lastIndexOf('@').takeIf { it >= 0 }?.let { authority = authority.substring(it + 1) }

            // A trailing :<digits> is a port, never a pattern — `api\.acme\.com:8080` would
            // otherwise be compared, colon and all, against a host that has no port in it.
            var port: Int? = null
            val colon = authority.lastIndexOf(':')
            if (colon >= 0) {
                val tail = authority.substring(colon + 1)
                if (tail.isNotEmpty() && tail.all { it.isDigit() }) {
                    port = tail.toIntOrNull()
                    authority = authority.substring(0, colon)
                }
            }

            return StructuredUrl(scheme = scheme, host = authority, port = port, path = path, queryParams = params)
        }

        /** Same shape [fromUrl] produces: every captured param required and compared exactly. */
        private fun parseQueryInto(query: String, into: MutableList<QueryParam>) {
            query.split("&").filter { it.isNotEmpty() }.forEach { param ->
                val parts = param.split("=", limit = 2)
                into.add(
                    QueryParam(parts[0], parts.getOrNull(1) ?: "", required = true, matchType = MatchType.EXACT)
                )
            }
        }
    }
}
