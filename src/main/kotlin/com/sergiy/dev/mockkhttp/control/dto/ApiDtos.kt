package com.sergiy.dev.mockkhttp.control.dto

import com.google.gson.annotations.SerializedName

/**
 * THE wire contract of the MockkHttp agent control plane (`/v1`, milestone M1).
 *
 * Everything an automated caller can send or receive is defined here, once. `ControlApi` speaks
 * only these types; the HTTP layer does nothing but (de)serialise them, and `docs/AGENT_API.md`
 * is generated from this file.
 *
 * ## Two rules that are not stylistic
 *
 * 1. **Request DTOs have nullable fields with null defaults.** Gson instantiates objects through
 *    `Unsafe` and never runs the Kotlin constructor, so a default value is NOT applied when the
 *    JSON omits the field — a non-null `Int = 25` deserialises to `0`, and a non-null `String`
 *    deserialises to `null` while the type system swears it cannot. Every request field is
 *    therefore nullable and every default is applied in [ControlApi], where it can also be
 *    reported back to the caller. Response DTOs are built in Kotlin and may use defaults freely.
 * 2. **Enum-ish inputs are `String`, not Kotlin enums.** Gson maps an unknown enum constant to
 *    `null` without complaining, which turns a typo into silent wrong behaviour. Inputs arrive as
 *    strings and are validated in `ControlApi`, which answers `INVALID_ARGUMENT` naming the legal
 *    values — a model can self-correct from that, but not from a silently ignored field.
 *
 * ## Honesty rules
 *
 * - Bodies always carry BOTH truncation flags: [FlowMessageView.bodyTruncated] (this response
 *   trimmed it to `max_body_chars`) and [FlowMessageView.bodyTruncatedByRetention] (the flow was
 *   ALREADY trimmed on the way into [com.sergiy.dev.mockkhttp.store.FlowStore] and the missing
 *   bytes no longer exist anywhere). An agent reading a body must be able to tell "this is short"
 *   from "this was cut".
 * - Whenever the server reduces a requested value it says so in `clamped`, naming the constraint.
 *   No parameter is ever silently ignored.
 * - Fields the current milestone cannot answer honestly are `null`, never a plausible-looking
 *   zero. `arms`, `intercepts` and `run` in [StatusResponse] are null until M2/M3 land.
 */

// ============================================================================
// Constants shared with the HTTP layer and the MCP bridge
// ============================================================================

/** Value of the `X-MockkHttp-Api-Version` header and of `api_version` in every payload. */
const val API_VERSION: String = "1.0"

/** Capture modes, exactly as [com.sergiy.dev.mockkhttp.proxy.OkHttpInterceptorServer.Mode] names them. */
const val MODE_RECORDING: String = "RECORDING"
const val MODE_DEBUG: String = "DEBUG"
const val MODE_MOCKK: String = "MOCKK"
const val MODE_MOCKK_DEBUG: String = "MOCKK_DEBUG"

/** `agent_control` states (D4). `SettingsStore` grows the persisted key in a later milestone. */
const val AGENT_CONTROL_FULL: String = "full"
const val AGENT_CONTROL_READ_ONLY: String = "read_only"
const val AGENT_CONTROL_OFF: String = "off"

/** Per-field match modes, mirroring [com.sergiy.dev.mockkhttp.model.MatchType]. */
const val MATCH_EXACT: String = "EXACT"
const val MATCH_WILDCARD: String = "WILDCARD"
const val MATCH_REGEX: String = "REGEX"

/** Device platforms, mirroring [com.sergiy.dev.mockkhttp.adb.DevicePlatform]. */
const val PLATFORM_ANDROID: String = "ANDROID"
const val PLATFORM_IOS_SIMULATOR: String = "IOS_SIMULATOR"
const val PLATFORM_IOS_DEVICE: String = "IOS_DEVICE"

/**
 * Where a device listing's app names came from.
 *
 * `announced` is proof — the app spoke the MockkHttp protocol to this IDE. `device_scan` is a
 * detection: it read what is installed. `none` means nothing is known yet.
 */
const val APPS_SOURCE_ANNOUNCED: String = "announced"
const val APPS_SOURCE_DEVICE_SCAN: String = "device_scan"
const val APPS_SOURCE_NONE: String = "none"

/** Who started the running session: the agent control plane, the Inspector, or nobody who said so. */
const val STARTED_BY_AGENT: String = "agent"
const val STARTED_BY_UI: String = "ui"
const val STARTED_BY_UNKNOWN: String = "unknown"

/** `include_body` values for flow listings. */
const val INCLUDE_BODY_NONE: String = "none"
const val INCLUDE_BODY_RESPONSE: String = "response"
const val INCLUDE_BODY_BOTH: String = "both"

/** Body transports. Anything that is not valid, printable UTF-8 comes back base64. */
const val BODY_ENCODING_UTF8: String = "utf8"
const val BODY_ENCODING_BASE64: String = "base64"

/**
 * How a flow was answered.
 *
 * Only [RESOLUTION_MOCKED], [RESOLUTION_MODIFIED] and [RESOLUTION_PASSTHROUGH] can be produced in
 * M1 — the other three describe armed stubs (M2) and the pause (M3). `UNCONFIRMED` exists from day
 * one because it is the honest answer whenever the plugin cannot prove the app consumed its reply.
 */
const val RESOLUTION_STUBBED: String = "STUBBED"
const val RESOLUTION_MOCKED: String = "MOCKED"
const val RESOLUTION_PASSTHROUGH: String = "PASSTHROUGH"
const val RESOLUTION_MODIFIED: String = "MODIFIED"
const val RESOLUTION_TIMEOUT: String = "TIMEOUT"
const val RESOLUTION_UNCONFIRMED: String = "UNCONFIRMED"

/** Import merge strategies, mirroring [com.sergiy.dev.mockkhttp.store.MockkRulesStore.ChangedRuleStrategy]. */
const val IMPORT_REPLACE: String = "REPLACE"
const val IMPORT_KEEP_BOTH: String = "KEEP_BOTH"
const val IMPORT_SKIP: String = "SKIP"

// ============================================================================
// Errors — one envelope for every failure, on every route
// ============================================================================

/**
 * Every domain failure the control plane can produce.
 *
 * [httpStatus] is what the HTTP layer must return; [retryable] tells the caller whether repeating
 * the identical call could ever succeed (the MCP bridge turns this into "wait and retry" vs "fix
 * your arguments"). Both travel with the code so no router has to keep a parallel table.
 */
enum class ErrorCode(val httpStatus: Int, val retryable: Boolean) {

