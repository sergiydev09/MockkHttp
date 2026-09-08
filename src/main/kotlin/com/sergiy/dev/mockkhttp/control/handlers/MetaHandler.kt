package com.sergiy.dev.mockkhttp.control.handlers

import com.google.gson.annotations.SerializedName
import com.sergiy.dev.mockkhttp.control.ControlApi
import com.sergiy.dev.mockkhttp.control.ControlRequest
import com.sergiy.dev.mockkhttp.control.ControlResponse
import com.sergiy.dev.mockkhttp.control.QueryResult
import com.sergiy.dev.mockkhttp.control.dto.API_VERSION
import com.sergiy.dev.mockkhttp.control.dto.ErrorCode

// ============================================================================
// Shared handler support
// ============================================================================

/**
 * The two error shapes every handler needs and the router keeps private.
 *
 * It lives in this file because M1 owns exactly four handler files; when `InterceptsHandler` and
 * `RunHandler` land (M2-M3) this object and [QueryReader] should move to their own
 * `control/handlers/HandlerSupport.kt` unchanged.
 *
 * Serialisation is **not** here: `ControlResponse.ok/json/fail/of` already own it, and a second Gson
 * in the handlers would be a second contract.
 */
internal object HandlerSupport {

    fun wrongMethod(request: ControlRequest, allowed: String): ControlResponse = ControlResponse.fail(
        ErrorCode.UNSUPPORTED_ACTION,
        "${request.method} is not allowed on ${request.path}.",
        "Use $allowed ${request.path}."
    )

    /** A resource this handler owns, but a sub-path it does not. The hint lists what does exist. */
    fun unknownSubRoute(request: ControlRequest, known: String): ControlResponse = ControlResponse.fail(
        ErrorCode.UNSUPPORTED_ACTION,
        "No route matches ${request.method} ${request.path}.",
        "On this resource: $known."
    )
}

/**
 * Reads a whole DTO's worth of query parameters, remembering the first one that was rejected.
 *
 * Parsing and the 400 itself belong to [ControlRequest.intQuery] and friends — this only folds
 * their three-way [QueryResult] into "the value, or null" while keeping the rejection, so a handler
 * can build a DTO in one expression per field and check [failure] once before doing any work.
 * Without the fold, every optional parameter costs a five-line `when` at the call site.
 */
internal class QueryReader(private val request: ControlRequest) {

    var failure: ControlResponse? = null
        private set

    fun str(key: String): String? = request.queryValue(key)?.trim()?.takeIf { it.isNotEmpty() }

    fun int(key: String): Int? = fold(request.intQuery(key))

    fun long(key: String): Long? = fold(request.longQuery(key))

    fun bool(key: String): Boolean? = fold(request.boolQuery(key))

    /** Comma-separated: a query map holds one value per key, so a repeated key cannot carry a list. */
    fun list(key: String): List<String>? =
        str(key)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }

    private fun <T : Any> fold(result: QueryResult<T>): T? {
        if (result is QueryResult.Invalid && failure == null) failure = result.response
        return result.orNull()
    }
}

// ============================================================================
// MetaHandler
// ============================================================================

/**
 * `GET /v1/meta`, `GET /v1/projects`, `GET /v1/docs`.
 *
 * The three routes a caller may use before it knows anything: what this build is, which projects it
 * can address, and how to drive it. None of them touches a project, so none can fail with an
 * addressing error — which is what makes `meta` a safe first call from a bridge that has just
 * started and resolved nothing yet.
 */
class MetaHandler(private val api: ControlApi) {

    fun handle(request: ControlRequest): ControlResponse {
        if (request.method != "GET") return HandlerSupport.wrongMethod(request, "GET")

        return when (request.resource) {
            RESOURCE_META ->
                if (request.tail.isEmpty()) ControlResponse.ok(api.meta())
                else HandlerSupport.unknownSubRoute(request, "GET /v1/meta")

            RESOURCE_PROJECTS ->
                if (request.tail.isEmpty()) ControlResponse.ok(api.listProjects())
                else HandlerSupport.unknownSubRoute(
                    request,
                    "GET /v1/projects, then /v1/projects/{project_id}/{status|session|flows|mocks}"
                )

            RESOURCE_DOCS ->
                if (request.tail.isEmpty()) docs(request)
                else HandlerSupport.unknownSubRoute(request, "GET /v1/docs?topic=…")

            else -> HandlerSupport.unknownSubRoute(
                request,
                "GET /v1/meta, GET /v1/projects, GET /v1/docs"
            )
        }
    }

