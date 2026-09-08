package com.sergiy.dev.mockkhttp.control

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.sergiy.dev.mockkhttp.control.dto.AGENT_CONTROL_FULL
import com.sergiy.dev.mockkhttp.control.dto.API_VERSION
import com.sergiy.dev.mockkhttp.control.dto.ApiError
import com.sergiy.dev.mockkhttp.control.dto.ApiResult
import com.sergiy.dev.mockkhttp.control.dto.ErrorCode
import com.sergiy.dev.mockkhttp.control.dto.ErrorEnvelope
import com.sergiy.dev.mockkhttp.control.handlers.FlowsHandler
import com.sergiy.dev.mockkhttp.control.handlers.MetaHandler
import com.sergiy.dev.mockkhttp.control.handlers.MocksHandler
import com.sergiy.dev.mockkhttp.control.handlers.SessionHandler
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The one Gson the control plane serialises with.
 *
 * - `serializeNulls` is **on**: a field this milestone cannot answer honestly is `null` on the wire
 *   (plan §3), and an omitted field reads as "this build has no such field" to a caller holding
 *   `docs/AGENT_API.md`. Being explicitly null is the whole point of those fields.
 * - `disableHtmlEscaping` is **on**: captured bodies are full of `<`, `>` and `&`, and escaping them
 *   into `<` makes the response body unreadable to the agent that asked for it.
 */
object ControlJson {

    val gson: Gson = GsonBuilder()
        .serializeNulls()
        .disableHtmlEscaping()
        .create()

    fun toJson(value: Any): String = gson.toJson(value)
}

/**
 * One parsed control-plane request, with no `com.sun.net.httpserver` types in it.
 *
 * Handlers see this and nothing else, which is what keeps them unit-testable and what will let a
 * second transport reuse them unchanged.
 *
 * ## How a path becomes these fields
 *
 * ```
 * /v1/projects/a1b2c3d4/flows/await   → projectId="a1b2c3d4", resource="flows",  tail=["await"]
 * /v1/projects/a1b2c3d4/flows/8f2c    → projectId="a1b2c3d4", resource="flows",  tail=["8f2c"]
 * /v1/projects/a1b2c3d4/status        → projectId="a1b2c3d4", resource="status", tail=[]
 * /v1/meta                            → projectId=null,       resource="meta",   tail=[]
 * /v1/projects                        → projectId=null,       resource="projects", tail=[]
 * ```
 *
 * `/v1` is consumed by the version gate and never appears in [resource] or [tail]. Segments are
 * percent-decoded; `+` stays a literal plus in the path and becomes a space in the query, which is
 * what the two grammars actually say.
 */
