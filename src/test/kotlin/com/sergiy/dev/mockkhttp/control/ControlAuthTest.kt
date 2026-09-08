package com.sergiy.dev.mockkhttp.control

import com.sergiy.dev.mockkhttp.control.dto.AGENT_CONTROL_FULL
import com.sergiy.dev.mockkhttp.control.dto.AGENT_CONTROL_OFF
import com.sergiy.dev.mockkhttp.control.dto.AGENT_CONTROL_READ_ONLY
import com.sergiy.dev.mockkhttp.control.dto.ApiError
import com.sergiy.dev.mockkhttp.control.dto.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.Locale

/**
 * The control plane's front door.
 *
 * A plain JUnit 4 test on purpose: [ControlAuth] touches nothing from the IntelliJ Platform, so it
 * needs no fixture and no IDE. (The platform test framework is JUnit 3/4 based — see
 * `ControlApiTest` — so JUnit 4 is what the `test` task runs; a JUnit 5 `@Test` here would be
 * collected by nothing and report success by being absent.)
 */
class ControlAuthTest {

    private companion object {
        /** [AuthRequest.headers] keys are lower-case; ROOT so no locale can bend the header name. */
        val CLIENT_HEADER_KEY: String = ControlAuth.CLIENT_HEADER.lowercase(Locale.ROOT)
    }

    private lateinit var auth: ControlAuth

    @Before
    fun setUp() {
        // A fresh instance per test: the token and the rate-limit bucket are per-object state.
        auth = ControlAuth()
    }

    // ========================================================================
    // Token
    // ========================================================================

    @Test
    fun `a request carrying the issued token is allowed through`() {
        assertNull(auth.authorize(request()))
    }

    @Test
    fun `a wrong token is rejected`() {
        val issued = auth.currentToken()

        val differentLastCharacter = issued.dropLast(1) + if (issued.last() == 'A') 'B' else 'A'
        assertEquals(ErrorCode.UNAUTHORIZED, refusal(request(headers = headers(token = differentLastCharacter))).code)
        assertEquals(ErrorCode.UNAUTHORIZED, refusal(request(headers = headers(token = issued.dropLast(1)))).code)
        assertEquals(ErrorCode.UNAUTHORIZED, refusal(request(headers = headers(token = issued + "x"))).code)
        assertEquals(ErrorCode.UNAUTHORIZED, refusal(request(headers = headers(token = ""))).code)
    }

    @Test
    fun `a missing Authorization header is unauthorized, not forbidden`() {
        val without = headers().minus("authorization")

        val error = refusal(request(headers = without))

        assertEquals(ErrorCode.UNAUTHORIZED, error.code)
        assertEquals(401, error.code.httpStatus)
        assertTrue(
            "the hint must name where the token comes from",
            error.hint.orEmpty().contains("instances")
        )
    }

    @Test
    fun `rotating the token invalidates the previous one immediately`() {
        val old = auth.currentToken()
        assertNull(auth.authorize(request(headers = headers(token = old))))

        val fresh = auth.rotateToken()

        assertFalse(fresh == old)
        assertEquals(ErrorCode.UNAUTHORIZED, refusal(request(headers = headers(token = old))).code)
        assertNull(auth.authorize(request(headers = headers(token = fresh))))
    }

    @Test
    fun `only a Bearer scheme is accepted`() {
        val issued = auth.currentToken()

        // Case of the scheme keyword does not matter; a different scheme does.
        assertNull(auth.authorize(request(headers = rawHeaders("Authorization" to "bearer $issued"))))
        assertEquals(
            ErrorCode.UNAUTHORIZED,
            refusal(request(headers = rawHeaders("Authorization" to "Basic $issued"))).code
        )
        assertEquals(
            ErrorCode.UNAUTHORIZED,
            refusal(request(headers = rawHeaders("Authorization" to issued))).code
        )
    }

    /**
     * A regression guard, not a timing measurement: a timing assertion on a JIT-compiled JVM is
     * noise, so what is pinned instead is that the comparison still goes through
     * [java.security.MessageDigest.isEqual] and that nobody has reintroduced a short-circuiting
     * `==` on the secret.
     */
    @Test
    fun `the token comparison is constant-time`() {
        val source = controlAuthSource()

        assertTrue(
            "ControlAuth must compare the bearer token with MessageDigest.isEqual",
            source.contains("MessageDigest.isEqual")
        )
        assertTrue(
            "authorize() must route the token comparison through constantTimeEquals",
            source.contains("constantTimeEquals(token, presented)")
        )
        assertFalse(
            "the token must never be compared with a short-circuiting string equality",
            Regex("""token\s*==\s*presented|presented\s*==\s*token|token\.equals\(presented""")
                .containsMatchIn(source)
        )
    }