    private fun docs(request: ControlRequest): ControlResponse {
        val topic = request.query["topic"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return ControlResponse.ok(index())

        val page = AgentDocs.pages[topic]
            ?: return ControlResponse.fail(
                ErrorCode.INVALID_ARGUMENT,
                "No documentation topic '$topic'.",
                "Legal topics: ${AgentDocs.pages.keys.joinToString(", ")}. Omit topic for the index."
            )
        return ControlResponse.ok(page)
    }

    private fun index(): DocsIndex = DocsIndex(
        apiVersion = API_VERSION,
        milestone = ControlApi.MILESTONE,
        topics = AgentDocs.pages.map { (topic, page) -> DocsTopicSummary(topic, page.summary) },
        hint = "Call GET /v1/docs?topic=quickstart first. Every topic states plainly what this " +
                "milestone (${ControlApi.MILESTONE}) does and does not implement."
    )

    private companion object {
        const val RESOURCE_META = "meta"
        const val RESOURCE_DOCS = "docs"
        const val RESOURCE_PROJECTS = "projects"
    }
}

// ---------------------------------------------------------------------------
// Docs payloads
//
// The only wire shapes not in control/dto/ApiDtos.kt: that file is owned elsewhere and has no docs
// types. They belong there; moving them is a pure rename.
// ---------------------------------------------------------------------------

internal data class DocsTopicSummary(
    @SerializedName("topic") val topic: String,
    @SerializedName("summary") val summary: String
)

internal data class DocsIndex(
    @SerializedName("api_version") val apiVersion: String,
    @SerializedName("milestone") val milestone: String,
    @SerializedName("topics") val topics: List<DocsTopicSummary>,
    @SerializedName("hint") val hint: String
)

internal data class DocsPage(
    @SerializedName("topic") val topic: String,
    @SerializedName("summary") val summary: String,
    @SerializedName("markdown") val markdown: String,
    @SerializedName("related_routes") val relatedRoutes: List<String>
)

/**
 * The workflow guide, served on demand rather than inflating every tool description.
 *
 * Written for a model to act on, and **honest about the milestone**: a topic that describes a
 * feature this build does not have says so and gives the M1 way to get the same result. Sending an
 * agent to `/v1/projects/{pid}/arms` (M2) would cost it a 501 and a wasted turn.
 */
internal object AgentDocs {

    private val QUICKSTART = DocsPage(
        topic = "quickstart",
        summary = "The shortest path from nothing to a mocked response.",
        markdown = """
            ## MockkHttp quickstart (milestone ${ControlApi.MILESTONE})

            1. `GET /v1/projects/{pid}/status` — always first. It tells you the project, whether a
               capture session is running, the current mode, and what is blocking you. If no session
               is running, start one yourself with `POST /v1/projects/{pid}/session/start`, passing
               package_name (and serial with several devices). Port 9876 is bound by a session, an app
               scan or the open Inspector — check `interceptor.bound`: while it is false an app launched
               now cannot announce itself, so pass package_name; while it is true the app announces
               itself on startup, and start needs no argument when exactly one device has exactly one
               announced app (otherwise pass package_name, and serial).
            2. Drive the app so it makes the call you care about.
            3. `GET /v1/projects/{pid}/flows?limit=25` — summaries only, so a chatty app cannot blow
               your context. Add `since_seq=<next_seq from the last call>` to read only what is new.
            4. `GET /v1/projects/{pid}/flows/{flow_id}` — full headers and bodies for one flow.
            5. `POST /v1/projects/{pid}/mocks` with `{"from_flow_id":"…","response":{"status_code":500}}`
               — clone the captured call into a rule and change only the answer.
            6. `POST /v1/projects/{pid}/session/mode` with `{"mode":"MOCKK"}` — rules are inert in
               RECORDING mode. This is the single most common reason a rule "does not work".
            7. Drive the app again and confirm with
               `GET /v1/projects/{pid}/flows/await?path=/v1/login&count=1&wait_ms=25000`. Never sleep.

            The app never reaches the network for a mocked call: the rule is answered in the
            pre-flight handshake, before the request is sent.
        """.trimIndent(),
        relatedRoutes = listOf(
            "GET /v1/projects/{pid}/status",
            "GET /v1/projects/{pid}/flows",
            "POST /v1/projects/{pid}/mocks",
            "POST /v1/projects/{pid}/session/mode"
        )
    )

