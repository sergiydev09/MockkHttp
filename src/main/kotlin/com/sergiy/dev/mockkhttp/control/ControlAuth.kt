package com.sergiy.dev.mockkhttp.control

import com.sergiy.dev.mockkhttp.control.dto.AGENT_CONTROL_FULL
import com.sergiy.dev.mockkhttp.control.dto.AGENT_CONTROL_OFF
import com.sergiy.dev.mockkhttp.control.dto.AGENT_CONTROL_READ_ONLY
import com.sergiy.dev.mockkhttp.control.dto.ApiError
import com.sergiy.dev.mockkhttp.control.dto.ErrorCode
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * Everything the auth layer needs about one request, with no `com.sun.net.httpserver` types in it.
 *
 * Built by [ControlRouter] from the live exchange; built by hand in tests. [headers] keys are
 * **lower-case** — use [ControlAuth.lowerCaseHeaders] to produce it, never a raw `Headers` map,
 * or the browser-lockout checks silently stop matching.
 */
data class AuthRequest(
    val method: String,
    val path: String,
    /** The peer's address as reported by the socket. Null is treated as hostile. */
    val remoteAddress: InetAddress?,
    val headers: Map<String, String>,
    /** Whether this route can change state. Decided by the router's table, not by the HTTP verb. */
    val mutating: Boolean,
    val projectId: String?
)

/**
 * The control plane's front door: token, browser lockout, rate limit and long-poll budget.
 *
 * ## Threat model (plan §7)
 *
 * This channel rewrites the HTTP responses a debug build receives and can read captured bodies
 * holding live credentials. The realistic attackers are **a web page in the developer's browser**
 * and **another user on a shared machine** — a same-uid process is explicitly out of scope, since
 * it can already read `~/.ssh` and the IDE's own tokens. So the goal here is not defence in depth
 * against a local root: it is that no web page can ever drive this, and that every request is
 * attributable and revocable.
 *
 * ## Order of checks, and why it is that order
 *
 * 1. **Loopback peer** — cheapest and strongest. The listener only binds `127.0.0.1`, so this can
 *    only fail if something upstream is proxying; that is exactly the case we must refuse.
 * 2. **Browser lockout, BEFORE authentication.** A page on `evil.com` rebound to `127.0.0.1` must
 *    be rejected before it can learn whether a token was even required — and it never learns the
 *    port either, because the port is ephemeral.
 * 3. **`X-MockkHttp-Client`** — a non-simple header. A browser form POST cannot send one without a
 *    CORS preflight, and the preflight (`OPTIONS`) is answered 403, so the request never happens.
 * 4. **Token**, constant-time. Checked *before* the rate limit on purpose: an unauthenticated
 *    caller must not be able to drain the legitimate bridge's bucket.
 * 5. **Rate limit**, then **READ_ONLY**. READ_ONLY is last because it is the only decision that
 *    depends on the route, and it should be reported to an authenticated caller, not to a stranger.
 *
 * Every rejection carries a `hint` naming the exact next call, because a model that gets one
 * self-corrects in a single round trip and a model that gets a bare 403 gives up.
 */
class ControlAuth {

    companion object {
        /** Mandatory identifying header, e.g. `mockkhttp-mcp/1.8.0`. */
        const val CLIENT_HEADER: String = "X-MockkHttp-Client"

        /** 256 bits (plan §7). Base64url of 32 bytes is 43 characters, unpadded. */
        private const val TOKEN_BYTES = 32

        /** §7: 600 requests per minute per token. */
        const val RATE_LIMIT_PER_MINUTE: Int = 600

        /** §7: two concurrent long-polls per project; the third is told to wait. */
        const val MAX_LONG_POLLS_PER_PROJECT: Int = 2

        /** A client name is a label, not a payload. Anything longer is a caller bug. */
        private const val MAX_CLIENT_NAME_CHARS = 128

        /**
         * Host values we accept, compared as **literal strings**.
         *
         * Deliberately not `InetAddress.getByName(host).isLoopbackAddress`: that resolves whatever
         * the attacker put there, which is precisely the DNS-rebinding step we are trying to break.
         * The port is not checked — a wrong port cannot reach a listener that is not on it.
         */
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]")