    // ========================================================================
    // Browser lockout
    // ========================================================================

    @Test
    fun `a request carrying Origin is refused`() {
        assertBrowserRefusal(rawHeaders("Origin" to "https://evil.example.com"))
    }

    @Test
    fun `a request carrying Referer is refused`() {
        assertBrowserRefusal(rawHeaders("Referer" to "https://evil.example.com/page"))
    }

    @Test
    fun `a request carrying Sec-Fetch-Site is refused`() {
        assertBrowserRefusal(rawHeaders("Sec-Fetch-Site" to "cross-site"))
        assertBrowserRefusal(rawHeaders("Sec-Fetch-Mode" to "cors"))
    }

    @Test
    fun `a Mozilla User-Agent is refused`() {
        assertBrowserRefusal(rawHeaders("User-Agent" to "Mozilla/5.0 (Macintosh) AppleWebKit/537.36"))
    }

    @Test
    fun `a browser is refused before it can learn whether a token was required`() {
        // FORBIDDEN, never UNAUTHORIZED: a page rebound to 127.0.0.1 must not be able to tell an
        // authenticated endpoint from an unauthenticated one, with or without a valid token.
        val withValidToken = headers() + mapOf("origin" to "https://evil.example.com")
        assertEquals(ErrorCode.FORBIDDEN, refusal(request(headers = withValidToken)).code)

        val withoutAnyToken = withValidToken.minus("authorization")
        assertEquals(ErrorCode.FORBIDDEN, refusal(request(headers = withoutAnyToken)).code)
    }

    @Test
    fun `a CORS preflight is refused so no cross-origin call can ever be made`() {
        assertEquals(ErrorCode.FORBIDDEN, refusal(request(method = "OPTIONS")).code)
        assertEquals(ErrorCode.FORBIDDEN, refusal(request(method = "TRACE")).code)
        assertEquals(ErrorCode.FORBIDDEN, refusal(request(method = "CONNECT")).code)
        assertEquals(ErrorCode.FORBIDDEN, refusal(request(method = "options")).code)
    }

    @Test
    fun `a non-loopback Host header is refused`() {
        assertBrowserRefusal(rawHeaders("Host" to "evil.example.com"))
        assertBrowserRefusal(rawHeaders("Host" to "evil.example.com:63321"))
        assertBrowserRefusal(rawHeaders("Host" to "192.168.1.10:63321"))
    }

    @Test
    fun `loopback Host names are accepted in every spelling the stack produces`() {
        assertNull(auth.authorize(request(headers = rawHeaders("Host" to "127.0.0.1:63321"))))
        assertNull(auth.authorize(request(headers = rawHeaders("Host" to "localhost:63321"))))
        assertNull(auth.authorize(request(headers = rawHeaders("Host" to "LocalHost"))))
        assertNull(auth.authorize(request(headers = rawHeaders("Host" to "[::1]:63321"))))
        // An absent Host is not a browser — they always send one — so it must not be refused.
        assertNull(auth.authorize(request(headers = headers().minus("host"))))
    }

    @Test
    fun `a non-loopback peer is refused whatever the headers say`() {
        val error = refusal(request(peer = InetAddress.getByName("203.0.113.7")))

        assertEquals(ErrorCode.FORBIDDEN, error.code)
        assertTrue(error.message.contains("loopback"))
        // …and an unknown peer is treated as hostile rather than as a local caller.
        assertEquals(ErrorCode.FORBIDDEN, refusal(request(peer = null)).code)
    }

    @Test
    fun `the browser checks still fire when the headers come from lowerCaseHeaders`() {
        // The lockout compares lower-cased names; feeding it a raw multimap is the mistake that
        // would silently disable it, so exercise the exact helper the router uses.
        val fromWire = ControlAuth.lowerCaseHeaders(
            mapOf(
                "Host" to listOf("127.0.0.1:63321"),
                "Origin" to listOf("https://evil.example.com"),
                "Authorization" to listOf("Bearer ${auth.currentToken()}"),
                ControlAuth.CLIENT_HEADER to listOf("mockkhttp-mcp/1.8.0")
            )
        )

        assertEquals(ErrorCode.FORBIDDEN, refusal(request(headers = fromWire)).code)
    }

    // ========================================================================
    // Client identity
    // ========================================================================

    @Test
    fun `a request without the client header is refused`() {
        val error = refusal(request(headers = headers().minus(CLIENT_HEADER_KEY)))

        assertEquals(ErrorCode.FORBIDDEN, error.code)
        assertTrue(error.message.contains(ControlAuth.CLIENT_HEADER))
    }