    // --- caller's fault: arguments -------------------------------------------------------------
    /** A parameter is missing, malformed, or outside its legal set. `hint` names the legal values. */
    INVALID_ARGUMENT(400, false),
    /** The `action` is unknown, or known but not implemented in this milestone. */
    UNSUPPORTED_ACTION(400, false),
    /** The request body was not parseable JSON. */
    MALFORMED_JSON(400, false),
    /** A URL could not be parsed by `java.net.URI`. */
    INVALID_URL(400, false),
    /** A regex (path_regex, or a REGEX query/header matcher) does not compile. */
    INVALID_REGEX(400, false),
    /** A rule must live in a collection and none could be resolved or created. */
    COLLECTION_REQUIRED(400, false),
    /** DEBUG / MOCKK_DEBUG would pause EVERY request with nobody to answer. See the hint. */
    PAUSE_POLICY_REQUIRED(400, false),

    // --- caller's fault: authorisation ----------------------------------------------------------
    /** Missing or wrong bearer token. Raised by the auth layer, never by ControlApi. */
    UNAUTHORIZED(401, false),
    /** Loopback / browser-lockout / client-header rejection. Raised by the auth layer. */
    FORBIDDEN(403, false),
    /** `include_secrets:true` without the per-project settings toggle. */
    REVEAL_DISABLED(403, false),
    /** Agent control is READ_ONLY and the verb mutates. Raised by the auth layer. */
    READ_ONLY(403, false),

    // --- addressing -----------------------------------------------------------------------------
    /** No open project matches the given id (or no project is open at all). */
    PROJECT_NOT_FOUND(404, false),
    /** Several projects are open and none was named. */
    AMBIGUOUS_PROJECT(409, false),
    /** The project was closed between resolution and use. */
    PROJECT_DISPOSED(409, false),
    /** No flow with that id is retained any more (the cache is a bounded ring). */
    UNKNOWN_FLOW(404, false),
    /** No mock rule with that id. */
    UNKNOWN_RULE(404, false),
    /** No collection with that id. */
    UNKNOWN_COLLECTION(404, false),

    // --- state ----------------------------------------------------------------------------------
    /** Nothing is capturing for this project, so the call would have been a success-shaped no-op. */
    SESSION_NOT_RUNNING(409, true),
    /** Port 9876 is owned by another process; capture cannot work until that is resolved. */
    INTERCEPTOR_PORT_IN_USE(409, true),
    /** The interceptor accepted no registration. Not the caller's fault; the IDE log says why. */
    SESSION_START_FAILED(500, true),

    // --- choosing a capture target ---------------------------------------------------------------
    /** No adb executable, or the ADB bridge would not start. Fixable, then retryable. */
    ADB_UNAVAILABLE(409, true),
    /** Nothing is connected: no ADB device, no booted simulator, no paired iPhone. */
    NO_DEVICES(409, true),
    /** A serial was named and no connected device has it. `details` lists the ones that exist. */
    DEVICE_NOT_FOUND(404, false),
    /** Several devices are connected and none was named. `details` lists them. */
    AMBIGUOUS_DEVICE(409, false),
    /** The device is connected but cannot reach the plugin — `adb reverse` failed on a phone. */
    DEVICE_UNREACHABLE(409, true),
    /**
     * No package filter was given and none could be inferred.
     *
     * Retryable on purpose: the fix is usually to launch the app, which then announces itself and
     * makes the identical call succeed. Passing `package_name` explicitly also works.
     */
    APP_NOT_CHOSEN(409, true),
    /** Several instrumented apps are candidates and none was named. `details` lists them. */
    AMBIGUOUS_APP(409, false),

    // --- transport budgets (raised by the HTTP layer) --------------------------------------------
    /** Request body over the 8 MB cap. */
    PAYLOAD_TOO_LARGE(413, false),
    /** Token bucket exhausted. */
    RATE_LIMITED(429, true),
    /** Too many concurrent long-polls for this project. */
    TOO_MANY_WAITERS(429, true),

    // --- ours ------------------------------------------------------------------------------------
    /** An unexpected throwable. Always logged with a stack trace on the plugin side. */
    INTERNAL_ERROR(500, true),
    /** A store rejected the write. */
    STORE_ERROR(500, false),
    /** The route exists in the contract but lands in a later milestone. */
    NOT_IMPLEMENTED(501, false),
    /** Android-only verb asked of an iOS target (and vice versa). */
    NOT_SUPPORTED_ON_PLATFORM(501, false)
}

/**
 * The one error shape. `hint` is not decoration: it must contain the exact next call to make,
 * because that is what turns a failed tool call into a self-correcting round trip.
 */
data class ApiError(
    @SerializedName("code") val code: ErrorCode,
    @SerializedName("message") val message: String,
    @SerializedName("hint") val hint: String? = null,
    @SerializedName("retryable") val retryable: Boolean = code.retryable,
    @SerializedName("details") val details: Map<String, String>? = null
)

/** What the HTTP layer serialises on any non-2xx: `{"error": {...}}`. */
data class ErrorEnvelope(
    @SerializedName("error") val error: ApiError
)

/**
 * The return type of every [com.sergiy.dev.mockkhttp.control.ControlApi] method.
 *
 * Not an exception type on purpose: a domain failure here is data (it carries a hint the model is
 * meant to act on), and making the caller `when` over it means no route can forget to translate it.
 */
sealed class ApiResult<out T : Any> {

    data class Ok<out T : Any>(val value: T) : ApiResult<T>()

    data class Err(val error: ApiError) : ApiResult<Nothing>()

    companion object {
        fun <T : Any> ok(value: T): ApiResult<T> = Ok(value)

        fun fail(
            code: ErrorCode,
            message: String,
            hint: String? = null,
            details: Map<String, String>? = null
        ): ApiResult<Nothing> = Err(ApiError(code, message, hint, code.retryable, details))
    }
}

/**
 * Reported whenever the server reduced a requested value, so a caller can never mistake a clamp
 * for its own parameter having been honoured.
 */
data class ClampInfo(
    @SerializedName("requested") val requested: Long,
    @SerializedName("applied") val applied: Long,
    @SerializedName("reason") val reason: String
)

// ============================================================================
// Meta & projects
// ============================================================================

/** Hard numbers a caller should not have to discover by being rejected. */
data class LimitsDto(
    @SerializedName("max_body_chars_default") val maxBodyCharsDefault: Int,
    @SerializedName("max_body_chars_hard") val maxBodyCharsHard: Int,
    @SerializedName("flow_limit_default") val flowLimitDefault: Int,
    @SerializedName("flow_limit_max") val flowLimitMax: Int,
    @SerializedName("await_wait_ms_max") val awaitWaitMsMax: Long,
    @SerializedName("request_body_bytes_max") val requestBodyBytesMax: Int,
    @SerializedName("redacted_headers") val redactedHeaders: List<String>
)