data class ControlRequest(
    /** Upper-case. `HEAD` is presented as `GET`; the router suppresses the body on the way out. */
    val method: String,
    /** The full decoded path, for hints and log lines. */
    val path: String,
    /** First segment after `/v1`, or after `/v1/projects/{pid}`. */
    val resource: String,
    /** `{pid}` from `/v1/projects/{pid}/…`; null on the global routes. */
    val projectId: String?,
    /** Decoded segments after [resource]. */
    val tail: List<String>,
    /** Decoded query parameters; a repeated key keeps the last value. */
    val query: Map<String, String>,
    /** Raw UTF-8 request body, `""` when there is none. Never over 8 MB — the router caps it. */
    val body: String,
    /** The caller's `X-MockkHttp-Client` label, for audit lines and warnings. */
    val clientName: String,
    /**
     * The ceiling a handler must pass to `ControlApi.awaitFlow(maxWaitMs = …)`.
     *
     * The transport owns this policy, not the façade: it drops to 0 once the IDE starts shutting
     * down, so a long-poll begun during teardown returns immediately instead of holding a worker —
     * and a worker that outlives `dispose()` pins the plugin classloader on unload.
     */
    val longPollBudgetMs: Long,
    /** True when agent control is READ_ONLY. Mutating routes are already refused; this is for warnings. */
    val readOnly: Boolean
) {

    fun queryValue(name: String): String? = query[name]

    /**
     * Read a whole-number query parameter.
     *
     * Three outcomes, not two, because "absent" and "present but nonsense" must not collapse: the
     * first means "apply your documented default", the second is a caller error that has to be
     * reported. Silently treating `?limit=lots` as absent is exactly the "never silently ignore a
     * parameter" rule the contract forbids.
     */
    fun intQuery(name: String): QueryResult<Int> {
        val raw = query[name] ?: return QueryResult.Absent
        return raw.trim().toIntOrNull()?.let { QueryResult.Value(it) } ?: badQuery(name, raw, "a whole number")
    }

    fun longQuery(name: String): QueryResult<Long> {
        val raw = query[name] ?: return QueryResult.Absent
        return raw.trim().toLongOrNull()?.let { QueryResult.Value(it) } ?: badQuery(name, raw, "a whole number")
    }

    fun boolQuery(name: String): QueryResult<Boolean> {
        val raw = query[name] ?: return QueryResult.Absent
        return when (raw.trim().lowercase(Locale.ROOT)) {
            "true", "1", "yes" -> QueryResult.Value(true)
            "false", "0", "no" -> QueryResult.Value(false)
            else -> badQuery(name, raw, "true or false")
        }
    }

    /**
     * Parse the body into [type].
     *
     * An empty body parses as `{}`: every request DTO is all-nullable by design, so "no body" and
     * "an empty object" mean the same thing and a caller should not have to send `{}` to say
     * nothing. A syntax error is [ErrorCode.MALFORMED_JSON] with the parser's own message, never a
     * 500 — a model fixes its JSON from that in one round trip.
     */
    fun <T : Any> jsonBody(type: Class<T>): ApiResult<T> = try {
        val parsed: T? = ControlJson.gson.fromJson(body.ifBlank { "{}" }, type)
        if (parsed == null) {
            ApiResult.fail(
                ErrorCode.MALFORMED_JSON,
                "The request body parsed to null.",
                "Send a JSON object as the body of $method $path, or no body at all — every field of ${type.simpleName} is optional."
            )
        } else {
            ApiResult.ok(parsed)
        }
    } catch (e: JsonParseException) {
        ApiResult.fail(
            ErrorCode.MALFORMED_JSON,
            "The request body is not valid JSON: ${e.message}",
            "Send one UTF-8 JSON object as the body of $method $path."
        )
    }

    private fun badQuery(name: String, raw: String, expected: String): QueryResult<Nothing> = QueryResult.Invalid(
        ControlResponse.fail(
            ErrorCode.INVALID_ARGUMENT,
            "Query parameter '$name' must be $expected (got '$raw').",
            "Retry $method $path with ?$name=<$expected>, or omit it to take the default."
        )
    )
}

/**
 * The outcome of reading one typed query parameter.
 *
 * `ApiResult` cannot express this: its type parameter is `T : Any`, so it has no way to say
 * "successfully absent". Handlers read it as
 *
 * ```kotlin
 * val limit = when (val q = request.intQuery("limit")) {
 *     is QueryResult.Invalid -> return q.response
 *     else -> q.orNull()          // null = not sent, let ControlApi apply its default
 * }
 * ```
 */
sealed class QueryResult<out T : Any> {

    /** Not sent. Apply the documented default — and let `ControlApi` be the one that knows it. */
    object Absent : QueryResult<Nothing>()

    data class Value<out T : Any>(val value: T) : QueryResult<T>()

    /** Present but unparseable. [response] is the typed 400 to return unchanged. */
    data class Invalid(val response: ControlResponse) : QueryResult<Nothing>()

    /** The parsed value, or null when the parameter was absent or rejected. */
    fun orNull(): T? = when (this) {
        is Value -> value
        else -> null
    }
}

/**
 * What a handler returns. [body] is already-serialised JSON; the router only adds headers.
 *
 * Build these with the companion helpers rather than the constructor — [Companion.of] in particular
 * is the single place an `ApiResult.Err` becomes an HTTP status, so no route can invent its own
 * mapping and drift from [ErrorCode.httpStatus].
 */
