package com.sergiy.dev.mockkhttp.bridge

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * A failure the model is meant to read and act on. Every one of these becomes an `isError` tool
 * result — never a JSON-RPC error, which a model cannot recover from.
 */
class BridgeException(message: String) : RuntimeException(message)

/** Which project a tool call addresses, and the audit trail of how the bridge chose it. */
data class Scope(
    /** Path segment for `/v1/projects/{pid}`: a locationHash, or a name the control plane resolves. */
    val pid: String,
    val matchedBy: String,
    val cwd: String,
    val instanceId: String?,
    val projectName: String?,
    val basePath: String?,
    val baseUrl: String,
    val ide: String?,
    val pluginVersion: String?,
    val liveInstances: Int,
    /**
     * The `project:` argument that pointed this scope at an IDE window OTHER than the one the
     * working directory resolves to, or null when the cwd window is the right one.
     *
     * Non-null means every HTTP call built from this scope MUST be addressed to that window — see
     * [RestClient.targetFor]. What travels is the ARGUMENT, not the endpoint it resolved to: it can
     * be resolved again after the IDE restarts onto a new port, and it keeps the bearer token out of
     * a structure that is handed back to the model in `mockkhttp_status`.
     */
    val instancePin: String? = null
) {
    /** Folded into `mockkhttp_status` so a model can see WHY it is looking at this project. */
    fun describe(): JsonObject {
        val json = JsonObject()
        json.addProperty("bridge_version", BRIDGE_VERSION)
        json.addProperty("matched_by", matchedBy)
        json.addProperty("cwd", cwd)
        json.addProperty("project", pid)
        projectName?.let { json.addProperty("project_name", it) }
        basePath?.let { json.addProperty("base_path", it) }
        instanceId?.let { json.addProperty("instance_id", it) }
        ide?.let { json.addProperty("ide", it) }
        pluginVersion?.let { json.addProperty("plugin_version", it) }
        json.addProperty("control_base_url", baseUrl)
        json.addProperty("live_ide_instances", liveInstances)
        return json
    }
}

/**
 * Talks to the plugin's loopback control plane (`http://127.0.0.1:<ephemeral>/v1`).
 *
 * Three things are load-bearing here:
 *  · the bearer token and the mandatory `X-MockkHttp-Client` header — the control plane 403s any
 *    request without the latter, which is what keeps a browser (which cannot send a non-simple
 *    header without a preflight) from reaching it;
 *  · one automatic re-resolve + retry on a connection failure or a 401, because the IDE rotates its
 *    token on every start and walks to a new ephemeral port, and the whole point of the discovery
 *    design is that neither of those needs a config change;
 *  · translating the REST error envelope into a sentence that names the next call to make.
 */
class RestClient {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /** Only used when discovery cannot name a project locally (base-url override). */
    @Volatile
    private var remotelyResolved: Scope? = null

    // ── project addressing ────────────────────────────────────────────────────────────────────────

    fun scope(explicitProject: String?): Scope {
        if (!explicitProject.isNullOrBlank()) {
            // An explicit project may live in a DIFFERENT IDE instance than the cwd points at, and
            // the pin has to travel with the scope: describing the second window while building the
            // request against the first one would apply the write to the wrong project and report it
            // as a success, which is the worst answer this bridge can give.
            Discovery.forProject(explicitProject)?.let { retargeted ->
                return scopeOf(
                    retargeted,
                    retargeted.project?.projectId ?: explicitProject,
                    instancePin = explicitProject
                )
            }
            // Nothing claimed it — but our view of the instance files may simply be stale:
            // Discovery caches its resolution, so a window opened after this bridge's first call
            // is invisible until something invalidates. Re-read once before giving up, otherwise
            // a project opened mid-session stays unreachable for the life of the session and the
            // error blames the user for a name that is perfectly correct.
            Discovery.invalidate()
            Discovery.forProject(explicitProject)?.let { retargeted ->
                return scopeOf(
                    retargeted,
                    retargeted.project?.projectId ?: explicitProject,
                    instancePin = explicitProject
                )
            }

            // Still unknown: hand it over as-is. The control plane resolves ids, names and base
            // paths, and knows about projects opened one second ago.
            // No local project matched, so none is named either — reporting the cwd project's name
            // and path next to somebody else's project id is how a model ends up trusting the wrong
            // window. Only the ONE instance we can reach can answer, so there is nothing to pin.
            return scopeOf(target(), explicitProject, project = null, matchedBy = "explicit-arg")
        }

        val target = target()
        target.project?.let { return scopeOf(target, it.projectId) }
        remotelyResolved?.let { return it }
        return resolveOverTheWire(target).also { remotelyResolved = it }
    }