    private val MODES = DocsPage(
        topic = "modes",
        summary = "What each capture mode does, and which one you almost always want.",
        markdown = """
            ## Modes

            | Mode | Traffic captured | Mock rules served | Requests paused |
            |---|---|---|---|
            | `RECORDING` | yes | **no** | no |
            | `MOCKK` | yes | **yes** | no |
            | `DEBUG` | yes | no | **every request** |
            | `MOCKK_DEBUG` | yes | yes | **every request not mocked** |

            **Use `MOCKK`.** It is the only mode in which a mock rule fires, it adds no latency, and
            nothing waits for a human.

            `DEBUG` and `MOCKK_DEBUG` pause *every* request the app makes — background polling
            included — behind a modal dialog in the IDE that only a human can answer, for up to 55
            seconds each. This build therefore refuses those modes unless you repeat the call with
            `"confirm_pause_all": true`, and there is no way for you to answer a paused request until
            milestone M3. Setting them from an agent will hang the app under test.

            The mode can only be changed while a session is running; `SESSION_NOT_RUNNING` means no
            session is running — start one yourself with `POST /v1/projects/{pid}/session/start`.
        """.trimIndent(),
        relatedRoutes = listOf(
            "POST /v1/projects/{pid}/session/mode",
            "GET /v1/projects/{pid}/session"
        )
    )

    private val MOCKING = DocsPage(
        topic = "mocking",
        summary = "Create, enable and delete persistent mock rules.",
        markdown = """
            ## Mock rules

            A rule is persistent, lives in a collection, and is answered before the app touches the
            network. Rules only fire in `MOCKK` / `MOCKK_DEBUG`.

            **The fastest create path is cloning a flow you already captured:**

            ```json
            POST /v1/projects/{pid}/mocks
            { "from_flow_id": "8f2c…",
              "name": "login 500",
              "response": { "status_code": 500, "body": "{\"error\":\"boom\"}" },
              "exclusive": true }
            ```

            - `from_flow_id` copies the method, URL and captured query parameters, so the rule matches
              the same endpoint you just saw.
            - `loosen_query` (default true with `from_flow_id`) marks volatile parameters — `ts`,
              `nonce`, `sig`, long integers, UUIDs — as not required. Without it a cloned rule matches
              the captured call exactly once and never again. The response lists what it loosened.
            - `exclusive: true` disables other enabled rules on the same endpoint, which is what you
              want when two rules would otherwise race.

            The create/update response carries `will_match`, `will_fire_in_modes`, `current_mode` and
            `warnings` — read them. A rule created while the session is in `RECORDING` is inert, and
            the response says so.

            Other routes: `GET …/mocks` (list), `GET|PUT|DELETE …/mocks/{rule_id}`,
            `POST …/mocks/{rule_id}/enable`, `GET|POST …/mocks/collections`,
            `GET …/mocks/export`, `POST …/mocks/import`.
        """.trimIndent(),
        relatedRoutes = listOf(
            "POST /v1/projects/{pid}/mocks",
            "POST /v1/projects/{pid}/mocks/{rule_id}/enable",
            "POST /v1/projects/{pid}/mocks/explain"
        )
    )

