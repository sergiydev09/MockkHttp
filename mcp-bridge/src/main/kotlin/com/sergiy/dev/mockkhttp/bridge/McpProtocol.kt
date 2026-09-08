package com.sergiy.dev.mockkhttp.bridge

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Server name the MCP client shows next to the tool list. */
const val BRIDGE_NAME: String = "mockkhttp"

/**
 * Read out of the jar manifest (`Implementation-Version`, set by `:mcp-bridge:fatJar`) so the bridge
 * reports the plugin version it shipped with. Falls back to "dev" when running from class files.
 */
val BRIDGE_VERSION: String =
    McpProtocol::class.java.`package`?.implementationVersion ?: "dev"

/** Value of the mandatory `X-MockkHttp-Client` header — the control plane rejects requests without it. */
val CLIENT_HEADER: String = "mockkhttp-mcp-bridge/$BRIDGE_VERSION"

/**
 * The one Gson used on the wire. HTML escaping OFF: without it every `=`, `<` and `&` in a captured
 * URL comes back as `=`, which is valid JSON and unreadable to a model. Nulls ON: JSON-RPC
 * requires a literal `"id": null` on a parse error, and dropping it produces a frame some clients
 * reject outright.
 */
val WIRE: Gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()

// ── Protocol revisions ────────────────────────────────────────────────────────────────────────────
//
// Two eras have to work, because the client picks, not us:
//
//  · up to and including 2025-11-25 — `initialize` + `notifications/initialized` handshake, the
//    negotiated version lives in the session.
//  · 2026-07-28 (SEP-2575, "stateless MCP") — the handshake, the session and the GET stream are
//    GONE. Capabilities are fetched with `server/discover`, the protocol version and client info
//    travel in `params._meta` on EVERY request, and every result MUST carry `resultType`.
//
// Serving both is cheap: answer `initialize` AND `server/discover`, and put `resultType:"complete"`
// on every result — older clients treat unknown result fields as pass-through data.

const val PROTOCOL_2026_07_28: String = "2026-07-28"
const val PROTOCOL_2025_11_25: String = "2025-11-25"

/** Newest first. Advertised verbatim in `server/discover.supportedVersions`. */
val SUPPORTED_PROTOCOL_VERSIONS: List<String> = listOf(
    PROTOCOL_2026_07_28,
    PROTOCOL_2025_11_25,
    "2025-06-18",
    "2025-03-26",
    "2024-11-05"
)

/** `_meta` key carrying the protocol version on every 2026-07-28 request. */
const val META_PROTOCOL_VERSION: String = "io.modelcontextprotocol/protocolVersion"

/** `_meta` key carrying client identity on every 2026-07-28 request (was `initialize.clientInfo`). */
const val META_CLIENT_INFO: String = "io.modelcontextprotocol/clientInfo"

/** `_meta` key carrying server identity in a `server/discover` result. */
const val META_SERVER_INFO: String = "io.modelcontextprotocol/serverInfo"

// JSON-RPC 2.0 error codes.
const val PARSE_ERROR: Int = -32700
const val INVALID_REQUEST: Int = -32600
const val METHOD_NOT_FOUND: Int = -32601
const val INVALID_PARAMS: Int = -32602
const val RPC_INTERNAL_ERROR: Int = -32603

/** MCP-specific: the client asked for a protocol revision the server cannot speak (SEP-2575). */
const val UNSUPPORTED_PROTOCOL_VERSION: Int = -32022

/**
 * What the model is told about MockkHttp before it sees a single tool. Kept short on purpose: the
 * detail lives in the tool descriptions and in `mockkhttp_docs`.
 */
