package com.sergiy.dev.mockkhttp.control.handlers

import com.sergiy.dev.mockkhttp.control.ControlApi
import com.sergiy.dev.mockkhttp.control.ControlRequest
import com.sergiy.dev.mockkhttp.control.ControlResponse
import com.sergiy.dev.mockkhttp.control.dto.ApiResult
import com.sergiy.dev.mockkhttp.control.dto.CollectionCreateRequest
import com.sergiy.dev.mockkhttp.control.dto.ErrorCode
import com.sergiy.dev.mockkhttp.control.dto.ImportRequest
import com.sergiy.dev.mockkhttp.control.dto.MatchExplainRequest
import com.sergiy.dev.mockkhttp.control.dto.RuleCreateRequest
import com.sergiy.dev.mockkhttp.control.dto.RuleEnableRequest
import com.sergiy.dev.mockkhttp.control.dto.RuleUpdateRequest

/**
 * `/v1/projects/{pid}/mocks…` — rule and collection CRUD, `explain`, export and dry-run import.
 *
 * | Route | Verb | Meaning |
 * |---|---|---|
 * | `…/mocks` | `GET` | list rules (`collection_id`, `include_bodies`, `limit`) |
 * | `…/mocks` | `POST` | create a rule — `from_flow_id` clones a captured flow |
 * | `…/mocks/{ruleId}` | `GET` / `PUT` / `PATCH` / `DELETE` | read, update, delete one rule |
 * | `…/mocks/{ruleId}/enable` | `POST` / `PUT` | enable or disable, optionally `exclusive` |
 * | `…/mocks/collections` | `GET` / `POST` | list, create |
 * | `…/mocks/collections/{id}` | `DELETE` | delete (`remove_rules`, default true) |
 * | `…/mocks/explain` | `POST` / `GET` | dry-run the real matcher against one request |
 * | `…/mocks/export` | `GET` | the Mockk tab's own JSON |
 * | `…/mocks/import` | `POST` | merge import; `dry_run:true` writes nothing |
 *
 * **Persistence.** Every mutating route ends in a [ControlApi] method that already calls
 * `SaveAndSyncHandler.getInstance().scheduleProjectSave(project)` — verified on all seven mutating
 * paths — so an IDE crash cannot lose an agent's rules. This handler deliberately does not repeat
 * the call: it holds a project *id*, not a `Project`, and re-resolving one here would duplicate the
 * façade's resolution rules and could name a different project than the write actually hit.
 * (`com.intellij.ide.SaveAndSyncHandler.scheduleProjectSave(Project)` is `public final` in the
 * 2024.3.7.1 platform, carrying only `@JvmOverloads`/`@NotNull` — no `@Deprecated`, no
 * `@ApiStatus.Internal`. It does not live in `com.intellij.configurationStore`.)
 */
class MocksHandler(private val api: ControlApi) {

