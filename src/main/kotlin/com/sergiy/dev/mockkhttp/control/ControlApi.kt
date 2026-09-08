package com.sergiy.dev.mockkhttp.control

import com.intellij.ide.SaveAndSyncHandler
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.sergiy.dev.mockkhttp.adb.EmulatorInfo
import com.sergiy.dev.mockkhttp.control.dto.*
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import com.sergiy.dev.mockkhttp.model.HttpFlowData
import com.sergiy.dev.mockkhttp.model.MatchType
import com.sergiy.dev.mockkhttp.model.MockBody
import com.sergiy.dev.mockkhttp.model.MockkCollection
import com.sergiy.dev.mockkhttp.model.ModifiedResponseData
import com.sergiy.dev.mockkhttp.model.QueryParam
import com.sergiy.dev.mockkhttp.model.StructuredUrl
import com.sergiy.dev.mockkhttp.model.UrlSpec
import com.sergiy.dev.mockkhttp.proxy.GlobalOkHttpInterceptorServer
import com.sergiy.dev.mockkhttp.proxy.OkHttpInterceptorServer
import com.sergiy.dev.mockkhttp.session.CaptureSessionService
import com.sergiy.dev.mockkhttp.store.FlowStore
import com.sergiy.dev.mockkhttp.store.MockkRulesStore
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Environment facts the façade cannot discover on its own.
 *
 * `AgentControlServer` assigns this once it has bound its port and read the settings; until then
 * the defaults are the safe ones — no instance file, no port, and **secrets are not revealed**.
 */
data class ControlEnvironment(
    val instanceId: String? = null,
    val controlPort: Int? = null,
    val agentControl: String = AGENT_CONTROL_FULL,
    /** Backs `include_secrets`. Stays false until Settings → *Allow an agent to read redacted header values* exists. */
    val revealSecrets: Boolean = false
)

/**
 * The UI-free façade every automated caller goes through — REST today, anything else tomorrow.
 *
 * **Pure Kotlin by contract.** No HTTP types, no MCP types, no Swing, no `com.sun.net.httpserver`.
 * Methods take and return the DTOs in [com.sergiy.dev.mockkhttp.control.dto], which makes this the
 * unit-testable core and the single place the business rules live. `ControlRouter` and the handlers
 * must contain no logic beyond parsing, auth and serialisation.
 *
 * **Threading.** Every method is called from a control-plane worker thread and every read stays off
 * the EDT: `FlowStore`, `MockkRulesStore` and `GlobalOkHttpInterceptorServer` are all thread-safe
 * and snapshot internally. Writes do not need the EDT either — `MockkRulesStore` mutates under its
 * own `ReentrantReadWriteLock` and its UI listeners already re-enter Swing through
 * `SwingUtilities.invokeLater` (MockkRulesPanel.kt:121-141). Nothing here calls `invokeAndWait`; a
 * control thread blocking on the EDT while the EDT waits on a store lock is precisely the deadlock
 * this design avoids. If a future method genuinely needs the EDT, it must say why in a comment.
 *
 * **Milestone M1.** Meta, projects, status, flows (list/get/clear/await), mock rules and collections
 * (CRUD, explain, export, dry-run import) and the session mode. Armed stubs, the pause registry,
 * runs and verify are M2-M4 and deliberately absent — [MetaResponse.notImplementedYet] says so on
 * the wire rather than letting a caller discover it by getting a 404.
 */
@Service(Service.Level.APP)
class ControlApi {

    private val log = Logger.getInstance(ControlApi::class.java)

    /** Assigned by `AgentControlServer`; see [ControlEnvironment]. */
    @Volatile
    var environment: ControlEnvironment = ControlEnvironment()

    /**
     * Per-project flow cursors, keyed by `Project.locationHash`.
     *
     * Holds no `Project` and no `FlowStore` reference — only ids and sequence numbers — so a closed
     * project leaks nothing. Stale entries are dropped in [resolveProject], which already
     * enumerates the open projects.
     */
    private val flowIndexes = ConcurrentHashMap<String, ProjectFlowIndex>()

    /** Bounded cache of compiled matcher regexes: a hot `await` loop must not recompile per flow. */
    private val regexCache = ConcurrentHashMap<String, Regex>()

    companion object {
        private const val PLUGIN_ID = "com.sergiy.dev.MockkHttp"

        /**
         * Milestone this build STARTED from, echoed in `meta` so a bridge can refuse politely.
         *
         * It is not a feature list and never was: session control (planned as M4) shipped ahead of
         * M2/M3 because everything else the control plane can do is inert without it. What this
         * build implements is [CAPABILITIES]; what it does not is [NOT_IMPLEMENTED_YET]. Those two
         * are the contract — the milestone is a label.
         */
        const val MILESTONE = "M1"

        /** Verb families this build actually implements. Reported by both `meta` and `status`. */
        val CAPABILITIES: List<String> = listOf(
            "meta", "projects", "status", "flows", "flows_await", "mocks", "mock_collections",
            "mocks_explain", "mocks_export", "mocks_import", "session_mode",
            "session_start_stop", "session_package_filter", "devices"
        )

        /** Named so a caller knows why a verb is absent rather than guessing. */
        val NOT_IMPLEMENTED_YET: List<String> = listOf(
            "arms (M2)", "runs (M2)", "verify (M2)", "pause_policy (M3)",
            "intercepts (M3)", "app_launch (M4) — starting and stopping the app under test"
        )

        /** Status codes a mock rule may serve. Anything else is a caller error, not a payload. */
        const val MIN_STATUS_CODE = 100
        const val MAX_STATUS_CODE = 599

        const val DEFAULT_MAX_BODY_CHARS = 20_000
        const val HARD_MAX_BODY_CHARS = 262_144
        const val DEFAULT_FLOW_LIMIT = 25
        const val MAX_FLOW_LIMIT = 500

        /** Default ceiling for [awaitFlow]; the HTTP layer may hand down a smaller one. */
        const val MAX_AWAIT_WAIT_MS = 25_000L

        /**
         * How long a waiter sleeps before re-reading the store even if nothing signalled it.
         *
         * Not a fallback for a missed notification but for a REMOVED one: `InspectorPanel.kt:347`
         * calls `FlowStore.clearAllListeners()` in its constructor, so rebuilding the tool window
         * silently unsubscribes this service. The poll bounds the damage at one interval of extra
         * latency instead of a 25 s hang, and disappears once FlowStore takes Disposable-scoped
         * listeners.
         */
        const val AWAIT_POLL_INTERVAL_MS = 250L

        /** 8 MB, matching the router's request cap. Reported in `meta.limits`. */
        const val REQUEST_BODY_BYTES_MAX = 8 * 1024 * 1024

        private const val MAX_REGEX_CACHE = 256

        /** Redacted unless `include_secrets` is on AND the settings toggle allows it. */
        val REDACTED_HEADERS: Set<String> = setOf(
            "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token"
        )

        /**
         * Query parameters whose value is a credential. Redacted on every surface that shows a
         * URL, under the same `include_secrets` gate as headers, and never baked into a rule
         * cloned from a flow — rules persist under .idea/, which half the projects commit
         * (audit round 16, BG: an OpenWeatherMap `appid` reached the repository's history).
         */
        val REDACTED_QUERY_KEYS: Set<String> = setOf(
            "appid", "apikey", "apitoken", "xapikey", "key", "token", "accesstoken", "authtoken", "idtoken",
            "refreshtoken", "bearer", "jwt", "auth", "authorization", "password", "passwd", "pass", "pwd",
            "secret", "clientsecret", "credential", "credentials", "sig", "signature", "session", "sessionid",
            // An OAuth authorization code is a single-use credential exchangeable for a token.
            "code"
        )

        /** Whether a query parameter's name means its value is a credential: compared without case, `-` or `_`. */
        fun isCredentialParam(name: String): Boolean =
            name.lowercase().replace("-", "").replace("_", "") in REDACTED_QUERY_KEYS

        /**
         * Query params whose captured value is worthless as a matcher: they change on every call, so
         * a rule cloned from a flow with `required:true` + `EXACT` matches exactly once and never again.
         */
        private val VOLATILE_PARAM_KEYS: Set<String> = setOf(
            "ts", "timestamp", "_", "cb", "nonce", "sig", "token", "rnd", "v", "cachebuster"
        )

        /**
         * What a package name or a bundle id may contain. Looser than
         * `AppManager.PACKAGE_NAME_PATTERN` on purpose: an iOS bundle id may contain hyphens, and
         * refusing a legitimate id would be worse than accepting an odd one.
         */
        private val APP_ID_PATTERN = Regex("[A-Za-z0-9_.-]+")

        private val UUID_VALUE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        fun getInstance(): ControlApi =
            ApplicationManager.getApplication().getService(ControlApi::class.java)
    }

    // ========================================================================
    // Meta & projects
    // ========================================================================

    fun meta(): MetaResponse {
        val env = environment
        val appInfo = ApplicationInfo.getInstance()
        return MetaResponse(
            apiVersion = API_VERSION,
            pluginVersion = pluginVersion(),
            ide = appInfo.fullApplicationName,
            ideBuild = appInfo.build.asString(),
            instanceId = env.instanceId,
            writeAccess = env.agentControl,
            milestone = MILESTONE,
            capabilities = CAPABILITIES,
            notImplementedYet = NOT_IMPLEMENTED_YET,
            limits = LimitsDto(
                maxBodyCharsDefault = DEFAULT_MAX_BODY_CHARS,
                maxBodyCharsHard = HARD_MAX_BODY_CHARS,
                flowLimitDefault = DEFAULT_FLOW_LIMIT,
                flowLimitMax = MAX_FLOW_LIMIT,
                awaitWaitMsMax = MAX_AWAIT_WAIT_MS,
                requestBodyBytesMax = REQUEST_BODY_BYTES_MAX,
                redactedHeaders = REDACTED_HEADERS.toList()
            ),
            openProjects = openProjects().size
        )
    }

    fun listProjects(): ProjectsResponse {
        val projects = openProjects().map { summaryOf(it) }
        return ProjectsResponse(
            projects = projects,
            count = projects.size,
            hint = when {
                projects.isEmpty() -> "No project is open in this IDE window. Open the project you want to drive and call this again."
                projects.size > 1 -> "Several projects are open: pass project_id on every call, or let the bridge resolve it from the working directory."
                else -> null
            }
        )
    }

    fun status(projectId: String?, request: StatusRequest = StatusRequest()): ApiResult<StatusResponse> =
        withProject<StatusResponse>(projectId) { project ->
            val global = GlobalOkHttpInterceptorServer.getInstance()
            val registration = registrationOf(project)
            val flowStore = FlowStore.getInstance(project)
            val rulesStore = MockkRulesStore.getInstance(project)
            val index = indexFor(project)
            index.assignSequences(flowStore)

            val bound = global.isBound()
            val bindError = global.getBindError()
            val warnings = mutableListOf<String>()

            // Unbound is the RESTING state now that the port is released when the last session
            // stops: with nobody registered and no bind failure recorded there is nothing to fix,
            // and the "no capture session" warning below already says what to do. The port is a
            // fault only when a session expects it (registered but not listening) or when a bind
            // actually failed — a port owned by another process deserves the alarm; an idle IDE
            // does not.
            if (!bound && (registration != null || bindError != null)) {
                warnings += "The interceptor is not listening on port ${GlobalOkHttpInterceptorServer.SERVER_PORT}" +
                        (bindError?.let { ": $it" } ?: ".") +
                        " No traffic can reach MockkHttp until that is fixed."
            }
            if (registration == null) {
                warnings += "No capture session is running for this project, so flows, mode changes and mock serving " +
                        "are all inert. Start one with POST /v1/projects/${project.locationHash}/session/start " +
                        "(with one device and one instrumented app, an empty body is enough)."
            } else if (registration.mode == GlobalOkHttpInterceptorServer.InterceptMode.RECORDING) {
                warnings += "Mode is RECORDING: mock rules are never consulted. Call session/mode with MOCKK to make them fire."
            }
            if (registration?.packageNameFilter == null && registration != null) {
                warnings += "This project has no package filter, so it captures traffic from EVERY instrumented app and " +
                        "will steal flows from any other open project."
            }

            val rules = rulesStore.getAllRules()
            val enabledCollectionIds = rulesStore.getAllCollections().filter { it.enabled }.map { it.id }.toSet()

            ApiResult.ok(
                StatusResponse(
                    apiVersion = API_VERSION,
                    resolution = ResolutionInfo(
                        matchedBy = request.matchedBy ?: if (projectId.isNullOrBlank()) "sole-open-project" else "explicit-arg",
                        cwd = request.cwd,
                        instanceId = environment.instanceId,
                        ide = ApplicationInfo.getInstance().fullApplicationName,
                        pluginVersion = pluginVersion(),
                        projectId = project.locationHash,
                        projectName = project.name,
                        basePath = project.basePath,
                        otherOpenProjects = openProjects()
                            .filter { it.locationHash != project.locationHash }
                            .map { summaryOf(it) }
                    ),
                    writeAccess = environment.agentControl,
                    capabilities = CAPABILITIES,
                    notImplementedYet = NOT_IMPLEMENTED_YET,
                    interceptor = InterceptorInfo(
                        port = GlobalOkHttpInterceptorServer.SERVER_PORT,
                        bound = bound,
                        ownedByThisProcess = bound,
                        // Needs an lsof/netstat probe; a guess here would be worse than an honest null.
                        heldByPid = null,
                        bindError = bindError
                    ),
                    session = sessionInfo(project),
                    client = clientInfoFor(project),
                    flows = FlowsSummary(
                        count = flowStore.getFlowCount(),
                        nextSeq = index.nextSeq(),
                        capacity = flowStore.maxFlows(),
                        evictedSinceClear = flowStore.getEvictedSinceClear(),
                        rejectedSinceClear = rejectedSinceClear(project, packageFilterOf(project) ?: clientReportFor(project)?.packageName),
                        headersDroppedSinceClear = headersDroppedSinceClear(project, packageFilterOf(project) ?: clientReportFor(project)?.packageName)
                    ),
                    mocks = MocksSummary(
                        collections = rulesStore.getAllCollections().size,
                        rules = rules.size,
                        rulesEnabled = rules.count { it.enabled && it.collectionId in enabledCollectionIds }
                    ),
                    arms = null,
                    intercepts = null,
                    run = null,
                    warnings = warnings
                )
            )
        }

    // ========================================================================
    // Flows
    // ========================================================================

    fun listFlows(projectId: String?, query: FlowQuery): ApiResult<FlowListResponse> =
        withProject<FlowListResponse>(projectId) { project ->
            val clamped = LinkedHashMap<String, ClampInfo>()
            val warnings = mutableListOf<String>()

            val matcher = when (val compiled = compileMatcher(query.toMatcher())) {
                is ApiResult.Err -> return@withProject compiled
                is ApiResult.Ok -> compiled.value
            }
            val secrets = when (val reveal = resolveSecrets(query.includeSecrets)) {
                is ApiResult.Err -> return@withProject reveal
                is ApiResult.Ok -> reveal.value
            }

            val limit = clampInt(
                query.limit, DEFAULT_FLOW_LIMIT, 1, MAX_FLOW_LIMIT, "limit",
                "Listings are capped at $MAX_FLOW_LIMIT flows so a chatty app cannot exhaust the caller's context. " +
                        "Page with since_seq instead.", clamped
            )
            val maxBodyChars = clampBodyChars(query.maxBodyChars, clamped)
            val includeBody = when (val parsed = validateIncludeBody(query.includeBody)) {
                is ApiResult.Err -> return@withProject parsed
                is ApiResult.Ok -> parsed.value
            }

            val flowStore = FlowStore.getInstance(project)
            val index = indexFor(project)
            val all = index.assignSequences(flowStore)
            val sinceSeq = query.sinceSeq ?: 0L

            val matching = all.filter { it.seq >= sinceSeq && matcher.matches(it.flow) && matchesFlowQuery(query, it.flow) }
            // Newest first: an agent almost always wants what just happened.
            val page = matching.sortedByDescending { it.seq }.take(limit)

            val bodies = if (includeBody == INCLUDE_BODY_NONE) null else page.associate { entry ->
                entry.flow.flowId to FlowBodies(
                    request = if (includeBody == INCLUDE_BODY_BOTH) requestView(entry.flow, maxBodyChars, secrets) else null,
                    response = responseView(entry.flow, maxBodyChars, secrets)
                )
            }

            if (matching.size > page.size) {
                warnings += "${matching.size - page.size} more flows match. Raise limit (max $MAX_FLOW_LIMIT) or page with since_seq."
            }
            val summaries = page.map { summaryOf(it, secrets) }
            secretsWarning(bodies.orEmpty().flatMap { listOf(it.value.request, it.value.response) }, summaries.flatMap { it.revealedQuery })
                ?.let { warnings += it }
            retentionWarning(bodies)?.let { warnings += it }

            ApiResult.ok(
                FlowListResponse(
                    nextSeq = index.nextSeq(),
                    returned = page.size,
                    totalMatching = matching.size,
                    flows = summaries,
                    bodies = bodies,
                    client = clientInfoFor(project),
                    note = if (includeBody == INCLUDE_BODY_NONE) {
                        "Bodies omitted. Call flows/{flow_id} for one flow, or repeat with include_body:'response'."
                    } else null,
                    clamped = clamped.takeIf { it.isNotEmpty() },
                    warnings = warnings
                )
            )
        }