        /** Sent by browsers, never by our bridge. Any one of them is disqualifying. */
        private val BROWSER_HEADERS = setOf("origin", "referer")

        /** Only a browser sends `Sec-Fetch-*`; the whole family is disqualifying. */
        private const val BROWSER_HEADER_PREFIX = "sec-fetch-"

        /** Methods that only exist to probe us. `OPTIONS` is the CORS preflight we must fail. */
        private val REFUSED_METHODS = setOf("OPTIONS", "TRACE", "CONNECT")

        /**
         * Flatten a `com.sun.net.httpserver.Headers` (or any multimap) into the lower-cased,
         * first-value-wins map [AuthRequest] requires.
         */
        fun lowerCaseHeaders(raw: Map<String, List<String>>): Map<String, String> {
            val out = HashMap<String, String>(raw.size * 2)
            for ((name, values) in raw) {
                out[name.lowercase(Locale.ROOT)] = values.firstOrNull() ?: ""
            }
            return out
        }
    }

    private val secureRandom = SecureRandom()

    /**
     * The bearer token. Rotated on every IDE start (this object is built once per control server)
     * and on demand by the Revoke action, which is what makes revocation instant: every connected
     * bridge fails its next call and has to re-read the instance file.
     */
    @Volatile
    private var token: String = newToken()

    /** Mirror of `SettingsStore.agentControl`. Pushed in by [AgentControlServer], never read from here. */
    @Volatile
    private var agentControl: String = AGENT_CONTROL_FULL

    // Token bucket. One bucket, because there is one token: per-token keying would be a map with
    // one entry in it. Guarded by its own monitor so a burst cannot contend with anything else.
    private val bucketLock = Any()
    private var bucketTokens: Double = RATE_LIMIT_PER_MINUTE.toDouble()
    private var bucketRefilledAtNanos: Long = System.nanoTime()

    /**
     * Long-poll permits, keyed by project.
     *
     * Bounded by the number of projects ever addressed in this IDE run, so it needs no eviction.
     * The cap exists because a long-poll holds a control-plane worker for up to 25 s: without it a
     * caller in a retry loop parks every thread in the pool and locks itself out of `status`.
     */
    private val longPollSlots = ConcurrentHashMap<String, Semaphore>()

    // ========================================================================
    // Token
    // ========================================================================

    fun currentToken(): String = token

    /**
     * Generate a new token and invalidate the old one.
     *
     * @return the new token, which the caller must republish (instance file) before any bridge can
     *         reconnect. Nothing else is reset: rate-limit and long-poll state are about resources,
     *         not identity.
     */
    fun rotateToken(): String {
        val fresh = newToken()
        token = fresh
        return fresh
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    // ========================================================================
    // Mode
    // ========================================================================

    fun getAgentControl(): String = agentControl

    /** Accepts only the three wire values; anything else falls back to [AGENT_CONTROL_OFF]. */
    fun setAgentControl(value: String) {
        agentControl = when (value.lowercase(Locale.ROOT)) {
            AGENT_CONTROL_FULL -> AGENT_CONTROL_FULL
            AGENT_CONTROL_READ_ONLY -> AGENT_CONTROL_READ_ONLY
            // An unrecognised value must fail closed: a typo in a setting cannot silently mean "full".
            else -> AGENT_CONTROL_OFF
        }
    }

    // ========================================================================
    // The check
    // ========================================================================

    /**
     * @return null when the request may proceed, or the [ApiError] the router must serialise.
     */
    fun authorize(request: AuthRequest): ApiError? {
        val headers = request.headers

        // --- 1. loopback peer -------------------------------------------------------------------
        val peer = request.remoteAddress
        if (peer == null || !peer.isLoopbackAddress) {
            return forbidden(
                "This control plane serves loopback callers only; the request arrived from ${peer?.hostAddress ?: "an unknown address"}.",
                "Run the MCP bridge on the same machine as the IDE. There is no setting that opens this port to the network."
            )
        }

        // --- 2. browser lockout, before authentication -------------------------------------------
        if (request.method.uppercase(Locale.ROOT) in REFUSED_METHODS) {
            return forbidden(
                "The ${request.method} method is not served.",
                "Use GET, POST, PUT, PATCH or DELETE. OPTIONS is refused so no cross-origin preflight can ever succeed."
            )
        }
        val browserHeader = headers.keys.firstOrNull { it in BROWSER_HEADERS || it.startsWith(BROWSER_HEADER_PREFIX) }
        if (browserHeader != null) {
            return browserRejection("the request carried a '$browserHeader' header")
        }
        val userAgent = headers["user-agent"].orEmpty()
        if (userAgent.startsWith("Mozilla/")) {
            return browserRejection("the User-Agent identifies a browser")
        }
        val host = headers["host"]
        if (host != null && !isLoopbackHost(host)) {
            return browserRejection("the Host header '$host' is not a loopback name")
        }

        // --- 3. mandatory client identity ---------------------------------------------------------
        val client = headers[CLIENT_HEADER.lowercase(Locale.ROOT)]
        if (client.isNullOrBlank()) {
            return forbidden(
                "Every request must identify its caller with a $CLIENT_HEADER header.",
                "Add `$CLIENT_HEADER: <name>/<version>` (for example `mockkhttp-mcp/1.8.0`). It is a non-simple header on purpose: a web page cannot send one without a preflight, and preflights are refused."
            )
        }
        if (client.length > MAX_CLIENT_NAME_CHARS || client.startsWith("Mozilla/")) {
            return forbidden(
                "The $CLIENT_HEADER value is not a client name.",
                "Send a short `<name>/<version>` label, at most $MAX_CLIENT_NAME_CHARS characters."
            )
        }

        // --- 4. is the door open at all? ----------------------------------------------------------
        if (agentControl == AGENT_CONTROL_OFF) {
            return ApiError(
                ErrorCode.FORBIDDEN,
                "Agent control is switched off for this IDE.",
                "Turn it on in the MockkHttp tool window → Settings → AI Agent Access, then re-read ~/.mockkhttp/instances/<id>.json: the port and token change when it is re-enabled."
            )
        }

        // --- 5. token, constant time ---------------------------------------------------------------
        val presented = bearerOf(headers["authorization"])
        if (presented == null) {
            return ApiError(
                ErrorCode.UNAUTHORIZED,
                "Missing bearer token.",
                "Send `Authorization: Bearer <token>` using control.token from ~/.mockkhttp/instances/<instanceId>.json. Never hard-code it: it is regenerated on every IDE start and by the Revoke action."
            )
        }
        if (!constantTimeEquals(token, presented)) {
            return ApiError(
                ErrorCode.UNAUTHORIZED,
                "The bearer token is not the one this IDE issued.",
                "Re-read control.token from ~/.mockkhttp/instances/<instanceId>.json — the IDE restarted, or the token was revoked."
            )
        }

        // --- 6. rate limit --------------------------------------------------------------------------
        if (!consumeRateToken()) {
            return ApiError(
                ErrorCode.RATE_LIMITED,
                "More than $RATE_LIMIT_PER_MINUTE control requests in the last minute.",
                "Stop polling. Use flows/await, which blocks server-side until traffic matches instead of asking again, and pass the next_seq you were given so you never re-read what you already have."
            )
        }

        // --- 7. write authorisation ------------------------------------------------------------------
        if (request.mutating && agentControl == AGENT_CONTROL_READ_ONLY) {
            return ApiError(
                ErrorCode.READ_ONLY,
                "Agent control is READ_ONLY, so ${request.method} ${request.path} is refused.",
                "Every GET still works — read flows, rules and status freely. To allow writes, set AI Agent Access to Full in the MockkHttp tool window → Settings."
            )
        }

        return null
    }

    /** The caller's self-declared name, for the audit trail. Never trusted for a decision. */
    fun clientNameOf(headers: Map<String, String>): String =
        headers[CLIENT_HEADER.lowercase(Locale.ROOT)]?.take(MAX_CLIENT_NAME_CHARS)?.ifBlank { null } ?: "unidentified"

    // ========================================================================
    // Long-poll budget
    // ========================================================================

    /** @return false when this project already has [MAX_LONG_POLLS_PER_PROJECT] callers parked. */
    fun tryAcquireLongPoll(projectId: String?): Boolean =
        slotsFor(projectId).tryAcquire()

    /** Must be called from a `finally`: a leaked permit disables long-polling until the IDE restarts. */
    fun releaseLongPoll(projectId: String?) {
        slotsFor(projectId).release()
    }

    fun tooManyWaiters(projectId: String?): ApiError = ApiError(
        ErrorCode.TOO_MANY_WAITERS,
        "This project already has $MAX_LONG_POLLS_PER_PROJECT long-polls in flight.",
        "Wait for one of your outstanding flows/await calls to return before starting another — each one holds a control-plane thread for up to 25 s. One waiter with a good matcher beats three with guesses.",
        details = projectId?.let { mapOf("project_id" to it) }
    )

    private fun slotsFor(projectId: String?): Semaphore =
        longPollSlots.computeIfAbsent(projectId ?: "") { Semaphore(MAX_LONG_POLLS_PER_PROJECT) }

    // ========================================================================
    // Internals
    // ========================================================================

    private fun consumeRateToken(): Boolean = synchronized(bucketLock) {
        val now = System.nanoTime()
        val elapsedSeconds = (now - bucketRefilledAtNanos).coerceAtLeast(0L) / 1_000_000_000.0
        if (elapsedSeconds > 0.0) {
            bucketRefilledAtNanos = now
            val refill = elapsedSeconds * (RATE_LIMIT_PER_MINUTE / 60.0)
            bucketTokens = (bucketTokens + refill).coerceAtMost(RATE_LIMIT_PER_MINUTE.toDouble())
        }
        if (bucketTokens >= 1.0) {
            bucketTokens -= 1.0
            true
        } else {
            false
        }
    }

    private fun bearerOf(header: String?): String? {
        val value = header?.trim() ?: return null
        val space = value.indexOf(' ')
        if (space <= 0) return null
        if (!value.regionMatches(0, "Bearer", 0, space, ignoreCase = true)) return null
        return value.substring(space + 1).trim().ifEmpty { null }
    }

    /**
     * Constant-time for equal-length inputs, which is all [MessageDigest.isEqual] promises and all
     * that matters here: the token's length is public.
     */
    private fun constantTimeEquals(expected: String, presented: String): Boolean =
        MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            presented.toByteArray(StandardCharsets.UTF_8)
        )

    private fun isLoopbackHost(host: String): Boolean {
        val trimmed = host.trim()
        // An absent or empty Host is not a browser (they always send one) — do not reject it.
        if (trimmed.isEmpty()) return true
        val hostPart = if (trimmed.startsWith("[")) {
            val close = trimmed.indexOf(']')
            if (close < 0) return false
            trimmed.substring(0, close + 1)
        } else {
            trimmed.substringBefore(':')
        }
        return hostPart.lowercase(Locale.ROOT) in LOOPBACK_HOSTS
    }

    private fun browserRejection(reason: String): ApiError = forbidden(
        "Refused because $reason. The MockkHttp control plane is never reachable from a web page.",
        "Call it from the MockkHttp MCP bridge, or from a plain HTTP client with no Origin, Referer or Sec-Fetch-* headers and a non-browser User-Agent."
    )

    private fun forbidden(message: String, hint: String): ApiError =
        ApiError(ErrorCode.FORBIDDEN, message, hint)
}