/** `GET /v1/meta` — who is answering, what it can do, and what it will refuse. */
data class MetaResponse(
    @SerializedName("api_version") val apiVersion: String,
    @SerializedName("plugin_version") val pluginVersion: String,
    @SerializedName("ide") val ide: String,
    @SerializedName("ide_build") val ideBuild: String,
    @SerializedName("instance_id") val instanceId: String?,
    /**
     * WRITE PERMISSION — `full` | `read_only` | `off`. NOT feature coverage.
     *
     * Named `write_access` because `agent_control: "full"` was read by agents as "everything is
     * implemented", so they tried to start a capture session and burned turns on a route that
     * answers NOT_IMPLEMENTED. What this build actually implements is [capabilities]; what it does
     * not is [notImplementedYet].
     */
    @SerializedName("write_access") val writeAccess: String,
    @SerializedName("milestone") val milestone: String,
    /** Verb families this build actually implements, e.g. `flows`, `mocks`, `session_mode`. */
    @SerializedName("capabilities") val capabilities: List<String>,
    /** Named so a caller knows why `arm`/`verify` are absent rather than guessing. */
    @SerializedName("not_implemented_yet") val notImplementedYet: List<String>,
    @SerializedName("limits") val limits: LimitsDto,
    @SerializedName("open_projects") val openProjects: Int
)

/** One open project, as seen from outside the IDE. `project_id` is `Project.locationHash`. */
data class ProjectSummary(
    @SerializedName("project_id") val projectId: String,
    @SerializedName("name") val name: String,
    @SerializedName("base_path") val basePath: String?,
    @SerializedName("session_running") val sessionRunning: Boolean,
    @SerializedName("mode") val mode: String?,
    @SerializedName("package_filter") val packageFilter: String?
)

/** `GET /v1/projects` — the input to the bridge's longest-prefix cwd match. */
data class ProjectsResponse(
    @SerializedName("projects") val projects: List<ProjectSummary>,
    @SerializedName("count") val count: Int,
    @SerializedName("hint") val hint: String? = null
)

// ============================================================================
// Status
// ============================================================================

/** Why this project answered — lets a model notice it is driving the wrong IDE window. */
data class ResolutionInfo(
    /** `cwd-prefix` | `env-pin` | `explicit-arg` | `base-url-override` | `sole-open-project`. */
    @SerializedName("matched_by") val matchedBy: String,
    @SerializedName("cwd") val cwd: String?,
    @SerializedName("instance_id") val instanceId: String?,
    @SerializedName("ide") val ide: String,
    @SerializedName("plugin_version") val pluginVersion: String,
    @SerializedName("project_id") val projectId: String,
    @SerializedName("project_name") val projectName: String,
    @SerializedName("base_path") val basePath: String?,
    @SerializedName("other_open_projects") val otherOpenProjects: List<ProjectSummary>
)

/** The socket on 9876 — bound or not, and if not, why. */
data class InterceptorInfo(
    @SerializedName("port") val port: Int,
    @SerializedName("bound") val bound: Boolean,
    @SerializedName("owned_by_this_process") val ownedByThisProcess: Boolean,
    @SerializedName("held_by_pid") val heldByPid: Int?,
    @SerializedName("bind_error") val bindError: String?
)

/** Capture target. Null everywhere in M1: device selection still lives in the Swing Inspector. */
data class DeviceInfo(
    @SerializedName("serial") val serial: String,
    @SerializedName("platform") val platform: String,
    @SerializedName("model") val model: String?,
    @SerializedName("online") val online: Boolean,
    @SerializedName("api_level") val apiLevel: Int?
)

/**
 * What the capture session is doing right now.
 *
 * Built from `CaptureSessionService.state()`, which reads `running` / `mode` / `package_filter`
 * off the live interceptor registration — the same registration the data plane answers
 * `CHECK_MOCK` from. There is deliberately no second copy of those three anywhere.
 */
data class SessionInfo(
    @SerializedName("running") val running: Boolean,
    @SerializedName("mode") val mode: String?,
    @SerializedName("package_filter") val packageFilter: String?,
    /**
     * The device the session targets, as observed when it started — `devices` is the live view.
     * Null when the session is stopped, and also when it was started by the Inspector before
     * `CaptureSessionService` existed to record a device.
     */
    @SerializedName("device") val device: DeviceInfo?,
    @SerializedName("started_at") val startedAt: Long?,
    @SerializedName("adb_path") val adbPath: String?,
    /** `agent` | `ui` | `unknown` — who started what is running. Null while stopped. */
    @SerializedName("started_by") val startedBy: String?,
    /** Packages that have spoken the MockkHttp protocol to this IDE at least once. */
    @SerializedName("instrumented_packages") val instrumentedPackages: List<String>
)

/** Client build capabilities from the CHECK_MOCK handshake. Null until the 1.8 clients ship. */
data class ClientDedupInfo(
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("window_ms") val windowMs: Int,
    @SerializedName("controllable") val controllable: Boolean
)

/**
 * The app's MockkHttp library, as it last described itself on port 9876 — clients from 1.8.0 on
 * send a `client` object with every message. `stats` are the client's own counters (flows sent,
 * by layer where it has two; passes one layer yielded to the other; claims made and withdrawn):
 * the way to check from outside that one request is one flow. Null for older clients.
 */
data class ClientInfo(
    @SerializedName("library") val library: String?,
    @SerializedName("version") val version: String?,
    @SerializedName("platform") val platform: String? = null,
    /** The app the report came from, when it said. */
    @SerializedName("package_name") val packageName: String? = null,
    @SerializedName("read_timeout_ms") val readTimeoutMs: Long?,
    @SerializedName("dedup") val dedup: ClientDedupInfo?,
    @SerializedName("caps") val caps: List<String>,
    @SerializedName("stats") val stats: Map<String, Long>? = null,
    /**
     * The client's counters start from zero on every app start; `run_id` changes with it and
     * `started_at` says when. `since_clear` is what to compare with `flows.count`: the client's
     * `flows_sent` minus its value when this project's flows were last cleared (its whole count
     * when it had sent nothing by then), `comparable` exactly when every flow in the store came
     * from this run, nothing was evicted since the clear, and no message of this app was rejected
     * since the clear — see [SinceClear].
     */
    @SerializedName("run_id") val runId: String? = null,
    @SerializedName("started_at") val startedAt: Long? = null,
    @SerializedName("since_clear") val sinceClear: SinceClear? = null,
    /** Epoch millis of the message that carried this report. */
    @SerializedName("seen_at") val seenAt: Long? = null
)