    private fun scopeOf(
        target: Discovery.Target,
        pid: String,
        project: Discovery.ProjectRef? = target.project,
        matchedBy: String = target.matchedBy,
        instancePin: String? = null
    ) = Scope(
        pid = pid,
        matchedBy = matchedBy,
        cwd = target.cwd,
        instanceId = target.instanceId,
        projectName = project?.name,
        basePath = project?.basePath,
        baseUrl = target.baseUrl,
        ide = target.ide,
        pluginVersion = target.pluginVersion,
        liveInstances = target.liveInstances,
        instancePin = instancePin
    )

    /**
     * `MOCKKHTTP_BASE_URL` says nothing about which projects that IDE has open, so ask it. Same
     * longest-prefix rule as the local path, so both routes agree on which project is "here".
     */
    private fun resolveOverTheWire(target: Discovery.Target): Scope {
        target.projectProblem?.let { throw BridgeException(it) }

        val payload = request("GET", "/projects")
        val projects = (payload as? JsonObject)?.optArray("projects") ?: JsonArray()
        val entries = projects.mapNotNull { it as? JsonObject }
        if (entries.isEmpty()) {
            throw BridgeException(
                "The MockkHttp instance at ${target.baseUrl} has no project open. Open the app project " +
                    "in Android Studio and call again."
            )
        }

        val pin = Discovery.projectPin()
        if (pin != null) {
            val pinned = entries.firstOrNull { entry ->
                pin.equals(entry.optString("project_id"), ignoreCase = true) ||
                    pin.equals(entry.optString("name"), ignoreCase = true) ||
                    pin.equals(entry.optString("base_path"), ignoreCase = true)
            }
            if (pinned != null) return remoteScope(target, pinned, "env-pin")
            throw BridgeException(
                "MOCKKHTTP_PROJECT is set to \"$pin\" but that IDE has no such project open: " +
                    entries.joinToString(", ") { it.optString("name") ?: it.optString("project_id") ?: "?" } + "."
            )
        }

        val here = Discovery.normalize(target.cwd)
        var best: JsonObject? = null
        var bestDepth = -1
        if (here != null) {
            for (entry in entries) {
                val base = Discovery.normalize(entry.optString("base_path")) ?: continue
                if (!here.startsWith(base)) continue
                if (base.nameCount > bestDepth) {
                    bestDepth = base.nameCount
                    best = entry
                }
            }
        }
        best?.let { return remoteScope(target, it, "cwd-prefix") }

        entries.singleOrNull()?.let { return remoteScope(target, it, "sole-open-project") }

        throw BridgeException(
            "The working directory ${target.cwd} is not inside any of the projects that IDE has open: " +
                entries.joinToString(", ") {
                    "${it.optString("name") ?: it.optString("project_id")} at ${it.optString("base_path") ?: "unknown path"}"
                } +
                ". Repeat the call with project:\"<name>\"."
        )
    }

    private fun remoteScope(target: Discovery.Target, entry: JsonObject, matchedBy: String) = Scope(
        pid = entry.optString("project_id") ?: entry.optString("name").orEmpty(),
        matchedBy = matchedBy,
        cwd = target.cwd,
        instanceId = target.instanceId,
        projectName = entry.optString("name"),
        basePath = entry.optString("base_path"),
        baseUrl = target.baseUrl,
        ide = target.ide,
        pluginVersion = target.pluginVersion,
        liveInstances = target.liveInstances
    )

    // ── HTTP ──────────────────────────────────────────────────────────────────────────────────────