    private val MATCHING = DocsPage(
        topic = "matching",
        summary = "Why a rule does not fire, and how to prove it before driving the app.",
        markdown = """
            ## Matching

            A rule matches when **all** of these hold:

            - method is equal, ignoring case;
            - host matches, `EXACT` (ignoring case) or `REGEX` per `host_match`;
            - path matches, `EXACT` (case-sensitive, trailing `/` normalised) or `REGEX` per
              `path_match`;
            - every query parameter marked `required: true` is present and matches by `EXACT`,
              `WILDCARD` or `REGEX`;
            - the rule is enabled **and** its collection is enabled.

            The last two are the most common silent failures, and neither is visible from the rule
            alone. Do not guess — ask:

            ```json
            POST /v1/projects/{pid}/mocks/explain
            { "method": "POST", "url": "https://api.acme.com/v1/login?ts=1772460003" }
            ```

            The answer names the winner, every candidate that lost, and `rejected_because` for each —
            the field, both values and the fix. `would_skip_network` tells you whether the app would
            actually be answered without a network call in the **current** mode.

            Flow queries use a flatter matcher: `method`, `host`, `path` (exact), `path_contains`,
            `path_regex` (anchored), `url_contains`, `body_contains`, `status` / `status_min` /
            `status_max`.
        """.trimIndent(),
        relatedRoutes = listOf(
            "POST /v1/projects/{pid}/mocks/explain",
            "GET /v1/projects/{pid}/mocks"
        )
    )