data class ControlResponse(
    val status: Int,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
    val contentType: String = JSON_CONTENT_TYPE
) {

    companion object {
        const val JSON_CONTENT_TYPE: String = "application/json; charset=utf-8"

        fun ok(payload: Any): ControlResponse = json(200, payload)

        fun json(status: Int, payload: Any): ControlResponse =
            ControlResponse(status = status, body = ControlJson.toJson(payload))

        fun noContent(): ControlResponse = ControlResponse(status = 204)

        /** The only status mapping in the plugin: straight off [ErrorCode.httpStatus]. */
        fun error(error: ApiError): ControlResponse = json(error.code.httpStatus, ErrorEnvelope(error))

        fun fail(
            code: ErrorCode,
            message: String,
            hint: String? = null,
            details: Map<String, String>? = null
        ): ControlResponse = error(ApiError(code, message, hint, code.retryable, details))

        /** Translate a façade result. Every handler ends in this. */
        fun <T : Any> of(result: ApiResult<T>): ControlResponse = when (result) {
            is ApiResult.Ok -> ok(result.value)
            is ApiResult.Err -> error(result.error)
        }
    }
}

/** A route implemented outside this file — the agent audit log, and whatever a later milestone adds. */
fun interface ControlRouteHandler {
    fun handle(request: ControlRequest): ControlResponse
}

/**
 * Dispatch for everything under `/v1`: the version gate, the 8 MB body cap, the auth call, and
 * the single `try/catch` that turns any [Throwable] into the typed 500 envelope.
 *
 * **No business logic lives here.** The router parses, authorises, hands a [ControlRequest] to one
 * handler and serialises what comes back. Anything that reasons about flows, rules or modes belongs
 * in [ControlApi]; anything that reasons about JSON shapes belongs in a handler.
 *
 * ## The routing table
 *
 * | Path | Handler |
 * |---|---|
 * | `GET /v1/meta`, `GET /v1/docs`, `GET /v1/projects` | [MetaHandler] |
 * | `/v1/projects/{pid}/status`, `/v1/projects/{pid}/session…` | [SessionHandler] |
 * | `/v1/projects/{pid}/flows…` | [FlowsHandler] |
 * | `/v1/projects/{pid}/mocks…` | [MocksHandler] |
 * | `arms`, `runs`, `verify`, `intercepts`, `pause-policy`, `devices`, `app` | typed 501 naming the milestone |
 *
 * A route that a later milestone will build answers `501 NOT_IMPLEMENTED` with that milestone in the
 * hint rather than a bare 404: a caller must be able to tell "this will exist" from "you typed it
 * wrong", and only the first of those is worth retrying after an upgrade.
 *
 * ## Order of operations, and why
 *
 * Authorisation happens **before the body is read**. An unauthenticated caller must not be able to
 * make the IDE buffer 8 MB, and the routing decision that auth needs (is this verb mutating?) comes
 * from the path alone.
 */
class ControlRouter(private val server: AgentControlServer) : HttpHandler {

    private val logger = Logger.getInstance(ControlRouter::class.java)

    // Services are resolved lazily, never in a constructor: this object is built while
    // AgentControlServer.start() runs, and a service asking for another service in its constructor
    // is exactly what the platform tells you not to do.
    private val api: ControlApi by lazy { ControlApi.getInstance() }
    private val metaHandler: MetaHandler by lazy { MetaHandler(api) }
    private val sessionHandler: SessionHandler by lazy { SessionHandler(api) }
    private val flowsHandler: FlowsHandler by lazy { FlowsHandler(api) }
    private val mocksHandler: MocksHandler by lazy { MocksHandler(api) }

    /** Routes owned by files this one must not depend on. See [registerRoute]. */
    private val extraRoutes = ConcurrentHashMap<String, ControlRouteHandler>()

