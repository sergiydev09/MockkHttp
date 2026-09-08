package com.sergiy.dev.mockkhttp.bridge

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** One tool answer: the text the model reads, and whether it should treat it as a failure. */
data class ToolOutcome(val text: String, val isError: Boolean)

// Tool names. Everything else in this file exists to describe or serve these seven.
private const val STATUS = "mockkhttp_status"
private const val FLOWS = "mockkhttp_flows"
private const val AWAIT_FLOW = "mockkhttp_await_flow"
private const val MOCKS = "mockkhttp_mocks"
private const val MATCH_EXPLAIN = "mockkhttp_match_explain"
private const val SESSION = "mockkhttp_session"
private const val DOCS = "mockkhttp_docs"

/**
 * The M1 tool surface, and the ONLY place tool wording lives.
 *
 * These descriptions are written for a model that has never seen MockkHttp: what the tool does, when
 * to reach for it, and — repeated wherever it matters — the doctrine that decides whether a session
 * is fast or unusable:
 *
 *   A MOCK RULE is answered during the app's CHECK_MOCK pre-flight, before the request leaves the
 *   device. The app skips the network, the agent is not consulted, latency is zero. That is how
 *   responses should be faked.
 *
 *   A LIVE INTERCEPT (DEBUG mode) instead freezes the app's HTTP thread until somebody answers, and
 *   the app abandons the wait after 60 s. It is for exploring, never for tests — and in this
 *   milestone nothing can answer it automatically at all.
 *
 * Later milestones add arms (ordinal, TTL'd ephemeral stubs), runs and verify. They are deliberately
 * absent here: a tool that is advertised but not implemented is worse than one that is missing.
 */
class Tools(private val client: RestClient = RestClient()) {

    private val specs: List<ToolSpec> by lazy { buildSpecs() }

    fun names(): List<String> = specs.map { it.name }

    fun has(name: String): Boolean = specs.any { it.name == name }

    fun definitions(): JsonArray {
        val array = JsonArray()
        for (spec in specs) {
            val tool = JsonObject()
            tool.addProperty("name", spec.name)
            tool.addProperty("description", spec.description)
            tool.add("inputSchema", spec.schema)
            array.add(tool)
        }
        return array
    }

    fun call(name: String, args: JsonObject): ToolOutcome = try {
        when (name) {
            STATUS -> status(args)
            FLOWS -> flows(args)
            AWAIT_FLOW -> awaitFlow(args)
            MOCKS -> mocks(args)
            MATCH_EXPLAIN -> matchExplain(args)
            SESSION -> session(args)
            DOCS -> docs(args)
            else -> fail("Unknown tool \"$name\". This server provides: ${names().joinToString(", ")}.")
        }
    } catch (e: BridgeException) {
        // Everything the user's environment can cause lands here: no IDE, wrong project, refused
        // write. It is a RESULT, not a JSON-RPC error, because a model can act on a result.
        fail(e.message ?: "MockkHttp could not answer, and gave no reason.")
    } catch (t: Throwable) {
        fail(
            "The MockkHttp bridge itself failed while running $name: ${t.javaClass.simpleName}: ${t.message}. " +
                "This is a bug in the bridge, not in your arguments. Arguments were: ${WIRE.toJson(args)}"
        )
    }

    // ── handlers ──────────────────────────────────────────────────────────────────────────────────

    private fun status(args: JsonObject): ToolOutcome {
        val scope = client.scope(args.optString("project"))
        val payload = client.request(
            "GET",
            "/projects/${segment(scope.pid)}/status",
            // The control plane echoes these into resolution{} so the model can check the bridge's work.
            query = listOf("matched_by" to scope.matchedBy, "cwd" to scope.cwd),
            scope = scope
        )
        if (payload.isJsonObject && !payload.asJsonObject.has("bridge")) {
            payload.asJsonObject.add("bridge", scope.describe())
        }
        return ok(payload)
    }

    private fun flows(args: JsonObject): ToolOutcome {
        val scope = client.scope(args.optString("project"))
        val base = "/projects/${segment(scope.pid)}/flows"
        return when (val action = args.optString("action")?.lowercase() ?: "list") {
            "list" -> ok(client.request("GET", base, query = queryOf(args, FLOW_QUERY_KEYS), scope = scope))

            "get" -> {
                val flowId = args.optString("flow_id")
                    ?: return fail(
                        "mockkhttp_flows action \"get\" needs flow_id. Call it with action \"list\" first and " +
                            "take flow_id from the summary you want."
                    )
                ok(
                    client.request(
                        "GET",
                        "$base/${segment(flowId)}",
                        query = queryOf(args, listOf("max_body_chars", "include_secrets")),
                        scope = scope
                    )
                )
            }

            "clear" -> ok(client.request("DELETE", base, scope = scope))

            else -> fail("Unknown action \"$action\" for mockkhttp_flows. Use \"list\", \"get\" or \"clear\".")
        }
    }

    private fun awaitFlow(args: JsonObject): ToolOutcome {
        val scope = client.scope(args.optString("project"))
        return ok(
            client.request(
                "POST",
                "/projects/${segment(scope.pid)}/flows/await",
                body = pick(args, AWAIT_KEYS),
                // Server-side long poll, hard-capped at 25 s; give the socket room to answer.
                timeout = RestClient.LONG_POLL_TIMEOUT,
                scope = scope
            )
        )
    }