data class FlowsSummary(
    @SerializedName("count") val count: Int,
    /** Cursor: pass this back as `since_seq` to see only what arrives after this call. */
    @SerializedName("next_seq") val nextSeq: Long,
    /** The most flows the store keeps (Settings → MockkHttp → Cache); beyond it the oldest are evicted. */
    @SerializedName("capacity") val capacity: Int,
    /** Flows the cap has evicted since the last clear: while it is not zero, `count` is a window, not a total. */
    @SerializedName("evicted_since_clear") val evictedSinceClear: Int,
    /**
     * Messages on port 9876 from this project's app that the plugin could not turn into a flow
     * since the last clear (malformed FLOW or CHECK_MOCK; idea.log says "MockkHttp rejected").
     * Answered to the app, counted by the app, absent from `count`. Scoped to the app the project
     * captures — its filter, or the app that reported — plus the rejections no app could be named
     * for (a message cut short on the wire cannot say whose it is); never another named app's.
     */
    @SerializedName("rejected_since_clear") val rejectedSinceClear: Long,
    /** Headers dropped from stored flows since the last clear (a value that was not text); the flows themselves are in `count`. */
    @SerializedName("headers_dropped_since_clear") val headersDroppedSinceClear: Long = 0L
)

data class MocksSummary(
    @SerializedName("collections") val collections: Int,
    @SerializedName("rules") val rules: Int,
    /** Rules that are enabled AND live in an enabled collection — i.e. rules that can actually fire. */
    @SerializedName("rules_enabled") val rulesEnabled: Int
)

/** M2. Present in the contract from day one so the shape never changes under a caller. */
data class ArmsSummary(
    @SerializedName("active") val active: Int,
    @SerializedName("expired_since_last_status") val expiredSinceLastStatus: Int
)

/** M3. */
data class InterceptsSummary(
    @SerializedName("pending") val pending: Int
)

/** M2. */
data class RunSummary(
    @SerializedName("run_id") val runId: String,
    @SerializedName("started_at") val startedAt: Long,
    @SerializedName("expires_at") val expiresAt: Long
)

/**
 * The client's count of flows sent since this project's flows were last cleared — the number to
 * hold against `flows.count` — and whether that comparison is honest right now. It is exactly
 * when every flow in the store came from the reporting run, the store has evicted nothing since
 * the clear, AND the plugin has rejected no message from this app since the clear; a flow an
 * earlier run sent before a restart, an older client's, a flow the cap pushed out, or a message
 * that never became a flow makes `comparable` false, and `reason` says how many and what they
 * are. A `clear_flows` makes it true again.
 */
data class SinceClear(
    @SerializedName("flows_sent") val flowsSent: Long,
    @SerializedName("comparable") val comparable: Boolean,
    @SerializedName("reason") val reason: String? = null,
    /**
     * Flows in `flows.count` this run did not send: an earlier run's after a restart, an older
     * client's, an import's. Zero is what makes the comparison exact — counted from the flows
     * themselves, each stamped with the run whose message carried it.
     */
    @SerializedName("flows_not_from_this_run") val flowsNotFromThisRun: Int = 0,
    /**
     * Flows the store's cap has evicted since the last clear. While it is not zero `flows.count` is
     * a window onto the traffic, not a total, and no comparison with it is exact (audit round 11, AQ).
     */
    @SerializedName("flows_evicted_since_clear") val flowsEvictedSinceClear: Int = 0,
    /**
     * Messages the plugin rejected since the last clear: the app counted them as sent, the store
     * never saw them. While it is not zero the comparison cannot be exact (audit round 12, AS).
     */
    @SerializedName("flows_rejected_since_clear") val flowsRejectedSinceClear: Long = 0L,
    /**
     * Headers dropped from this app's stored flows since the last clear because their value was
     * not text. The flows are stored and counted; they are shorter than the app sent them, and
     * idea.log names each header (audit round 15, AZ). Does not affect `comparable`.
     */
    @SerializedName("headers_dropped") val headersDropped: Long = 0L
)

/** `GET /v1/projects/{pid}/status` — the first call of every session. */
data class StatusResponse(
    @SerializedName("api_version") val apiVersion: String,
    @SerializedName("resolution") val resolution: ResolutionInfo,
    /** Write permission (`full` | `read_only` | `off`), not feature coverage. See MetaResponse. */
    @SerializedName("write_access") val writeAccess: String,
    /**
     * FEATURE coverage, repeated here from `GET /v1/meta` on purpose.
     *
     * `status` is the first call of every session, and an agent that reads it should not have to
     * make a second call — or read prose from `docs?topic=limits` — to learn whether the verb it
     * is about to use exists at all. Same two lists, same meaning as [MetaResponse.capabilities]
     * and [MetaResponse.notImplementedYet].
     */
    @SerializedName("capabilities") val capabilities: List<String>,
    @SerializedName("not_implemented_yet") val notImplementedYet: List<String>,
    @SerializedName("interceptor") val interceptor: InterceptorInfo,
    @SerializedName("session") val session: SessionInfo,
    @SerializedName("client") val client: ClientInfo?,
    @SerializedName("flows") val flows: FlowsSummary,
    @SerializedName("mocks") val mocks: MocksSummary,
    @SerializedName("arms") val arms: ArmsSummary?,
    @SerializedName("intercepts") val intercepts: InterceptsSummary?,
    @SerializedName("run") val run: RunSummary?,
    @SerializedName("warnings") val warnings: List<String>
)

/** Optional context the bridge knows and the plugin does not. All fields optional. */
data class StatusRequest(
    @SerializedName("matched_by") val matchedBy: String? = null,
    @SerializedName("cwd") val cwd: String? = null
)

// ============================================================================
// Matching (shared by flow queries and await; armed stubs and verify reuse it in M2)
// ============================================================================

/** One query-parameter condition. `match`: EXACT | WILDCARD (presence only) | REGEX. */
data class QueryMatchDto(
    @SerializedName("key") val key: String? = null,
    @SerializedName("value") val value: String? = null,
    @SerializedName("required") val required: Boolean? = null,
    @SerializedName("match") val match: String? = null
)

/** One header condition. `match`: EXACT | REGEX. Header names compare case-insensitively. */
data class HeaderMatchDto(
    @SerializedName("key") val key: String? = null,
    @SerializedName("value") val value: String? = null,
    @SerializedName("match") val match: String? = null
)

/**
 * Every present field must match (AND). An empty matcher matches everything.
 *
 * `path` is exact and case-SENSITIVE with a normalised trailing slash; `host` is exact and
 * case-insensitive; `path_regex` is anchored (it must match the whole path) and is mutually
 * exclusive with `path`.
 */