private val INSTRUCTIONS: String = """
    MockkHttp captures and fakes the HTTP traffic of a mobile app under test (native Android/OkHttp,
    or Flutter on Android and iOS) from INSIDE the app — no proxy and no certificate involved.

    Always call mockkhttp_status first: it names the IDE, the project resolved from the working
    directory, whether a capture session is running and which mode it is in. Nothing is captured
    while no session runs, and mock rules only answer in MOCKK / MOCKK_DEBUG mode.

    Doctrine: a MOCK RULE (mockkhttp_mocks) is answered during the app's CHECK_MOCK pre-flight, so
    the app skips the network entirely and the answer costs zero agent latency — that is the tool to
    reach for. Live interception (DEBUG) instead pauses the app's thread until somebody answers a
    dialog; it is expensive, capped by the app's own 60 s timeout, and is for exploration only.

    Wait for traffic with mockkhttp_await_flow, never with sleep.
""".trimIndent()

/** A JSON-RPC parse-error response, with a null id as the spec requires when the id is unknowable. */
fun parseErrorResponse(detail: String): JsonObject {
    val error = JsonObject()
    error.addProperty("code", PARSE_ERROR)
    error.addProperty("message", "Invalid JSON: $detail")
    val response = JsonObject()
    response.addProperty("jsonrpc", "2.0")
    response.add("id", com.google.gson.JsonNull.INSTANCE)
    response.add("error", error)
    return response
}

/**
 * Dispatches one JSON-RPC message. Returns the response, or `null` for a notification (which must
 * never be answered — an answer to a notification is a protocol violation some clients abort on).
 *
 * Nothing in here throws: an unexpected failure becomes a JSON-RPC error response, and a failure
 * inside a tool becomes an `isError` RESULT, which is the only shape a model can self-correct from.
 */
class McpProtocol(private val tools: Tools = Tools()) {

    /** Last protocol version the client mentioned. Diagnostic only — the tool surface is identical. */
    @Volatile
    private var negotiatedVersion: String = PROTOCOL_2025_11_25