    private val FLOWS = DocsPage(
        topic = "flows",
        summary = "Reading captured traffic without drowning in it, and waiting deterministically.",
        markdown = """
            ## Flows

            `GET /v1/projects/{pid}/flows` returns **summaries** — no bodies — plus `next_seq`.

            **`since_seq` is inclusive and `next_seq` is the next unassigned number**, so passing back
            the `next_seq` you were just given returns only flows that arrived after your last read.
            That is the whole incremental-read protocol; never re-list everything.

            - one flow, with headers and bodies: `GET …/flows/{flow_id}`
            - bodies in a listing: `include_body=response|both` (default `none`)
            - `max_body_chars` defaults to ${ControlApi.DEFAULT_MAX_BODY_CHARS} and is hard-capped at
              ${ControlApi.HARD_MAX_BODY_CHARS}; `body_truncated` says when it bit, and
              `body_truncated_by_retention` says the plugin had already trimmed the body on capture —
              those are different failures and both are reported.
            - non-UTF-8 payloads come back as `body_base64` with `body_encoding: "base64"`.
            - `DELETE …/flows` clears the list. Sequence numbers keep climbing across a clear, so a
              cursor you are holding can never start matching different flows.

            **Waiting: `GET …/flows/await`.** It blocks until `count` matching flows have been
            recorded, up to `wait_ms` (capped at ${ControlApi.MAX_AWAIT_WAIT_MS} ms; any reduction is
            reported in `clamped`). A timeout is **not** an error — you get `satisfied: false` plus
            `closest_observed`, the traffic that nearly matched, which is normally enough to fix the
            matcher in one round trip. Use this instead of sleeping.

            **Credentials in the URL.** A query parameter named like a credential (`appid`, `api_key`,
            `token`, `signature`, …) is redacted on every surface that shows a URL — `<redacted:Nb>` in
            `url`, named in `redacted_query`, or in `revealed_query` under `include_secrets` — and a rule
            cloned with `from_flow_id` never keeps its value (required, match WILDCARD). Rules persist
            under .idea/, which is often committed, and `mocks export` hands rules back verbatim: never
            write a credential into a rule's query yourself.

            **`client`: the app's own numbers.** `status` and the listing both carry the last report the
            app's MockkHttp library sent with its messages: library, version, platform and counters —
            `flows_sent` (for Flutter also `flows_sent_by_dio` / `flows_sent_by_io`), `claims_made`
            (requests the dio layer let go down to the network — a request its mock answers never goes
            down and makes no claim), `passes_yielded` (the dart:io layer stepped aside for one of them),
            `claims_withdrawn`, `stood_back`, `claims_untaken_beneath` (a claim went down and nothing
            beneath took it: a native adapter), `claims_not_carried` (a request went down without its
            claim and was reported twice — decided by two witnesses, never a guess: the adapter of that
            very Dio was replaced while the request was in flight, AND the dart:io layer saw the same
            method and URL go past unclaimed after the claim was made; said once by the package when
            that request ends), `claims_not_fetched` (never went down through the wrapper and that Dio's
            adapter was not replaced meanwhile, so nothing beneath can have seen it: answered or cancelled
            above the adapter — nothing duplicated), `claims_unresolved` (never went down through the
            wrapper, that Dio's adapter WAS replaced meanwhile, and nothing beneath matched: answered
            above the adapter, sent through an adapter that bypasses dart:io, or fetched under a URL the
            new adapter rewrote and reported twice — the package cannot tell which and says so, once),
            `wrapper_replaced` (the app replaced dio's adapter after MockkHttp wrapped it — a fact of
            configuration, harmless in itself), `claims_evicted`, `beneath_witness_evicted` (the dart:io
            layer remembers unclaimed passes only for the identity of a live dio claim, in a bounded
            list; a non-zero value means a lost claim could have gone unwitnessed) and `claims_pending`
            (claims alive right now; one that stays put with nothing in flight is a request that never
            came back to the dio layer — a cache interceptor that resolved with dio's default flag — and
            that request is NOT in the flow list). The capture never drops a
            request — one request is one flow — and these are how you check that from outside. The
            client's counters start from zero on every app start (`run_id` changes, `started_at` says
            when); every report carries a `seq`, and the plugin keeps the highest per run, so a message
            overtaken on the wire never rolls the numbers back. Compare `flows.count` with
            `client.since_clear.flows_sent` ONLY when `since_clear.comparable` is true — which is exactly
            when every flow in the store came from the reporting run (`flows_not_from_this_run` is 0)
            AND the store has evicted nothing since the clear (`flows_evicted_since_clear` is 0: the
            store keeps at most `flows.capacity` flows, 300 by default, applied on every write and every
            read, and past that `flows.count` is a window, not a total) AND the plugin has rejected no
            message from this app since the clear (`flows_rejected_since_clear` is 0; `flows.rejected_since_clear`
            on status: a malformed FLOW or CHECK_MOCK is answered to the app and counted by the app, but
            never becomes a flow — idea.log says "MockkHttp rejected" with the reason; counted for the
            app this project captures plus the messages no app could be named for, never another
            named app's; and a header whose value is not text is dropped from a stored flow with an
            idea.log line "MockkHttp dropped" naming it, counted in `flows.headers_dropped_since_clear`
            and `since_clear.headers_dropped` — the flow itself is stored, so this does not affect
            `comparable`); when it is false,
            `reason` says what the count holds that this run did not send, how many flows the cap pushed
            out, or how many messages were rejected, and a `clear_flows` anchors the two counts again.
            Clients older than 1.8.0 send no report;
            `client` is then null. With a package filter set, `client` is that app's report or null —
            never another app's.
        """.trimIndent(),
        relatedRoutes = listOf(
            "GET /v1/projects/{pid}/flows",
            "GET /v1/projects/{pid}/flows/{flow_id}",
            "GET /v1/projects/{pid}/flows/await",
            "DELETE /v1/projects/{pid}/flows"
        )
    )

    private val AUTOMATED_TEST = DocsPage(
        topic = "automated_test",
        summary = "How to write a repeatable test with what this milestone actually has.",
        markdown = """
            ## An automated test on milestone ${ControlApi.MILESTONE}

            1. `GET …/status` — confirm the session is running and note `flows.next_seq`.
            2. `DELETE …/flows` — start from a clean journal.
            3. `POST …/mocks` — create the rules the scenario needs, `exclusive: true` so an older
               rule cannot win.
            4. `POST …/session/mode {"mode":"MOCKK"}`.
            5. `POST …/mocks/explain` with the exact request you expect — assert
               `would_skip_network: true` **before** you drive the app. This is the cheapest possible
               failure.
            6. Drive the app. If you run a Gradle or test command, run it as a **background** job: a
               foreground shell locks you out of your own tools for the duration.
            7. `GET …/flows/await?…&count=1&wait_ms=25000` — never sleep.
            8. `GET …/flows?since_seq=<next_seq>` (the `next_seq` from step 1) and assert on the summaries.
            9. `DELETE …/mocks/{rule_id}` or disable the rules you created. Nothing expires them for
               you in this build.

            **Not in this build:** ordinal stubs (`skip`/`times`, "500 on the second call"), runs and
            `verify` are milestone M2, and there is no TTL on anything you create — a rule you forget
            to delete keeps stubbing the developer's own traffic. Clean up in a finally-shaped step.
        """.trimIndent(),
        relatedRoutes = listOf(
            "DELETE /v1/projects/{pid}/flows",
            "POST /v1/projects/{pid}/mocks",
            "GET /v1/projects/{pid}/flows/await"
        )
    )