    fun handle(request: ControlRequest): ControlResponse {
        val tail = request.tail
        val projectId = request.projectId

        return when {
            // `/mocks` and `/mocks/rules` are the same collection. The explicit spelling is what
            // the MCP bridge emits and what pairs with `/mocks/collections`; the bare form is kept
            // because it is the shape a hand-written curl reaches for first.
            tail.isEmpty() || (tail.size == 1 && tail[0] == RULES) -> when (request.method) {
                "GET" -> listRules(request)
                "POST" -> when (val body = request.jsonBody(RuleCreateRequest::class.java)) {
                    is ApiResult.Err -> ControlResponse.error(body.error)
                    is ApiResult.Ok -> ControlResponse.of(api.createRule(projectId, body.value))
                }
                else -> HandlerSupport.wrongMethod(request, "GET or POST")
            }

            tail.size == 1 && tail[0] == COLLECTIONS -> when (request.method) {
                "GET" -> ControlResponse.of(api.listCollections(projectId))
                "POST" -> when (val body = request.jsonBody(CollectionCreateRequest::class.java)) {
                    is ApiResult.Err -> ControlResponse.error(body.error)
                    is ApiResult.Ok -> ControlResponse.of(api.createCollection(projectId, body.value))
                }
                else -> HandlerSupport.wrongMethod(request, "GET or POST")
            }

            tail.size == 1 && tail[0] == EXPLAIN -> explain(request)

            tail.size == 1 && tail[0] == EXPORT -> when (request.method) {
                "GET" -> export(request)
                else -> HandlerSupport.wrongMethod(request, "GET")
            }

            tail.size == 1 && tail[0] == IMPORT -> when (request.method) {
                "POST", "PUT" -> when (val body = request.jsonBody(ImportRequest::class.java)) {
                    is ApiResult.Err -> ControlResponse.error(body.error)
                    is ApiResult.Ok -> ControlResponse.of(api.importMocks(projectId, body.value))
                }
                else -> HandlerSupport.wrongMethod(request, "POST")
            }

            tail.size == 1 -> rule(request, tail[0])

            tail.size == 2 && tail[0] == COLLECTIONS -> when (request.method) {
                "DELETE" -> deleteCollection(request, tail[1])
                else -> HandlerSupport.wrongMethod(request, "DELETE")
            }

            // /mocks/rules/{id}
            tail.size == 2 && tail[0] == RULES -> rule(request, tail[1])

            // Both spellings: the bridge says `enabled` (it sets a state), the earlier handler said
            // `enable` (it names an action). Accepting both costs one comparison and removes a
            // whole class of 404 nobody would be able to debug from the agent side.
            tail.size == 2 && (tail[1] == ENABLE || tail[1] == ENABLED) -> when (request.method) {
                "POST", "PUT", "PATCH" -> enable(request, tail[0])
                else -> HandlerSupport.wrongMethod(request, "POST")
            }

            // /mocks/rules/{id}/enabled
            tail.size == 3 && tail[0] == RULES && (tail[2] == ENABLE || tail[2] == ENABLED) ->
                when (request.method) {
                    "POST", "PUT", "PATCH" -> enable(request, tail[1])
                    else -> HandlerSupport.wrongMethod(request, "POST")
                }

            else -> HandlerSupport.unknownSubRoute(request, KNOWN_ROUTES)
        }
    }

    // ------------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------------

    private fun listRules(request: ControlRequest): ControlResponse {
        val q = QueryReader(request)
        val collectionId = q.str("collection_id")
        val includeBodies = q.bool("include_bodies")
        val limit = q.int("limit")
        q.failure?.let { return it }
        return ControlResponse.of(api.listRules(request.projectId, collectionId, includeBodies, limit))
    }

    private fun rule(request: ControlRequest, ruleId: String): ControlResponse = when (request.method) {
        "GET" -> ControlResponse.of(api.getRule(request.projectId, ruleId))
        "PUT", "PATCH", "POST" -> update(request, ruleId)
        "DELETE" -> ControlResponse.of(api.deleteRule(request.projectId, ruleId))
        else -> HandlerSupport.wrongMethod(request, "GET, PUT or DELETE")
    }

    private fun update(request: ControlRequest, ruleId: String): ControlResponse {
        val body = when (val parsed = request.jsonBody(RuleUpdateRequest::class.java)) {
            is ApiResult.Err -> return ControlResponse.error(parsed.error)
            is ApiResult.Ok -> parsed.value
        }
        // A body id that disagrees with the path is refused rather than silently resolved: the two
        // plausible resolutions update two different rules, and both would look like success.
        val bodyId = body.ruleId?.trim()?.takeIf { it.isNotEmpty() }
        if (bodyId != null && bodyId != ruleId) {
            return ControlResponse.fail(
                ErrorCode.INVALID_ARGUMENT,
                "rule_id in the body ('$bodyId') is not the rule_id in the path ('$ruleId').",
                "Send PUT /v1/projects/${request.projectId}/mocks/$ruleId and leave rule_id out of the body."
            )
        }
        return ControlResponse.of(api.updateRule(request.projectId, body.copy(ruleId = ruleId)))
    }