    companion object {
        /** The only API version this build serves. */
        const val API_PREFIX: String = "v1"

        /** §7: 8 MB request bodies, then `413 PAYLOAD_TOO_LARGE`. Only an import gets close. */
        const val MAX_REQUEST_BODY_BYTES: Int = 8 * 1024 * 1024

        /**
         * The transport's long-poll ceiling, handed to handlers as [ControlRequest.longPollBudgetMs].
         *
         * Equal to [ControlApi.MAX_AWAIT_WAIT_MS], but restated here because it is a *transport*
         * budget — how long one control-plane worker may be held — and the façade enforces whatever
         * the transport gives it rather than a constant of its own.
         */
        const val LONG_POLL_BUDGET_MS: Long = ControlApi.MAX_AWAIT_WAIT_MS

        const val RESOURCE_META: String = "meta"
        const val RESOURCE_DOCS: String = "docs"
        const val RESOURCE_PROJECTS: String = "projects"
        const val RESOURCE_STATUS: String = "status"
        const val RESOURCE_SESSION: String = "session"
        const val RESOURCE_FLOWS: String = "flows"
        const val RESOURCE_MOCKS: String = "mocks"

        /** Contract routes that land in a later milestone. The value is what the hint says. */
        private val FUTURE_RESOURCES = mapOf(
            "arms" to "M2 — armed stubs (mockkhttp_arm)",
            "runs" to "M2 — runs (mockkhttp_run)",
            "verify" to "M2 — expectations (mockkhttp_verify)",
            "intercepts" to "M3 — the live Debug pause (mockkhttp_await_intercept)",
            "pause-policy" to "M3 — the live Debug pause (mockkhttp_pause_policy)",
            "app" to "M4 — app control (mockkhttp_app)"
        )

        /**
         * Routes where a **POST** only reads: it carries a filter or a matcher too big for a query
         * string. The verb matters — `DELETE …/flows` clears the journal and must stay a write.
         */
        private val READ_SHAPED_POST_ROUTES = setOf(
            RESOURCE_FLOWS,
            "$RESOURCE_FLOWS/await",
            "$RESOURCE_MOCKS/explain"
        )

        private const val READ_BUFFER_BYTES = 8 * 1024
    }

    /**
     * Publish a route this file does not know about — the audit log's `GET /v1/audit`, and whatever
     * a later milestone bolts on — without editing the router.
     *
     * [resource] is the first segment after `/v1` (global) or after `/v1/projects/{pid}`. Registered
     * routes win over the built-in table, so a resource can also be overridden in a test. Mutation
     * is classified from the HTTP verb for these, conservatively: anything but GET is a write.
     */
    fun registerRoute(resource: String, handler: ControlRouteHandler) {
        extraRoutes[resource] = handler
    }

    // ========================================================================
    // HttpHandler
    // ========================================================================

    override fun handle(exchange: HttpExchange) {
        val startedAt = System.currentTimeMillis()
        val rawMethod = exchange.requestMethod.uppercase(Locale.ROOT)
        val rawPath = exchange.requestURI.rawPath ?: "/"
        val headers = ControlAuth.lowerCaseHeaders(exchange.requestHeaders)
        val remote = exchange.remoteAddress?.address
        val clientName = server.auth.clientNameOf(headers)
        val route = parseRoute(rawPath, rawMethod)

        // THE try/catch. Every failure below becomes a typed envelope; nothing escapes to the JDK
        // server, which would drop the socket and leave the caller waiting out its own timeout.
        val response: ControlResponse = try {
            serve(exchange, route, headers, remote)
        } catch (t: Throwable) {
            internalError(rawMethod, rawPath, t)
        }

        try {
            respond(exchange, response, headOnly = rawMethod == "HEAD")
        } catch (e: IOException) {
            // The caller hung up: a bridge killed mid-call, or a long-poll abandoned. Not a fault of
            // ours and not worth an error-level line that reads like a plugin crash.
            logger.info("⚠️ Agent control: could not write the response for $rawPath — ${e.message}")
        } finally {
            exchange.close()
            server.notifyCall(
                ControlCall(
                    clientName = clientName,
                    method = rawMethod,
                    path = rawPath,
                    status = response.status,
                    mutating = route.mutating,
                    durationMs = System.currentTimeMillis() - startedAt
                )
            )
        }
    }

