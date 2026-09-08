package com.sergiy.dev.mockkhttp.control.handlers

import com.sergiy.dev.mockkhttp.control.ControlApi
import com.sergiy.dev.mockkhttp.control.ControlRequest
import com.sergiy.dev.mockkhttp.control.ControlResponse
import com.sergiy.dev.mockkhttp.control.dto.ApiResult
import com.sergiy.dev.mockkhttp.control.dto.AwaitFlowRequest
import com.sergiy.dev.mockkhttp.control.dto.ErrorCode
import com.sergiy.dev.mockkhttp.control.dto.FlowQuery
import com.sergiy.dev.mockkhttp.control.dto.MatcherDto

/**
 * `/v1/projects/{pid}/flows` — list, get, clear, and the bounded long-poll.
 *
 * | Route | Verb | Meaning |
 * |---|---|---|
 * | `…/flows` | `GET` | summaries, filtered by the query string |
 * | `…/flows` | `POST` | the same listing, with the filter as a JSON body |
 * | `…/flows` | `DELETE` | clear the journal (sequence numbers keep climbing) |
 * | `…/flows/await` | `GET` / `POST` | block until `count` flows match, or the budget expires |
 * | `…/flows/{flowId}` | `GET` | one flow with headers and bodies |
 *
 * Redaction, base64 fallback, truncation flags, sequence numbers and `closest_observed` all live in
 * [ControlApi]. This file parses parameters and hands the result to `ControlResponse.of`, nothing
 * else.
 */
class FlowsHandler(private val api: ControlApi) {

    fun handle(request: ControlRequest): ControlResponse {
        val tail = request.tail

        return when {
            tail.isEmpty() -> when (request.method) {
                // POST is a read here: a filter with a body is still a listing, and the router
                // classifies it as non-mutating for exactly that reason.
                "GET", "POST" -> list(request)
                "DELETE" -> ControlResponse.of(api.clearFlows(request.projectId))
                else -> HandlerSupport.wrongMethod(request, "GET, POST or DELETE")
            }

            tail.size == 1 && tail[0] == AWAIT -> when (request.method) {
                // POST is accepted because a matcher does not fit a query string comfortably and
                // several HTTP clients refuse to send a body on GET.
                "GET", "POST" -> await(request)
                else -> HandlerSupport.wrongMethod(request, "GET or POST")
            }

            // `…/flows/clear` is not a route, but it is the first spelling anyone reaches for.
            // Left to the branch below it became a closed loop (audit finding T): a POST answered
            // "Use GET …/flows/clear", and obeying that GET treated `clear` as a flow id and 404'd.
            // Neither message named the call that works, so name it here, for every verb.
            tail.size == 1 && tail[0] == CLEAR -> ControlResponse.fail(
                ErrorCode.UNSUPPORTED_ACTION,
                "${request.method} /v1/projects/${request.projectId}/flows/clear is not a route: clearing is DELETE on the collection.",
                "DELETE /v1/projects/${request.projectId}/flows clears the whole journal (sequence numbers keep climbing)."
            )

            tail.size == 1 -> when (request.method) {
                "GET" -> get(request, tail[0])
                "DELETE" -> ControlResponse.fail(
                    ErrorCode.UNSUPPORTED_ACTION,
                    "Flows cannot be deleted one at a time.",
                    "DELETE /v1/projects/${request.projectId}/flows clears the whole journal."
                )
                // A flow only answers GET, and this segment may not be a flow id at all. A
                // `wrongMethod` here would invent "Use GET …/flows/<segment>" from the verb table
                // without checking that anything answers there; list what exists instead.
                else -> HandlerSupport.unknownSubRoute(request, KNOWN_ROUTES)
            }

            else -> HandlerSupport.unknownSubRoute(request, KNOWN_ROUTES)
        }
    }

    // ------------------------------------------------------------------------
    // list
    // ------------------------------------------------------------------------