    private fun mocks(args: JsonObject): ToolOutcome {
        val scope = client.scope(args.optString("project"))
        val base = "/projects/${segment(scope.pid)}/mocks"
        return when (val action = args.optString("action")?.lowercase() ?: "list") {
            "list" -> ok(
                client.request(
                    "GET",
                    "$base/rules",
                    query = queryOf(args, listOf("collection_id", "include_bodies", "limit")),
                    scope = scope
                )
            )

            "get" -> {
                val ruleId = args.optString("rule_id") ?: return needs("rule_id", "get")
                ok(client.request("GET", "$base/rules/${segment(ruleId)}", scope = scope))
            }

            "create" -> ok(client.request("POST", "$base/rules", body = pick(args, RULE_CREATE_KEYS), scope = scope))

            "update" -> {
                val ruleId = args.optString("rule_id") ?: return needs("rule_id", "update")
                val body = pick(args, RULE_UPDATE_KEYS)
                body.addProperty("rule_id", ruleId)
                ok(client.request("PUT", "$base/rules/${segment(ruleId)}", body = body, scope = scope))
            }

            "set_enabled" -> {
                val ruleId = args.optString("rule_id") ?: return needs("rule_id", "set_enabled")
                val enabled = args.optBoolean("enabled")
                    ?: return fail(
                        "mockkhttp_mocks action \"set_enabled\" needs enabled:true or enabled:false — there is no " +
                            "sensible default for it."
                    )
                val body = JsonObject()
                body.addProperty("rule_id", ruleId)
                body.addProperty("enabled", enabled)
                args.optBoolean("exclusive")?.let { body.addProperty("exclusive", it) }
                ok(client.request("POST", "$base/rules/${segment(ruleId)}/enabled", body = body, scope = scope))
            }

            "delete" -> {
                val ruleId = args.optString("rule_id") ?: return needs("rule_id", "delete")
                ok(client.request("DELETE", "$base/rules/${segment(ruleId)}", scope = scope))
            }

            "list_collections" -> ok(client.request("GET", "$base/collections", scope = scope))

            "create_collection" -> {
                val body = pick(args, listOf("name", "package_name", "description"))
                if (!body.has("name")) return needs("name", "create_collection")
                ok(client.request("POST", "$base/collections", body = body, scope = scope))
            }

            "delete_collection" -> {
                val collectionId = args.optString("collection_id")
                    ?: return needs("collection_id", "delete_collection")
                ok(
                    client.request(
                        "DELETE",
                        "$base/collections/${segment(collectionId)}",
                        query = queryOf(args, listOf("remove_rules")),
                        scope = scope
                    )
                )
            }

            "export" -> {
                val ids = args.optArray("collection_ids")
                    ?.mapNotNull { it.takeIf { element -> element.isJsonPrimitive }?.asString }
                    ?.takeIf { it.isNotEmpty() }
                    ?.joinToString(",")
                okWhole(client.request("GET", "$base/export", query = listOf("collection_ids" to ids), scope = scope))
            }

            "import" -> {
                val body = pick(args, listOf("json", "strategy", "dry_run"))
                if (!body.has("json")) {
                    return fail(
                        "mockkhttp_mocks action \"import\" needs json: the whole exported document as a STRING. " +
                            "Run action \"export\" first to see the shape, and pass dry_run:true to preview the merge " +
                            "without writing anything."
                    )
                }
                ok(client.request("POST", "$base/import", body = body, scope = scope))
            }

            else -> fail(
                "Unknown action \"$action\" for mockkhttp_mocks. Use one of: list, get, create, update, " +
                    "set_enabled, delete, list_collections, create_collection, delete_collection, export, import."
            )
        }
    }

    private fun matchExplain(args: JsonObject): ToolOutcome {
        val scope = client.scope(args.optString("project"))
        val method = args.optString("method") ?: return needs("method", "mockkhttp_match_explain")
        val url = args.optString("url") ?: return needs("url", "mockkhttp_match_explain")
        val body = JsonObject()
        body.addProperty("method", method)
        body.addProperty("url", url)
        return ok(client.request("POST", "/projects/${segment(scope.pid)}/mocks/explain", body = body, scope = scope))
    }