data class MatcherDto(
    @SerializedName("method") val method: String? = null,
    @SerializedName("host") val host: String? = null,
    @SerializedName("path") val path: String? = null,
    @SerializedName("path_regex") val pathRegex: String? = null,
    @SerializedName("url_contains") val urlContains: String? = null,
    @SerializedName("query") val query: List<QueryMatchDto>? = null,
    @SerializedName("header") val header: List<HeaderMatchDto>? = null,
    @SerializedName("body_contains") val bodyContains: String? = null
)

// ============================================================================
// Flows
// ============================================================================

/**
 * `GET /v1/projects/{pid}/flows`.
 *
 * `since_seq` is INCLUSIVE — it returns flows whose `seq` is >= the value — and the cursor to pass
 * is always the `next_seq` of the previous response. Nothing else is a supported cursor: sequence
 * numbers are assigned by the control plane in FlowStore insertion order and are stable per flow.
 */
data class FlowQuery(
    @SerializedName("method") val method: String? = null,
    @SerializedName("host") val host: String? = null,
    @SerializedName("path") val path: String? = null,
    /** Case-insensitive substring of the path — the forgiving alternative to exact `path`. */
    @SerializedName("path_contains") val pathContains: String? = null,
    @SerializedName("path_regex") val pathRegex: String? = null,
    @SerializedName("url_contains") val urlContains: String? = null,
    @SerializedName("body_contains") val bodyContains: String? = null,
    @SerializedName("status") val status: Int? = null,
    @SerializedName("status_min") val statusMin: Int? = null,
    @SerializedName("status_max") val statusMax: Int? = null,
    @SerializedName("resolution") val resolution: String? = null,
    @SerializedName("since_seq") val sinceSeq: Long? = null,
    @SerializedName("limit") val limit: Int? = null,
    /** `none` (default) | `response` | `both`. */
    @SerializedName("include_body") val includeBody: String? = null,
    @SerializedName("max_body_chars") val maxBodyChars: Int? = null,
    @SerializedName("include_secrets") val includeSecrets: Boolean? = null
)

/**
 * One captured exchange, without bodies.
 *
 * `app_notified` is null in M1: proving the app consumed our reply needs the `writer.checkError()`
 * signal that only exists on the pause path (M3). It is never guessed.
 */
data class FlowSummary(
    @SerializedName("flow_id") val flowId: String,
    @SerializedName("seq") val seq: Long,
    @SerializedName("ts") val ts: Long,
    @SerializedName("method") val method: String,
    @SerializedName("url") val url: String,
    @SerializedName("host") val host: String,
    @SerializedName("path") val path: String,
    @SerializedName("status") val status: Int?,
    @SerializedName("duration_ms") val durationMs: Long,
    @SerializedName("req_body_bytes") val reqBodyBytes: Int,
    @SerializedName("res_body_bytes") val resBodyBytes: Int,
    @SerializedName("resolution") val resolution: String,
    @SerializedName("source_id") val sourceId: String?,
    @SerializedName("app_notified") val appNotified: Boolean?,
    @SerializedName("paused") val paused: Boolean,
    @SerializedName("mock_rule_name") val mockRuleName: String?,
    /** Query parameters in `url` whose value was replaced with `<redacted:Nb>` (a credential: an API key, a token). */
    @SerializedName("redacted_query") val redactedQuery: List<String> = emptyList(),
    /** Credential-bearing query parameters handed over IN CLEAR because `include_secrets` was set. */
    @SerializedName("revealed_query") val revealedQuery: List<String> = emptyList()
)

/**
 * Headers + body of one half of an exchange.
 *
 * Read [bodyTruncated] and [bodyTruncatedByRetention] together: the first says this response
 * trimmed the body to `max_body_chars` (ask again with a bigger budget); the second says the bytes
 * were already discarded when the flow was retained (Settings → Cache) and are gone for good.
 */
data class FlowMessageView(
    @SerializedName("headers") val headers: Map<String, String>,
    @SerializedName("body") val body: String?,
    @SerializedName("body_base64") val bodyBase64: String?,
    @SerializedName("body_encoding") val bodyEncoding: String,
    @SerializedName("body_truncated") val bodyTruncated: Boolean,
    @SerializedName("body_truncated_by_retention") val bodyTruncatedByRetention: Boolean,
    @SerializedName("body_bytes") val bodyBytes: Int,
    /** Sensitive headers whose value was replaced with `<redacted:Nb>`. */
    @SerializedName("redacted_headers") val redactedHeaders: List<String>,
    /**
     * Sensitive headers handed over IN CLEAR because `include_secrets` was set.
     *
     * Never both non-empty with [redactedHeaders]. This exists so a payload carrying live
     * credentials says so: these answers end up in test reports and logs, and an empty
     * `redacted_headers` alone could not be told apart from "there was nothing sensitive here".
     */
    @SerializedName("revealed_headers") val revealedHeaders: List<String> = emptyList(),
    /** Request half only: query parameters of the URL whose credential value was replaced with `<redacted:Nb>`. */
    @SerializedName("redacted_query") val redactedQuery: List<String> = emptyList(),
    /** Request half only: credential-bearing query parameters handed over IN CLEAR under `include_secrets`. */
    @SerializedName("revealed_query") val revealedQuery: List<String> = emptyList()
)

/** `GET …/flows/{flowId}` — always includes both bodies, subject to `max_body_chars`. */
data class FlowDetail(
    @SerializedName("flow") val flow: FlowSummary,
    @SerializedName("request") val request: FlowMessageView,
    @SerializedName("response") val response: FlowMessageView?,
    /** True when either half was already trimmed by the retention cache. */
    @SerializedName("stored_body_truncated_by_retention") val storedBodyTruncatedByRetention: Boolean,
    @SerializedName("clamped") val clamped: Map<String, ClampInfo>? = null,
    @SerializedName("warnings") val warnings: List<String> = emptyList()
)

data class FlowListResponse(
    @SerializedName("next_seq") val nextSeq: Long,
    @SerializedName("returned") val returned: Int,
    @SerializedName("total_matching") val totalMatching: Int,
    /** Newest first (descending `seq`): a caller almost always wants what just happened. */
    @SerializedName("flows") val flows: List<FlowSummary>,
    /** Present only when `include_body` asked for bodies; parallel to [flows] by flow_id. */
    @SerializedName("bodies") val bodies: Map<String, FlowBodies>? = null,
    /** The capturing library's own counters — see [ClientInfo]. Null until a 1.8.0 client has spoken. */
    @SerializedName("client") val client: ClientInfo? = null,
    @SerializedName("note") val note: String? = null,
    @SerializedName("clamped") val clamped: Map<String, ClampInfo>? = null,
    @SerializedName("warnings") val warnings: List<String> = emptyList()
)

/** Bodies attached to a listing entry when `include_body` is `response` or `both`. */
data class FlowBodies(
    @SerializedName("request") val request: FlowMessageView?,
    @SerializedName("response") val response: FlowMessageView?
)