    private fun serve(
        exchange: HttpExchange,
        route: Route,
        headers: Map<String, String>,
        remote: InetAddress?
    ): ControlResponse {
        val auth = server.auth

        // Auth first, and before the body is read — see the class KDoc. A stranger also learns
        // nothing about which paths exist, because this runs before the version gate answers.
        val denied = auth.authorize(
            AuthRequest(
                method = route.method,
                path = route.path,
                remoteAddress = remote,
                headers = headers,
                mutating = route.mutating,
                projectId = route.projectId
            )
        )
        if (denied != null) return ControlResponse.error(denied)

        if (route.traversal) {
            return ControlResponse.fail(
                ErrorCode.INVALID_ARGUMENT,
                "Path segments '.' and '..' are not accepted.",
                "Call the route literally, e.g. GET /$API_PREFIX/meta."
            )
        }
        if (!route.versionOk) return versionError(route)

        val body = readBody(exchange) ?: return ControlResponse.fail(
            ErrorCode.PAYLOAD_TOO_LARGE,
            "The request body is larger than the ${MAX_REQUEST_BODY_BYTES / (1024 * 1024)} MB limit.",
            "Split the payload: import one collection per call, and keep a mock response body small — the app under test has to hold it in memory too."
        )

        val request = ControlRequest(
            method = route.method,
            path = route.path,
            resource = route.resource,
            projectId = route.projectId,
            tail = route.tail,
            query = parseQuery(exchange.requestURI.rawQuery),
            body = body,
            clientName = auth.clientNameOf(headers),
            longPollBudgetMs = if (server.isShuttingDown()) 0L else LONG_POLL_BUDGET_MS,
            readOnly = auth.getAgentControl() != AGENT_CONTROL_FULL
        )

        if (!route.longPoll) return dispatch(request)

        // A long-poll parks a worker for up to LONG_POLL_BUDGET_MS. Two per project, then the caller
        // is told to wait — otherwise a retry loop parks every worker and locks itself out of status.
        if (!auth.tryAcquireLongPoll(request.projectId)) {
            return ControlResponse.error(auth.tooManyWaiters(request.projectId))
        }
        return try {
            dispatch(request)
        } finally {
            auth.releaseLongPoll(request.projectId)
        }
    }

    private fun dispatch(request: ControlRequest): ControlResponse {
        extraRoutes[request.resource]?.let { return it.handle(request) }

        if (request.projectId == null) {
            return when (request.resource) {
                RESOURCE_META, RESOURCE_DOCS -> metaHandler.handle(request)
                RESOURCE_PROJECTS -> if (request.tail.isEmpty()) metaHandler.handle(request) else unknownRoute(request)
                else -> futureOrUnknown(request)
            }
        }

        return when (request.resource) {
            RESOURCE_STATUS, RESOURCE_SESSION, "devices", "device", "app" -> sessionHandler.handle(request)
            RESOURCE_FLOWS -> flowsHandler.handle(request)
            RESOURCE_MOCKS -> mocksHandler.handle(request)
            else -> futureOrUnknown(request)
        }
    }

    // ========================================================================
    // Route parsing
    // ========================================================================

    /**
     * Everything the path alone decides. Never fails: a bad path produces a [Route] whose
     * [versionOk] or [traversal] flag makes [serve] answer, so authorisation still runs first.
     */
    private data class Route(
        val method: String,
        val path: String,
        val resource: String,
        val projectId: String?,
        val tail: List<String>,
        val versionOk: Boolean,
        val versionSegment: String,
        val traversal: Boolean,
        val mutating: Boolean,
        val longPoll: Boolean
    )

    private fun parseRoute(rawPath: String, rawMethod: String): Route {
        val rawSegments = rawPath.split('/').filter { it.isNotEmpty() }
        val segments = rawSegments.map { decodePathSegment(it) }
        // Checked on the DECODED segments, because those are what routing, projectId and the echoed
        // path are built from. Testing the raw ones let `%2e%2e` through the door and arrive at
        // dispatch as "..". No route reaches the filesystem today, so this was not exploitable —
        // but a guard that inspects something other than what is used is not a guard.
        val traversal = segments.any { it == "." || it == ".." }
        val versionSegment = segments.firstOrNull().orEmpty()
        val versionOk = versionSegment == API_PREFIX

        val afterVersion = if (versionOk) segments.drop(1) else emptyList()
        val projectScoped = afterVersion.size >= 3 && afterVersion[0] == RESOURCE_PROJECTS
        val projectId = if (projectScoped) afterVersion[1] else null
        val resource = if (projectScoped) afterVersion[2] else afterVersion.firstOrNull().orEmpty()
        val tail = if (projectScoped) afterVersion.drop(3) else afterVersion.drop(1)

        // HEAD is served as GET and answered without a body. Nothing here is expensive enough for
        // that to be worth a second code path, and refusing HEAD only makes probes look broken.
        val method = if (rawMethod == "HEAD") "GET" else rawMethod

        return Route(
            method = method,
            path = "/" + segments.joinToString("/"),
            resource = resource,
            projectId = projectId,
            tail = tail,
            versionOk = versionOk,
            versionSegment = versionSegment,
            traversal = traversal,
            mutating = isMutating(method, resource, projectId, tail),
            longPoll = versionOk && isLongPoll(resource, tail)
        )
    }