    private fun session(args: JsonObject): ToolOutcome {
        val scope = client.scope(args.optString("project"))
        val base = "/projects/${segment(scope.pid)}"
        return when (val action = args.optString("action")?.lowercase() ?: "get") {
            "get" -> ok(client.request("GET", "$base/session", scope = scope))

            "set_mode" -> {
                val mode = args.optString("mode")?.uppercase()
                    ?: return fail(
                        "mockkhttp_session action \"set_mode\" needs mode. Use MOCKK to make mock rules answer at " +
                            "CHECK_MOCK with no latency, or RECORDING to only capture."
                    )
                val body = JsonObject()
                body.addProperty("mode", mode)
                args.optBoolean("confirm_pause_all")?.let { body.addProperty("confirm_pause_all", it) }
                ok(client.request("POST", "$base/session/mode", body = body, scope = scope))
            }

            // Same endpoint as mockkhttp_flows action "clear"; offered here because "reset the
            // session before I drive the app" is how the step is usually thought about.
            "clear_flows" -> ok(client.request("DELETE", "$base/flows", scope = scope))

            // Nothing this tool does has any effect until a session runs: rules only answer at
            // CHECK_MOCK while capture is live. Start is therefore the first call of most sessions.
            // With one device and one instrumented app, an empty body is enough.
            "start" -> ok(client.request("POST", "$base/session/start", body = sessionStartBody(args), scope = scope))

            "stop" -> ok(client.request("POST", "$base/session/stop", scope = scope))

            "restart" -> ok(client.request("POST", "$base/session/restart", body = sessionStartBody(args), scope = scope))

            "set_app" -> {
                val pkg = args.optString("package_name")
                    ?: return fail(
                        "mockkhttp_session action \"set_app\" needs package_name. Call action \"devices\" to see " +
                            "which apps have announced themselves to this IDE."
                    )
                val body = JsonObject()
                body.addProperty("package_name", pkg)
                ok(client.request("POST", "$base/session/app", body = body, scope = scope))
            }

            // Devices AND the apps each one has that speak the MockkHttp protocol. An app that has
            // announced itself is the strongest signal there is; the slower APK scan is opt-in.
            "devices" -> {
                val deep = args.optBoolean("scan") == true
                ok(client.request("GET", "$base/session/devices", query = listOf("scan" to if (deep) "true" else null), scope = scope))
            }

            else -> fail(
                "Unknown action \"$action\" for mockkhttp_session. Supported: \"get\", \"start\", \"stop\", " +
                    "\"restart\", \"set_app\", \"devices\", \"set_mode\", \"clear_flows\"."
            )
        }
    }

    private fun docs(args: JsonObject): ToolOutcome {
        val topic = args.optString("topic")
        return try {
            ok(client.request("GET", "/docs", query = listOf("topic" to topic)))
        } catch (e: BridgeException) {
            // The guide lives in the plugin, but a model that cannot reach the plugin is exactly the
            // one that needs to know what MockkHttp is and what to ask the user for. Still isError:
            // the call did not do what it said it would.
            ToolOutcome(Format.renderText("${e.message}\n\n$OFFLINE_GUIDE"), isError = true)
        }
    }

    // ── plumbing ──────────────────────────────────────────────────────────────────────────────────

    /**
     * The body `start` and `restart` share. The control plane parses both with the same
     * StartSessionRequest and applies the same pause gate to both, so a flag one of them forwarded
     * and the other dropped (confirm_pause_all, audit round three) was a 403 on restart only.
     */
    private fun sessionStartBody(args: JsonObject): JsonObject {
        val body = JsonObject()
        args.optString("serial")?.let { body.addProperty("serial", it) }
        args.optString("package_name")?.let { body.addProperty("package_name", it) }
        args.optString("mode")?.uppercase()?.let { body.addProperty("mode", it) }
        args.optBoolean("confirm_pause_all")?.let { body.addProperty("confirm_pause_all", it) }
        return body
    }

    private fun ok(payload: JsonElement) = ToolOutcome(Format.render(payload), isError = false)

    /** For payloads that are only useful whole — see [Format.renderWhole]. */
    private fun okWhole(payload: JsonElement) = ToolOutcome(Format.renderWhole(payload), isError = false)

    private fun fail(text: String) = ToolOutcome(Format.renderText(text), isError = true)

    private fun needs(field: String, action: String) = fail(
        "The \"$action\" call needs $field. List what exists first (mockkhttp_mocks with action \"list\" or " +
            "\"list_collections\", mockkhttp_flows with action \"list\") and take the value from that answer."
    )

    /** Copies present arguments straight into the REST body: MCP argument names ARE the wire names. */
    private fun pick(args: JsonObject, keys: List<String>): JsonObject {
        val body = JsonObject()
        for (key in keys) {
            val value = args.get(key) ?: continue
            if (value.isJsonNull) continue
            body.add(key, value)
        }
        return body
    }

    private fun queryOf(args: JsonObject, keys: List<String>): List<Pair<String, String?>> =
        keys.map { key -> key to args.get(key)?.takeIf { it.isJsonPrimitive }?.asString }

    private fun segment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private class ToolSpec(val name: String, val description: String, val schema: JsonObject)

    // ── schemas & descriptions ────────────────────────────────────────────────────────────────────