data class ClearFlowsResponse(
    @SerializedName("cleared") val cleared: Int,
    @SerializedName("next_seq") val nextSeq: Long
)

/**
 * `GET …/flows/await` — deterministic waiting instead of `sleep`.
 *
 * `wait_ms` is what the CALLER asks for; the HTTP layer decides the ceiling and ControlApi enforces
 * exactly what it is handed. Timing out is NOT an error: it comes back `satisfied:false` with
 * `closest_observed`, which is what lets a model fix a near-miss matcher in one round trip.
 */
data class AwaitFlowRequest(
    @SerializedName("match") val match: MatcherDto? = null,
    @SerializedName("count") val count: Int? = null,
    @SerializedName("since_seq") val sinceSeq: Long? = null,
    @SerializedName("wait_ms") val waitMs: Long? = null,
    @SerializedName("include_body") val includeBody: String? = null,
    @SerializedName("max_body_chars") val maxBodyChars: Int? = null,
    @SerializedName("include_secrets") val includeSecrets: Boolean? = null
)

/** Traffic that arrived while waiting but did not match — the near-miss report. */
data class ClosestObserved(
    @SerializedName("method") val method: String,
    @SerializedName("path") val path: String,
    @SerializedName("hits") val hits: Int
)

data class AwaitFlowResponse(
    @SerializedName("satisfied") val satisfied: Boolean,
    @SerializedName("actual") val actual: Int,
    @SerializedName("expected") val expected: Int,
    @SerializedName("waited_ms") val waitedMs: Long,
    @SerializedName("next_seq") val nextSeq: Long,
    /** Oldest first (ascending `seq`): the order the app made them. */
    @SerializedName("flows") val flows: List<FlowSummary>,
    @SerializedName("bodies") val bodies: Map<String, FlowBodies>? = null,
    @SerializedName("closest_observed") val closestObserved: List<ClosestObserved> = emptyList(),
    @SerializedName("hint") val hint: String? = null,
    @SerializedName("clamped") val clamped: Map<String, ClampInfo>? = null,
    @SerializedName("warnings") val warnings: List<String> = emptyList()
)

// ============================================================================
// Mock rules & collections
// ============================================================================

data class QueryParamDto(
    @SerializedName("key") val key: String? = null,
    @SerializedName("value") val value: String? = null,
    @SerializedName("required") val required: Boolean? = null,
    @SerializedName("match") val match: String? = null
)

/**
 * A response to serve.
 *
 * `delay_ms` belongs to armed stubs (M2) and is REJECTED on a mock rule rather than accepted and
 * ignored — a rule has nowhere to store it, and silently dropping it would make a latency test
 * report success while measuring nothing.
 *
 * `status_code` is validated to 100..599 for the same reason in reverse: an unchecked 9999 is
 * stored, exported and only fails inside the app under test, arbitrarily far from the call that
 * introduced it.
 */
data class ResponseSpecDto(
    @SerializedName("status_code") val statusCode: Int? = null,
    @SerializedName("headers") val headers: Map<String, String>? = null,
    @SerializedName("body") val body: String? = null,
    @SerializedName("body_base64") val bodyBase64: String? = null,
    @SerializedName("delay_ms") val delayMs: Int? = null
)

data class ResponseView(
    @SerializedName("status_code") val statusCode: Int,
    @SerializedName("headers") val headers: Map<String, String>,
    @SerializedName("body") val body: String?,
    @SerializedName("body_bytes") val bodyBytes: Int,
    @SerializedName("body_truncated") val bodyTruncated: Boolean
)

data class RuleView(
    @SerializedName("rule_id") val ruleId: String,
    @SerializedName("name") val name: String,
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("method") val method: String,
    @SerializedName("url") val url: String,
    @SerializedName("scheme") val scheme: String,
    @SerializedName("host") val host: String,
    @SerializedName("host_match") val hostMatch: String,
    @SerializedName("port") val port: Int?,
    @SerializedName("path") val path: String,
    @SerializedName("path_match") val pathMatch: String,
    @SerializedName("query") val query: List<QueryParamDto>,
    @SerializedName("collection_id") val collectionId: String,
    @SerializedName("collection_name") val collectionName: String?,
    @SerializedName("collection_enabled") val collectionEnabled: Boolean,
    @SerializedName("response") val response: ResponseView
)

data class CollectionView(
    @SerializedName("collection_id") val collectionId: String,
    @SerializedName("name") val name: String,
    @SerializedName("package_name") val packageName: String,
    @SerializedName("description") val description: String,
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("rule_count") val ruleCount: Int,
    @SerializedName("created_at") val createdAt: Long
)

/** Collection to create alongside a rule, when the caller has none yet. */
data class NewCollectionDto(
    @SerializedName("name") val name: String? = null,
    @SerializedName("package_name") val packageName: String? = null,
    @SerializedName("description") val description: String? = null
)

/**
 * `POST …/mocks`.
 *
 * Either give a `url` (+ `method`), or a `from_flow_id` to clone a captured flow — the fastest
 * path, and the one where `loosen_query` matters: `StructuredUrl.fromUrl` marks EVERY captured
 * query param `required:true` + `EXACT`, which produces a rule that matches the call it was cloned
 * from and nothing afterwards.
 */
data class RuleCreateRequest(
    @SerializedName("name") val name: String? = null,
    @SerializedName("method") val method: String? = null,
    @SerializedName("url") val url: String? = null,

    /**
     * Host and path as FIRST-CLASS fields, so a REGEX pattern never has to survive URL parsing.
     * `url` is parsed as an absolute URL before the match modes are read, which meant
     * `https://api.example.com/v1/orders/[0-9]+` failed with INVALID_URL — the canonical REGEX
     * example the tool's own description advertises could not be written at all.
     */
    @SerializedName("host") val host: String? = null,
    @SerializedName("path") val path: String? = null,    @SerializedName("host_match") val hostMatch: String? = null,
    @SerializedName("path_match") val pathMatch: String? = null,
    @SerializedName("query") val query: List<QueryParamDto>? = null,
    @SerializedName("response") val response: ResponseSpecDto? = null,
    @SerializedName("collection_id") val collectionId: String? = null,
    @SerializedName("new_collection") val newCollection: NewCollectionDto? = null,
    @SerializedName("from_flow_id") val fromFlowId: String? = null,
    /** Defaults to true when `from_flow_id` is used, false otherwise. */
    @SerializedName("loosen_query") val loosenQuery: Boolean? = null,
    @SerializedName("enabled") val enabled: Boolean? = null,
    /** Disable every other enabled rule with the same (method, host, path) signature. */
    @SerializedName("exclusive") val exclusive: Boolean? = null
)