    /**
     * Whether this route can change state — the input to READ_ONLY enforcement.
     *
     * Derived from the route AND the verb, not from the verb alone: `flows`, `flows/await` and
     * `mocks/explain` accept a POST that only reads (a filter or a Matcher does not fit in a query
     * string, and several HTTP clients refuse to put a body on a GET), and refusing those under
     * READ_ONLY would break exactly the observation that mode exists to allow. The verb still
     * matters on the same paths — `DELETE …/flows` clears the journal and stays a write — and
     * `mocks/import` counts as mutating even with `dry_run:true`, because the router does not parse
     * bodies to decide.
     */
    private fun isMutating(method: String, resource: String, projectId: String?, tail: List<String>): Boolean {
        if (method == "GET" || method == "HEAD") return false
        if (projectId == null && resource in setOf(RESOURCE_META, RESOURCE_DOCS, RESOURCE_PROJECTS)) return false
        val route = if (tail.isEmpty()) resource else "$resource/${tail[0]}"
        // The explain handler takes PUT as a synonym of POST; it is the same read either way.
        val readShaped = (method == "POST" && route in READ_SHAPED_POST_ROUTES) ||
                (method == "PUT" && route == "$RESOURCE_MOCKS/explain")
        return !readShaped
    }

    /** Routes that block server-side. `intercepts/next` joins this in M3. */
    private fun isLongPoll(resource: String, tail: List<String>): Boolean =
        resource == RESOURCE_FLOWS && tail.firstOrNull() == "await"

    // ========================================================================
    // Errors
    // ========================================================================

    private fun versionError(route: Route): ControlResponse {
        val segment = route.versionSegment
        val looksVersioned = segment.length > 1 && segment[0] == 'v' && segment.drop(1).all { it.isDigit() }
        return ControlResponse.fail(
            ErrorCode.UNSUPPORTED_ACTION,
            if (looksVersioned) "This IDE serves API version $API_VERSION only; '/$segment' is not served."
            else "Every control-plane route lives under /$API_PREFIX.",
            "Call GET /$API_PREFIX/meta first — it reports api_version, the milestone this build implements, and the routes it can answer."
        )
    }

    private fun futureOrUnknown(request: ControlRequest): ControlResponse {
        val milestone = FUTURE_RESOURCES[request.resource] ?: return unknownRoute(request)
        return ControlResponse.fail(
            ErrorCode.NOT_IMPLEMENTED,
            "'${request.resource}' is part of the contract but is not built in this release.",
            "It lands in $milestone. GET /$API_PREFIX/meta lists what this build implements in `capabilities` and what it does not in `not_implemented_yet`."
        )
    }

    private fun unknownRoute(request: ControlRequest): ControlResponse = ControlResponse.fail(
        ErrorCode.UNSUPPORTED_ACTION,
        "No route matches ${request.method} ${request.path}.",
        "Global routes: /$API_PREFIX/meta, /$API_PREFIX/projects, /$API_PREFIX/docs. " +
                "Project routes: /$API_PREFIX/projects/{project_id}/{status|session|flows|mocks}. " +
                "Call GET /$API_PREFIX/projects for the ids this IDE currently has open."
    )