    private fun buildSpecs(): List<ToolSpec> = listOf(
        ToolSpec(STATUS, STATUS_DESCRIPTION, schema(listOf("project" to projectProp()))),

        ToolSpec(
            FLOWS,
            FLOWS_DESCRIPTION,
            schema(
                listOf(
                    "project" to projectProp(),
                    "action" to stringProp(
                        "list (default) returns summaries only; get returns one whole flow with bodies; " +
                            "clear empties the recorded list.",
                        listOf("list", "get", "clear")
                    ),
                    "flow_id" to stringProp("Required by action \"get\". Comes from a summary in action \"list\"."),
                    "method" to stringProp("Filter: HTTP method, compared ignoring case."),
                    "host" to stringProp("Filter: exact host, e.g. api.acme.com."),
                    "path" to stringProp("Filter: exact, case-SENSITIVE path. Prefer path_contains unless you copied the path from a captured flow."),
                    "path_contains" to stringProp("Filter: case-insensitive substring of the path. The forgiving option."),
                    "path_regex" to stringProp("Filter: regex matched against the whole path."),
                    "url_contains" to stringProp("Filter: case-insensitive substring of the full URL."),
                    "body_contains" to stringProp("Filter: substring of the request body."),
                    "status" to integerProp("Filter: exact response status code."),
                    "status_min" to integerProp("Filter: lowest status code to include, e.g. 400 for failures only."),
                    "status_max" to integerProp("Filter: highest status code to include."),
                    "resolution" to stringProp(
                        "Filter: how the response was produced. MOCKED = answered by a mock rule, PASSTHROUGH = the " +
                            "real server answered, MODIFIED = a human edited it in Debug, UNCONFIRMED = the app build " +
                            "cannot confirm it consumed our answer.",
                        listOf("STUBBED", "MOCKED", "PASSTHROUGH", "MODIFIED", "TIMEOUT", "UNCONFIRMED")
                    ),
                    "since_seq" to integerProp(
                        "Cursor: return only flows with seq >= this. Pass back the next_seq you were given and you " +
                            "will only ever see new traffic."
                    ),
                    "limit" to integerProp("How many flows to return (default 25, max 500), newest first."),
                    "include_body" to stringProp(
                        "Bodies in a LIST are off by default because a chatty app will drown the context window.",
                        listOf("none", "response", "both")
                    ),
                    "max_body_chars" to integerProp("Body truncation limit (default 20000, hard max 262144). The answer always says whether it truncated."),
                    "include_secrets" to booleanProp(
                        "Ask for unredacted Authorization/Cookie headers. Fails with REVEAL_DISABLED unless the user " +
                            "turned that on in MockkHttp Settings; leave it out."
                    )
                )
            )
        ),

        ToolSpec(
            AWAIT_FLOW,
            AWAIT_DESCRIPTION,
            schema(
                listOf(
                    "project" to projectProp(),
                    "match" to matcherProp(),
                    "count" to integerProp("How many matching flows to wait for (default 1). Use 2 to watch a retry (on native Android an identical retry inside 500 ms is dropped by the interceptor unless enableDeduplication is off)."),
                    "since_seq" to integerProp(
                        "Only count flows with seq >= this. Take it from the next_seq of an earlier call so traffic " +
                            "that arrived before you started driving the app is not counted."
                    ),
                    "wait_ms" to integerProp("How long to block, clamped to 25000 by the control plane."),
                    "include_body" to stringProp("Bodies of the matched flows.", listOf("none", "response", "both")),
                    "max_body_chars" to integerProp("Body truncation limit for the flows returned.")
                )
            )
        ),

        ToolSpec(
            MOCKS,
            MOCKS_DESCRIPTION,
            schema(
                listOf(
                    "project" to projectProp(),
                    "action" to stringProp(
                        "What to do. Default list.",
                        listOf(
                            "list", "get", "create", "update", "set_enabled", "delete",
                            "list_collections", "create_collection", "delete_collection", "export", "import"
                        )
                    ),
                    "rule_id" to stringProp("The rule to get, update, enable/disable or delete."),
                    "collection_id" to stringProp("Collection to file a new rule under, to list, or to delete. Rules must live in a collection."),
                    "collection_ids" to arrayProp("Restrict action \"export\" to these collections.", stringProp("A collection id.")),
                    "name" to stringProp("Rule name (or collection name for create_collection). Make it describe the scenario, e.g. \"login 500\"."),
                    "method" to stringProp("HTTP method the rule answers, e.g. GET or POST."),
                    "url" to stringProp(
                        "Full URL the rule answers, e.g. https://api.acme.com/v1/login. Query parameters in it " +
                            "become matching rules. A URL is parsed as a URL, so it can only hold LITERAL text: to " +
                            "match a family of paths or hosts, send host and path instead."
                    ),
                    "host" to stringProp(
                        "The host on its own, taken verbatim — the way to write a pattern, since it is never parsed " +
                            "as part of a URL. With host_match REGEX escape the dots: (api|beta)\\.acme\\.com, " +
                            "because a bare . matches any character and .*.acme.test also matches XacmeXtest."
                    ),
                    "path" to stringProp(
                        "The path on its own, taken verbatim: path:'/v1/orders/[0-9]+' with path_match:'REGEX' is " +
                            "the canonical one-rule-per-endpoint-family. Not split on '?', so a pattern may contain " +
                            "one; match query parameters with query instead."
                    ),
                    "host_match" to stringProp(
                        "How to compare the host, case-insensitively. EXACT is literal, WILDCARD treats * as any run " +
                            "of characters, REGEX is a full-string regex. Default EXACT.",
                        MATCH_MODES
                    ),
                    "path_match" to stringProp(
                        "How to compare the path, case-SENSITIVELY. REGEX lets one rule cover /v1/orders/<any id> " +
                            "(anchored: the whole path must match). Default EXACT.",
                        MATCH_MODES
                    ),
                    "query" to arrayProp(
                        "Per-query-parameter matching.",
                        objectProp(
                            "One query parameter matcher.",
                            listOf(
                                "key" to stringProp("Parameter name."),
                                "value" to stringProp("Expected value."),
                                "required" to booleanProp(
                                    "Must the parameter be PRESENT? Default true. required:false means absent is " +
                                        "fine — it does not stop the value being compared when the parameter is " +
                                        "there, so pair it with match:'WILDCARD' to ignore a timestamp."
                                ),
                                "match" to stringProp("Comparison mode.", MATCH_MODES)
                            )
                        )
                    ),
                    "response" to responseProp(),
                    "from_flow_id" to stringProp(
                        "Clone a captured flow into a rule: URL, method and its real response are copied, and you " +
                            "override only what you want changed (usually response.status_code). The fastest way to " +
                            "build a rule that actually matches."
                    ),
                    "loosen_query" to booleanProp(
                        "With from_flow_id, mark volatile query parameters (ts, nonce, sig, cache-busters, long " +
                            "numbers, UUIDs) optional. Defaults to true there, and is the difference between a rule " +
                            "that fires again and one that matched exactly once."
                    ),
                    "enabled" to booleanProp("Whether the rule is active. Disabled rules never answer anything."),
                    "exclusive" to booleanProp(
                        "Disable every other enabled rule for the same method+host+path. Two enabled rules on one " +
                            "endpoint is the most common reason the wrong body comes back."
                    ),
                    "new_collection" to objectProp(
                        "Create a collection for this rule in the same call.",
                        listOf(
                            "name" to stringProp("Collection name."),
                            "package_name" to stringProp("App package the collection belongs to (optional)."),
                            "description" to stringProp("Free text (optional).")
                        )
                    ),
                    "package_name" to stringProp("For create_collection: the app package this collection belongs to."),
                    "description" to stringProp("For create_collection: free text."),
                    "remove_rules" to booleanProp("For delete_collection: also delete the rules inside it. Default true."),
                    "include_bodies" to booleanProp("For list: include response bodies instead of just their size."),
                    "limit" to integerProp("For list: how many rules to return."),
                    "json" to stringProp("For import: the exported document, as a string."),
                    "strategy" to stringProp(
                        "For import: what to do with a rule whose endpoint already exists but whose response differs.",
                        listOf("REPLACE", "KEEP_BOTH", "SKIP")
                    ),
                    "dry_run" to booleanProp("For import: report what WOULD change and write nothing. Always try this first.")
                )
            )
        ),

        ToolSpec(
            MATCH_EXPLAIN,
            EXPLAIN_DESCRIPTION,
            schema(
                listOf(
                    "project" to projectProp(),
                    "method" to stringProp("HTTP method of the hypothetical request, e.g. POST."),
                    "url" to stringProp("Full URL of the hypothetical request, query string included.")
                ),
                required = listOf("method", "url")
            )
        ),

        ToolSpec(
            SESSION,
            SESSION_DESCRIPTION,
            schema(
                listOf(
                    "project" to projectProp(),
                    "action" to stringProp(
                        "get (default) reports the session. start begins capture — call it FIRST, because mock rules " +
                            "only answer while a session runs; with one device and one instrumented app it needs no " +
                            "arguments. devices lists the devices and the apps that have announced themselves. " +
                            "set_app re-targets a running session. stop ends it, restart re-establishes it. " +
                            "set_mode changes what MockkHttp does with each request; clear_flows empties the list.",
                        listOf("get", "start", "stop", "restart", "set_app", "devices", "set_mode", "clear_flows")
                    ),
                    "serial" to stringProp(
                        "Device serial for start/restart, e.g. emulator-5554. Omit when exactly one device is connected."
                    ),
                    "package_name" to stringProp(
                        "App package for start/restart/set_app. Omit on start when exactly one app has announced itself."
                    ),
                    "scan" to booleanProp(
                        "devices only: also run the slow APK scan. Off by default — apps that have announced " +
                            "themselves are already listed and are the more reliable signal."
                    ),
                    "mode" to stringProp(
                        "RECORDING captures only. MOCKK additionally answers matching mock rules at CHECK_MOCK, with " +
                            "no network call and no latency — this is the mode you want. DEBUG and MOCKK_DEBUG PAUSE " +
                            "every request until a human answers a dialog and cannot be driven from here.",
                        listOf("RECORDING", "MOCKK", "DEBUG", "MOCKK_DEBUG")
                    ),
                    "confirm_pause_all" to booleanProp(
                        "Required to acknowledge that DEBUG/MOCKK_DEBUG pauses EVERY request, background polling " +
                            "included, for up to a minute each. Do not set it unless a human asked for Debug mode."
                    )
                )
            )
        ),

        ToolSpec(
            DOCS,
            DOCS_DESCRIPTION,
            schema(
                listOf(
                    "topic" to stringProp(
                        "Topic to read. Omit for the index of what is available in this plugin build — that index, " +
                            "not this list, is authoritative.",
                        DOCS_TOPICS
                    )
                )
            )
        )
    )