/** `PATCH …/mocks/{ruleId}` — every field optional; omitted fields are left exactly as they are. */
data class RuleUpdateRequest(
    @SerializedName("rule_id") val ruleId: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("method") val method: String? = null,
    @SerializedName("url") val url: String? = null,

    /**
     * Host and path as FIRST-CLASS fields, so a REGEX pattern never has to survive URL parsing.
     * `url` is parsed as an absolute URL before the match modes are read, which meant
     * `https://api.example.com/v1/orders/[0-9]+` failed with INVALID_URL — the canonical REGEX
     * example the tool's own description advertises could not be written at all.
     */
    @SerializedName("host") val host: String? = null,
    @SerializedName("path") val path: String? = null,    @SerializedName("host_match") val hostMatch: String? = null,
    @SerializedName("path_match") val pathMatch: String? = null,
    @SerializedName("query") val query: List<QueryParamDto>? = null,
    @SerializedName("response") val response: ResponseSpecDto? = null,
    @SerializedName("exclusive") val exclusive: Boolean? = null
)

data class RuleEnableRequest(
    @SerializedName("rule_id") val ruleId: String? = null,
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("exclusive") val exclusive: Boolean? = null
)

/** What the rule will actually compare — the field that stops "my rule never fires". */
data class WillMatchDto(
    @SerializedName("method") val method: String,
    @SerializedName("host") val host: String,
    @SerializedName("host_match") val hostMatch: String,
    @SerializedName("path") val path: String,
    @SerializedName("path_match") val pathMatch: String,
    @SerializedName("required_params") val requiredParams: List<String>
)

data class RuleMutationResponse(
    @SerializedName("rule") val rule: RuleView,
    @SerializedName("will_match") val willMatch: WillMatchDto,
    @SerializedName("disabled_conflicting") val disabledConflicting: List<String>,
    @SerializedName("loosened_params") val loosenedParams: List<String>,
    /** Modes in which this rule can actually be served. Answered at CHECK_MOCK. */
    @SerializedName("will_fire_in_modes") val willFireInModes: List<String>,
    @SerializedName("current_mode") val currentMode: String?,
    @SerializedName("created_collection") val createdCollection: CollectionView?,
    @SerializedName("warnings") val warnings: List<String>
)

data class RuleListResponse(
    @SerializedName("rules") val rules: List<RuleView>,
    @SerializedName("returned") val returned: Int,
    @SerializedName("total") val total: Int,
    @SerializedName("current_mode") val currentMode: String?,
    @SerializedName("clamped") val clamped: Map<String, ClampInfo>? = null,
    @SerializedName("warnings") val warnings: List<String> = emptyList()
)

data class CollectionListResponse(
    @SerializedName("collections") val collections: List<CollectionView>,
    @SerializedName("count") val count: Int
)

data class CollectionCreateRequest(
    @SerializedName("name") val name: String? = null,
    @SerializedName("package_name") val packageName: String? = null,
    @SerializedName("description") val description: String? = null
)

data class DeleteResponse(
    @SerializedName("deleted") val deleted: Boolean,
    @SerializedName("id") val id: String,
    @SerializedName("removed_rules") val removedRules: Int,
    @SerializedName("warnings") val warnings: List<String> = emptyList()
)

/** `GET …/mocks/export`. The JSON is the same format the Mockk tab imports and exports. */
data class ExportResponse(
    @SerializedName("json") val json: String,
    @SerializedName("collections") val collections: Int,
    @SerializedName("rules") val rules: Int
)

/** `POST …/mocks/import`. With `dry_run:true` nothing is written and only the diff comes back. */
data class ImportRequest(
    @SerializedName("json") val json: String? = null,
    /** REPLACE | KEEP_BOTH | SKIP — what to do with a rule whose endpoint exists but differs. */
    @SerializedName("strategy") val strategy: String? = null,
    @SerializedName("dry_run") val dryRun: Boolean? = null
)

data class ImportCollectionDiff(
    @SerializedName("name") val name: String,
    @SerializedName("exists") val exists: Boolean,
    @SerializedName("new_rules") val newRules: Int,
    @SerializedName("changed_rules") val changedRules: Int,
    @SerializedName("identical_rules") val identicalRules: Int
)

data class ImportResponse(
    @SerializedName("dry_run") val dryRun: Boolean,
    @SerializedName("collections") val collections: List<ImportCollectionDiff>,
    @SerializedName("would_create") val wouldCreate: Int,
    @SerializedName("would_replace") val wouldReplace: Int,
    @SerializedName("would_skip") val wouldSkip: Int,
    @SerializedName("collections_created") val collectionsCreated: Int,
    @SerializedName("rules_added") val rulesAdded: Int,
    @SerializedName("rules_replaced") val rulesReplaced: Int,
    @SerializedName("rules_kept_both") val rulesKeptBoth: Int,
    @SerializedName("rules_skipped") val rulesSkipped: Int,
    @SerializedName("warnings") val warnings: List<String> = emptyList()
)

/** `POST …/mocks/explain` — dry-run the real matcher against a hypothetical request. */
data class MatchExplainRequest(
    @SerializedName("method") val method: String? = null,
    @SerializedName("url") val url: String? = null
)

/**
 * One rule considered for a hypothetical request.
 *
 * `rejected_because` is written for a model to act on: it names the field, both values and the fix.
 */
data class MatchCandidate(
    /** `rule` today; `arm` joins in M2. */
    @SerializedName("kind") val kind: String,
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("matched") val matched: Boolean,
    @SerializedName("rejected_because") val rejectedBecause: String?,
    @SerializedName("note") val note: String? = null
)

data class MatchExplainResponse(
    /**
     * Named `current_mode`, like every other response that carries it — this one said `mode`, and
     * the docs described it as `current_mode`, so a model looking for the documented field found
     * nothing. It is the field that answers "is my rule inert because the session is in RECORDING",
     * which the docs call the number-one failure, so it is the worst one to have misnamed.
     */
    @SerializedName("current_mode") val mode: String?,
    @SerializedName("winner") val winner: MatchCandidate?,
    @SerializedName("candidates") val candidates: List<MatchCandidate>,
    /** True only if a winner exists AND the current mode actually serves mocks. */
    @SerializedName("would_skip_network") val wouldSkipNetwork: Boolean,
    @SerializedName("warnings") val warnings: List<String> = emptyList()
)

// ============================================================================
// Session
// ============================================================================

/**
 * `POST …/session/mode`.
 *
 * `confirm_pause_all` is the deliberate footgun guard for DEBUG / MOCKK_DEBUG: until the pause
 * policy lands (M3) those modes pause EVERY request the app makes — background polling included —
 * behind a Swing dialog no agent can click, for up to 55 s each.
 */