    @Test
    fun `a client header that is not a short label is refused`() {
        assertEquals(
            ErrorCode.FORBIDDEN,
            refusal(request(headers = rawHeaders(ControlAuth.CLIENT_HEADER to "x".repeat(500)))).code
        )
        assertEquals(
            ErrorCode.FORBIDDEN,
            refusal(request(headers = rawHeaders(ControlAuth.CLIENT_HEADER to "Mozilla/5.0"))).code
        )
    }

    @Test
    fun `the audited client name falls back to a placeholder rather than to a lie`() {
        assertEquals("mockkhttp-mcp/1.8.0", auth.clientNameOf(headers()))
        assertEquals("unidentified", auth.clientNameOf(emptyMap()))
        assertEquals("unidentified", auth.clientNameOf(rawHeaders(ControlAuth.CLIENT_HEADER to "   ")))
    }

    // ========================================================================
    // Modes
    // ========================================================================

    @Test
    fun `READ_ONLY refuses a mutating call and allows a read`() {
        auth.setAgentControl(AGENT_CONTROL_READ_ONLY)

        val refused = refusal(request(method = "POST", mutating = true))
        assertEquals(ErrorCode.READ_ONLY, refused.code)
        assertEquals(403, refused.code.httpStatus)
        assertTrue("the hint must say how to allow writes", refused.hint.orEmpty().contains("Full"))

        assertNull(auth.authorize(request(method = "GET", path = "/v1/projects", mutating = false)))
    }

    @Test
    fun `whether a call mutates is decided by the route, not by the HTTP verb`() {
        auth.setAgentControl(AGENT_CONTROL_READ_ONLY)

        // flows/await is a POST that changes nothing, and must stay usable in READ_ONLY.
        assertNull(auth.authorize(request(method = "POST", path = "/v1/projects/p/flows/await", mutating = false)))
        // A GET the router marked mutating would still be refused.
        assertEquals(
            ErrorCode.READ_ONLY,
            refusal(request(method = "GET", path = "/v1/projects/p/mocks/apply", mutating = true)).code
        )
    }

    @Test
    fun `OFF refuses everything, reads included`() {
        auth.setAgentControl(AGENT_CONTROL_OFF)

        assertEquals(ErrorCode.FORBIDDEN, refusal(request(mutating = false)).code)
        assertEquals(ErrorCode.FORBIDDEN, refusal(request(mutating = true)).code)
    }

    @Test
    fun `an unrecognised mode fails closed`() {
        auth.setAgentControl("full-ish")

        // A typo in a settings file must never read as "full".
        assertEquals(AGENT_CONTROL_OFF, auth.getAgentControl())
        assertEquals(ErrorCode.FORBIDDEN, refusal(request(mutating = false)).code)

        auth.setAgentControl(AGENT_CONTROL_FULL)
        assertEquals(AGENT_CONTROL_FULL, auth.getAgentControl())
        assertNull(auth.authorize(request()))
    }

    @Test
    fun `the door is closed before the token is even looked at`() {
        auth.setAgentControl(AGENT_CONTROL_OFF)

        // No token at all: still FORBIDDEN (the door), never UNAUTHORIZED (the lock).
        val error = refusal(request(headers = headers().minus("authorization")))

        assertEquals(ErrorCode.FORBIDDEN, error.code)
        assertTrue(error.message.contains("switched off"))
    }

    // ========================================================================
    // Budgets
    // ========================================================================

    @Test
    fun `the rate limiter eventually says no`() {
        // The bucket starts full, so the first RATE_LIMIT_PER_MINUTE calls can never be refused
        // however slowly the test runs — refill only ever adds tokens, capped at the same maximum.
        repeat(ControlAuth.RATE_LIMIT_PER_MINUTE) { index ->
            assertNull("call #$index was refused while the bucket still had tokens", auth.authorize(request()))
        }

        // Refill is 10 tokens/second, so a further 200 in-memory calls cannot all be funded.
        var limited: ApiError? = null
        for (i in 0 until 200) {
            val error = auth.authorize(request())
            if (error != null) {
                limited = error
                break
            }
        }

        assertNotNull("the bucket never emptied, so it is not limiting anything", limited)
        assertEquals(ErrorCode.RATE_LIMITED, limited!!.code)
        assertEquals(429, limited.code.httpStatus)
        assertTrue("a rate limit is worth retrying, and must say so", limited.retryable)
        assertTrue(
            "the hint must point at the primitive that replaces polling",
            limited.hint.orEmpty().contains("flows/await")
        )
    }