    private fun projectProp(): JsonObject = stringProp(
        "Which project to address: its name, or the project_id from mockkhttp_status. Normally omitted — the bridge " +
            "resolves the project by matching the working directory against every open project's path, and " +
            "mockkhttp_status reports how it decided under bridge.matched_by. Pass it when two IDE windows are open " +
            "or the answer names the wrong project."
    )

    private fun matcherProp(): JsonObject = objectProp(
        "Which request to wait for. Every field you set must match (AND). Set as few as possible: an over-specific " +
            "matcher is the usual reason a wait times out.",
        listOf(
            "method" to stringProp("HTTP method, compared ignoring case."),
            "host" to stringProp("Exact host, compared ignoring case."),
            "path" to stringProp("Exact, case-SENSITIVE path. Trailing slashes are normalised."),
            "path_regex" to stringProp("Regex matched against the whole path; mutually exclusive with path."),
            "url_contains" to stringProp("Case-insensitive substring of the full URL. The most forgiving option."),
            "body_contains" to stringProp("Substring of the request body, e.g. grant_type=refresh."),
            "query" to arrayProp(
                "Query parameters that must be present.",
                objectProp(
                    "One query parameter matcher.",
                    listOf(
                        "key" to stringProp("Parameter name."),
                        "value" to stringProp("Expected value."),
                        "required" to booleanProp("Must it be present? Default true."),
                        "match" to stringProp("Comparison mode.", listOf("EXACT", "WILDCARD", "REGEX"))
                    )
                )
            ),
            "header" to arrayProp(
                "Request headers that must be present.",
                objectProp(
                    "One header matcher.",
                    listOf(
                        "key" to stringProp("Header name."),
                        "value" to stringProp("Expected value."),
                        "match" to stringProp("Comparison mode.", listOf("EXACT", "REGEX"))
                    )
                )
            )
        )
    )