data class SetModeRequest(
    @SerializedName("mode") val mode: String? = null,
    @SerializedName("confirm_pause_all") val confirmPauseAll: Boolean? = null
)

data class SessionModeResponse(
    @SerializedName("mode") val mode: String,
    @SerializedName("previous_mode") val previousMode: String?,
    @SerializedName("changed") val changed: Boolean,
    @SerializedName("session_running") val sessionRunning: Boolean,
    @SerializedName("package_filter") val packageFilter: String?,
    /** False while the Inspector's radio buttons are still the UI's own source of truth. */
    @SerializedName("ui_in_sync") val uiInSync: Boolean,
    @SerializedName("warnings") val warnings: List<String>
)

// ----------------------------------------------------------------------------
// Session control: start, stop, package filter, devices
// ----------------------------------------------------------------------------

/**
 * `POST …/session/start`.
 *
 * **Every field is optional, and that is the point.** An agent has no combo box: with one device
 * connected and one app that has announced itself, `{}` is a complete request. Anything ambiguous
 * comes back as a typed error listing the candidates in `details` — never a silent guess.
 *
 * `serial` is an ADB serial (`emulator-5554`) or an iOS UDID; `package_name` is the package filter
 * (an Android package or an iOS bundle id). `mode` defaults to `RECORDING`; `MOCKK` is the one in
 * which mock rules are served. `confirm_pause_all` is the same footgun guard as on `session/mode`:
 * DEBUG and MOCKK_DEBUG pause EVERY request behind a dialog only a human can answer.
 */
data class StartSessionRequest(
    @SerializedName("serial") val serial: String? = null,
    @SerializedName("package_name") val packageName: String? = null,
    @SerializedName("mode") val mode: String? = null,
    @SerializedName("confirm_pause_all") val confirmPauseAll: Boolean? = null
)

/**
 * `started:false` + `already_running:true` is a success, not a failure: the caller asked for a
 * state that already held. Any `mode` or `package_name` it passed has been APPLIED to the running
 * session — never silently dropped — and `warnings` says so.
 */
data class SessionStartResponse(
    @SerializedName("started") val started: Boolean,
    @SerializedName("already_running") val alreadyRunning: Boolean,
    @SerializedName("session") val session: SessionInfo,
    /** `explicit` | `remembered` | `sole-device` | `only-device-with-app`. Null when adopted. */
    @SerializedName("device_resolved_by") val deviceResolvedBy: String?,
    /** `explicit` | `remembered` | `sole-announced-app` | `sole-app-on-device`. Null when adopted. */
    @SerializedName("app_resolved_by") val appResolvedBy: String?,
    @SerializedName("warnings") val warnings: List<String>,
    @SerializedName("hint") val hint: String? = null
)

data class SessionStopResponse(
    @SerializedName("stopped") val stopped: Boolean,
    /** False means there was nothing to stop — reported rather than dressed up as a stop. */
    @SerializedName("was_running") val wasRunning: Boolean,
    @SerializedName("session") val session: SessionInfo,
    @SerializedName("warnings") val warnings: List<String>,
    @SerializedName("hint") val hint: String? = null
)

/**
 * `POST …/session/app` — the package filter, not app launching (that is `mockkhttp_app`, M4).
 *
 * `clear:true` is required to remove the filter: a session with no filter captures EVERY
 * instrumented app and steals flows from every other open project, so it cannot be reached by
 * simply omitting `package_name`.
 */
data class SetPackageFilterRequest(
    @SerializedName("package_name") val packageName: String? = null,
    @SerializedName("clear") val clear: Boolean? = null
)

data class SessionAppResponse(
    @SerializedName("package_filter") val packageFilter: String?,
    /** False when nothing was running: the filter is remembered for the next start instead. */
    @SerializedName("applied_to_live_session") val appliedToLiveSession: Boolean,
    @SerializedName("session") val session: SessionInfo,
    @SerializedName("warnings") val warnings: List<String>,
    @SerializedName("hint") val hint: String? = null
)

/**
 * One app on one device.
 *
 * [announced] is proof — the app spoke the MockkHttp protocol to this IDE, which no scan can
 * better. [instrumented] is the weaker "MockkHttp was detected in it". [installedOnDevice] is
 * `null` when the device could not be asked, which is NOT the same as false: an announced package
 * with an unknown installation is still a candidate.
 */
data class DeviceAppView(
    @SerializedName("package_name") val packageName: String,
    @SerializedName("app_name") val appName: String?,
    @SerializedName("announced") val announced: Boolean,
    @SerializedName("instrumented") val instrumented: Boolean,
    @SerializedName("installed_on_device") val installedOnDevice: Boolean?
)

/** One connected device. [serial] is what `session/start` takes. */
data class DeviceView(
    @SerializedName("serial") val serial: String,
    /** `ANDROID` | `IOS_SIMULATOR` | `IOS_DEVICE`. */
    @SerializedName("platform") val platform: String,
    @SerializedName("label") val label: String,
    @SerializedName("model") val model: String?,
    @SerializedName("online") val online: Boolean,
    @SerializedName("emulator") val emulator: Boolean,
    @SerializedName("api_level") val apiLevel: Int?,
    @SerializedName("os_version") val osVersion: String?,
    @SerializedName("apps") val apps: List<DeviceAppView>,
    /** `announced` | `device_scan` | `none` — see [APPS_SOURCE_ANNOUNCED]. */
    @SerializedName("apps_source") val appsSource: String,
    @SerializedName("note") val note: String? = null
)

/**
 * `GET …/devices` — what a human would read out of the Inspector's two combo boxes.
 *
 * The app lists come from what has ANNOUNCED itself first (`instrumented_packages`): an app that
 * has spoken the protocol has proven it is instrumented. A full device scan is opt-in
 * (`scan=true`), is only taken when nothing has announced itself, and takes **minutes** on a
 * physical device — it reads every installed APK.
 */
data class DeviceListResponse(
    @SerializedName("devices") val devices: List<DeviceView>,
    @SerializedName("count") val count: Int,
    /** Every package that has announced itself to this IDE, whatever device it is on. */
    @SerializedName("instrumented_packages") val instrumentedPackages: List<String>,
    @SerializedName("adb_available") val adbAvailable: Boolean,
    @SerializedName("adb_path") val adbPath: String?,
    @SerializedName("ios_tooling_available") val iosToolingAvailable: Boolean,
    /** True when simctl/devicectl FAILED — which is not the same as "no iOS device is connected". */
    @SerializedName("ios_enumeration_failed") val iosEnumerationFailed: Boolean,
    @SerializedName("deep_scanned") val deepScanned: Boolean,
    @SerializedName("hint") val hint: String? = null,
    @SerializedName("warnings") val warnings: List<String> = emptyList()
)