    fun handle(message: JsonObject): JsonElement? {
        val id = message.get("id")?.takeIf { !it.isJsonNull }
        val method = message.get("method")?.takeIf { it.isJsonPrimitive }?.asString
        val params = message.get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

        if (method == null) {
            return if (id == null) null else errorResponse(id, INVALID_REQUEST, "Not a JSON-RPC request: no \"method\".")
        }

        rememberProtocolVersion(params)

        // No id ⇒ notification. That covers notifications/initialized, notifications/cancelled and
        // anything a future revision adds: acknowledge by staying silent.
        if (id == null) return null

        return try {
            when (method) {
                "initialize" -> resultResponse(id, initializeResult(params))
                "server/discover" -> resultResponse(id, discoverResult())
                "ping" -> resultResponse(id, JsonObject())
                "tools/list" -> resultResponse(id, toolsListResult())
                "tools/call" -> callTool(id, params)
                else -> errorResponse(
                    id,
                    METHOD_NOT_FOUND,
                    "MockkHttp's bridge implements tools only (initialize, server/discover, ping, " +
                        "tools/list, tools/call). It serves no resources, prompts or sampling, so " +
                        "\"$method\" has no meaning here."
                )
            }
        } catch (t: Throwable) {
            errorResponse(id, RPC_INTERNAL_ERROR, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * 2026-07-28 sends the negotiated version on every request instead of a handshake. Unknown
     * versions are ACCEPTED rather than answered with -32022: this bridge's surface (tools/list +
     * tools/call) has been stable across every revision, and rejecting a version we simply have not
     * heard of yet would take the whole server down for no benefit.
     */
    private fun rememberProtocolVersion(params: JsonObject) {
        val meta = params.get("_meta")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        val version = meta.get(META_PROTOCOL_VERSION)?.takeIf { it.isJsonPrimitive }?.asString ?: return
        if (version.isNotBlank() && version != negotiatedVersion) {
            negotiatedVersion = version
            if (version !in SUPPORTED_PROTOCOL_VERSIONS) {
                System.err.println("[mockkhttp-mcp] client speaks $version, which this bridge does not know; serving tools anyway")
            }
        }
    }

    private fun initializeResult(params: JsonObject): JsonObject {
        val requested = params.get("protocolVersion")?.takeIf { it.isJsonPrimitive }?.asString
        if (!requested.isNullOrBlank()) negotiatedVersion = requested

        val result = JsonObject()
        // Echo what the client asked for. Downgrading an unknown-but-newer version would make the
        // client re-negotiate for a surface that does not actually differ.
        result.addProperty("protocolVersion", requested?.takeIf { it.isNotBlank() } ?: PROTOCOL_2025_11_25)
        result.add("capabilities", capabilities())
        result.add("serverInfo", serverInfo())
        result.addProperty("instructions", INSTRUCTIONS)
        return result
    }

    /** SEP-2575 replacement for `initialize`. Stateless: it may be called at any time, or never. */
    private fun discoverResult(): JsonObject {
        val result = JsonObject()
        val versions = JsonArray()
        SUPPORTED_PROTOCOL_VERSIONS.forEach { versions.add(it) }
        result.add("supportedVersions", versions)
        result.add("capabilities", capabilities())
        // The SEP puts serverInfo at the top level, the architecture guide shows it under _meta.
        // Both are additive, so emit both and let either reader find it.
        result.add("serverInfo", serverInfo())
        val meta = JsonObject()
        meta.add(META_SERVER_INFO, serverInfo())
        result.add("_meta", meta)
        result.addProperty("instructions", INSTRUCTIONS)
        return result
    }

    private fun capabilities(): JsonObject {
        val tools = JsonObject()
        // The tool set is baked into the jar, so it cannot change while the process lives.
        tools.addProperty("listChanged", false)
        val capabilities = JsonObject()
        capabilities.add("tools", tools)
        return capabilities
    }

    private fun serverInfo(): JsonObject {
        val info = JsonObject()
        info.addProperty("name", BRIDGE_NAME)
        info.addProperty("title", "MockkHttp")
        info.addProperty("version", BRIDGE_VERSION)
        return info
    }

    private fun toolsListResult(): JsonObject {
        val result = JsonObject()
        result.add("tools", tools.definitions())
        return result
    }

    private fun callTool(id: JsonElement, params: JsonObject): JsonElement {
        val name = params.get("name")?.takeIf { it.isJsonPrimitive }?.asString
            ?: return errorResponse(id, INVALID_PARAMS, "tools/call needs a \"name\".")

        val argumentsElement = params.get("arguments")
        val arguments = when {
            argumentsElement == null || argumentsElement.isJsonNull -> JsonObject()
            argumentsElement.isJsonObject -> argumentsElement.asJsonObject
            else -> return errorResponse(id, INVALID_PARAMS, "\"arguments\" must be an object, got ${argumentsElement}.")
        }

        // Unknown tool / schema violation is a protocol error (-32602); everything else — including
        // "the IDE is not running" — is a normal result with isError, because a model can recover
        // from a result and cannot recover from a JSON-RPC error.
        if (!tools.has(name)) {
            return errorResponse(
                id,
                INVALID_PARAMS,
                "Unknown tool \"$name\". This server provides: ${tools.names().joinToString(", ")}."
            )
        }

        val outcome = tools.call(name, arguments)

        val content = JsonArray()
        val text = JsonObject()
        text.addProperty("type", "text")
        text.addProperty("text", outcome.text)
        content.add(text)

        val result = JsonObject()
        result.add("content", content)
        result.addProperty("isError", outcome.isError)
        return resultResponse(id, result)
    }

    private fun resultResponse(id: JsonElement, result: JsonObject): JsonObject {
        // Mandatory from 2026-07-28 ("complete" vs "input_required"); earlier revisions ignore it.
        if (!result.has("resultType")) result.addProperty("resultType", "complete")
        val response = JsonObject()
        response.addProperty("jsonrpc", "2.0")
        response.add("id", id)
        response.add("result", result)
        return response
    }

    private fun errorResponse(id: JsonElement, code: Int, message: String): JsonObject {
        val error = JsonObject()
        error.addProperty("code", code)
        error.addProperty("message", message)
        val response = JsonObject()
        response.addProperty("jsonrpc", "2.0")
        response.add("id", id)
        response.add("error", error)
        return response
    }
}