    private fun responseProp(): JsonObject = objectProp(
        "The response the app will receive instead of the real one.",
        listOf(
            "status_code" to integerProp("HTTP status to return, e.g. 500. Must be a real status, 100-599."),
            "headers" to objectProp("Response headers to send, e.g. {\"Content-Type\":\"application/json\"}.", emptyList()),
            "body" to stringProp("Response body as text. For JSON, pass the serialised JSON as a string."),
            "body_base64" to stringProp(
                "Response body as base64, for text whose quoting is awkward. Use instead of body. It must decode " +
                    "to valid UTF-8 TEXT: a rule body is a string all the way to the device, so a PNG or a protobuf " +
                    "is refused rather than stored as replacement characters. Binary mock bodies are not supported."
            ),
            "delay_ms" to integerProp("Reserved: latency injection lands in a later milestone, and a non-zero value is refused today rather than silently ignored.")
        )
    )

    private fun schema(properties: List<Pair<String, JsonObject>>, required: List<String> = emptyList()): JsonObject {
        val props = JsonObject()
        for ((name, value) in properties) props.add(name, value)
        val root = JsonObject()
        root.addProperty("type", "object")
        root.add("properties", props)
        if (required.isNotEmpty()) {
            val requiredArray = JsonArray()
            for (name in required) requiredArray.add(name)
            root.add("required", requiredArray)
        }
        return root
    }

    private fun stringProp(description: String, values: List<String>? = null): JsonObject {
        val prop = JsonObject()
        prop.addProperty("type", "string")
        prop.addProperty("description", description)
        if (values != null) {
            val enumeration = JsonArray()
            for (value in values) enumeration.add(value)
            prop.add("enum", enumeration)
        }
        return prop
    }

    private fun integerProp(description: String): JsonObject {
        val prop = JsonObject()
        prop.addProperty("type", "integer")
        prop.addProperty("description", description)
        return prop
    }

    private fun booleanProp(description: String): JsonObject {
        val prop = JsonObject()
        prop.addProperty("type", "boolean")
        prop.addProperty("description", description)
        return prop
    }

    private fun objectProp(description: String, properties: List<Pair<String, JsonObject>>): JsonObject {
        val prop = JsonObject()
        prop.addProperty("type", "object")
        prop.addProperty("description", description)
        if (properties.isNotEmpty()) {
            val props = JsonObject()
            for ((name, value) in properties) props.add(name, value)
            prop.add("properties", props)
        }
        return prop
    }

    private fun arrayProp(description: String, items: JsonObject): JsonObject {
        val prop = JsonObject()
        prop.addProperty("type", "array")
        prop.addProperty("description", description)
        prop.add("items", items)
        return prop
    }
}

// ── argument sets forwarded verbatim ──────────────────────────────────────────────────────────────
//
// MCP argument names are identical to the REST field names on purpose: forwarding is a filtered copy,
// so a renamed field cannot silently disappear in translation.

private val FLOW_QUERY_KEYS = listOf(
    "method", "host", "path", "path_contains", "path_regex", "url_contains", "body_contains",
    "status", "status_min", "status_max", "resolution", "since_seq", "limit",
    "include_body", "max_body_chars", "include_secrets"
)

private val AWAIT_KEYS = listOf(
    "match", "count", "since_seq", "wait_ms", "include_body", "max_body_chars", "include_secrets"
)

private val RULE_CREATE_KEYS = listOf(
    "name", "method", "url", "host", "path", "host_match", "path_match", "query", "response",
    "collection_id", "new_collection", "from_flow_id", "loosen_query", "enabled", "exclusive"
)

private val RULE_UPDATE_KEYS = listOf(
    "name", "enabled", "method", "url", "host", "path", "host_match", "path_match", "query", "response", "exclusive"
)