    private val ARMS_AND_ORDERING = DocsPage(
        topic = "arms_and_ordering",
        summary = "Ordinal stubs (\"fail the second call\") — milestone M2, not this build.",
        markdown = """
            ## Ordering and ordinal stubs

            Ephemeral armed stubs — `skip` / `times` / a sequence of responses / TTL, the primitive
            behind *"succeed once, then 500"* — land in milestone **M2**. `/v1/projects/{pid}/arms`
            answers `501 NOT_IMPLEMENTED` today.

            What you can do now:

            - a mock rule is unconditional: while it is enabled it answers **every** matching call;
            - to change behaviour mid-scenario, drive the app to the first state, then
              `POST …/mocks/{rule_id}/enable {"enabled":true}` (or `PUT …/mocks/{rule_id}` with a new
              response) between steps, and use `…/flows/await` to be sure the app has made the first
              call before you flip;
            - `exclusive: true` on enable disables competing rules on the same endpoint, so the flip
              is atomic from the app's point of view.

            Client-side deduplication is gone in the 1.8.0 clients (Flutter and native Android): every
            request the app makes is a flow. An app still on the 1.6.1 Gradle plugin drops an identical
            request repeated within 500 ms; see `topic=troubleshooting`.
        """.trimIndent(),
        relatedRoutes = listOf("POST /v1/projects/{pid}/mocks/{rule_id}/enable")
    )

    private val DEBUG_INTERCEPT = DocsPage(
        topic = "debug_intercept",
        summary = "Pausing a live request — milestone M3, not this build.",
        markdown = """
            ## Live intercepts

            Pausing a real response and rewriting it is milestone **M3**:
            `/v1/projects/{pid}/intercepts` and `/pause-policy` answer `501 NOT_IMPLEMENTED`.

            Until then, `DEBUG` and `MOCKK_DEBUG` pause **every** request behind a modal dialog in the
            IDE that only a human can answer, and the app abandons the pause after 60 s. This build
            refuses to enter those modes unless you send `"confirm_pause_all": true`, and you should
            not: from an agent it stalls the app under test with nobody to unblock it.

            Mock rules (`topic=mocking`) get you the same substituted response with **zero** latency
            and no pause, and they work on every mode-aware client build. Use them.
        """.trimIndent(),
        relatedRoutes = listOf("POST /v1/projects/{pid}/session/mode")
    )