    fun request(
        method: String,
        path: String,
        query: List<Pair<String, String?>> = emptyList(),
        body: JsonElement? = null,
        timeout: Duration = DEFAULT_TIMEOUT,
        /** The scope [path] was built from. Omitted only by the project-less routes (/docs, /projects). */
        scope: Scope? = null
    ): JsonElement {
        var attempt = 0
        while (true) {
            val target = targetFor(scope)
            val response = try {
                http.send(
                    build(target, method, path, query, body, timeout),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                )
            } catch (e: HttpTimeoutException) {
                throw BridgeException(
                    "MockkHttp did not answer within ${timeout.toSeconds()}s (${e.javaClass.simpleName}). " +
                        "The IDE may be busy indexing, or a modal dialog is blocking its UI thread. Retry once; " +
                        "if it keeps happening, ask the user to check the MockkHttp Logs tab."
                )
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw BridgeException("The call to MockkHttp was interrupted before it completed.")
            } catch (e: IOException) {
                // Almost always an IDE that restarted onto a new port. Re-read the instance file once.
                Discovery.invalidate()
                remotelyResolved = null
                if (attempt++ == 0) continue
                throw BridgeException(unreachable(target, e))
            }

            if (response.statusCode() == 401 && attempt++ == 0) {
                // Tokens rotate on every IDE start and on Revoke; the new one is already on disk.
                Discovery.invalidate()
                remotelyResolved = null
                continue
            }

            warnOnApiVersion(response)

            val text = response.body() ?: ""
            if (response.statusCode() in 200..299) {
                // A 200 carries advice too — `hint` on a session start, `warnings` on a rule created
                // in RECORDING mode — written in REST routes just like an error's. Translating only
                // the error branch (audit finding P) taught a model that MockkHttp speaks tool calls
                // and then handed it "POST /v1/projects/…/session/mode" on the very next success.
                return if (text.isBlank()) JsonObject() else toolSpeakPayload(parse(text))
            }
            throw BridgeException(explain(response.statusCode(), text, target))
        }
    }