/**
 * The match modes the plugin's matcher implements, in the plugin's own spelling.
 *
 * Source of truth is the `MatchType` enum in the plugin (`model/MockRuleModels.kt`), which now
 * publishes exactly these three as `MatchType.WIRE_NAMES`. The bridge is a standalone jar with
 * neither the plugin nor IntelliJ on its classpath, so it cannot import them — naming them ONCE
 * here is what keeps the four schema positions from drifting apart again. They drifted before:
 * host_match/path_match advertised EXACT|REGEX while the server accepted WILDCARD and
 * match_explain's own error message recommended it.
 */
private val MATCH_MODES = listOf("EXACT", "WILDCARD", "REGEX")

/**
 * Every topic the plugin serves, in the order `GET /docs` lists them.
 *
 * Source of truth is `AgentDocs.pages` in the plugin (`control/handlers/MetaHandler.kt`); the index
 * this tool returns with no topic is always authoritative. An enum that is a SUBSET is worse than
 * no enum at all — a client validating against it refuses a topic the server would have answered,
 * which is how flows, automated_test, arms_and_ordering and debug_intercept became unreachable.
 */
private val DOCS_TOPICS = listOf(
    "quickstart", "modes", "mocking", "matching", "flows",
    "automated_test", "arms_and_ordering", "debug_intercept", "troubleshooting", "limits"
)

// ── descriptions ──────────────────────────────────────────────────────────────────────────────────

private val STATUS_DESCRIPTION = """
    Orientation. Call this FIRST in any MockkHttp session, before flows or mocks.

    MockkHttp is an Android Studio / IntelliJ plugin that captures and fakes the HTTP traffic of a
    mobile app under test — native Android (OkHttp) or Flutter on Android and iOS — from INSIDE the
    app. There is no proxy and no certificate: the app asks the plugin about every request before it
    is sent, so certificate pinning keeps working and nothing on the device needs configuring.

    Returns: which IDE and project this bridge resolved from the working directory and why
    (bridge.matched_by / resolution — check it if the answer looks like someone else's project);
    whether a capture session is running and in which MODE; how many flows have been recorded and how
    many mock rules exist; and warnings explaining anything that will silently not work.

    Two facts decide everything else. Nothing at all is captured while session.running is false —
    start one yourself with mockkhttp_session {"action":"start"}, which needs no arguments when one
    device has one instrumented app. And mock rules only answer in MOCKK or MOCKK_DEBUG mode; in
    RECORDING they are inert, however correct they are.
""".trimIndent()

private val FLOWS_DESCRIPTION = """
    Read what the app under test actually sent and received. This is the ground truth for "what did
    the app call", "did it retry", "what did the server answer" — with one caveat: the native Android
    interceptor (1.6.1) drops an identical request repeated within 500 ms unless the app sets
    MockkHttpInterceptor.enableDeduplication = false, so a fast retry from an Android app may never
    reach MockkHttp. mockk_http 1.8.0 on Flutter captures every request the app makes, and says so:
    the answer's `client.stats` carries the library's own counters (flows_sent, passes_yielded, …),
    so "one request, one flow" can be checked from outside.

    action "list" (default) returns summaries newest first — method, URL, status, duration, byte
    counts, and resolution (whether a mock answered it or the real network did) — with no bodies, so
    a chatty app cannot flood the context. Filter with host / path_contains / status_min / method,
    and page with since_seq: pass back the next_seq you were given and you see only new traffic.

    action "get" returns ONE flow complete, with request and response headers and bodies. That is the
    call to make once list has told you which flow matters.

    action "clear" empties the recorded list — do it before driving the app so the flows you then see
    are unambiguously the ones you caused.

    Secrets (Authorization, Cookie, X-Api-Key…) come back as <redacted:32b> unless the user has
    explicitly allowed revealing them. Bodies are truncated at max_body_chars and the answer always
    says when it truncated — never read a short body as a complete one.
""".trimIndent()

private val AWAIT_DESCRIPTION = """
    Block until the app makes a call that matches, instead of sleeping and hoping.

    Use it every time you have just done something that should cause traffic — driven the UI, started
    a test, restarted the app. Sleeping is always wrong here: too short and you read an empty list,
    too long and you waste the session.

    It never fails on timeout. If nothing matched you get satisfied:false plus closest_observed — the
    traffic that DID arrive, grouped by method and path. That field exists because the most common
    mistake is a slightly wrong matcher (path is exact and case-sensitive; url_contains is not), and
    it turns a blind 25-second wait into one round trip that tells you what to write instead.

    wait_ms is clamped to 25000 by the control plane. Pass since_seq from an earlier call so traffic
    that arrived before you started is not counted, and count:2 when you want to observe a retry
    (native Android: see the 500 ms deduplication caveat in mockkhttp_flows).
""".trimIndent()