    fun getFlow(
        projectId: String?,
        flowId: String,
        maxBodyChars: Int? = null,
        includeSecrets: Boolean? = null
    ): ApiResult<FlowDetail> = withProject<FlowDetail>(projectId) { project ->
        val clamped = LinkedHashMap<String, ClampInfo>()
        val secrets = when (val reveal = resolveSecrets(includeSecrets)) {
            is ApiResult.Err -> return@withProject reveal
            is ApiResult.Ok -> reveal.value
        }
        val budget = clampBodyChars(maxBodyChars, clamped)

        val flowStore = FlowStore.getInstance(project)
        val index = indexFor(project)
        val entry = index.assignSequences(flowStore).find { it.flow.flowId == flowId }
            ?: return@withProject ApiResult.fail(
                ErrorCode.UNKNOWN_FLOW,
                "No retained flow with id '$flowId'.",
                "The flow cache is a bounded ring (Settings → Cache). Call flows with a since_seq cursor to see what is still retained."
            )

        val request = requestView(entry.flow, budget, secrets)
        val response = responseView(entry.flow, budget, secrets)
        val retentionTruncated = request.bodyTruncatedByRetention || (response?.bodyTruncatedByRetention == true)
        val warnings = mutableListOf<String>()
        if (retentionTruncated) {
            warnings += "This flow was already truncated when it was retained (Settings → Cache → max stored body size). " +
                    "The missing bytes are gone; raise the limit before capturing if you need whole bodies."
        }
        secretsWarning(listOf(request, response))?.let { warnings += it }

        ApiResult.ok(
            FlowDetail(
                flow = summaryOf(entry, secrets),
                request = request,
                response = response,
                storedBodyTruncatedByRetention = retentionTruncated,
                clamped = clamped.takeIf { it.isNotEmpty() },
                warnings = warnings
            )
        )
    }

    fun clearFlows(projectId: String?): ApiResult<ClearFlowsResponse> =
        withProject<ClearFlowsResponse>(projectId) { project ->
            val flowStore = FlowStore.getInstance(project)
            val index = indexFor(project)
            val cleared = flowStore.getFlowCount()
            flowStore.clearAllFlows()
            // Sequence numbers deliberately keep climbing across a clear: a cursor an agent is
            // holding must never start matching different flows.
            index.forget()
            MockkHttpLogger.getInstance(project).info("🤖 Agent cleared $cleared flow(s)")
            ApiResult.ok(ClearFlowsResponse(cleared = cleared, nextSeq = index.nextSeq()))
        }

    /**
     * Block the CALLING thread until [AwaitFlowRequest.count] flows match, or the budget expires.
     *
     * [maxWaitMs] is the ceiling the transport imposes (the HTTP layer owns that policy); the
     * caller's own `wait_ms` is honoured up to it and any reduction is reported in `clamped`.
     *
     * Waking up is signalled through one long-lived [ProjectFlowIndex] listener per project, not a
     * listener per poll: `FlowStore` has no way to unregister one (no remove, no Disposable-scoped
     * overload), so a per-call registration would leak a listener on the hottest path in the plugin
     * — every intercepted request would then walk a list that grows with every agent poll. Waiters
     * park on this service's own condition instead, which unregisters itself by returning.
     */
    fun awaitFlow(
        projectId: String?,
        request: AwaitFlowRequest,
        maxWaitMs: Long = MAX_AWAIT_WAIT_MS
    ): ApiResult<AwaitFlowResponse> = withProject<AwaitFlowResponse>(projectId) { project ->
        val clamped = LinkedHashMap<String, ClampInfo>()
        val matcherDto = request.match ?: MatcherDto()
        val matcher = when (val compiled = compileMatcher(matcherDto)) {
            is ApiResult.Err -> return@withProject compiled
            is ApiResult.Ok -> compiled.value
        }
        val secrets = when (val reveal = resolveSecrets(request.includeSecrets)) {
            is ApiResult.Err -> return@withProject reveal
            is ApiResult.Ok -> reveal.value
        }

        val waitMs = clampLong(
            request.waitMs, minOf(5_000L, maxWaitMs), 0L, maxWaitMs, "wait_ms",
            "Long-polls are capped at ${maxWaitMs} ms so a caller cannot hold a control-plane thread open. " +
                    "Call again with the returned next_seq to keep waiting.", clamped
        )
        val expected = clampInt(
            request.count, 1, 1, 100, "count",
            "Waiting for more than 100 matching flows in one call is never the right shape.", clamped
        )
        val maxBodyChars = clampBodyChars(request.maxBodyChars, clamped)
        val includeBody = when (val parsed = validateIncludeBody(request.includeBody)) {
            is ApiResult.Err -> return@withProject parsed
            is ApiResult.Ok -> parsed.value
        }

        val flowStore = FlowStore.getInstance(project)
        val index = indexFor(project)
        // Number what is already stored BEFORE choosing the default cursor: without a since_seq the
        // caller means "wait for something new", and flows that were captured before this call must
        // not be handed back as if they had just arrived.
        var snapshot = index.assignSequences(flowStore)
        val sinceSeq = request.sinceSeq ?: index.nextSeq()
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + waitMs
        var interrupted = false

        var matched = snapshot.filter { it.seq >= sinceSeq && matcher.matches(it.flow) }

        while (matched.size < expected) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            if (!index.awaitChange(minOf(remaining, AWAIT_POLL_INTERVAL_MS))) {
                interrupted = true
                break
            }
            snapshot = index.assignSequences(flowStore)
            matched = snapshot.filter { it.seq >= sinceSeq && matcher.matches(it.flow) }
        }

        val waited = System.currentTimeMillis() - startedAt
        val satisfied = matched.size >= expected
        // Oldest first, so what is handed back is exactly what the cursor below steps past.
        val ordered = matched.sortedBy { it.seq }
        val returned = ordered.take(expected)
        val leftBehind = ordered.size - returned.size
        // A cursor past EVERY match would skip the ones this answer had no room for, and the
        // caller's since_seq loop would never see them again. Point it at the first one not
        // returned instead; the store still has it.
        val nextSeq = if (leftBehind > 0) returned.last().seq + 1 else index.nextSeq()
        val bodies = if (includeBody == INCLUDE_BODY_NONE) null else returned.associate { entry ->
            entry.flow.flowId to FlowBodies(
                request = if (includeBody == INCLUDE_BODY_BOTH) requestView(entry.flow, maxBodyChars, secrets) else null,
                response = responseView(entry.flow, maxBodyChars, secrets)
            )
        }

        val closest = if (satisfied) emptyList() else nearMisses(snapshot, sinceSeq, matcher)
        val hint = if (satisfied) null else buildAwaitHint(project, matcherDto, waited, closest, interrupted)

        val warnings = mutableListOf<String>()
        if (interrupted) {
            warnings += "The wait was interrupted before the deadline (the IDE is shutting down or the request was cancelled)."
        }
        if (leftBehind > 0) {
            warnings += "$leftBehind more flow(s) matched than count=$expected could return. next_seq points at the " +
                    "first of them: call again with since_seq:next_seq to read the rest, or raise count."
        }
        val summaries = returned.map { summaryOf(it, secrets) }
        secretsWarning(bodies.orEmpty().flatMap { listOf(it.value.request, it.value.response) }, summaries.flatMap { it.revealedQuery })
            ?.let { warnings += it }
        retentionWarning(bodies)?.let { warnings += it }