    @Test
    fun `an unauthenticated caller cannot drain the bucket of the legitimate bridge`() {
        val wrong = headers(token = "not-the-token")
        repeat(ControlAuth.RATE_LIMIT_PER_MINUTE * 2) {
            assertEquals(ErrorCode.UNAUTHORIZED, refusal(request(headers = wrong)).code)
        }

        // The token is checked before the rate limit precisely so this still works.
        assertNull(auth.authorize(request()))
    }

    @Test
    fun `a project gets a bounded number of concurrent long-polls`() {
        repeat(ControlAuth.MAX_LONG_POLLS_PER_PROJECT) {
            assertTrue(auth.tryAcquireLongPoll("project-a"))
        }
        assertFalse(auth.tryAcquireLongPoll("project-a"))

        // The budget is per project, so another project is unaffected.
        assertTrue(auth.tryAcquireLongPoll("project-b"))

        val error = auth.tooManyWaiters("project-a")
        assertEquals(ErrorCode.TOO_MANY_WAITERS, error.code)
        assertTrue(error.retryable)
        assertEquals("project-a", error.details?.get("project_id"))

        auth.releaseLongPoll("project-a")
        assertTrue("a released permit must come back", auth.tryAcquireLongPoll("project-a"))
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun request(
        method: String = "POST",
        path: String = "/v1/projects/p/mocks",
        peer: InetAddress? = InetAddress.getLoopbackAddress(),
        headers: Map<String, String> = headers(),
        mutating: Boolean = true,
        projectId: String? = "p"
    ) = AuthRequest(
        method = method,
        path = path,
        remoteAddress = peer,
        headers = headers,
        mutating = mutating,
        projectId = projectId
    )

    /** The header set a well-behaved bridge sends, already lower-cased as [AuthRequest] requires. */
    private fun headers(token: String = auth.currentToken()): Map<String, String> = mapOf(
        "host" to "127.0.0.1:63321",
        "authorization" to "Bearer $token",
        CLIENT_HEADER_KEY to "mockkhttp-mcp/1.8.0"
    )

    /**
     * A well-behaved header set with [overrides] applied, flattened through the very helper the
     * router uses — so the test exercises the lower-casing the browser lockout depends on.
     */
    private fun rawHeaders(vararg overrides: Pair<String, String>): Map<String, String> {
        val wire = LinkedHashMap<String, List<String>>()
        wire["Host"] = listOf("127.0.0.1:63321")
        wire["Authorization"] = listOf("Bearer ${auth.currentToken()}")
        wire[ControlAuth.CLIENT_HEADER] = listOf("mockkhttp-mcp/1.8.0")
        for ((name, value) in overrides) {
            // Replace any other spelling of the same header, so an override is never shadowed.
            wire.keys.filter { it.equals(name, ignoreCase = true) }.forEach { wire.remove(it) }
            wire[name] = listOf(value)
        }
        return ControlAuth.lowerCaseHeaders(wire)
    }

    private fun refusal(request: AuthRequest): ApiError =
        auth.authorize(request) ?: throw AssertionError("expected a refusal, but the request was allowed: $request")

    private fun assertBrowserRefusal(headers: Map<String, String>) {
        val error = refusal(request(headers = headers))

        assertEquals(ErrorCode.FORBIDDEN, error.code)
        assertTrue(
            "a browser rejection must say the control plane is never reachable from a web page, got: ${error.message}",
            error.message.contains("never reachable from a web page")
        )
    }

    /**
     * The on-disk source of [ControlAuth], located from wherever the compiled class came from.
     * Throws rather than skipping: a guard that cannot find what it guards must fail loudly.
     */
    private fun controlAuthSource(): String {
        val relative = "src/main/kotlin/com/sergiy/dev/mockkhttp/control/ControlAuth.kt"
        val roots = LinkedHashSet<File>()

        ControlAuth::class.java.protectionDomain?.codeSource?.location?.let { location ->
            runCatching { File(location.toURI()) }.getOrNull()?.let { start ->
                var dir: File? = if (start.isDirectory) start else start.parentFile
                while (dir != null) {
                    roots += dir
                    dir = dir.parentFile
                }
            }
        }
        var working: File? = File(System.getProperty("user.dir", ".")).absoluteFile
        while (working != null) {
            roots += working
            working = working.parentFile
        }

        for (root in roots) {
            val candidate = File(root, relative)
            if (candidate.isFile) return candidate.readText()
        }
        throw AssertionError(
            "Could not locate $relative from any ancestor of ${System.getProperty("user.dir")} " +
                    "or of the compiled ControlAuth class. Fix the lookup rather than deleting this guard."
        )
    }
}