private val MOCKS_DESCRIPTION = """
    Create and manage persistent MOCK RULES — the primary way to make the app see a fake response,
    and the one you should reach for by default.

    A rule is answered during the app's CHECK_MOCK pre-flight: the app asks before it sends, gets the
    canned response, and never touches the network. That means zero added latency, no pause, no
    agent in the request path, and it works for every request the app makes, forever, until you
    disable the rule. (The alternative — pausing the app in DEBUG mode and answering live — freezes
    the app's thread, is abandoned by the app after 60 seconds, and cannot be answered automatically
    in this milestone at all.)

    Rules only fire in MOCKK or MOCKK_DEBUG mode. Check mockkhttp_status.session.mode first and
    switch with mockkhttp_session(action:"set_mode", mode:"MOCKK") — a perfect rule in RECORDING mode
    does nothing, which is the single most common "my mock is not working".

    The fastest correct way to create one: let the app make the real call, find it with
    mockkhttp_flows, then create with from_flow_id (which clones the URL, method and real response)
    plus loosen_query:true and only the fields you want different, usually response.status_code.
    Building the URL by hand works too but every query parameter you include becomes a REQUIRED,
    EXACT match — so a rule with a timestamp in the URL matches exactly one request and then never
    again.

    To cover a FAMILY of requests, send host and path as their own fields instead of packing them
    into url: url is parsed as a URL and a pattern is not one, which is why a regex hidden inside it
    used to come back INVALID_URL. One rule for every order id is
    host:"api.acme.com", path:"/v1/orders/[0-9]+", path_match:"REGEX" — and in a REGEX host the dots
    must be escaped ((api|beta)\.acme\.com), or they match any character.

    Rules live in collections; if the project has none, one is created for you. Use exclusive:true so
    a new rule disables other enabled rules on the same endpoint — two enabled rules on one endpoint
    is the usual cause of the wrong body coming back. After creating, read will_fire_in_modes and
    warnings in the answer, then prove it with mockkhttp_match_explain.

    Actions: list, get, create, update, set_enabled, delete, list_collections, create_collection,
    delete_collection, export, import (import supports dry_run:true, which reports the merge and
    writes nothing).
""".trimIndent()

private val EXPLAIN_DESCRIPTION = """
    Dry-run the matcher. Given a method and a full URL, answer which mock rule would serve that exact
    request right now — and, for every rule that did not win, precisely why it was rejected: disabled,
    its collection is disabled, a required query parameter did not match, the host or path comparison
    mode is wrong.

    Reach for this the moment a mock "does not fire", BEFORE deleting and recreating it, and again
    right after creating a rule to prove it will actually be used. It changes nothing and costs
    nothing.

    It also reports the current mode and whether the request would skip the network, so it answers
    "will the app really get my fake response?" in one call.
""".trimIndent()

private val SESSION_DESCRIPTION = """
    Read the capture session and change its mode.

    action "get" reports whether capture is running, the mode, the device and the app package being
    filtered. action "clear_flows" empties the recorded flow list.

    action "set_mode" is the important one. RECORDING only captures traffic. MOCKK also answers
    matching mock rules during the app's CHECK_MOCK pre-flight — no network call, no latency, no
    pause — and is the mode to be in for anything you want to be deterministic. DEBUG and MOCKK_DEBUG
    instead PAUSE every request the app makes, background polling included, until somebody answers a
    dialog in the IDE; nothing can answer that automatically in this milestone, so a chatty app will
    simply stall. They are refused unless you also pass confirm_pause_all:true, and you should only
    do that if a human explicitly asked for Debug mode.

    Start a session with action "start" — no arguments when exactly one device has exactly one app
    that has announced itself; otherwise call action "devices" first and pass serial and/or
    package_name. "set_app" re-targets a running session, "stop" ends it, "restart" re-establishes it.
""".trimIndent()

private val DOCS_DESCRIPTION = """
    MockkHttp's own workflow guide, served by the plugin so it always matches the installed version.

    Call it with no topic for the index, or ask for one: quickstart (the shortest useful loop),
    modes (what RECORDING / MOCKK / DEBUG actually do to a request), mocking (how rules are matched
    and why one does not fire), matching (exact vs regex vs contains, and query parameters), flows
    (reading captured traffic), automated_test (driving a test run against mocks), arms_and_ordering
    (what later milestones add and what is not here yet), debug_intercept (what pausing a request
    really costs), troubleshooting (no flows appear, the mock is ignored, the wrong project
    answered), limits (body sizes, wait caps, what this milestone cannot do).

    Read troubleshooting before guessing when something silently does not happen.
""".trimIndent()

/**
 * Served when the control plane cannot be reached at all. Deliberately short: it exists so a model
 * facing a closed IDE can still tell the user what to do, not to duplicate the real guide.
 */
private val OFFLINE_GUIDE = """
    ── MockkHttp, in brief (offline fallback — the plugin's own guide is unreachable) ──

    MockkHttp intercepts a mobile app's HTTP traffic from inside the app (Android/OkHttp, or Flutter
    on Android and iOS) and can answer any request with a canned response, with no proxy and no
    certificate.

    Everything runs through the IDE, so nothing works until Android Studio (or IntelliJ) is open with
    the project and the MockkHttp plugin enabled. Ask the user to:
      1. open the project in Android Studio;
      2. run this agent from the project directory, so the bridge can match the working directory to
         the open project.

    The capture session itself you start: mockkhttp_session {"action":"start"}. The tool window is
    only needed for the one-time setup above.

    Once it answers, the loop is: mockkhttp_status -> mockkhttp_session(start) -> mockkhttp_session(set_mode MOCKK) ->
    mockkhttp_flows to see real traffic -> mockkhttp_mocks(create, from_flow_id) to fake a response ->
    mockkhttp_match_explain to prove the rule fires -> mockkhttp_await_flow to wait for the app to
    call again.
""".trimIndent()