    private val TROUBLESHOOTING = DocsPage(
        topic = "troubleshooting",
        summary = "The failures that actually happen, and the call that diagnoses each one.",
        markdown = """
            ## Troubleshooting

            **"My rule does not fire."** In order of likelihood:
            1. the session mode is `RECORDING` — rules are inert; `POST …/session/mode {"mode":"MOCKK"}`;
            2. the rule, or its collection, is disabled — `POST …/mocks/explain` says which;
            3. a query parameter is `required: true` and `EXACT` against a value that changes every
               call — recreate with `loosen_query: true`, or set `required: false`;
            4. the path differs by case or a trailing slash — `path` is case-sensitive; use
               `path_match: "REGEX"`;
            5. another enabled rule on the same endpoint wins — re-enable yours with
               `exclusive: true`.

            **"No flows at all."** `GET …/status`: `interceptor.bound` false with `bind_error` null means
            nobody has bound port 9876 yet — a session, an app scan or the open Inspector binds it;
            `bind_error` non-null means the last bind failed, and its message says why (most often
            another process holds the port). `session.running` false means no session is running —
            start one yourself; an empty
            `session.instrumented_packages` means no app has ever talked to this plugin — the app
            build is missing the interceptor, or it is not reaching the host.

            **"My retry never arrived."** With the 1.8.0 clients — `mockk_http` on Flutter, the 1.8.0
            Gradle plugin on native Android — there is no deduplication window: every request the app
            makes is a flow, and `client.stats` on `status` is the count to compare. An app still on the
            1.6.1 Gradle plugin deduplicates IDENTICAL requests — same method, scheme, host and path —
            inside a 500 ms window, so a fast retry of the very same call can be dropped before it
            reaches the plugin: upgrade the plugin, or turn it off with
            `MockkHttpInterceptor.enableDeduplication = false` in the debug app's `onCreate`.

            **`AMBIGUOUS_PROJECT`.** Several projects are open. Pass the `{pid}` from
            `GET /v1/projects` in the path.

            **`403 REVEAL_DISABLED`.** `include_secrets: true` needs the IDE-side setting that allows
            revealing secrets. Without it `Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key` and
            `X-Auth-Token` come back redacted; every response lists what it redacted.

            **`SESSION_NOT_RUNNING`.** Nothing is captured and no mock answers until a session runs.
            Start one yourself with `POST /v1/projects/{pid}/session/start`, which needs no arguments
            when exactly one device has exactly one app that has announced itself — and pass
            package_name while `interceptor.bound` is false, because an app launched before port 9876
            is bound cannot announce itself. Ambiguity comes back as AMBIGUOUS_DEVICE or
            AMBIGUOUS_APP listing the candidates.
        """.trimIndent(),
        relatedRoutes = listOf(
            "GET /v1/projects/{pid}/status",
            "POST /v1/projects/{pid}/mocks/explain"
        )
    )

    private val LIMITS = DocsPage(
        topic = "limits",
        summary = "Every budget the server enforces, and what it does when you exceed one.",
        markdown = """
            ## Limits

            | Budget | Value | On exceeding |
            |---|---|---|
            | Response body per message | ${ControlApi.DEFAULT_MAX_BODY_CHARS} chars default, ${ControlApi.HARD_MAX_BODY_CHARS} hard | truncated, `body_truncated: true`, reported in `clamped` |
            | Flows per listing | ${ControlApi.DEFAULT_FLOW_LIMIT} default, ${ControlApi.MAX_FLOW_LIMIT} max | clamped, reported in `clamped` |
            | Rules per listing | 100 default, 1000 max | clamped, reported in `clamped` |
            | `wait_ms` on `flows/await` | ${ControlApi.MAX_AWAIT_WAIT_MS} ms | clamped, reported in `clamped`; call again with `next_seq` |
            | `count` on `flows/await` | 100 | clamped |
            | Request body | ${ControlApi.REQUEST_BODY_BYTES_MAX} bytes | `413 PAYLOAD_TOO_LARGE` |
            | Concurrent long-polls | 2 per project | `429 TOO_MANY_WAITERS` |

            **The server never silently ignores a parameter.** Whenever a requested value is reduced
            the response carries `clamped: {"<field>": {"requested": …, "applied": …, "reason": "…"}}`.
            Read it: it is the difference between "my 60 s wait timed out" and "my wait was 25 s".

            Errors are always `{"error":{"code":"…","message":"…","hint":"…","retryable":…}}`. `hint`
            contains the next call to make; `retryable` says whether repeating the identical call
            could ever succeed.
        """.trimIndent(),
        relatedRoutes = listOf("GET /v1/meta")
    )

    /** Insertion order is the reading order the index suggests. */
    val pages: Map<String, DocsPage> = linkedMapOf(
        QUICKSTART.topic to QUICKSTART,
        MODES.topic to MODES,
        MOCKING.topic to MOCKING,
        MATCHING.topic to MATCHING,
        FLOWS.topic to FLOWS,
        AUTOMATED_TEST.topic to AUTOMATED_TEST,
        ARMS_AND_ORDERING.topic to ARMS_AND_ORDERING,
        DEBUG_INTERCEPT.topic to DEBUG_INTERCEPT,
        TROUBLESHOOTING.topic to TROUBLESHOOTING,
        LIMITS.topic to LIMITS
    )
}