    private fun build(
        target: Discovery.Target,
        method: String,
        path: String,
        query: List<Pair<String, String?>>,
        body: JsonElement?,
        timeout: Duration
    ): HttpRequest {
        val builder = HttpRequest.newBuilder(uri(target.baseUrl, path, query))
            .timeout(timeout)
            .header("Authorization", "${target.scheme} ${target.token}")
            // Mandatory: a request without it is refused before authentication (browser lockout).
            .header("X-MockkHttp-Client", CLIENT_HEADER)
            .header("Accept", "application/json")
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        } else {
            builder.header("Content-Type", "application/json")
            builder.method(method, HttpRequest.BodyPublishers.ofString(WIRE.toJson(body), StandardCharsets.UTF_8))
        }
        return builder.build()
    }

    private fun uri(baseUrl: String, path: String, query: List<Pair<String, String?>>): URI {
        val text = StringBuilder(baseUrl).append(path)
        var first = true
        for ((key, value) in query) {
            if (value == null) continue
            text.append(if (first) '?' else '&')
            first = false
            text.append(encode(key)).append('=').append(encode(value))
        }
        return URI.create(text.toString())
    }

    private fun target(): Discovery.Target = when (val resolution = Discovery.current()) {
        is Discovery.Resolution.Found -> resolution.target
        is Discovery.Resolution.Missing -> throw BridgeException(resolution.message)
    }

    /**
     * Which IDE this call is built against: the window the working directory resolves to, unless the
     * scope was re-pointed by an explicit `project:` argument, in which case that window and no other.
     *
     * Re-resolved on every attempt rather than captured once in the Scope, so the retry that follows
     * an IDE restart lands on the SAME project at its new port with its new token.
     */
    private fun targetFor(scope: Scope?): Discovery.Target {
        val pin = scope?.instancePin ?: return target()
        Discovery.forProject(pin)?.let { return it }

        // The window that had it open has gone since the scope was resolved. Falling back to the cwd
        // window is precisely the bug this method exists to prevent — a write applied to a project
        // nobody asked about, reported as a success — so refuse, and say that nothing was sent.
        val resolution = Discovery.current()
        if (resolution is Discovery.Resolution.Missing) throw BridgeException(resolution.message)
        throw BridgeException(
            "No open project matches project:\"$pin\" any more, so this call was NOT sent and nothing was " +
                "written: the IDE window that had it open has closed, or its agent control was switched off. " +
                "Call mockkhttp_status to see what is open now, or reopen that project in Android Studio."
        )
    }

    private fun parse(text: String): JsonElement = try {
        JsonParser.parseString(text)
    } catch (t: Throwable) {
        throw BridgeException(
            "MockkHttp answered with something that is not JSON (${t.javaClass.simpleName}). " +
                "First 300 characters: ${text.take(300)}"
        )
    }

    private fun warnOnApiVersion(response: HttpResponse<String>) {
        val version = response.headers().firstValue("X-MockkHttp-Api-Version").orElse(null) ?: return
        if (!version.startsWith("1.")) {
            System.err.println("[mockkhttp-mcp] control plane speaks API $version; this bridge was built against 1.x")
        }
    }

    private fun unreachable(target: Discovery.Target, cause: IOException): String =
        // ConnectException carries a null message on most JDKs; the class name alone is the signal.
        "MockkHttp is not answering at ${target.baseUrl} (${cause.javaClass.simpleName}" +
            (cause.message?.let { ": $it" } ?: "") + "). " +
            "The IDE that published that address has been closed, or agent control was switched off. " +
            "Ask the user to open the project in Android Studio with MockkHttp enabled — this bridge re-reads " +
            "~/.mockkhttp/instances on every call that fails, so a restarted IDE needs no configuration change."

    /**
     * Rewrite REST routes in a hint into the tool calls an MCP client can actually make.
     *
     * The control plane's best design idea is that the error carries the next call inside it — but
     * it is written for an HTTP caller, and a model on the other side of this bridge has no HTTP
     * client. "POST /v1/projects/{pid}/session/start" is a dead end for it; the same hint pointing
     * at mockkhttp_session is one call away. The plugin stays honest for REST users; the
     * translation belongs here, where the audience is known.
     *
     * A query string glued to a route (`…/flows?limit=25`, `…/devices?scan=true`) becomes arguments
     * of the tool call instead of a `?limit=25` dangling off a JSON object — the parameter is the
     * useful half of such a hint, so it must survive the rewrite.
     */
    internal fun toolSpeak(hint: String): String {
        var out = hint
        for ((pattern, replacement) in ROUTE_TO_TOOL) {
            out = pattern.replace(out, replacement)
        }
        return foldQueryIntoArguments(out)
    }

    /**
     * [toolSpeak] applied to every advisory field of a payload — `hint`, `warnings`, `note` — at
     * any depth, in place. Nothing else is touched: a captured body or a header value is data, and
     * rewriting a route that happens to appear inside one would falsify what the app sent.
     */
    internal fun toolSpeakPayload(payload: JsonElement): JsonElement {
        when {
            payload.isJsonObject -> {
                val obj = payload.asJsonObject
                for (key in obj.keySet().toList()) {
                    val value = obj.get(key)
                    when (key) {
                        in ADVISORY_KEYS -> obj.add(key, translateAdvisory(value))
                        // A header map carries names the APP chose. One spelled "hint" or "note" is
                        // still a header, and its value is what was on the wire.
                        in OPAQUE_KEYS -> Unit
                        else -> toolSpeakPayload(value)
                    }
                }
            }
            payload.isJsonArray -> payload.asJsonArray.forEach { toolSpeakPayload(it) }
        }
        return payload
    }

    private fun translateAdvisory(value: JsonElement): JsonElement = when {
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> JsonPrimitive(toolSpeak(value.asString))
        value.isJsonArray -> JsonArray().also { out -> value.asJsonArray.forEach { out.add(translateAdvisory(it)) } }
        else -> value
    }

    /**
     * `mockkhttp_flows {"action":"list"}?limit=25` → `mockkhttp_flows {"action":"list","limit":25}`.
     * Runs after the route table so it only ever sees tool calls it produced.
     */
    private fun foldQueryIntoArguments(text: String): String = CALL_WITH_QUERY.replace(text) { match ->
        val tool = match.groupValues[1]
        val existing = match.groupValues[2].trim().removePrefix("{").removeSuffix("}").trim()
        val fields = match.groupValues[3].split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) null else "\"${jsonEscape(decode(pair.substring(0, eq)))}\":${jsonValue(decode(pair.substring(eq + 1)))}"
        }
        val arguments = (listOf(existing).filter { it.isNotEmpty() } + fields).joinToString(",")
        "$tool {$arguments}"
    }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8)
    }.getOrDefault(value)

    private fun jsonValue(value: String): String = when {
        value == "true" || value == "false" -> value
        NUMBER.matches(value) -> value
        else -> "\"${jsonEscape(value)}\""
    }

    private fun jsonEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * The REST envelope is `{"error":{"code","message","hint","retryable","details"}}`. `hint` is
     * written by the control plane and names the exact next call, so it always wins over anything
     * this bridge could guess; the fallback only fires when the server had nothing to add.
     */
    private fun explain(status: Int, text: String, target: Discovery.Target): String {
        val error = runCatching { JsonParser.parseString(text) as? JsonObject }
            .getOrNull()
            ?.optObject("error")
        val code = error?.optString("code")
        val message = error?.optString("message")
        val hint = error?.optString("hint")
        val retryable = error?.optBoolean("retryable")

        val lines = ArrayList<String>()
        lines += "MockkHttp refused this call (HTTP $status${if (code != null) ", $code" else ""})."
        lines += message ?: text.take(400).ifBlank { "The control plane returned no explanation." }
        lines += "Next: " + (hint?.let { toolSpeak(it) } ?: nextStep(code, status, target))
        if (retryable == true) lines += "This one is retryable: the same call can succeed later."
        return lines.joinToString("\n")
    }

    private fun nextStep(code: String?, status: Int, target: Discovery.Target): String = when (code) {
        "PROJECT_NOT_FOUND", "AMBIGUOUS_PROJECT" ->
            "Call mockkhttp_status to see which projects this IDE has open, then repeat with project:\"<name>\"."
        "PROJECT_DISPOSED" ->
            "That project was closed in the IDE. Call mockkhttp_status to see what is open now."
        "SESSION_NOT_RUNNING" ->
            "Nothing is capturing. Start a session yourself: mockkhttp_session {\"action\":\"start\"}, " +
                "which needs no arguments when one device has one instrumented app. If it comes back " +
                "AMBIGUOUS_DEVICE or AMBIGUOUS_APP, call mockkhttp_session {\"action\":\"devices\"} and pass " +
                "the serial and/or package_name you want."
        "UNKNOWN_FLOW" ->
            "Captured flows live in a bounded ring and that one has been evicted. Re-list with " +
                "mockkhttp_flows {\"action\":\"list\"} and use a flow_id from that answer."
        "UNKNOWN_RULE", "UNKNOWN_COLLECTION" ->
            "List what exists (mockkhttp_mocks with action \"list\" or \"list_collections\") and use an id from it."
        "REVEAL_DISABLED" ->
            "Secrets stay redacted unless the user turns on MockkHttp -> Settings -> Allow agent to reveal " +
                "secrets. Repeat the call without include_secrets."
        "READ_ONLY" ->
            "Agent control is read-only, so reads work and writes do not. Ask the user to set MockkHttp -> " +
                "Settings -> AI Agent Access to Full."
        "PAUSE_POLICY_REQUIRED" ->
            "DEBUG and MOCKK_DEBUG pause EVERY request the app makes, including background polling, and nothing " +
                "can answer that pause in this milestone. Stay in MOCKK and use mock rules instead."
        "RATE_LIMITED", "TOO_MANY_WAITERS" ->
            "Wait a couple of seconds and retry once. Do not loop."
        "INVALID_ARGUMENT", "MALFORMED_JSON", "INVALID_URL", "INVALID_REGEX", "COLLECTION_REQUIRED" ->
            "Fix the argument named in the message above and call again."
        "NOT_IMPLEMENTED", "UNSUPPORTED_ACTION", "NOT_SUPPORTED_ON_PLATFORM" ->
            "That action is not in this MockkHttp build. Call mockkhttp_docs for what it can do."
        "INTERCEPTOR_PORT_IN_USE" ->
            "Another IDE process owns port 9876, so nothing can be captured until it is closed."
        "PAYLOAD_TOO_LARGE" ->
            "Send a smaller body (the control plane caps requests at 8 MB)."
        else -> when (status) {
            401 -> "The bearer token was rejected even after re-reading ~/.mockkhttp/instances. Ask the user to " +
                "restart the IDE, or check MockkHttp -> Settings -> AI Agent Access."
            403 -> "The control plane refused the caller (loopback, browser lockout or read-only mode). " +
                "Check MockkHttp -> Settings -> AI Agent Access at ${target.baseUrl}."
            404 -> "That route does not exist in this plugin build; the bridge jar and the plugin are out of step. " +
                "Update MockkHttp and restart the IDE."
            else -> "Read the message above; if it is empty, the MockkHttp Logs tab in the IDE has the detail."
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    companion object {
        /** Generous enough for a busy IDE, short enough that a wedged one is reported, not waited on. */
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(20)

        /** await_flow is a server-side long poll capped at 25 s; leave it room to answer. */
        val LONG_POLL_TIMEOUT: Duration = Duration.ofSeconds(45)
    }
}

/** Payload fields that carry advice for the caller rather than data about the app. */
private val ADVISORY_KEYS: Set<String> = setOf("hint", "warnings", "note", "markdown")

/** Subtrees that are captured or user-authored data, whatever their keys are called. */
private val OPAQUE_KEYS: Set<String> = setOf("headers")

/**
 * A tool call [toolSpeak] produced, with a query string still glued to it. The query ends before
 * whitespace, a quote or a bracket — and before the full stop, comma or semicolon that ends the
 * sentence it sits in, which is punctuation, not part of the last value.
 */
private val CALL_WITH_QUERY = Regex("""(mockkhttp_[a-z_]+)(\s*\{[^}]*\})?\?([^\s`"')]*[^\s`"').,;])""")

private val NUMBER = Regex("""-?\d+(?:\.\d+)?""")

private const val VERB = """(?:POST|GET|PUT|PATCH|DELETE)"""
/**
 * The project prefix, in both spellings the control plane writes it: the real one and the
 * abbreviated `…/route` many hints use. The abbreviation used to reach only the bare-spelling
 * fallbacks, which left `POST …/` glued in front of the tool call and dropped the argument.
 */
private const val PID = """(?:/v1/projects/[^/\s]+|…|\.\.\.)"""

/**
 * One path segment that names a resource: a rule id, a flow id, a collection id, or a placeholder
 * such as `{rule_id}`. Never one of the fixed sub-routes, which are matched by name above it, and
 * never the full stop that ends the sentence the route sits in.
 */
private const val ID = """(?!(?:rules|collections|explain|export|import|await|clear)(?:[/\s?`"',;.)]|$))([^/\s?`"',;)]*[^/\s?`"',;).])"""

/**
 * REST route → tool call, most specific first. Deliberately a small, explicit table rather than a
 * clever parser: a wrong rewrite would send a model somewhere that does not exist, which is worse
 * than leaving the route visible.
 *
 * `$1` is the resource id captured by [ID], carried into the call as the argument the tool takes.
 */
private val ROUTE_TO_TOOL: List<Pair<Regex, String>> = listOf(
    // ── session ──
    Regex("""$VERB\s+$PID/session/start""") to """mockkhttp_session {"action":"start"}""",
    Regex("""$VERB\s+$PID/session/stop""") to """mockkhttp_session {"action":"stop"}""",
    Regex("""$VERB\s+$PID/session/restart""") to """mockkhttp_session {"action":"restart"}""",
    Regex("""$VERB\s+$PID/session/devices?""") to """mockkhttp_session {"action":"devices"}""",
    Regex("""$VERB\s+$PID/session/(?:app|set_app|package|package_filter)""")
        to """mockkhttp_session {"action":"set_app","package_name":"…"}""",
    // No example mode here: every hint that names this route says "with mode:'MOCKK'" right
    // after it, and the call would otherwise read the mode twice.
    Regex("""$VERB\s+$PID/session/mode""") to """mockkhttp_session {"action":"set_mode"}""",
    Regex("""$VERB\s+$PID/session""") to """mockkhttp_session {"action":"get"}""",
    // The bare spelling is the one the status and devices hints actually write.
    Regex("""$VERB\s+$PID/devices?""") to """mockkhttp_session {"action":"devices"}""",
    Regex("""$VERB\s+$PID/(?:app|set_app|package|package_filter)""")
        to """mockkhttp_session {"action":"set_app","package_name":"…"}""",
    // ── mocks ──
    Regex("""$VERB\s+$PID/mocks/explain""") to """mockkhttp_match_explain""",
    Regex("""$VERB\s+$PID/mocks/export""") to """mockkhttp_mocks {"action":"export"}""",
    Regex("""$VERB\s+$PID/mocks/import""") to """mockkhttp_mocks {"action":"import"}""",
    Regex("""$VERB\s+$PID/mocks/collections/$ID""")
        to """mockkhttp_mocks {"action":"delete_collection","collection_id":"$1"}""",
    Regex("""GET\s+$PID/mocks/collections""") to """mockkhttp_mocks {"action":"list_collections"}""",
    Regex("""$VERB\s+$PID/mocks/collections""") to """mockkhttp_mocks {"action":"create_collection"}""",
    Regex("""$VERB\s+$PID/mocks/(?:rules/)?$ID/enabled?""")
        to """mockkhttp_mocks {"action":"set_enabled","rule_id":"$1"}""",
    Regex("""GET\s+$PID/mocks/(?:rules/)?$ID""") to """mockkhttp_mocks {"action":"get","rule_id":"$1"}""",
    Regex("""DELETE\s+$PID/mocks/(?:rules/)?$ID""") to """mockkhttp_mocks {"action":"delete","rule_id":"$1"}""",
    Regex("""(?:PUT|PATCH|POST)\s+$PID/mocks/(?:rules/)?$ID""")
        to """mockkhttp_mocks {"action":"update","rule_id":"$1"}""",
    Regex("""POST\s+$PID/mocks(?:/rules)?""") to """mockkhttp_mocks {"action":"create"}""",
    Regex("""$VERB\s+$PID/mocks(?:/rules)?""") to """mockkhttp_mocks {"action":"list"}""",
    // ── flows ──
    Regex("""$VERB\s+$PID/flows/await""") to """mockkhttp_await_flow""",
    Regex("""DELETE\s+$PID/flows""") to """mockkhttp_session {"action":"clear_flows"}""",
    Regex("""$VERB\s+$PID/flows/$ID""") to """mockkhttp_flows {"action":"get","flow_id":"$1"}""",
    Regex("""$VERB\s+$PID/flows""") to """mockkhttp_flows {"action":"list"}""",
    Regex("""$VERB\s+$PID/status""") to """mockkhttp_status""",
    // ── routes without a project ──
    // /v1/docs keeps its topic through the query folding below. There is no tool that lists
    // projects: status answers with the resolved one in `resolution` and the rest in
    // `other_open_projects`, and meta's capabilities travel in status too.
    Regex("""$VERB\s+/v1/docs""") to """mockkhttp_docs""",
    Regex("""$VERB\s+/v1/projects(?![/\w])""") to """mockkhttp_status""",
    Regex("""$VERB\s+/v1/meta""") to """mockkhttp_status""",
    // ── bare spellings, as several hints write them ──
    Regex("""\bsession/mode\b""") to """mockkhttp_session {"action":"set_mode"}""",
    Regex("""\bsession/start\b""") to """mockkhttp_session {"action":"start"}""",
    Regex("""\bsession/stop\b""") to """mockkhttp_session {"action":"stop"}""",
    Regex("""\bsession/restart\b""") to """mockkhttp_session {"action":"restart"}""",
    Regex("""\bsession/devices\b""") to """mockkhttp_session {"action":"devices"}""",
    Regex("""\bsession/app\b""") to """mockkhttp_session {"action":"set_app"}"""
)