    private fun internalError(method: String, path: String, t: Throwable): ControlResponse {
        val cancelled = t is ProcessCanceledException
        // Logged at WARN with the stack trace, deliberately not at ERROR: Logger.error() raises the
        // IDE's fatal-error dialog, and a project disposed under us mid-call, or a store throwing
        // during shutdown, is the user's environment rather than a bug worth reporting to JetBrains.
        // The trace is still in idea.log, which is what the hint points at.
        if (cancelled) {
            logger.info("⚠️ Agent control: $method $path was cancelled by the IDE")
        } else {
            logger.warn("❌ Agent control: $method $path failed", t)
        }
        return ControlResponse.fail(
            ErrorCode.INTERNAL_ERROR,
            if (cancelled) "The IDE cancelled this operation before it finished."
            else "${t.javaClass.simpleName}: ${t.message ?: "no message"}",
            "Retry once — this is retryable. If it repeats it is a MockkHttp bug: the stack trace is in idea.log (Help → Show Log) under com.sergiy.dev.mockkhttp."
        )
    }

    // ========================================================================
    // HTTP plumbing
    // ========================================================================

    /** @return the body, or null when it exceeds [MAX_REQUEST_BODY_BYTES]. */
    private fun readBody(exchange: HttpExchange): String? {
        val declared = exchange.requestHeaders.getFirst("Content-Length")?.trim()?.toLongOrNull()
        if (declared != null && declared > MAX_REQUEST_BODY_BYTES) return null

        val buffer = ByteArray(READ_BUFFER_BYTES)
        val initial = if (declared != null && declared in 1..MAX_REQUEST_BODY_BYTES) declared.toInt() else 256
        val out = ByteArrayOutputStream(initial)
        exchange.requestBody.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                // Enforced as bytes arrive, not from Content-Length: a chunked body declares no
                // length, and an honest declaration is not something we get to assume.
                if (out.size() + read > MAX_REQUEST_BODY_BYTES) return null
                out.write(buffer, 0, read)
            }
        }
        return out.toString(StandardCharsets.UTF_8)
    }

    private fun respond(exchange: HttpExchange, response: ControlResponse, headOnly: Boolean) {
        val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
        val out = exchange.responseHeaders
        out.set("Content-Type", response.contentType)
        out.set("X-MockkHttp-Api-Version", API_VERSION)
        // Nothing here is cacheable and nothing here is a document: both headers exist so that a
        // browser that somehow reaches us cannot sniff a response into an executable context.
        out.set("Cache-Control", "no-store")
        out.set("X-Content-Type-Options", "nosniff")
        for ((name, value) in response.headers) out.set(name, value)

        if (headOnly || bytes.isEmpty() || response.status == 204) {
            // -1 means "no response body" to HttpExchange; 0 would mean "chunked, length unknown".
            exchange.sendResponseHeaders(response.status, -1)
            return
        }
        exchange.sendResponseHeaders(response.status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /**
     * Percent-decode one path segment.
     *
     * Not `URLDecoder`: that turns `+` into a space, which is the *query* grammar. In a path a `+`
     * is a literal plus, and silently rewriting one inside a rule or flow id would make it
     * unfindable — the id would come back "unknown" with no clue why.
     */
    private fun decodePathSegment(segment: String): String {
        if ('%' !in segment) return segment
        val bytes = ByteArrayOutputStream(segment.length)
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            if (c == '%' && i + 2 < segment.length) {
                val value = segment.substring(i + 1, i + 3).toIntOrNull(16)
                if (value != null) {
                    bytes.write(value)
                    i += 3
                    continue
                }
            }
            val encoded = c.toString().toByteArray(StandardCharsets.UTF_8)
            bytes.write(encoded, 0, encoded.size)
            i++
        }
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val name = if (eq < 0) pair else pair.substring(0, eq)
            val value = if (eq < 0) "" else pair.substring(eq + 1)
            out[decodeQueryPart(name)] = decodeQueryPart(value)
        }
        return out
    }

    private fun decodeQueryPart(part: String): String = try {
        URLDecoder.decode(part, StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        // A malformed escape is far more likely to be a caller's typo than an attack; handing the
        // raw text to the handler produces "no rule with id '%zz'", which says what went wrong.
        part
    }
}