    private fun enable(request: ControlRequest, ruleId: String): ControlResponse {
        // An empty body means "enable it": the route already says what to do.
        val body = when (val parsed = request.jsonBody(RuleEnableRequest::class.java)) {
            is ApiResult.Err -> return ControlResponse.error(parsed.error)
            is ApiResult.Ok -> parsed.value
        }
        val q = QueryReader(request)
        val enabledParam = q.bool("enabled")
        val exclusiveParam = q.bool("exclusive")
        q.failure?.let { return it }

        return ControlResponse.of(
            api.setRuleEnabled(
                request.projectId,
                body.copy(
                    ruleId = ruleId,
                    enabled = body.enabled ?: enabledParam,
                    exclusive = body.exclusive ?: exclusiveParam
                )
            )
        )
    }

    // ------------------------------------------------------------------------
    // Collections, explain, export
    // ------------------------------------------------------------------------

    private fun deleteCollection(request: ControlRequest, collectionId: String): ControlResponse {
        val q = QueryReader(request)
        val removeRules = q.bool("remove_rules")
        q.failure?.let { return it }
        // Default true: a rule left without its collection is invisible in the UI and comes back
        // disabled on the next load, which reads as "the plugin ate my rule".
        return ControlResponse.of(
            api.deleteCollection(request.projectId, collectionId, removeRules ?: true)
        )
    }

    private fun explain(request: ControlRequest): ControlResponse {
        val explainRequest: MatchExplainRequest = when (request.method) {
            "POST", "PUT" -> when (val parsed = request.jsonBody(MatchExplainRequest::class.java)) {
                is ApiResult.Err -> return ControlResponse.error(parsed.error)
                is ApiResult.Ok -> parsed.value
            }

            "GET" -> {
                val q = QueryReader(request)
                val built = MatchExplainRequest(method = q.str("method"), url = q.str("url"))
                q.failure?.let { return it }
                built
            }

            else -> return HandlerSupport.wrongMethod(request, "POST")
        }
        return ControlResponse.of(api.explainMatch(request.projectId, explainRequest))
    }

    private fun export(request: ControlRequest): ControlResponse {
        val q = QueryReader(request)
        // A query map holds one value per key, so a selection arrives comma-separated.
        val ids = q.list("collection_ids") ?: q.str("collection_id")?.let { listOf(it) }
        q.failure?.let { return it }
        return ControlResponse.of(api.exportMocks(request.projectId, ids))
    }

    private companion object {
        const val COLLECTIONS = "collections"
        const val EXPLAIN = "explain"
        const val EXPORT = "export"
        const val IMPORT = "import"
        const val ENABLE = "enable"
        const val ENABLED = "enabled"
        const val RULES = "rules"

        // One verb per route: the MCP bridge rewrites each "VERB route" into a tool call, and a
        // "GET|POST …" shorthand translates only its last verb.
        const val KNOWN_ROUTES =
            "GET /v1/projects/{pid}/mocks, POST /v1/projects/{pid}/mocks, " +
                    "GET /v1/projects/{pid}/mocks/{rule_id}, PUT /v1/projects/{pid}/mocks/{rule_id}, " +
                    "DELETE /v1/projects/{pid}/mocks/{rule_id}, POST /v1/projects/{pid}/mocks/{rule_id}/enable, " +
                    "GET /v1/projects/{pid}/mocks/collections, POST /v1/projects/{pid}/mocks/collections, " +
                    "DELETE /v1/projects/{pid}/mocks/collections/{collection_id}, " +
                    "POST /v1/projects/{pid}/mocks/explain, GET /v1/projects/{pid}/mocks/export, " +
                    "POST /v1/projects/{pid}/mocks/import"
    }
}