    private fun list(request: ControlRequest): ControlResponse {
        val query: FlowQuery = if (request.body.isNotBlank()) {
            when (val parsed = request.jsonBody(FlowQuery::class.java)) {
                is ApiResult.Err -> return ControlResponse.error(parsed.error)
                is ApiResult.Ok -> parsed.value
            }
        } else {
            val q = QueryReader(request)
            val built = FlowQuery(
                method = q.str("method"),
                host = q.str("host"),
                path = q.str("path"),
                pathContains = q.str("path_contains"),
                pathRegex = q.str("path_regex"),
                urlContains = q.str("url_contains"),
                bodyContains = q.str("body_contains"),
                status = q.int("status"),
                statusMin = q.int("status_min"),
                statusMax = q.int("status_max"),
                resolution = q.str("resolution"),
                sinceSeq = q.long("since_seq"),
                limit = q.int("limit"),
                includeBody = q.str("include_body"),
                maxBodyChars = q.int("max_body_chars"),
                includeSecrets = q.bool("include_secrets")
            )
            // Built first so a malformed parameter is reported before any work is done with the rest.
            q.failure?.let { return it }
            built
        }
        return ControlResponse.of(api.listFlows(request.projectId, query))
    }

    // ------------------------------------------------------------------------
    // get
    // ------------------------------------------------------------------------

    private fun get(request: ControlRequest, flowId: String): ControlResponse {
        val q = QueryReader(request)
        val maxBodyChars = q.int("max_body_chars")
        val includeSecrets = q.bool("include_secrets")
        q.failure?.let { return it }
        return ControlResponse.of(api.getFlow(request.projectId, flowId, maxBodyChars, includeSecrets))
    }

    // ------------------------------------------------------------------------
    // await
    // ------------------------------------------------------------------------

    /**
     * Blocks this control-plane worker for up to [ControlRequest.longPollBudgetMs].
     *
     * The budget is the transport's, not the façade's: it is 0 while the IDE is shutting down, so a
     * long-poll started during teardown returns at once instead of pinning a worker. `ControlApi`
     * enforces exactly what it is given and reports any reduction of the caller's `wait_ms` in
     * `clamped`, which is why the cap is never applied here.
     */
    private fun await(request: ControlRequest): ControlResponse {
        val awaitRequest: AwaitFlowRequest = if (request.body.isNotBlank()) {
            when (val parsed = request.jsonBody(AwaitFlowRequest::class.java)) {
                is ApiResult.Err -> return ControlResponse.error(parsed.error)
                is ApiResult.Ok -> parsed.value
            }
        } else {
            val q = QueryReader(request)
            // The query-string form carries only the flat matcher fields; `query` and `header`
            // matchers need the JSON body.
            val matcher = MatcherDto(
                method = q.str("method"),
                host = q.str("host"),
                path = q.str("path"),
                pathRegex = q.str("path_regex"),
                urlContains = q.str("url_contains"),
                bodyContains = q.str("body_contains")
            )
            val built = AwaitFlowRequest(
                match = matcher,
                count = q.int("count"),
                sinceSeq = q.long("since_seq"),
                waitMs = q.long("wait_ms"),
                includeBody = q.str("include_body"),
                maxBodyChars = q.int("max_body_chars"),
                includeSecrets = q.bool("include_secrets")
            )
            q.failure?.let { return it }
            built
        }
        return ControlResponse.of(
            api.awaitFlow(request.projectId, awaitRequest, request.longPollBudgetMs)
        )
    }

    private companion object {
        const val AWAIT = "await"
        const val CLEAR = "clear"

        // One verb per route: the MCP bridge rewrites each "VERB route" into a tool call, and a
        // "GET|POST …" shorthand translates only its last verb.
        const val KNOWN_ROUTES = "GET /v1/projects/{pid}/flows, POST /v1/projects/{pid}/flows, " +
                "DELETE /v1/projects/{pid}/flows, GET /v1/projects/{pid}/flows/await, " +
                "GET /v1/projects/{pid}/flows/{flow_id}"
    }
}