        ApiResult.ok(
            AwaitFlowResponse(
                satisfied = satisfied,
                actual = matched.size,
                expected = expected,
                waitedMs = waited,
                nextSeq = nextSeq,
                flows = summaries,
                bodies = bodies,
                closestObserved = closest,
                hint = hint,
                clamped = clamped.takeIf { it.isNotEmpty() },
                warnings = warnings
            )
        )
    }

    // ========================================================================
    // Mock rules
    // ========================================================================

    fun listRules(
        projectId: String?,
        collectionId: String? = null,
        includeBodies: Boolean? = null,
        limit: Int? = null
    ): ApiResult<RuleListResponse> = withProject<RuleListResponse>(projectId) { project ->
        val clamped = LinkedHashMap<String, ClampInfo>()
        val store = MockkRulesStore.getInstance(project)
        val applied = clampInt(
            limit, 100, 1, 1000, "limit",
            "Rule listings are capped at 1000.", clamped
        )
        val collections = store.getAllCollections().associateBy { it.id }
        if (collectionId != null && !collections.containsKey(collectionId)) {
            return@withProject ApiResult.fail(
                ErrorCode.UNKNOWN_COLLECTION,
                "No collection with id '$collectionId'.",
                "Call mocks with action:'list_collections' to see the collection ids that exist."
            )
        }
        val all = store.getAllRules().filter { collectionId == null || it.collectionId == collectionId }
        val page = all.take(applied)
        ApiResult.ok(
            RuleListResponse(
                rules = page.map { ruleView(it, collections[it.collectionId], includeBodies == true) },
                returned = page.size,
                total = all.size,
                currentMode = registrationOf(project)?.mode?.name,
                clamped = clamped.takeIf { it.isNotEmpty() },
                warnings = if (all.size > page.size) listOf("${all.size - page.size} more rules exist; raise limit.") else emptyList()
            )
        )
    }

    fun getRule(projectId: String?, ruleId: String): ApiResult<RuleView> =
        withProject<RuleView>(projectId) { project ->
            val store = MockkRulesStore.getInstance(project)
            val rule = store.getAllRules().find { it.id == ruleId }
                ?: return@withProject unknownRule(ruleId)
            ApiResult.ok(ruleView(rule, store.getCollection(rule.collectionId), includeBody = true))
        }

    fun createRule(projectId: String?, request: RuleCreateRequest): ApiResult<RuleMutationResponse> =
        withProject<RuleMutationResponse>(projectId) { project ->
            val store = MockkRulesStore.getInstance(project)
            val warnings = mutableListOf<String>()

            // ---- source: a captured flow, or an explicit method + url -------------------------
            var sourceFlow: HttpFlowData? = null
            if (!request.fromFlowId.isNullOrBlank()) {
                sourceFlow = FlowStore.getInstance(project).getAllFlows().find { it.flowId == request.fromFlowId }
                    ?: return@withProject ApiResult.fail(
                        ErrorCode.UNKNOWN_FLOW,
                        "No retained flow with id '${request.fromFlowId}' to clone.",
                        "List the retained flows first; the cache is a bounded ring and old flows are evicted."
                    )
            }
            val method = (request.method ?: sourceFlow?.request?.method)?.trim()?.uppercase()
            if (method.isNullOrEmpty()) {
                return@withProject ApiResult.fail(
                    ErrorCode.INVALID_ARGUMENT,
                    "method is required (or pass from_flow_id to clone a captured flow).",
                    "Retry with method:'GET' and url:'https://host/path'."
                )
            }
            // The match modes decide HOW the target is read, so they have to be resolved before
            // it is parsed: a REGEX path is not a URL and must never go through URL parsing.
            val hostMode = when (val m = parseMatchMode(request.hostMatch, "host_match", MatchType.EXACT)) {
                is ApiResult.Err -> return@withProject m
                is ApiResult.Ok -> m.value
            }
            val pathMode = when (val m = parseMatchMode(request.pathMatch, "path_match", MatchType.EXACT)) {
                is ApiResult.Err -> return@withProject m
                is ApiResult.Ok -> m.value
            }

            // host/path as first-class fields, exactly as the tool schema advertises them. Packing a
            // pattern into `url` cannot work — `/v1/orders/[0-9]+` is not a parseable URL — so the
            // schema documented the way out and this is what makes the server honour it.
            val structured = when (
                val spec = StructuredUrl.fromSpec(
                    url = request.url ?: sourceFlow?.request?.url,
                    host = request.host,
                    path = request.path,
                    hostMatch = hostMode,
                    pathMatch = pathMode
                )
            ) {
                is UrlSpec.Err -> return@withProject ApiResult.fail(
                    if (spec.field == "url") ErrorCode.INVALID_URL else ErrorCode.INVALID_ARGUMENT,
                    spec.message,
                    spec.hint
                )
                is UrlSpec.Ok -> spec.url
            }
            structured.hostMatch = hostMode
            structured.pathMatch = pathMode
            if (structured.path.isBlank()) structured.path = "/"
            // The check update already makes. A pattern that does not compile is stored as a rule
            // that never fires — the matcher swallows the PatternSyntaxException — so refuse it now.
            if (hostMode == MatchType.REGEX && compileRegex(structured.host) == null) {
                return@withProject invalidRegex("host", structured.host)
            }
            if (pathMode == MatchType.REGEX && compileRegex(structured.path) == null) {
                return@withProject invalidRegex("path", structured.path)
            }
            val requestedQuery = request.query
            if (requestedQuery != null) {
                when (val params = toQueryParams(requestedQuery)) {
                    is ApiResult.Err -> return@withProject params
                    is ApiResult.Ok -> structured.queryParams = params.value.toMutableList()
                }
            }
            val loosen = request.loosenQuery ?: (sourceFlow != null)
            val heuristically = if (loosen) loosenQueryParams(structured.queryParams) else emptyList()
            // A credential in a cloned flow's query — an API key, a token — must never be baked into
            // the rule: rules persist under .idea/, which half the projects commit, and `mocks get`
            // and `export` hand them back verbatim (audit round 16, BG). The parameter stays
            // required so the rule still describes the request, with any value. A query the caller
            // wrote explicitly is the caller's own words and is kept as written.
            val credentials = mutableListOf<String>()
            if (request.query == null && sourceFlow != null) {
                for (param in structured.queryParams) {
                    if (isCredentialParam(param.key) && param.value.isNotEmpty()) {
                        param.value = ""
                        param.required = true
                        param.matchType = MatchType.WILDCARD
                        credentials += param.key
                    }
                }
                if (credentials.isNotEmpty()) {
                    warnings += "${credentials.joinToString(", ")}: a credential travels in this query, so its value was " +
                            "left out of the rule (required, match WILDCARD). The rule matches any value of it."
                }
            }
            val loosened = (heuristically + credentials).distinct()

            // loosen_query asked for and nothing matched the heuristic. Silence here reads as
            // "done", and the rule then matches the one request it was cloned from and nothing
            // else — the exact "fired once and never again" this flag exists to prevent. Measured
            // case: a weather app's lat/lon come from device geolocation, look like ordinary
            // values, and pin the rule to one position on the map.
            if (loosen && loosened.isEmpty() && structured.queryParams.isNotEmpty()) {
                val pinned = structured.queryParams
                    .filter { it.required && it.matchType == MatchType.EXACT }
                    .map { it.key }
                if (pinned.isNotEmpty()) {
                    warnings += "loosen_query matched none of these params, so they stay EXACT and this rule only " +
                            "answers requests carrying those exact values: ${pinned.joinToString(", ")}. The " +
                            "heuristic only recognises obvious throwaways (ts, nonce, sig, cache busters, long " +
                            "numbers, UUIDs). If any of these vary between runs — a coordinate, a locale, a page " +
                            "number — pass query with match:'WILDCARD' for it, or the rule will fire once and " +
                            "never again."
                }
            }

            // ---- response ---------------------------------------------------------------------
            val body = when (val decoded = decodeResponseSpec(request.response)) {
                is ApiResult.Err -> return@withProject decoded
                is ApiResult.Ok -> decoded.value.text
            }
            val content = body ?: sourceFlow?.response?.content ?: ""
            if (FlowStore.isBodyTruncated(content)) {
                warnings += "The cloned response body was already truncated by the retention cache, so this rule would " +
                        "serve a cut-off payload. Pass an explicit response.body instead."
            }
            val statusCode = request.response?.statusCode ?: sourceFlow?.response?.statusCode ?: 200
            // Headers cloned from a captured flow go through the same gate as a flow read: the caller
            // never saw `Set-Cookie` in clear (flows redact it), so copying it into a rule that
            // `mocks get` and `export` then hand back verbatim would turn from_flow_id into a way
            // around include_secrets. Explicit response.headers are the caller's own words and are
            // kept as written.
            val headers = request.response?.headers
                ?: sourceFlow?.response?.headers?.let { captured ->
                    val secret = captured.keys.filter { it.lowercase() in REDACTED_HEADERS }
                    if (secret.isNotEmpty()) {
                        warnings += "Not copied from the captured response: ${secret.joinToString(", ")}. Captured " +
                                "credentials never enter a mock rule by cloning (the rule and its export are " +
                                "readable without include_secrets). If the mock must serve one, pass it explicitly " +
                                "in response.headers."
                    }
                    captured.filterKeys { it.lowercase() !in REDACTED_HEADERS }
                }
                ?: emptyMap()

            // ---- collection -------------------------------------------------------------------
            val resolved = when (val c = resolveCollection(store, request.collectionId, request.newCollection, warnings)) {
                is ApiResult.Err -> return@withProject c
                is ApiResult.Ok -> c.value
            }

            val name = request.name?.trim().takeUnless { it.isNullOrEmpty() }
                ?: "$method ${structured.host}${structured.path}"

            val rule = store.addRule(
                name = name,
                method = method,
                structuredUrl = structured,
                mockResponse = ModifiedResponseData(statusCode = statusCode, headers = headers, content = content),
                collectionId = resolved.collection.id
            )
            if (request.enabled == false) {
                store.setRuleEnabled(rule, false)
                warnings += uiRepaintWarning()
            }
            val disabled = if (request.exclusive == true && rule.enabled) exclusiveDisable(store, rule) else emptyList()
            if (disabled.isNotEmpty()) warnings += uiRepaintWarning()

            scheduleSave(project)
            MockkHttpLogger.getInstance(project).info("🤖 Agent created mock rule '${rule.name}' (${rule.id})")

            ApiResult.ok(mutationResponse(project, store, rule, disabled, loosened, resolved.created, warnings))
        }

    fun updateRule(projectId: String?, request: RuleUpdateRequest): ApiResult<RuleMutationResponse> =
        withProject<RuleMutationResponse>(projectId) { project ->
            val store = MockkRulesStore.getInstance(project)
            val ruleId = request.ruleId
            if (ruleId.isNullOrBlank()) {
                return@withProject ApiResult.fail(
                    ErrorCode.INVALID_ARGUMENT, "rule_id is required.", "Call mocks with action:'list' to get rule ids."
                )
            }
            val existing = store.getAllRules().find { it.id == ruleId } ?: return@withProject unknownRule(ruleId)
            val warnings = mutableListOf<String>()

            // Rebuild the URL only when something about it changed. Carrying the CURRENT match modes
            // over matters: MockkRulesStore.updateRule copies hostMatch/pathMatch off the
            // StructuredUrl it is given, so a rebuilt URL with the fromUrl() defaults would silently
            // downgrade a REGEX rule back to EXACT.
            val requestedUrl = request.url
            val requestedHost = request.host?.trim()?.takeIf { it.isNotEmpty() }
            val requestedPath = request.path?.trim()?.takeIf { it.isNotEmpty() }
            val touchesUrl = requestedUrl != null || requestedHost != null || requestedPath != null ||
                    request.hostMatch != null || request.pathMatch != null || request.query != null
            var structured: StructuredUrl? = null
            if (touchesUrl) {
                val rebuilt: StructuredUrl
                if (requestedUrl != null) {
                    rebuilt = StructuredUrl.fromUrl(requestedUrl)
                    if (rebuilt.host.isBlank()) {
                        return@withProject ApiResult.fail(
                            ErrorCode.INVALID_URL,
                            "Could not parse a host out of '$requestedUrl'.",
                            "Pass an absolute URL including the scheme."
                        )
                    }
                    if (rebuilt.path.isBlank()) rebuilt.path = "/"
                } else {
                    rebuilt = StructuredUrl(
                        scheme = existing.scheme,
                        host = existing.host,
                        port = existing.port,
                        path = existing.path,
                        queryParams = existing.queryParams.map { it.copy() }.toMutableList(),
                        hostMatch = existing.hostMatch,
                        pathMatch = existing.pathMatch
                    )
                }
                // host / path as first-class fields, exactly as create takes them: a REGEX host is
                // not a URL and can only arrive this way. Ignoring them answered "updated" with
                // the old host still in place.
                requestedHost?.let { rebuilt.host = it }
                requestedPath?.let { rebuilt.path = it }
                when (val modes = applyMatchModes(rebuilt, request.hostMatch, request.pathMatch, existing.hostMatch, existing.pathMatch)) {
                    is ApiResult.Err -> return@withProject modes
                    is ApiResult.Ok -> Unit
                }
                val requestedQuery = request.query
                if (requestedQuery != null) {
                    when (val params = toQueryParams(requestedQuery)) {
                        is ApiResult.Err -> return@withProject params
                        is ApiResult.Ok -> rebuilt.queryParams = params.value.toMutableList()
                    }
                }
                structured = rebuilt
            }

            val body = when (val decoded = decodeResponseSpec(request.response)) {
                is ApiResult.Err -> return@withProject decoded
                is ApiResult.Ok -> decoded.value.text
            }
            val responseSpec = request.response
            val response = if (responseSpec == null) null else ModifiedResponseData(
                statusCode = responseSpec.statusCode,
                headers = responseSpec.headers,
                content = body
            )

            val updated = store.updateRule(
                ruleId = ruleId,
                name = request.name,
                enabled = request.enabled,
                method = request.method?.trim()?.uppercase(),
                structuredUrl = structured,
                mockResponse = response
            ) ?: return@withProject unknownRule(ruleId)

            val disabled = if (request.exclusive == true && updated.enabled) exclusiveDisable(store, updated) else emptyList()
            if (disabled.isNotEmpty()) warnings += uiRepaintWarning()

            scheduleSave(project)
            MockkHttpLogger.getInstance(project).info("🤖 Agent updated mock rule '${updated.name}' (${updated.id})")

            ApiResult.ok(mutationResponse(project, store, updated, disabled, emptyList(), null, warnings))
        }

    fun setRuleEnabled(projectId: String?, request: RuleEnableRequest): ApiResult<RuleMutationResponse> =
        withProject<RuleMutationResponse>(projectId) { project ->
            val store = MockkRulesStore.getInstance(project)
            val ruleId = request.ruleId
            if (ruleId.isNullOrBlank()) {
                return@withProject ApiResult.fail(
                    ErrorCode.INVALID_ARGUMENT, "rule_id is required.", "Call mocks with action:'list' to get rule ids."
                )
            }
            val enabled = request.enabled ?: true
            val rule = store.getAllRules().find { it.id == ruleId } ?: return@withProject unknownRule(ruleId)

            store.setRuleEnabled(rule, enabled)
            val disabled = if (enabled && request.exclusive == true) exclusiveDisable(store, rule) else emptyList()

            scheduleSave(project)
            MockkHttpLogger.getInstance(project).info("🤖 Agent ${if (enabled) "enabled" else "disabled"} mock rule '${rule.name}'")

            ApiResult.ok(mutationResponse(project, store, rule, disabled, emptyList(), null, mutableListOf(uiRepaintWarning())))
        }

    fun deleteRule(projectId: String?, ruleId: String): ApiResult<DeleteResponse> =
        withProject<DeleteResponse>(projectId) { project ->
            val store = MockkRulesStore.getInstance(project)
            val rule = store.getAllRules().find { it.id == ruleId } ?: return@withProject unknownRule(ruleId)
            store.removeRule(rule)
            scheduleSave(project)
            MockkHttpLogger.getInstance(project).info("🤖 Agent deleted mock rule '${rule.name}' ($ruleId)")
            ApiResult.ok(DeleteResponse(deleted = true, id = ruleId, removedRules = 1))
        }

    // ========================================================================
    // Collections
    // ========================================================================

    fun listCollections(projectId: String?): ApiResult<CollectionListResponse> =
        withProject<CollectionListResponse>(projectId) { project ->
            val store = MockkRulesStore.getInstance(project)
            val rules = store.getAllRules()
            val views = store.getAllCollections().map { collectionView(it, rules.count { r -> r.collectionId == it.id }) }
            ApiResult.ok(CollectionListResponse(collections = views, count = views.size))
        }

    fun createCollection(projectId: String?, request: CollectionCreateRequest): ApiResult<CollectionView> =
        withProject<CollectionView>(projectId) { project ->
            val name = request.name?.trim()
            if (name.isNullOrEmpty()) {
                return@withProject ApiResult.fail(
                    ErrorCode.INVALID_ARGUMENT, "name is required to create a collection.",
                    "Retry with name:'Auth errors' and optionally package_name:'com.acme.app'."
                )
            }
            val store = MockkRulesStore.getInstance(project)
            val collection = store.addCollection(name, request.packageName ?: "", request.description ?: "")
            scheduleSave(project)
            MockkHttpLogger.getInstance(project).info("🤖 Agent created collection '$name'")
            ApiResult.ok(collectionView(collection, 0))
        }

    fun deleteCollection(projectId: String?, collectionId: String, removeRules: Boolean = true): ApiResult<DeleteResponse> =
        withProject<DeleteResponse>(projectId) { project ->
            val store = MockkRulesStore.getInstance(project)
            val collection = store.getCollection(collectionId)
                ?: return@withProject ApiResult.fail(
                    ErrorCode.UNKNOWN_COLLECTION,
                    "No collection with id '$collectionId'.",
                    "Call mocks with action:'list_collections' first."
                )
            val ruleCount = store.getRulesInCollection(collectionId).size
            store.removeCollection(collection, removeRules)
            scheduleSave(project)
            MockkHttpLogger.getInstance(project).info("🤖 Agent deleted collection '${collection.name}' ($ruleCount rule(s))")
            ApiResult.ok(
                DeleteResponse(
                    deleted = true,
                    id = collectionId,
                    removedRules = if (removeRules) ruleCount else 0,
                    warnings = if (!removeRules && ruleCount > 0) {
                        listOf("$ruleCount rule(s) were left orphaned and will be recovered DISABLED into the Default collection on the next load.")
                    } else emptyList()
                )
            )
        }

    fun exportMocks(projectId: String?, collectionIds: List<String>? = null): ApiResult<ExportResponse> =
        withProject<ExportResponse>(projectId) { project ->
            val store = MockkRulesStore.getInstance(project)
            val all = store.getAllCollections()
            val selected = if (collectionIds.isNullOrEmpty()) all else {
                val byId = all.associateBy { it.id }
                val missing = collectionIds.filterNot { byId.containsKey(it) }
                if (missing.isNotEmpty()) {
                    return@withProject ApiResult.fail(
                        ErrorCode.UNKNOWN_COLLECTION,
                        "Unknown collection id(s): ${missing.joinToString(", ")}.",
                        "Call mocks with action:'list_collections' to see valid ids."
                    )
                }
                collectionIds.mapNotNull { byId[it] }
            }
            val json = store.exportCollections(selected)
            ApiResult.ok(
                ExportResponse(
                    json = json,
                    collections = selected.size,
                    rules = selected.sumOf { store.getRulesInCollection(it.id).size }
                )
            )
        }

    fun importMocks(projectId: String?, request: ImportRequest): ApiResult<ImportResponse> =
        withProject<ImportResponse>(projectId) { project ->
            val json = request.json
            if (json.isNullOrBlank()) {
                return@withProject ApiResult.fail(
                    ErrorCode.INVALID_ARGUMENT, "json is required.",
                    "Pass the export payload as a JSON *string* in the `json` field."
                )
            }
            val strategy = when (request.strategy?.trim()?.uppercase() ?: IMPORT_SKIP) {
                IMPORT_REPLACE -> MockkRulesStore.ChangedRuleStrategy.REPLACE
                IMPORT_KEEP_BOTH -> MockkRulesStore.ChangedRuleStrategy.KEEP_BOTH
                IMPORT_SKIP -> MockkRulesStore.ChangedRuleStrategy.SKIP
                else -> return@withProject ApiResult.fail(
                    ErrorCode.INVALID_ARGUMENT,
                    "strategy must be one of $IMPORT_REPLACE, $IMPORT_KEEP_BOTH, $IMPORT_SKIP (got '${request.strategy}').",
                    "Retry with strategy:'$IMPORT_SKIP' to leave existing rules untouched."
                )
            }

            val store = MockkRulesStore.getInstance(project)
            val analysis = try {
                store.analyzeImport(json)
            } catch (e: Exception) {
                return@withProject ApiResult.fail(
                    ErrorCode.MALFORMED_JSON,
                    "The import payload could not be parsed: ${e.message}",
                    "Pass the exact JSON produced by mocks export."
                )
            }

            val diffs = analysis.diffs.map {
                ImportCollectionDiff(
                    name = it.incoming.collection.name,
                    exists = it.existing != null,
                    newRules = it.newRules.size,
                    changedRules = it.changedRules.size,
                    identicalRules = it.identicalRules.size
                )
            }
            val wouldCreate = diffs.sumOf { it.newRules }
            val wouldReplace = if (strategy == MockkRulesStore.ChangedRuleStrategy.REPLACE) diffs.sumOf { it.changedRules } else 0
            val wouldSkip = diffs.sumOf { it.identicalRules } +
                    if (strategy == MockkRulesStore.ChangedRuleStrategy.SKIP) diffs.sumOf { it.changedRules } else 0

            if (request.dryRun == true) {
                return@withProject ApiResult.ok(
                    ImportResponse(
                        dryRun = true, collections = diffs,
                        wouldCreate = wouldCreate, wouldReplace = wouldReplace, wouldSkip = wouldSkip,
                        collectionsCreated = 0, rulesAdded = 0, rulesReplaced = 0, rulesKeptBoth = 0, rulesSkipped = 0
                    )
                )
            }

            val result = store.applyMergeImport(analysis, strategy)
            scheduleSave(project)
            MockkHttpLogger.getInstance(project).info(
                "🤖 Agent imported mocks: +${result.collectionsCreated} collection(s), +${result.rulesAdded} rule(s)"
            )
            ApiResult.ok(
                ImportResponse(
                    dryRun = false, collections = diffs,
                    wouldCreate = wouldCreate, wouldReplace = wouldReplace, wouldSkip = wouldSkip,
                    collectionsCreated = result.collectionsCreated,
                    rulesAdded = result.rulesAdded,
                    rulesReplaced = result.rulesReplaced,
                    rulesKeptBoth = result.rulesKeptBoth,
                    rulesSkipped = result.rulesSkipped
                )
            )
        }

    // ========================================================================
    // Match explain
    // ========================================================================

    /**
     * Dry-run the real matcher against a hypothetical request.
     *
     * The WINNER always comes from [MockkRulesStore.findMatchingRuleObject] — the one implementation
     * that answers the app's CHECK_MOCK — so this can never disagree with what the app will get. The
     * per-candidate `rejected_because` strings are diagnostics computed here; they mirror the store's
     * rules and are the reason a caller can fix a rule that silently never fires.
     */
    fun explainMatch(projectId: String?, request: MatchExplainRequest): ApiResult<MatchExplainResponse> =
        withProject<MatchExplainResponse>(projectId) { project ->
            val method = request.method?.trim()?.uppercase()
            if (method.isNullOrEmpty()) {
                return@withProject ApiResult.fail(
                    ErrorCode.INVALID_ARGUMENT, "method is required.", "Retry with method:'GET' and the full url."
                )
            }
            val url = request.url
            if (url.isNullOrBlank()) {
                return@withProject ApiResult.fail(
                    ErrorCode.INVALID_ARGUMENT, "url is required.", "Retry with url:'https://api.example.com/v1/login?ts=1'."
                )
            }
            val parsed = parseUrl(url) ?: return@withProject ApiResult.fail(
                ErrorCode.INVALID_URL, "Could not parse '$url'.", "Pass an absolute URL including the scheme."
            )

            val store = MockkRulesStore.getInstance(project)
            val winnerRule = store.findMatchingRuleObject(method, parsed.host, parsed.path, parsed.query)
            val collections = store.getAllCollections().associateBy { it.id }
            val mode = registrationOf(project)?.mode?.name

            var winnerSeen = false
            val candidates = store.getAllRules().map { rule ->
                val isWinner = winnerRule != null && rule.id == winnerRule.id
                if (isWinner) winnerSeen = true
                val reason = rejectionReason(rule, collections[rule.collectionId], method, parsed)
                MatchCandidate(
                    kind = "rule",
                    id = rule.id,
                    name = rule.name,
                    enabled = rule.enabled,
                    matched = isWinner,
                    rejectedBecause = when {
                        isWinner -> null
                        reason != null -> reason
                        winnerSeen -> "another rule earlier in the list already matched this request — the first match wins"
                        else -> "the store's matcher rejected it for a reason this explanation could not reproduce; " +
                                "report this, it means the two disagree"
                    }
                )
            }

            val winner = candidates.find { it.matched }
            val warnings = mutableListOf<String>()
            // findMockForRequest only performs a lookup in MOCKK / MOCKK_DEBUG; in every other mode
            // the app is told "no mock" and calls the network, however perfect the rule is.
            val servesMocks = mode == MODE_MOCKK || mode == MODE_MOCKK_DEBUG
            if (winner != null && !servesMocks) {
                warnings += if (mode == null) {
                    "No capture session is running, so nothing is answering CHECK_MOCK: this rule cannot fire yet."
                } else {
                    "Mode is $mode. Mock rules are only served in MOCKK and MOCKK_DEBUG — call session/mode with MOCKK."
                }
            }

            ApiResult.ok(
                MatchExplainResponse(
                    mode = mode,
                    winner = winner,
                    candidates = candidates.filterNot { it.matched },
                    wouldSkipNetwork = winner != null && servesMocks,
                    warnings = warnings
                )
            )
        }

    // ========================================================================
    // Session
    // ========================================================================

    fun getSession(projectId: String?): ApiResult<SessionInfo> =
        withProject<SessionInfo>(projectId) { project -> ApiResult.ok(sessionInfo(project)) }

    /**
     * `POST …/session/start` — the call that makes everything else on this surface do anything.
     *
     * Until this existed, every capability the control plane advertised was inert until a human
     * pressed Start in the Inspector: an agent could write mock rules that could never fire and
     * read a flow journal nothing was ever added to. Resolution of the device and the app lives in
     * [CaptureSessionService] and is deliberately all-or-nothing — one device and one announced app
     * make an empty request complete, and anything ambiguous comes back as a typed error listing
     * the candidates in `details`.
     */
    fun startSession(projectId: String?, request: StartSessionRequest): ApiResult<SessionStartResponse> =
        withProject<SessionStartResponse>(projectId) { project ->
            val mode = when (val parsed = parseMode(request.mode, OkHttpInterceptorServer.Mode.RECORDING)) {
                is ApiResult.Err -> return@withProject parsed
                is ApiResult.Ok -> parsed.value
            }
            pauseGate(mode, request.confirmPauseAll)?.let { return@withProject ApiResult.Err(it) }

            val session = CaptureSessionService.getInstance(project)
            when (val result = session.startSession(
                serial = request.serial,
                packageName = request.packageName,
                mode = mode,
                origin = CaptureSessionService.SessionOrigin.AGENT
            )) {
                is CaptureSessionService.StartResult.Failed -> startFailure(project, result)

                is CaptureSessionService.StartResult.AlreadyRunning -> ApiResult.ok(
                    SessionStartResponse(
                        started = false,
                        alreadyRunning = true,
                        session = sessionInfo(project),
                        deviceResolvedBy = null,
                        appResolvedBy = null,
                        warnings = result.warnings,
                        hint = "A session was already capturing; anything you passed has been applied to it. " +
                                "Stop it first (POST …/session/stop) if you need a different device."
                    )
                )

                is CaptureSessionService.StartResult.Started -> {
                    val info = sessionInfo(project)
                    ApiResult.ok(
                        SessionStartResponse(
                            started = true,
                            alreadyRunning = false,
                            session = info,
                            deviceResolvedBy = result.deviceResolvedBy,
                            appResolvedBy = result.appResolvedBy,
                            warnings = result.warnings + inspectorSyncNote(),
                            hint = if (result.state.mode == OkHttpInterceptorServer.Mode.RECORDING) {
                                "Capturing in RECORDING: mock rules are never consulted. Call " +
                                        "POST …/session/mode with mode:'$MODE_MOCKK' to make them fire."
                            } else null
                        )
                    )
                }
            }
        }

    /**
     * `POST …/session/stop`.
     *
     * The `adb reverse` tunnel is left open on purpose — see `CaptureSessionService.stopSession`.
     * Stopping something that was not running is reported as `was_running:false` rather than
     * dressed up as a stop.
     */
    fun stopSession(projectId: String?): ApiResult<SessionStopResponse> =
        withProject<SessionStopResponse>(projectId) { project ->
            val result = CaptureSessionService.getInstance(project).stopSession()
            if (result.wasRunning) {
                MockkHttpLogger.getInstance(project).info("🤖 Agent stopped the capture session")
            }
            ApiResult.ok(
                SessionStopResponse(
                    stopped = result.wasRunning,
                    wasRunning = result.wasRunning,
                    session = sessionInfo(project),
                    warnings = if (result.wasRunning) result.warnings + inspectorSyncNote() else result.warnings,
                    hint = if (result.wasRunning) null else
                        "Nothing was capturing. POST …/session/start begins a session."
                )
            )
        }

    /**
     * `POST …/session/restart` — stop, then start with the arguments given.
     *
     * The one way to move a live session to a different device: the `adb reverse` tunnel belongs to
     * the device the session was started on, so a running session cannot be repointed in place.
     */
    fun restartSession(projectId: String?, request: StartSessionRequest): ApiResult<SessionStartResponse> =
        withProject<SessionStartResponse>(projectId) { project ->
            // Judge the request BEFORE stopping anything. startSession refuses DEBUG without
            // confirm_pause_all; a refusal that arrives after the stop leaves the caller with no
            // session at all — a "no" that destroyed state on the way out.
            val mode = when (val parsed = parseMode(request.mode, OkHttpInterceptorServer.Mode.RECORDING)) {
                is ApiResult.Err -> return@withProject parsed
                is ApiResult.Ok -> parsed.value
            }
            pauseGate(mode, request.confirmPauseAll)?.let { return@withProject ApiResult.Err(it) }
            CaptureSessionService.getInstance(project).stopSession()
            when (val started = startSession(project.locationHash, request)) {
                is ApiResult.Err -> started
                is ApiResult.Ok -> ApiResult.ok(
                    started.value.copy(
                        warnings = listOf("The previous session was stopped first.") + started.value.warnings
                    )
                )
            }
        }

    /**
     * `POST …/session/app` — the package filter.
     *
     * This is not app launching (that is `mockkhttp_app`, still unimplemented): it decides which
     * app's flows this project receives. Clearing it needs `clear:true` precisely because an empty
     * filter is not a neutral state — it captures every instrumented app on the machine and steals
     * flows from every other open project.
     */
    fun setPackageFilter(projectId: String?, request: SetPackageFilterRequest): ApiResult<SessionAppResponse> =
        withProject<SessionAppResponse>(projectId) { project ->
            val requested = request.packageName?.trim()?.takeIf { it.isNotEmpty() }
            val clear = request.clear == true

            if (clear && requested != null) {
                return@withProject ApiResult.fail(
                    ErrorCode.INVALID_ARGUMENT,
                    "clear:true and package_name:'$requested' ask for two different things.",
                    "Send package_name alone to set the filter, or clear:true alone to remove it."
                )
            }
            if (!clear && requested == null) {
                return@withProject ApiResult.fail(
                    ErrorCode.INVALID_ARGUMENT,
                    "package_name is required.",
                    "Retry with package_name:'com.example.app'. GET /v1/projects/${project.locationHash}/devices " +
                            "lists the apps that have announced themselves. To capture every app instead, send clear:true."
                )
            }
            if (requested != null && !looksLikeAppId(requested)) {
                return@withProject ApiResult.fail(
                    ErrorCode.INVALID_ARGUMENT,
                    "'$requested' is not a package name or bundle id.",
                    "It is compared literally against what the app reports, so a stray space or quote " +
                            "captures nothing. Retry with something like package_name:'com.example.app'."
                )
            }

            val session = CaptureSessionService.getInstance(project)
            val wasRunning = session.state().running
            val state = session.setPackageFilter(requested)

            val warnings = mutableListOf<String>()
            if (clear) {
                warnings += "This project now captures EVERY instrumented app and will take flows from any " +
                        "other open project that has no filter of its own."
            } else if (requested != null &&
                requested !in GlobalOkHttpInterceptorServer.getInstance().getKnownMockkHttpPackages()
            ) {
                warnings += "$requested has never announced itself to this IDE. If that is a typo the session " +
                        "will capture nothing, with no other symptom."
            }
            if (wasRunning) warnings += inspectorSyncNote()

            MockkHttpLogger.getInstance(project).info(
                "🤖 Agent set the package filter to ${requested ?: "none (every app)"}"
            )

            ApiResult.ok(
                SessionAppResponse(
                    packageFilter = state.packageFilter,
                    appliedToLiveSession = wasRunning,
                    session = sessionInfo(project),
                    warnings = warnings,
                    hint = if (wasRunning) null else
                        "Nothing is capturing yet, so this is remembered for the next POST …/session/start."
                )
            )
        }

    /**
     * `GET …/devices` — the two combo boxes at the top of the Inspector, as data.
     *
     * @param deepScan the caller opting into `AppManager.getInstalledApps`, which reads every
     *   installed APK and takes minutes on a physical device. It is only taken when nothing has
     *   announced itself: an app that has spoken the protocol is already proof, and no scan
     *   improves on proof.
     */
    fun listDevices(projectId: String?, deepScan: Boolean = false): ApiResult<DeviceListResponse> =
        withProject<DeviceListResponse>(projectId) { project ->
            val listing = CaptureSessionService.getInstance(project).listDevices(deepScan)
            val devices = listing.devices.map { candidate ->
                DeviceView(
                    serial = candidate.serial,
                    platform = candidate.device.platform.name,
                    label = candidate.device.displayName,
                    model = candidate.device.model ?: candidate.device.avdName,
                    online = candidate.device.isOnline,
                    emulator = candidate.device.isEmulator,
                    apiLevel = candidate.device.apiLevel.takeIf { it > 0 },
                    osVersion = candidate.device.osVersion,
                    apps = candidate.apps.map {
                        DeviceAppView(
                            packageName = it.packageName,
                            appName = it.appName,
                            announced = it.announced,
                            instrumented = it.instrumented,
                            installedOnDevice = it.installedOnDevice
                        )
                    },
                    appsSource = appsSourceOf(candidate.appsSource),
                    note = candidate.note
                )
            }

            val soleDevice = devices.singleOrNull()
            val soleApp = soleDevice?.apps?.filter { it.instrumented }?.singleOrNull()
            val hint = when {
                devices.isEmpty() ->
                    "Nothing is connected. Boot an emulator (or plug a device in) and call this again."

                soleDevice != null && soleApp != null ->
                    "POST /v1/projects/${project.locationHash}/session/start with an empty body starts " +
                            "capturing ${soleApp.packageName} on ${soleDevice.serial}."

                listing.instrumentedPackages.isEmpty() && !listing.deepScanned ->
                    "No app has announced itself. Launch the app under test — an instrumented build " +
                            "PINGs on startup — or repeat with ?scan=true to read the installed APKs, " +
                            "which takes minutes on a physical device."

                else ->
                    "Pass serial and package_name from this list to POST " +
                            "/v1/projects/${project.locationHash}/session/start."
            }

            ApiResult.ok(
                DeviceListResponse(
                    devices = devices,
                    count = devices.size,
                    instrumentedPackages = listing.instrumentedPackages,
                    adbAvailable = listing.adbAvailable,
                    adbPath = listing.adbPath,
                    iosToolingAvailable = listing.iosToolingAvailable,
                    iosEnumerationFailed = listing.iosEnumerationFailed,
                    deepScanned = listing.deepScanned,
                    hint = hint,
                    warnings = listing.warnings
                )
            )
        }

    /**
     * Change the capture mode of a RUNNING session.
     *
     * Refuses on a stopped session instead of reporting a success-shaped no-op: `start(mode, filter)`
     * takes its mode as an argument, so a mode set beforehand is overwritten the moment the session
     * actually starts. Pass the mode to `session/start` instead.
     */
    fun setMode(projectId: String?, request: SetModeRequest): ApiResult<SessionModeResponse> =
        withProject<SessionModeResponse>(projectId) { project ->
            val requested = request.mode?.trim()?.uppercase()
            val mode = when (val parsed = parseMode(request.mode, null)) {
                is ApiResult.Err -> return@withProject parsed
                is ApiResult.Ok -> parsed.value
            }

            val registration = registrationOf(project)
                ?: return@withProject ApiResult.fail(
                    ErrorCode.SESSION_NOT_RUNNING,
                    "No capture session is running for project '${project.name}', so the mode change would be silently lost.",
                    "Start one first: POST /v1/projects/${project.locationHash}/session/start " +
                            "{\"mode\":\"$requested\"} — with one device and one instrumented app it needs nothing else."
                )

            val previous = registration.mode.name
            if (previous == requested) {
                return@withProject ApiResult.ok(
                    SessionModeResponse(
                        mode = previous, previousMode = previous, changed = false, sessionRunning = true,
                        packageFilter = registration.packageNameFilter, uiInSync = true, warnings = emptyList()
                    )
                )
            }

            pauseGate(mode, request.confirmPauseAll)?.let { return@withProject ApiResult.Err(it) }

            // Through the session service rather than straight at the interceptor, so that whatever
            // is watching the session (the Inspector's agent strip) sees the change.
            CaptureSessionService.getInstance(project).setMode(mode)
            val applied = registrationOf(project)?.mode?.name ?: previous
            MockkHttpLogger.getInstance(project).info("🤖 Agent changed capture mode: $previous → $applied")

            val warnings = mutableListOf<String>()
            if (applied != requested) {
                warnings += "The registration still reports mode $applied. The session may have been stopped concurrently."
            }
            warnings += inspectorSyncNote()

            ApiResult.ok(
                SessionModeResponse(
                    mode = applied,
                    previousMode = previous,
                    changed = applied != previous,
                    sessionRunning = true,
                    packageFilter = registration.packageNameFilter,
                    uiInSync = false,
                    warnings = warnings
                )
            )
        }

    // ------------------------------------------------------------------------
    // Session helpers
    // ------------------------------------------------------------------------

    /**
     * Parse a mode name.
     *
     * @param default what an absent value means. Null makes the value mandatory, which is what
     *   `session/mode` needs — there is nothing sensible to default a mode CHANGE to.
     */
    private fun parseMode(
        raw: String?,
        default: OkHttpInterceptorServer.Mode?
    ): ApiResult<OkHttpInterceptorServer.Mode> {
        val requested = raw?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
        if (requested == null) {
            return default?.let { ApiResult.ok(it) } ?: ApiResult.fail(
                ErrorCode.INVALID_ARGUMENT,
                "mode is required.",
                "Retry with mode:'$MODE_MOCKK' — that is the mode in which mock rules are served."
            )
        }
        return when (requested) {
            MODE_RECORDING -> ApiResult.ok(OkHttpInterceptorServer.Mode.RECORDING)
            MODE_DEBUG -> ApiResult.ok(OkHttpInterceptorServer.Mode.DEBUG)
            MODE_MOCKK -> ApiResult.ok(OkHttpInterceptorServer.Mode.MOCKK)
            MODE_MOCKK_DEBUG -> ApiResult.ok(OkHttpInterceptorServer.Mode.MOCKK_DEBUG)
            else -> ApiResult.fail(
                ErrorCode.INVALID_ARGUMENT,
                "mode must be one of $MODE_RECORDING, $MODE_DEBUG, $MODE_MOCKK, $MODE_MOCKK_DEBUG (got '$raw').",
                "Retry with mode:'$MODE_MOCKK' — that is the mode in which mock rules are served."
            )
        }
    }

    /**
     * The DEBUG footgun guard, shared by `session/start` and `session/mode`.
     *
     * DEBUG and MOCKK_DEBUG pause EVERY request behind a Swing dialog that only a human can answer.
     * Until the pause policy and the agent resolver exist, entering them from an automated caller
     * stalls the app for up to 55 s per request, background polling included.
     *
     * @return the error to return, or null when the mode is safe (or explicitly confirmed).
     */
    private fun pauseGate(mode: OkHttpInterceptorServer.Mode, confirmed: Boolean?): ApiError? {
        val pauses = mode == OkHttpInterceptorServer.Mode.DEBUG ||
                mode == OkHttpInterceptorServer.Mode.MOCKK_DEBUG
        if (!pauses || confirmed == true) return null
        return ApiError(
            ErrorCode.PAUSE_POLICY_REQUIRED,
            "${mode.name} pauses every request the app makes — background polling included — behind a dialog only a " +
                    "human can answer, for up to 55 s each. Selective pausing and agent-answered intercepts are not in this build.",
            "Use mode:'$MODE_MOCKK' to serve mock rules with zero pausing. If you really want a human at the keyboard " +
                    "to answer every call, repeat with confirm_pause_all:true."
        )
    }

    /** Map a typed start failure onto the error code and the hint that actually unblocks it. */
    private fun startFailure(
        project: Project,
        failed: CaptureSessionService.StartResult.Failed
    ): ApiResult<Nothing> {
        val pid = project.locationHash
        val devices = failed.devices.associate { candidate ->
            candidate.serial to buildString {
                append(candidate.device.displayName)
                append(" (").append(candidate.device.platform.name)
                if (!candidate.device.isOnline) append(", offline")
                append(")")
                val apps = candidate.apps.joinToString { it.packageName }
                if (apps.isNotEmpty()) append(" apps: ").append(apps)
            }
        }
        val apps = failed.apps.associate { app ->
            app.packageName to (if (app.announced) "announced to this IDE" else "found on the device")
        }

        return when (failed.reason) {
            CaptureSessionService.StartFailure.ADB_NOT_FOUND -> ApiResult.fail(
                ErrorCode.ADB_UNAVAILABLE, failed.message,
                "Set the adb path in MockkHttp → Settings, or install the Android SDK platform-tools, " +
                        "then call GET /v1/projects/$pid/devices. An iOS Simulator needs no adb at all."
            )

            CaptureSessionService.StartFailure.ADB_UNAVAILABLE -> ApiResult.fail(
                ErrorCode.ADB_UNAVAILABLE, failed.message,
                "Run `adb devices` in a terminal: it either fixes the bridge or names the reason. Then retry."
            )

            CaptureSessionService.StartFailure.NO_DEVICES -> ApiResult.fail(
                ErrorCode.NO_DEVICES, failed.message,
                "Boot an emulator or plug a device in, then GET /v1/projects/$pid/devices to confirm it is seen."
            )

            CaptureSessionService.StartFailure.DEVICE_NOT_FOUND -> ApiResult.fail(
                ErrorCode.DEVICE_NOT_FOUND, failed.message,
                "GET /v1/projects/$pid/devices lists the serials that exist; pass one as serial.",
                details = devices
            )

            CaptureSessionService.StartFailure.AMBIGUOUS_DEVICE -> ApiResult.fail(
                ErrorCode.AMBIGUOUS_DEVICE, failed.message,
                "Retry with serial:'<one of the keys in details>'.",
                details = devices
            )

            CaptureSessionService.StartFailure.APP_NOT_CHOSEN -> ApiResult.fail(
                ErrorCode.APP_NOT_CHOSEN, failed.message,
                // Port 9876 is bound by a session, an app scan or the open Inspector — not before. While
                // it is not, "launch the app and it will announce itself" is advice that cannot work:
                // the app's socket is refused and it stays silent. Only while the port is bound is
                // launching the app a way out (audit round 15, BB; round 16, BF).
                if (GlobalOkHttpInterceptorServer.getInstance().isBound()) {
                    "Retry with package_name:'com.example.app' (and serial:'…' with several devices), or launch " +
                            "the app under test: port 9876 is bound, so an instrumented build announces itself on " +
                            "startup and then needs no argument at all. GET /v1/projects/$pid/devices?scan=true " +
                            "reads the installed APKs instead (minutes)."
                } else {
                    "Retry with package_name:'com.example.app' (and serial:'…' with several devices). Launching " +
                            "the app first will not help right now: port 9876 is not bound (status.interceptor.bound " +
                            "is false), so an app started now cannot announce itself — start the session with the " +
                            "package name, then launch the app. GET /v1/projects/$pid/devices?scan=true reads the " +
                            "installed APKs instead (minutes)."
                },
                details = devices
            )

            CaptureSessionService.StartFailure.AMBIGUOUS_APP -> ApiResult.fail(
                ErrorCode.AMBIGUOUS_APP, failed.message,
                "Retry with package_name:'<one of the keys in details>'.",
                details = apps
            )

            CaptureSessionService.StartFailure.REVERSE_TUNNEL_FAILED -> ApiResult.fail(
                ErrorCode.DEVICE_UNREACHABLE, failed.message,
                "Check the cable and that USB debugging is still authorised on the device " +
                        "(`adb devices` must show it as `device`, not `unauthorized`), then retry."
            )

            CaptureSessionService.StartFailure.INTERCEPTOR_UNAVAILABLE -> ApiResult.fail(
                ErrorCode.INTERCEPTOR_PORT_IN_USE, failed.message,
                "Port ${GlobalOkHttpInterceptorServer.SERVER_PORT} is usually held by a second IDE with " +
                        "MockkHttp open. Close it, or stop its capture session, then retry. " +
                        "GET /v1/projects/$pid/status reports interceptor.bind_error."
            )

            CaptureSessionService.StartFailure.REGISTRATION_FAILED -> ApiResult.fail(
                ErrorCode.SESSION_START_FAILED, failed.message,
                "Retry once. If it repeats it is a MockkHttp bug: the reason is in idea.log " +
                        "(Help → Show Log) under com.sergiy.dev.mockkhttp."
            )

            CaptureSessionService.StartFailure.PROJECT_DISPOSED -> ApiResult.fail(
                ErrorCode.PROJECT_DISPOSED, failed.message,
                "Call GET /v1/projects to see which projects this IDE still has open."
            )
        }
    }

    private fun appsSourceOf(source: CaptureSessionService.AppsSource): String = when (source) {
        CaptureSessionService.AppsSource.ANNOUNCED -> APPS_SOURCE_ANNOUNCED
        CaptureSessionService.AppsSource.DEVICE_SCAN -> APPS_SOURCE_DEVICE_SCAN
        CaptureSessionService.AppsSource.NONE -> APPS_SOURCE_NONE
    }

    /**
     * A package filter is compared literally against what the app reports, so a value that cannot
     * be an app id can only ever capture nothing. Deliberately loose — an iOS bundle id may contain
     * hyphens, and refusing a legitimate id would be worse than accepting an odd one.
     */
    private fun looksLikeAppId(value: String): Boolean =
        value.length <= 255 && APP_ID_PATTERN.matches(value)

    // ========================================================================
    // Project resolution
    // ========================================================================

    private fun openProjects(): List<Project> =
        ProjectManager.getInstance().openProjects.filter { !it.isDisposed }

    /**
     * Map a `project_id` to an open [Project].
     *
     * `locationHash` is the contract, but a caller hand-writing curl will reach for the project's
     * name or path, so both are accepted when they are unambiguous. Failure is always typed and the
     * hint always names the call that fixes it.
     */
    private fun resolveProject(projectId: String?): ApiResult<Project> {
        val open = openProjects()
        // Cheap opportunistic cleanup: drop cursors for projects that are no longer open.
        if (flowIndexes.isNotEmpty()) {
            flowIndexes.keys.retainAll(open.map { it.locationHash }.toSet())
        }

        if (open.isEmpty()) {
            return ApiResult.fail(
                ErrorCode.PROJECT_NOT_FOUND,
                "No project is open in this IDE window.",
                "Open the project you want to drive, then call GET /v1/projects to confirm it is listed."
            )
        }
        if (projectId.isNullOrBlank()) {
            return if (open.size == 1) ApiResult.ok(open.first()) else ApiResult.fail(
                ErrorCode.AMBIGUOUS_PROJECT,
                "${open.size} projects are open and none was named.",
                "Call GET /v1/projects and pass one of the returned project_id values.",
                details = open.associate { it.locationHash to it.name }
            )
        }

        open.find { it.locationHash == projectId }?.let { return ApiResult.ok(it) }

        val byName = open.filter { it.name == projectId }
        if (byName.size == 1) return ApiResult.ok(byName.first())
        if (byName.size > 1) {
            return ApiResult.fail(
                ErrorCode.AMBIGUOUS_PROJECT,
                "${byName.size} open projects are called '$projectId'.",
                "Pass the project_id (Project.locationHash) from GET /v1/projects instead of the name.",
                details = byName.associate { it.locationHash to (it.basePath ?: it.name) }
            )
        }

        val byPath = open.filter { it.basePath == projectId }
        if (byPath.size == 1) return ApiResult.ok(byPath.first())

        return ApiResult.fail(
            ErrorCode.PROJECT_NOT_FOUND,
            "No open project matches '$projectId'.",
            "Call GET /v1/projects and use one of the returned project_id values.",
            details = open.associate { it.locationHash to it.name }
        )
    }

    private fun <T : Any> withProject(projectId: String?, block: (Project) -> ApiResult<T>): ApiResult<T> =
        when (val resolved = resolveProject(projectId)) {
            is ApiResult.Err -> resolved
            is ApiResult.Ok -> try {
                block(resolved.value)
            } catch (e: Exception) {
                // The router turns anything unhandled into a 500 too, but doing it here keeps the
                // stack trace attached to the project it happened in.
                log.warn("❌ Agent control call failed for project ${resolved.value.name}", e)
                ApiResult.fail(
                    ErrorCode.INTERNAL_ERROR,
                    "${e.javaClass.simpleName}: ${e.message ?: "no message"}",
                    "This is a bug in MockkHttp. The full stack trace is in the IDE log."
                )
            }
        }

    private fun registrationOf(project: Project): GlobalOkHttpInterceptorServer.ProjectRegistration? =
        GlobalOkHttpInterceptorServer.getInstance().getRegisteredProjects()
            .find { it.projectId == project.locationHash }

    private fun summaryOf(project: Project): ProjectSummary {
        val registration = registrationOf(project)
        return ProjectSummary(
            projectId = project.locationHash,
            name = project.name,
            basePath = project.basePath,
            sessionRunning = registration != null,
            mode = registration?.mode?.name,
            packageFilter = registration?.packageNameFilter
        )
    }

    /**
     * The session as `CaptureSessionService` sees it — which is the interceptor registration for
     * `running`/`mode`/`package_filter` plus that service's own record of the device and the start.
     *
     * `device`, `started_at` and `adb_path` used to be hard-coded null with a comment saying a
     * guess would be a lie. They are answerable now, except for a session the Inspector started
     * before this service existed to record one, where they stay null for exactly that reason.
     */
    private fun sessionInfo(project: Project): SessionInfo {
        val state = CaptureSessionService.getInstance(project).state()
        return SessionInfo(
            running = state.running,
            mode = state.mode?.name,
            packageFilter = state.packageFilter,
            device = state.device?.takeIf { state.running }?.let { deviceInfoOf(it) },
            startedAt = state.startedAt,
            adbPath = state.adbPath,
            startedBy = if (state.running) startedByOf(state.startedBy) else null,
            instrumentedPackages = GlobalOkHttpInterceptorServer.getInstance().getKnownMockkHttpPackages().toList()
        )
    }

    private fun startedByOf(origin: CaptureSessionService.SessionOrigin): String = when (origin) {
        CaptureSessionService.SessionOrigin.AGENT -> STARTED_BY_AGENT
        CaptureSessionService.SessionOrigin.UI -> STARTED_BY_UI
        CaptureSessionService.SessionOrigin.UNKNOWN -> STARTED_BY_UNKNOWN
    }

    private fun deviceInfoOf(device: EmulatorInfo): DeviceInfo = DeviceInfo(
        serial = device.serialNumber,
        platform = device.platform.name,
        model = device.model ?: device.avdName,
        online = device.isOnline,
        // 0 is EmulatorManager's "could not read it", and reporting API 0 would be worse than null.
        apiLevel = device.apiLevel.takeIf { it > 0 }
    )

    private fun pluginVersion(): String = com.sergiy.dev.mockkhttp.MockkHttpBuild.VERSION

    private fun scheduleSave(project: Project) {
        // Persisting immediately: an agent can create fifty rules and a crash before the IDE's own
        // save tick would lose all of them with nothing on screen to hint at it.
        try {
            SaveAndSyncHandler.getInstance().scheduleProjectSave(project)
        } catch (e: Exception) {
            log.warn("⚠️ Could not schedule a project save after an agent mutation", e)
        }
    }

    // ========================================================================
    // Flow sequence numbers and the await primitive
    // ========================================================================

    /** A flow plus the control plane's monotonic cursor for it. */
    private data class SeqFlow(val seq: Long, val flow: HttpFlowData)

    /**
     * Monotonic sequence numbers over `FlowStore`, derived from its insertion order.
     *
     * `FlowStore` has no `seq` of its own (see the result notes: adding one there is a required
     * follow-up), so numbers are assigned lazily here the first time a flow id is seen. Because
     * `getAllFlows()` returns insertion order and eviction only ever removes from the front, lazily
     * numbering that list yields exactly the arrival order, and a number never changes for a flow —
     * which is what makes it usable as a cursor. Numbers keep climbing across evictions and clears.
     */
    private class ProjectFlowIndex {
        private val lock = ReentrantLock()
        private val flowArrived = lock.newCondition()
        private val seqByFlowId = LinkedHashMap<String, Long>()
        private var next = 1L

        /** Set once, so exactly one FlowStore listener is ever registered per project. */
        val listenerAttached = AtomicBoolean(false)

        /** Called from interceptor socket threads. Takes the lock only to wake the waiters. */
        fun signal() {
            lock.withLock { flowArrived.signalAll() }
        }

        fun assignSequences(store: FlowStore): List<SeqFlow> {
            val flows = store.getAllFlows()
            return lock.withLock {
                val present = HashSet<String>(flows.size * 2)
                val out = ArrayList<SeqFlow>(flows.size)
                for (flow in flows) {
                    present.add(flow.flowId)
                    val seq = seqByFlowId.getOrPut(flow.flowId) { next++ }
                    out.add(SeqFlow(seq, flow))
                }
                // Evicted flows can never come back, so their numbers are dead weight.
                if (seqByFlowId.size > present.size) seqByFlowId.keys.retainAll(present)
                out
            }
        }

        /** The cursor to hand out: pass it back as `since_seq` to see only what arrives next. */
        fun nextSeq(): Long = lock.withLock { next }

        /** Drop the id→seq map after a clear; [next] deliberately keeps climbing. */
        fun forget() {
            lock.withLock { seqByFlowId.clear() }
        }

        /** @return false if the wait was interrupted, true on a signal or a timeout. */
        fun awaitChange(timeoutMs: Long): Boolean = try {
            lock.withLock { flowArrived.await(timeoutMs, TimeUnit.MILLISECONDS) }
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun indexFor(project: Project): ProjectFlowIndex {
        val index = flowIndexes.computeIfAbsent(project.locationHash) { ProjectFlowIndex() }
        if (index.listenerAttached.compareAndSet(false, true)) {
            // ONE listener per project for the life of the IDE — never one per poll. FlowStore
            // exposes no way to unregister (no remove, no Disposable-scoped overload), so a
            // per-call registration would leak a callback into the interceptor's hot path forever.
            // Waiters instead park on this index's own condition and leave by returning.
            val store = FlowStore.getInstance(project)
            store.addFlowAddedListener { index.signal() }
            store.addFlowUpdatedListener { index.signal() }
        }
        return index
    }

    private fun nearMisses(snapshot: List<SeqFlow>, sinceSeq: Long, matcher: FlowMatcher): List<ClosestObserved> =
        snapshot.asSequence()
            .filter { it.seq >= sinceSeq && !matcher.matches(it.flow) }
            .groupingBy { it.flow.request.method to it.flow.request.path }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(3)
            .map { ClosestObserved(method = it.key.first, path = it.key.second, hits = it.value) }

    private fun buildAwaitHint(
        project: Project,
        matcher: MatcherDto,
        waitedMs: Long,
        closest: List<ClosestObserved>,
        interrupted: Boolean
    ): String {
        if (interrupted) return "The wait was cut short before its deadline. Retry with the returned next_seq."
        val builder = StringBuilder("Nothing matched in $waitedMs ms. ")
        if (closest.isEmpty()) {
            builder.append("No traffic at all reached MockkHttp while waiting. ")
            val registration = registrationOf(project)
            builder.append(
                when {
                    registration == null -> "No capture session is running: start one yourself with POST " +
                            "/v1/projects/${project.locationHash}/session/start (package_name when the app has not announced itself)."
                    !GlobalOkHttpInterceptorServer.getInstance().isBound() ->
                        "The interceptor is not listening on port ${GlobalOkHttpInterceptorServer.SERVER_PORT}; check status."
                    registration.packageNameFilter != null ->
                        "The session only accepts traffic from '${registration.packageNameFilter}' — check the app under test is that package."
                    else -> "Make the app perform the request, then call again with the returned next_seq."
                }
            )
        } else {
            val top = closest.first()
            builder.append("The closest traffic was ${top.method} ${top.path} (${top.hits} hit(s)). ")
            if (matcher.path != null) {
                builder.append(
                    "Your matcher used path:'${matcher.path}', which is an exact, case-sensitive comparison. " +
                            "Try url_contains, or path_regex:'^${matcher.path}'."
                )
            } else {
                builder.append("Loosen the matcher — url_contains is the most forgiving field.")
            }
        }
        return builder.toString()
    }

    // ========================================================================
    // Matching
    // ========================================================================

    /** A parsed request URL: everything the matcher and the rule engine compare against. */
    private data class ParsedUrl(val host: String, val path: String, val query: Map<String, String>)

    private fun parseUrl(url: String): ParsedUrl? = try {
        val parsed = java.net.URI.create(url).toURL()
        val query = parsed.query?.split("&")?.filter { it.isNotEmpty() }?.associate { param ->
            val parts = param.split("=", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        } ?: emptyMap()
        val host = parsed.host
        if (host.isNullOrBlank()) null else ParsedUrl(host, (parsed.path ?: "").ifEmpty { "/" }, query)
    } catch (_: Exception) {
        null
    }

    /**
     * The compiled form of a [MatcherDto].
     *
     * Every present field must match (AND). Kept here rather than in a shared `match/` class only
     * because that class is M2's; the semantics are the ones the whole catalog documents, so lifting
     * this out later is a move, not a rewrite.
     */
    private class FlowMatcher(
        private val method: String?,
        private val host: String?,
        private val path: String?,
        private val pathRegex: Regex?,
        private val urlContains: String?,
        private val query: List<QueryMatchDto>,
        private val queryRegex: Map<String, Regex>,
        private val header: List<HeaderMatchDto>,
        private val headerRegex: Map<String, Regex>,
        private val bodyContains: String?
    ) {
        fun matches(flow: HttpFlowData): Boolean {
            val request = flow.request
            if (method != null && !request.method.equals(method, ignoreCase = true)) return false
            if (host != null && !request.host.equals(host, ignoreCase = true)) return false
            if (path != null && normalizePath(request.path) != path) return false
            if (pathRegex != null && !pathRegex.matches(request.path)) return false
            if (urlContains != null && !request.url.contains(urlContains, ignoreCase = true)) return false
            if (bodyContains != null && !request.content.contains(bodyContains)) return false

            if (query.isNotEmpty()) {
                val actual = queryOf(request.url)
                for (condition in query) {
                    val key = condition.key ?: continue
                    val value = actual[key]
                    val required = condition.required ?: true
                    if (value == null) {
                        if (required) return false else continue
                    }
                    when ((condition.match ?: MATCH_EXACT).uppercase()) {
                        MATCH_WILDCARD -> Unit
                        MATCH_REGEX -> if (queryRegex[key]?.matches(value) != true) return false
                        else -> if (condition.value != null && condition.value != value) return false
                    }
                }
            }

            if (header.isNotEmpty()) {
                val actual = request.headers.entries.associate { it.key.lowercase() to it.value }
                for (condition in header) {
                    val key = condition.key?.lowercase() ?: continue
                    val value = actual[key] ?: return false
                    when ((condition.match ?: MATCH_EXACT).uppercase()) {
                        MATCH_REGEX -> if (headerRegex[key]?.matches(value) != true) return false
                        else -> if (condition.value != null && condition.value != value) return false
                    }
                }
            }
            return true
        }

        companion object {
            fun normalizePath(path: String): String =
                if (path.length > 1 && path.endsWith("/")) path.dropLast(1) else path

            fun queryOf(url: String): Map<String, String> {
                val q = url.substringAfter('?', "")
                if (q.isEmpty()) return emptyMap()
                return q.split("&").filter { it.isNotEmpty() }.associate { param ->
                    val parts = param.split("=", limit = 2)
                    parts[0] to (parts.getOrNull(1) ?: "")
                }
            }
        }
    }

    private fun compileMatcher(dto: MatcherDto): ApiResult<FlowMatcher> {
        if (dto.path != null && dto.pathRegex != null) {
            return ApiResult.fail(
                ErrorCode.INVALID_ARGUMENT,
                "path and path_regex are mutually exclusive.",
                "Keep path_regex (anchored, matches the whole path) and drop path."
            )
        }
        val pathRegex = dto.pathRegex?.let {
            compileRegex(it) ?: return invalidRegex("path_regex", it)
        }
        val queryRegex = HashMap<String, Regex>()
        for (condition in dto.query.orEmpty()) {
            if ((condition.match ?: "").equals(MATCH_REGEX, ignoreCase = true)) {
                val key = condition.key ?: continue
                val pattern = condition.value ?: continue
                queryRegex[key] = compileRegex(pattern) ?: return invalidRegex("query['$key']", pattern)
            }
        }
        val headerRegex = HashMap<String, Regex>()
        for (condition in dto.header.orEmpty()) {
            if ((condition.match ?: "").equals(MATCH_REGEX, ignoreCase = true)) {
                val key = condition.key?.lowercase() ?: continue
                val pattern = condition.value ?: continue
                headerRegex[key] = compileRegex(pattern) ?: return invalidRegex("header['$key']", pattern)
            }
        }
        return ApiResult.ok(
            FlowMatcher(
                method = dto.method?.trim()?.takeIf { it.isNotEmpty() },
                host = dto.host?.trim()?.takeIf { it.isNotEmpty() },
                path = dto.path?.let { FlowMatcher.normalizePath(it) },
                pathRegex = pathRegex,
                urlContains = dto.urlContains?.takeIf { it.isNotEmpty() },
                query = dto.query.orEmpty(),
                queryRegex = queryRegex,
                header = dto.header.orEmpty(),
                headerRegex = headerRegex,
                bodyContains = dto.bodyContains?.takeIf { it.isNotEmpty() }
            )
        )
    }

    private fun invalidRegex(field: String, pattern: String): ApiResult<Nothing> = ApiResult.fail(
        ErrorCode.INVALID_REGEX,
        "$field is not a valid regular expression: '$pattern'.",
        "Regexes are anchored (they must match the whole value) and use Java syntax. Escape any literal dot as \\\\."
    )

    private fun compileRegex(pattern: String): Regex? {
        regexCache[pattern]?.let { return it }
        return try {
            Regex(pattern).also {
                // Bounded so a caller cannot grow the cache without limit; a clear is cheaper than
                // an LRU here because a miss only costs one Pattern.compile.
                if (regexCache.size >= MAX_REGEX_CACHE) regexCache.clear()
                regexCache[pattern] = it
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun FlowQuery.toMatcher(): MatcherDto = MatcherDto(
        method = method,
        host = host,
        path = path,
        pathRegex = pathRegex,
        urlContains = urlContains,
        bodyContains = bodyContains
    )

    /** Filters that are about the RESPONSE rather than the request, so they live outside FlowMatcher. */
    private fun matchesFlowQuery(query: FlowQuery, flow: HttpFlowData): Boolean {
        val status = flow.response?.statusCode
        query.status?.let { if (status != it) return false }
        query.statusMin?.let { if (status == null || status < it) return false }
        query.statusMax?.let { if (status == null || status > it) return false }
        query.resolution?.let { if (!resolutionOf(flow).equals(it, ignoreCase = true)) return false }
        query.pathContains?.let { if (!flow.request.path.contains(it, ignoreCase = true)) return false }
        return true
    }

    // ========================================================================
    // Views: flows
    // ========================================================================

    /**
     * How the flow was answered.
     *
     * M1 can only distinguish these three honestly. STUBBED belongs to armed stubs (M2) and TIMEOUT
     * to the pause (M3); UNCONFIRMED is used when there is no response at all, because claiming
     * PASSTHROUGH would assert something we did not observe.
     */
    private fun resolutionOf(flow: HttpFlowData): String = when {
        flow.modified -> RESOLUTION_MODIFIED
        flow.mockApplied -> RESOLUTION_MOCKED
        flow.response == null -> RESOLUTION_UNCONFIRMED
        else -> RESOLUTION_PASSTHROUGH
    }

    private fun summaryOf(entry: SeqFlow, revealSecrets: Boolean = false): FlowSummary {
        val flow = entry.flow
        val query = redactQuery(flow.request.url, revealSecrets)
        return FlowSummary(
            flowId = flow.flowId,
            seq = entry.seq,
            ts = (flow.timestamp * 1000).toLong(),
            method = flow.request.method,
            url = query.url,
            host = flow.request.host,
            path = flow.request.path,
            status = flow.response?.statusCode,
            durationMs = (flow.duration * 1000).toLong(),
            reqBodyBytes = flow.request.content.toByteArray(Charsets.UTF_8).size,
            resBodyBytes = flow.response?.content?.toByteArray(Charsets.UTF_8)?.size ?: 0,
            resolution = resolutionOf(flow),
            sourceId = flow.mockRuleId,
            // Only the pause path can prove the app consumed our reply (writer.checkError()); that
            // lands with the intercept registry. Never guessed.
            appNotified = null,
            paused = flow.paused,
            mockRuleName = flow.mockRuleName,
            redactedQuery = query.redacted,
            revealedQuery = query.revealed
        )
    }

    private fun requestView(flow: HttpFlowData, maxBodyChars: Int, revealSecrets: Boolean): FlowMessageView =
        messageView(flow.request.headers, flow.request.content, maxBodyChars, revealSecrets, redactQuery(flow.request.url, revealSecrets))

    private fun responseView(flow: HttpFlowData, maxBodyChars: Int, revealSecrets: Boolean): FlowMessageView? =
        flow.response?.let { messageView(it.headers, it.content, maxBodyChars, revealSecrets) }

    private fun messageView(
        headers: Map<String, String>,
        content: String,
        maxBodyChars: Int,
        revealSecrets: Boolean,
        query: QueryRedaction? = null
    ): FlowMessageView {
        val redaction = redactHeaders(headers, revealSecrets)
        val safeHeaders = redaction.headers
        // Two independent truths, both reported: the retention cache may have cut this body long
        // before we saw it, and THIS response may cut it again to fit max_body_chars.
        val truncatedByRetention = FlowStore.isBodyTruncated(content)
        val binary = looksBinary(content)
        val trimmed = if (content.length > maxBodyChars) content.take(maxBodyChars) else content
        return FlowMessageView(
            headers = safeHeaders,
            body = if (binary) null else trimmed,
            bodyBase64 = if (binary) Base64.getEncoder().encodeToString(trimmed.toByteArray(Charsets.UTF_8)) else null,
            bodyEncoding = if (binary) BODY_ENCODING_BASE64 else BODY_ENCODING_UTF8,
            bodyTruncated = content.length > maxBodyChars,
            bodyTruncatedByRetention = truncatedByRetention,
            bodyBytes = content.toByteArray(Charsets.UTF_8).size,
            redactedHeaders = redaction.redacted,
            revealedHeaders = redaction.revealed,
            redactedQuery = query?.redacted.orEmpty(),
            revealedQuery = query?.revealed.orEmpty()
        )
    }

    /** A URL with its credential-bearing query values replaced, and the record of which they were. */
    private data class QueryRedaction(val url: String, val redacted: List<String>, val revealed: List<String>)

    /**
     * The query-string counterpart of [redactHeaders]: a parameter named in [REDACTED_QUERY_KEYS]
     * has its value replaced with `<redacted:Nb>` unless secrets are being revealed, in which case
     * it is named in `revealed`. The rest of the URL is handed back byte for byte.
     */
    private fun redactQuery(url: String, revealSecrets: Boolean): QueryRedaction {
        val q = url.indexOf('?')
        if (q < 0) return QueryRedaction(url, emptyList(), emptyList())
        val end = url.indexOf('#', q).let { if (it < 0) url.length else it }
        val query = url.substring(q + 1, end)
        if (query.isEmpty()) return QueryRedaction(url, emptyList(), emptyList())
        val sensitive = mutableListOf<String>()
        // Pairs are separated by '&' — or by ';', which some servers and frameworks still accept;
        // a credential behind a ';' must not hide inside the previous pair's value.
        val rebuilt = StringBuilder()
        var start = 0
        while (true) {
            var i = start
            while (i < query.length && query[i] != '&' && query[i] != ';') i++
            val pair = query.substring(start, i)
            val eq = pair.indexOf('=')
            val rawKey = if (eq < 0) pair else pair.substring(0, eq)
            val key = try { java.net.URLDecoder.decode(rawKey, "UTF-8") } catch (_: Exception) { rawKey }
            if (eq >= 0 && isCredentialParam(key)) {
                sensitive += key.lowercase()
                rebuilt.append(if (revealSecrets) pair else "$rawKey=<redacted:${pair.substring(eq + 1).toByteArray(Charsets.UTF_8).size}b>")
            } else {
                rebuilt.append(pair)
            }
            if (i >= query.length) break
            rebuilt.append(query[i])
            start = i + 1
        }
        if (sensitive.isEmpty()) return QueryRedaction(url, emptyList(), emptyList())
        val names = sensitive.distinct()
        return if (revealSecrets) QueryRedaction(url, emptyList(), names)
        else QueryRedaction(url.substring(0, q + 1) + rebuilt + url.substring(end), names, emptyList())
    }

    /**
     * Whether a stored body is better handed over base64.
     *
     * The wire protocol carries bodies as JSON strings, so anything binary already arrived as
     * replacement characters. One stray U+FFFD in a large JSON payload must not push the whole body
     * into base64 — that would make it unreadable for the caller to save a byte of honesty — so this
     * only fires on a NUL or on a genuinely control-character-dense payload.
     */
    private fun looksBinary(content: String): Boolean {
        if (content.isEmpty()) return false
        var suspicious = 0
        for (ch in content) {
            if (ch == '\u0000') return true
            if (ch == '\uFFFD' || (ch.code < 0x20 && ch != '\n' && ch != '\r' && ch != '\t')) suspicious++
        }
        return suspicious > 0 && suspicious * 50 > content.length
    }

    /**
     * Header handling, and the record of what happened to the sensitive ones.
     *
     * [Redaction.revealed] exists because the previous shape returned an empty `redacted_headers`
     * when secrets were revealed — indistinguishable from "there were no secrets here". These
     * payloads end up in test reports and logs, so the one moment credentials are handed over in
     * clear is exactly the moment that must leave a trace. `redacted_headers` keeps its meaning
     * ("these were hidden") and `revealed_headers` is its complement, rather than one field that
     * means the opposite thing depending on a flag nobody re-reads.
     */
    private data class Redaction(
        val headers: Map<String, String>,
        val redacted: List<String>,
        val revealed: List<String>
    )

    private fun redactHeaders(headers: Map<String, String>, revealSecrets: Boolean): Redaction {
        val sensitive = headers.keys.filter { it.lowercase() in REDACTED_HEADERS }.map { it.lowercase() }

        if (revealSecrets) {
            // Handed over verbatim, but named: a reader of this payload can see at a glance which
            // values in it are live credentials.
            return Redaction(headers, emptyList(), sensitive)
        }

        val redacted = mutableListOf<String>()
        val out = LinkedHashMap<String, String>(headers.size)
        for ((key, value) in headers) {
            val lower = key.lowercase()
            if (lower in REDACTED_HEADERS) {
                redacted += lower
                out[key] = "<redacted:${value.toByteArray(Charsets.UTF_8).size}b>"
            } else {
                out[key] = value
            }
        }
        return Redaction(out, redacted, emptyList())
    }

    /**
     * `include_body` accepted values, judged the same way on every path that renders bodies.
     * `getFlow` has no such parameter; `listFlows` and `awaitFlow` both go through here.
     */
    private fun validateIncludeBody(value: String?): ApiResult<String> {
        val includeBody = value ?: INCLUDE_BODY_NONE
        if (includeBody !in setOf(INCLUDE_BODY_NONE, INCLUDE_BODY_RESPONSE, INCLUDE_BODY_BOTH)) {
            return ApiResult.fail(
                ErrorCode.INVALID_ARGUMENT,
                "include_body must be one of none, response, both (got '$includeBody').",
                "Retry with include_body:'none' and fetch a single flow with action:'get' when you need a body."
            )
        }
        return ApiResult.ok(includeBody)
    }

    /**
     * The retention-truncation warning `getFlow` has always carried, for the bodies a listing or a
     * wait hands back: a per-body flag nobody reads is not a warning.
     */
    private fun retentionWarning(bodies: Map<String, FlowBodies>?): String? {
        val cut = bodies.orEmpty().filterValues { body ->
            body.request?.bodyTruncatedByRetention == true || body.response?.bodyTruncatedByRetention == true
        }.keys
        if (cut.isEmpty()) return null
        return "${cut.size} flow(s) were already truncated when they were retained (Settings → Cache → max stored " +
                "body size): ${cut.joinToString(", ")}. The missing bytes are gone; raise the limit before capturing " +
                "if you need whole bodies."
    }

    /**
     * The warning that goes with a payload carrying live credentials.
     *
     * MockkHttp warns about everything else — a stale Mockk tab, a RECORDING mode that makes rules
     * inert, every truncation — and said nothing at the one point where the answer contains real
     * secrets. These payloads get pasted into test reports; the warning is what makes a reader stop
     * and think before they do it.
     *
     * Every path that renders a [FlowMessageView] with `secrets = true` must call this — `listFlows`,
     * `getFlow` AND `awaitFlow`. The third audit round found it wired into the listing only: the
     * detail route, which is the one an agent reads once it knows which flow it wants, handed the
     * credential over in silence. A test per path guards each of the three now.
     */
    private fun secretsWarning(views: List<FlowMessageView?>, revealedQuery: List<String> = emptyList()): String? {
        // The summaries carry the URL — and so a revealed credential — whatever include_body says;
        // a warning fed only from the bodies stayed silent on a listing with the default include_body
        // (adversarial review of audit round 16).
        val revealed = (views.filterNotNull().flatMap { it.revealedHeaders + it.revealedQuery.map { q -> "query $q" } } +
                revealedQuery.map { q -> "query $q" }).distinct()
        if (revealed.isEmpty()) return null
        return "include_secrets was set: ${revealed.size} sensitive value(s) — ${revealed.joinToString(", ")} — " +
                "are in this answer IN CLEAR, not redacted. Treat it as a credential: do not paste it into a " +
                "report, a commit or a log. Omit include_secrets to get <redacted:Nb> placeholders instead."
    }

    /**
     * The last `client` report from the app this project captures — by package filter, or from
     * whoever spoke last when the project has none. Null until a 1.8.0 client has sent one.
     */
    private fun clientInfoFor(project: Project): ClientInfo? {
        val reported = clientReportFor(project) ?: return null
        val report = reported.report
        val flowsSent = report.stats?.get("flows_sent")
        // What the store itself remembered at the last clear — the agent's or the Inspector's —
        // so no listener has to have been attached first (adversarial review of audit round 12).
        val baseline = FlowStore.getInstance(project).lastClearSnapshot()?.reports
        val sinceClear = flowsSent?.let { sent ->
            // The client's count since the clear: its whole count when it had sent nothing by then.
            val atClear = baseline?.firstOrNull { it.report.runId == report.runId }?.report?.stats?.get("flows_sent") ?: 0L
            val since = (sent - atClear).coerceAtLeast(0L)
            // Whether flows.count is comparable with it is decided by what the store actually holds
            // — each flow stamped with the run whose message carried it — never by what happened to
            // be cleared when. A restart squared figures that hid a duplicate (audit round 9, AK);
            // then a restart right after a clear left the numbers exact and the reason false
            // (round 10, AO). The count of flows this run did not send settles both.
            val store = FlowStore.getInstance(project)
            val foreign = store.getAllFlows().count { it.clientRunId != report.runId }
            // The store has a cap. An eviction changes nobody's provenance, so the run check
            // alone kept saying "comparable" while flows.count had a floor (audit round 11, AQ).
            val evicted = store.getEvictedSinceClear()
            // A message the plugin could not turn into a flow was counted as sent by the app and
            // never reached the store; the subtraction is short by it (audit round 12, AS).
            val rejected = rejectedSinceClear(project, reported.packageName ?: packageFilterOf(project))
            when {
                foreign == 0 && evicted == 0 && rejected == 0L -> SinceClear(since, comparable = true)
                rejected > 0L -> SinceClear(
                    since, comparable = false, flowsNotFromThisRun = foreign, flowsEvictedSinceClear = evicted,
                    flowsRejectedSinceClear = rejected,
                    reason = "$rejected message(s) from the app could not be turned into a flow since the last clear " +
                            "(malformed FLOW or CHECK_MOCK; idea.log says \"MockkHttp rejected\"): the app counted them as sent, " +
                            "flows.count never saw them. " +
                            (if (evicted > 0) "$evicted flow(s) were also evicted by the store's cap. " else "") +
                            (if (foreign > 0) "$foreign of the flows in flows.count are not from this run either. " else "") +
                            "A clear_flows makes the counts comparable again."
                )
                evicted > 0 -> SinceClear(
                    since, comparable = false, flowsNotFromThisRun = foreign, flowsEvictedSinceClear = evicted,
                    reason = "$evicted flow(s) have been evicted since the last clear: the store keeps at most " +
                            "${store.maxFlows()} (Settings → MockkHttp → Cache), so flows.count is a window, not a total. " +
                            (if (foreign > 0) "$foreign of the flows in it are not from this run either. " else "") +
                            "Raise the limit or clear more often; a clear_flows makes the counts comparable again."
                )
                baseline == null -> SinceClear(
                    since, comparable = false, flowsNotFromThisRun = foreign,
                    reason = "Nothing has been cleared since this app run started, and $foreign of the flows in " +
                            "flows.count are not from this run (an earlier run of the app, an older client, or an import). " +
                            "Call clear_flows to anchor the two counts."
                )
                else -> {
                    val earlier = baseline.firstOrNull { it.packageName == reported.packageName && it.report.runId != report.runId }
                        ?.report?.runId
                    SinceClear(
                        since, comparable = false, flowsNotFromThisRun = foreign,
                        reason = (if (earlier != null) "The app has restarted since this project's flows were last cleared " +
                                "(run $earlier → ${report.runId}), and " else "") +
                                "$foreign of the flows in flows.count are not from this run. Call clear_flows to anchor again."
                    )
                }
            }
        }?.copy(headersDropped = headersDroppedSinceClear(project, reported.packageName ?: packageFilterOf(project)))
        return ClientInfo(
            library = report.library,
            version = report.version,
            platform = report.platform,
            packageName = reported.packageName,
            readTimeoutMs = null,
            dedup = report.dedup?.let {
                ClientDedupInfo(enabled = it.enabled ?: false, windowMs = it.windowMs ?: 0, controllable = it.controllable ?: false)
            },
            caps = report.caps.orEmpty(),
            stats = report.stats,
            runId = report.runId,
            startedAt = report.startedAt,
            sinceClear = sinceClear,
            seenAt = reported.seenAt
        )
    }

    /**
     * The report of the app this project captures: the one its package filter names — set on the
     * session, whether or not it is running — or, with no filter, whoever spoke last.
     */
    private fun clientReportFor(project: Project): GlobalOkHttpInterceptorServer.ReportedClient? =
        GlobalOkHttpInterceptorServer.getInstance().clientReportFor(packageFilterOf(project))

    /** The app this project captures, when it has been told: the session's filter, or the registration's. */
    private fun packageFilterOf(project: Project): String? =
        CaptureSessionService.getInstance(project).state().packageFilter
            ?: registrationOf(project)?.packageNameFilter

    /**
     * Messages rejected since this project's flows were last cleared, for the app the project
     * captures: its own package (the filter, or the app that reported) plus the rejections no app
     * could be named for — a message cut short on the wire carries its package name inside and
     * cannot say it, and it is no less likely to be this app's than any other's (audit round 13,
     * AW). Never another NAMED app's, and never the process-wide total (adversarial review of
     * audit round 12). Erring here errs towards a `comparable: false` too many, never a true one.
     */
    private fun rejectedSinceClear(project: Project, packageName: String?): Long {
        val global = GlobalOkHttpInterceptorServer.getInstance()
        return sinceClearCount(packageName, global.rejections(), FlowStore.getInstance(project).lastClearSnapshot()?.rejections)
    }

    /** Headers dropped from stored flows since the last clear, scoped exactly like [rejectedSinceClear] (audit round 15, AZ). */
    private fun headersDroppedSinceClear(project: Project, packageName: String?): Long {
        val global = GlobalOkHttpInterceptorServer.getInstance()
        return sinceClearCount(packageName, global.headersDropped(), FlowStore.getInstance(project).lastClearSnapshot()?.headersDropped)
    }

    private fun sinceClearCount(
        packageName: String?,
        now: GlobalOkHttpInterceptorServer.Rejections,
        then: GlobalOkHttpInterceptorServer.Rejections?
    ): Long {
        fun count(r: GlobalOkHttpInterceptorServer.Rejections): Long =
            if (packageName == null) r.unknown else r.forPackage(packageName) + r.unknown
        return (count(now) - (then?.let { count(it) } ?: 0L)).coerceAtLeast(0L)
    }

    private fun resolveSecrets(requested: Boolean?): ApiResult<Boolean> {
        if (requested != true) return ApiResult.ok(false)
        if (!environment.revealSecrets) {
            return ApiResult.fail(
                ErrorCode.REVEAL_DISABLED,
                "Revealing request/response secrets is disabled for this IDE.",
                "Turn on Settings → MockkHttp → Allow an agent to read redacted header values, or read the flow without include_secrets — " +
                        "header values come back as <redacted:Nb> with their true length."
            )
        }
        return ApiResult.ok(true)
    }

    // ========================================================================
    // Views: rules
    // ========================================================================

    private fun ruleView(
        rule: MockkRulesStore.MockkRule,
        collection: MockkCollection?,
        includeBody: Boolean
    ): RuleView = RuleView(
        ruleId = rule.id,
        name = rule.name,
        enabled = rule.enabled,
        method = rule.method,
        url = redactQuery(rule.getDisplayUrl(), false).url,
        scheme = rule.scheme,
        host = rule.host,
        hostMatch = rule.hostMatch.name,
        port = rule.port,
        path = rule.path,
        pathMatch = rule.pathMatch.name,
        // A rule written with a credential in an EXACT value (the caller's own words, or a rule
        // from before 1.8.0) still shows it redacted here: mocks views have no include_secrets.
        query = rule.queryParams.map {
            val secret = isCredentialParam(it.key) && it.matchType == MatchType.EXACT && it.value.isNotEmpty()
            QueryParamDto(
                key = it.key,
                value = if (secret) "<redacted:${it.value.toByteArray(Charsets.UTF_8).size}b>" else it.value,
                required = it.required,
                match = it.matchType.name
            )
        },
        collectionId = rule.collectionId,
        collectionName = collection?.name,
        collectionEnabled = collection?.enabled ?: false,
        response = ResponseView(
            statusCode = rule.statusCode,
            headers = rule.headers,
            body = if (includeBody) rule.content.take(HARD_MAX_BODY_CHARS) else null,
            bodyBytes = rule.content.toByteArray(Charsets.UTF_8).size,
            bodyTruncated = includeBody && rule.content.length > HARD_MAX_BODY_CHARS
        )
    )

    private fun collectionView(collection: MockkCollection, ruleCount: Int): CollectionView = CollectionView(
        collectionId = collection.id,
        name = collection.name,
        packageName = collection.packageName,
        description = collection.description,
        enabled = collection.enabled,
        ruleCount = ruleCount,
        createdAt = collection.createdAt
    )

    private fun mutationResponse(
        project: Project,
        store: MockkRulesStore,
        rule: MockkRulesStore.MockkRule,
        disabledConflicting: List<String>,
        loosenedParams: List<String>,
        createdCollection: MockkCollection?,
        warnings: MutableList<String>
    ): RuleMutationResponse {
        val collection = store.getCollection(rule.collectionId)
        val mode = registrationOf(project)?.mode?.name
        if (rule.enabled && collection?.enabled == false) {
            warnings += "The rule is enabled but its collection '${collection.name}' is disabled, so it can never fire."
        }
        if (mode == null) {
            warnings += "No capture session is running, so nothing is answering CHECK_MOCK yet."
        } else if (mode != MODE_MOCKK && mode != MODE_MOCKK_DEBUG) {
            warnings += "Mode is $mode: mock rules are inert. Call session/mode with '$MODE_MOCKK'."
        }
        return RuleMutationResponse(
            rule = ruleView(rule, collection, includeBody = false),
            willMatch = WillMatchDto(
                method = rule.method.uppercase(),
                host = rule.host,
                hostMatch = rule.hostMatch.name,
                path = rule.path,
                pathMatch = rule.pathMatch.name,
                requiredParams = rule.queryParams.filter { it.required }.map { it.key }
            ),
            disabledConflicting = disabledConflicting,
            loosenedParams = loosenedParams,
            // findMockForRequest short-circuits in every other mode, so a rule genuinely cannot fire
            // outside these two — whatever the documentation of later milestones promises.
            willFireInModes = listOf(MODE_MOCKK, MODE_MOCKK_DEBUG),
            currentMode = mode,
            createdCollection = createdCollection?.let { collectionView(it, store.getRulesInCollection(it.id).size) },
            warnings = warnings
        )
    }

    private fun uiRepaintWarning(): String =
        "The Mockk tab does not repaint on a programmatic enable/disable yet; reopen the tool window to see the new state."

    /**
     * Said on every session change rather than claimed as a defect: whether the Inspector follows a
     * programmatic change depends on whether this build wired its listener, and promising either
     * way would be a guess. What is always true is which side is authoritative — the capture is
     * running whatever the button says.
     */
    private fun inspectorSyncNote(): String =
        "The capture session is what this response reports, not what the tool window shows: if the " +
                "Inspector's controls still show the previous state, reopen the MockkHttp tool window."

    private fun unknownRule(ruleId: String): ApiResult<Nothing> = ApiResult.fail(
        ErrorCode.UNKNOWN_RULE,
        "No mock rule with id '$ruleId'.",
        "Call mocks with action:'list' to get the current rule ids — they are regenerated on import."
    )

    // ========================================================================
    // Rule building helpers
    // ========================================================================

    private data class ResolvedCollection(val collection: MockkCollection, val created: MockkCollection?)

    /**
     * Every rule must live in a collection: an orphaned rule is invisible in the UI and is recovered
     * DISABLED on the next load, i.e. silently dead. Rather than fail a first-time caller who has no
     * collections at all, one is created and reported.
     */
    private fun resolveCollection(
        store: MockkRulesStore,
        collectionId: String?,
        newCollection: NewCollectionDto?,
        warnings: MutableList<String>
    ): ApiResult<ResolvedCollection> {
        if (!collectionId.isNullOrBlank()) {
            val existing = store.getCollection(collectionId)
                ?: return ApiResult.fail(
                    ErrorCode.UNKNOWN_COLLECTION,
                    "No collection with id '$collectionId'.",
                    "Call mocks with action:'list_collections', or pass new_collection:{name:'…'} to create one."
                )
            return ApiResult.ok(ResolvedCollection(existing, null))
        }
        if (newCollection != null) {
            val name = newCollection.name?.trim()
            if (name.isNullOrEmpty()) {
                return ApiResult.fail(
                    ErrorCode.INVALID_ARGUMENT,
                    "new_collection.name is required.",
                    "Retry with new_collection:{name:'Agent mocks'}."
                )
            }
            val created = store.addCollection(name, newCollection.packageName ?: "", newCollection.description ?: "")
            return ApiResult.ok(ResolvedCollection(created, created))
        }
        val all = store.getAllCollections()
        if (all.size == 1) {
            warnings += "No collection_id was given; the rule went into the only collection that exists, '${all.first().name}'."
            return ApiResult.ok(ResolvedCollection(all.first(), null))
        }
        if (all.isEmpty()) {
            val created = store.addCollection("Agent mocks", "", "Created automatically for a rule made over the control plane")
            warnings += "This project had no mock collections, so 'Agent mocks' was created to hold this rule."
            return ApiResult.ok(ResolvedCollection(created, created))
        }
        return ApiResult.fail(
            ErrorCode.COLLECTION_REQUIRED,
            "${all.size} collections exist, so collection_id is required.",
            "Call mocks with action:'list_collections' and pass one of the ids, or new_collection:{name:'…'}.",
            details = all.associate { it.id to it.name }
        )
    }

    private fun applyMatchModes(
        structured: StructuredUrl,
        hostMatch: String?,
        pathMatch: String?,
        hostFallback: MatchType,
        pathFallback: MatchType
    ): ApiResult<Unit> {
        structured.hostMatch = when (val parsed = parseMatchType(hostMatch)) {
            null -> if (hostMatch == null) hostFallback else return invalidMatchType("host_match", hostMatch)
            else -> parsed
        }
        structured.pathMatch = when (val parsed = parseMatchType(pathMatch)) {
            null -> if (pathMatch == null) pathFallback else return invalidMatchType("path_match", pathMatch)
            else -> parsed
        }
        if (structured.hostMatch == MatchType.REGEX && compileRegex(structured.host) == null) {
            return invalidRegex("host", structured.host)
        }
        if (structured.pathMatch == MatchType.REGEX && compileRegex(structured.path) == null) {
            return invalidRegex("path", structured.path)
        }
        return ApiResult.ok(Unit)
    }

    /**
     * One match mode, resolved before anything is parsed.
     *
     * [applyMatchModes] settles the modes onto an already-built StructuredUrl, which is too late
     * when the mode decides whether the target can be parsed as a URL at all.
     */
    private fun parseMatchMode(value: String?, field: String, fallback: MatchType): ApiResult<MatchType> {
        if (value == null) return ApiResult.ok(fallback)
        val parsed = parseMatchType(value) ?: return invalidMatchType(field, value)
        return ApiResult.ok(parsed)
    }

    private fun parseMatchType(value: String?): MatchType? = when (value?.trim()?.uppercase()) {
        MATCH_EXACT -> MatchType.EXACT
        MATCH_WILDCARD -> MatchType.WILDCARD
        MATCH_REGEX -> MatchType.REGEX
        else -> null
    }

    private fun invalidMatchType(field: String, value: String): ApiResult<Nothing> = ApiResult.fail(
        ErrorCode.INVALID_ARGUMENT,
        "$field must be one of $MATCH_EXACT, $MATCH_WILDCARD, $MATCH_REGEX (got '$value').",
        "$MATCH_EXACT compares literally, $MATCH_WILDCARD treats '*' as any run of characters, $MATCH_REGEX is an anchored regex."
    )

    private fun toQueryParams(dtos: List<QueryParamDto>): ApiResult<List<QueryParam>> {
        val out = ArrayList<QueryParam>(dtos.size)
        for (dto in dtos) {
            val key = dto.key?.trim()
            if (key.isNullOrEmpty()) {
                return ApiResult.fail(
                    ErrorCode.INVALID_ARGUMENT, "Every query entry needs a key.",
                    "Retry with query:[{key:'id', value:'7', required:true, match:'$MATCH_EXACT'}]."
                )
            }
            val rawMatch = dto.match
            val match = parseMatchType(rawMatch)
                ?: if (rawMatch == null) MatchType.EXACT else return invalidMatchType("query['$key'].match", rawMatch)
            val value = dto.value ?: ""
            if (match == MatchType.REGEX && compileRegex(value) == null) {
                return invalidRegex("query['$key'].value", value)
            }
            out += QueryParam(key = key, value = value, required = dto.required ?: true, matchType = match)
        }
        return ApiResult.ok(out)
    }

    /**
     * Mark volatile query params optional, so a rule cloned from a captured flow keeps matching.
     * Returns the names it loosened, because silently changing what a rule matches would be worse
     * than leaving it broken.
     */
    private fun loosenQueryParams(params: MutableList<QueryParam>): List<String> {
        val loosened = mutableListOf<String>()
        for (param in params) {
            if (!param.required) continue
            val value = param.value
            val volatileValue = (value.length >= 10 && value.all { it.isDigit() }) || UUID_VALUE.matches(value)
            if (param.key.lowercase() in VOLATILE_PARAM_KEYS || volatileValue) {
                // BOTH, not just `required`. `required` now decides only what happens when the
                // parameter is ABSENT; a parameter that IS present is always compared with its
                // match type. Clearing `required` alone would leave the captured timestamp pinned
                // as an EXACT value, so a rule cloned from a flow would match that one request and
                // nothing else — the opposite of what loosening means.
                param.required = false
                param.matchType = MatchType.WILDCARD
                loosened += param.key
            }
        }
        return loosened
    }

    /**
     * The decoded body of a [ResponseSpecDto].
     *
     * Wrapped rather than returned bare because "no body was sent" and "an empty body was sent" are
     * different instructions to [MockkRulesStore.updateRule] — the first leaves the stored body
     * alone, the second replaces it with "" — and `ApiResult` cannot carry a nullable value.
     */
    private data class DecodedBody(val text: String?)

    /**
     * Validate a response spec and decode its body.
     *
     * Validation and decoding are one call because every caller needs both and neither is useful
     * without the other. Everything rejected here is rejected AT THE CALL rather than stored: a
     * status code of 9999 is accepted by the store, exported intact, and only surfaces as a failure
     * inside the app under test — arbitrarily far from the call that caused it.
     */
    private fun decodeResponseSpec(spec: ResponseSpecDto?): ApiResult<DecodedBody> {
        if (spec == null) return ApiResult.ok(DecodedBody(null))
        if (spec.delayMs != null && spec.delayMs != 0) {
            return ApiResult.fail(
                ErrorCode.INVALID_ARGUMENT,
                "A mock rule cannot delay a response; delay_ms belongs to armed stubs, which this build does not have.",
                "Drop delay_ms. Latency injection arrives with mockkhttp_arm(action_on_match:'delay')."
            )
        }
        val status = spec.statusCode
        if (status != null && status !in MIN_STATUS_CODE..MAX_STATUS_CODE) {
            return ApiResult.fail(
                ErrorCode.INVALID_ARGUMENT,
                "status_code must be between $MIN_STATUS_CODE and $MAX_STATUS_CODE (got $status).",
                "Retry with the status you meant, e.g. status_code:500 for a server error or " +
                        "status_code:404 for a missing resource."
            )
        }
        if (spec.body != null && spec.bodyBase64 != null) {
            return ApiResult.fail(
                ErrorCode.INVALID_ARGUMENT,
                "response.body and response.body_base64 are mutually exclusive.",
                "Send one of them."
            )
        }
        val encoded = spec.bodyBase64
        if (encoded != null) {
            // Strict: a rule body is stored, exported and sent over the wire as a String, so bytes
            // that are not valid UTF-8 cannot survive the trip. `String(bytes, UTF_8)` would replace
            // each one with U+FFFD — a 70-byte PNG came back out of the store as 94 different bytes,
            // and the export made the corruption look like the real payload. Refusing is the only
            // honest answer until the wire carries bytes.
            return when (val decoded = MockBody.decodeBase64(encoded)) {
                is MockBody.Decoded.Text -> ApiResult.ok(DecodedBody(decoded.value))

                MockBody.Decoded.NotBase64 -> ApiResult.fail(
                    ErrorCode.INVALID_ARGUMENT, "response.body_base64 is not valid base64.",
                    "Send the payload in response.body as plain text if it is text."
                )

                is MockBody.Decoded.NotText -> ApiResult.fail(
                    ErrorCode.INVALID_ARGUMENT,
                    "response.body_base64 decodes to ${decoded.byteCount} bytes that are not UTF-8 text " +
                            "(starts with ${decoded.firstBytesHex}), and a mock body is stored and sent as text. " +
                            "Accepting it would corrupt every byte outside UTF-8 and the app would receive a broken payload.",
                    "Binary mock bodies are not supported yet. Mock the metadata instead — status code, " +
                            "headers, or a JSON error body — or let the real binary response through."
                )
            }
        }
        return ApiResult.ok(DecodedBody(spec.body))
    }

    /** Disable every other enabled rule answering the same endpoint, and report which. */
    private fun exclusiveDisable(store: MockkRulesStore, rule: MockkRulesStore.MockkRule): List<String> {
        val signature = endpointSignature(rule)
        val disabled = mutableListOf<String>()
        for (other in store.getAllRules()) {
            if (other.id == rule.id || !other.enabled) continue
            if (endpointSignature(other) != signature) continue
            store.setRuleEnabled(other, false)
            disabled += other.id
        }
        return disabled
    }

    private fun endpointSignature(rule: MockkRulesStore.MockkRule): String =
        "${rule.method.uppercase()} ${rule.host.lowercase()}${rule.path}"

    // ========================================================================
    // Explain diagnostics
    // ========================================================================

    /**
     * Why this rule did not answer — or null when nothing about the rule rules it out.
     *
     * Diagnostics only: [MockkRulesStore.findMatchingRuleObject] decides the winner. The checks are
     * kept in the same order the store applies them so the first reason reported is the first reason
     * the store hit.
     */
    private fun rejectionReason(
        rule: MockkRulesStore.MockkRule,
        collection: MockkCollection?,
        method: String,
        request: ParsedUrl
    ): String? {
        if (!rule.enabled) return "the rule is disabled"
        if (collection == null) return "its collection no longer exists, so the rule is unreachable"
        if (!collection.enabled) return "collection '${collection.name}' is disabled"
        if (!rule.method.equals(method, ignoreCase = true)) {
            return "method mismatch: the rule is ${rule.method.uppercase()}, the request is $method"
        }
        if (!matchesPart(rule.host, rule.hostMatch, request.host, ignoreCase = true)) {
            val loosenHint = if (rule.hostMatch == MatchType.EXACT) {
                " — set host_match:'$MATCH_WILDCARD' or '$MATCH_REGEX' to loosen it"
            } else ""
            return "host mismatch (${rule.hostMatch.name}): rule '${rule.host}' vs request '${request.host}'$loosenHint"
        }
        if (!matchesPart(rule.path, rule.pathMatch, request.path, ignoreCase = false)) {
            val loosenHint = if (rule.pathMatch == MatchType.EXACT) {
                " — set path_match:'$MATCH_REGEX' to match a family of paths"
            } else ""
            return "path mismatch (${rule.pathMatch.name}, case-sensitive): rule '${rule.path}' vs request '${request.path}'$loosenHint"
        }
        for (param in rule.queryParams) {
            val actual = request.query[param.key]

            // `required` decides only the ABSENT case. A parameter that IS present is always
            // compared with its match type — the matcher was changed to work this way and this
            // explainer was not, so an optional-but-present mismatch fell through to
            // "rejected for a reason this explanation could not reproduce": the best tool in the
            // MCP going blind at exactly the case that had just been fixed.
            if (actual == null) {
                if (!param.required) continue
                return "query param '${param.key}' is required:true but the request has no such param — " +
                        "set required:false, or drop it from the rule"
            }

            val optionalNote = if (param.required) {
                "is required:true and"
            } else {
                "is present, so it is checked even though required:false, and it is"
            }

            when (param.matchType) {
                MatchType.EXACT -> if (param.value != actual) {
                    val secret = isCredentialParam(param.key)
                    val expected = if (secret) "<redacted>" else param.value
                    val got = if (secret) "<redacted>" else actual
                    return "query param '${param.key}' $optionalNote $MATCH_EXACT ('$expected' vs '$got') — " +
                            "set match:'$MATCH_WILDCARD' to accept any value when it is present"
                }
                MatchType.WILDCARD -> Unit
                MatchType.REGEX -> if (compileRegex(param.value)?.matches(actual) != true) {
                    return "query param '${param.key}' $optionalNote $MATCH_REGEX '${param.value}', " +
                            "which does not match '$actual' — set match:'$MATCH_WILDCARD' to accept any value"
                }
            }
        }
        return null
    }

    /** Mirrors MockkRulesStore.matchesUrlPart, which is private there. */
    private fun matchesPart(pattern: String, matchType: MatchType, actual: String, ignoreCase: Boolean): Boolean =
        when (matchType) {
            MatchType.EXACT -> pattern.equals(actual, ignoreCase = ignoreCase)
            MatchType.WILDCARD -> compileFlexible(globToRegex(pattern), ignoreCase)?.matches(actual) ?: false
            MatchType.REGEX -> compileFlexible(pattern, ignoreCase)?.matches(actual) ?: false
        }

    private fun globToRegex(glob: String): String = glob.split("*").joinToString(".*") { Regex.escape(it) }

    private fun compileFlexible(pattern: String, ignoreCase: Boolean): Regex? = try {
        if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
    } catch (_: Exception) {
        null
    }

    // ========================================================================
    // Clamping
    // ========================================================================

    private fun clampBodyChars(requested: Int?, clamped: MutableMap<String, ClampInfo>): Int = clampInt(
        requested, DEFAULT_MAX_BODY_CHARS, 0, HARD_MAX_BODY_CHARS, "max_body_chars",
        "Bodies are capped at $HARD_MAX_BODY_CHARS characters per response. Fetch the flow again with a narrower " +
                "selection, or read it in the Inspector.", clamped
    )

    private fun clampInt(
        requested: Int?,
        default: Int,
        min: Int,
        max: Int,
        field: String,
        reason: String,
        clamped: MutableMap<String, ClampInfo>
    ): Int {
        if (requested == null) return default
        val applied = requested.coerceIn(min, max)
        if (applied != requested) {
            clamped[field] = ClampInfo(requested.toLong(), applied.toLong(), reason)
        }
        return applied
    }

    private fun clampLong(
        requested: Long?,
        default: Long,
        min: Long,
        max: Long,
        field: String,
        reason: String,
        clamped: MutableMap<String, ClampInfo>
    ): Long {
        if (requested == null) return default.coerceIn(min, max)
        val applied = requested.coerceIn(min, max)
        if (applied != requested) {
            clamped[field] = ClampInfo(requested, applied, reason)
        }
        return applied
    }
}
