package com.sergiy.dev.mockkhttp.bridge

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

// ── Lenient JSON accessors ────────────────────────────────────────────────────────────────────────
//
// The bridge deliberately never mirrors the plugin's DTOs: it forwards whatever the control plane
// answered. That keeps a new field in ApiDtos.kt from needing a bridge release, but it means every
// read has to survive a missing key, a null, or a value of the wrong type.

internal fun JsonObject.optString(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString

internal fun JsonObject.optInt(key: String): Int? =
    get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

internal fun JsonObject.optLong(key: String): Long? =
    get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }

internal /**
 * A boolean argument, or null when the key is absent OR is not really a boolean.
 *
 * Gson's `asBoolean` falls back to `Boolean.parseBoolean(asString())` for a non-boolean primitive,
 * which answers **false** for `1`, `"1"`, `"yes"` and `"on"`. Callers treat null as "not supplied"
 * and say so, but they act on false — so a model writing `enabled: 1` would have silently DISABLED
 * a rule while believing it enabled one. Only a real boolean, or the exact strings "true"/"false",
 * count here; everything else is reported as missing.
 */
fun JsonObject.optBoolean(key: String): Boolean? {
    val primitive = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
    if (primitive.isBoolean) return primitive.asBoolean
    if (!primitive.isString) return null
    return when (primitive.asString.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

internal fun JsonObject.optObject(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject.optArray(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray

/**
 * Turns a control-plane answer into the text a model reads.
 *
 * The rule this file exists to enforce: **nothing is ever removed silently.** A response body of
 * 400 kB or a list of 800 flows would otherwise blow the context window, so oversized answers are
 * shortened in three widening steps — long strings first (bodies are almost always the problem),
 * then long arrays, then a hard cut as the last resort — and every single reduction adds a line
 * saying exactly what was dropped and which call retrieves it. A model that cannot tell truncated
 * data from complete data will happily assert that a field is absent when it was merely cut.
 */
object Format {

    /** Same 20 000 the control plane uses for bodies, so both ends shorten at the same scale. */
    const val MAX_OUTPUT_CHARS: Int = 20_000

    /** Per-string ceiling before elision. Big enough for a readable JSON payload, small enough that
     *  half a dozen of them still fit inside [MAX_OUTPUT_CHARS]. */
    private const val MAX_STRING_CHARS: Int = 2_000

    /** Array sizes tried in order once string elision alone is not enough. */
    private val ARRAY_CAPS: IntArray = intArrayOf(25, 10, 5, 3, 1)

    /**
     * Appended to the key of any string this renderer shortened, e.g. `body_truncated_by_bridge`.
     * Named for WHO cut it, so it can never be confused with the control plane's own
     * `body_truncated` / `body_truncated_by_retention`.
     */
    private const val BRIDGE_TRUNCATION_SUFFIX: String = "_truncated_by_bridge"

    /** Top-level or root path segments that mean "this answer is about mock rules" / "…flows". */
    private val RULE_ROOTS: Set<String> = setOf("rule", "rules")
    private val FLOW_ROOTS: Set<String> = setOf("flow", "flows", "bodies")

    private val PRETTY: Gson = GsonBuilder()
        .setPrettyPrinting()
        // Without this every '=' and '&' in a captured URL is escaped to = / &.
        .disableHtmlEscaping()
        // A null field is an ANSWER, not an absence. Dropping them silently deleted documented
        // fields on their way to the model: `mode: null` from mocks/explain — the field that says
        // "your rule is inert because the session is in RECORDING", which the docs call the number
        // one failure — plus most of session/get. The model then cannot distinguish "the server did
        // not report this" from "the server reported it as null", and the docs promise the server
        // never silently drops anything. McpProtocol.WIRE already serializes nulls; this renderer
        // has to agree with it.
        .serializeNulls()
        .create()

    fun render(payload: JsonElement): String {
        val direct = PRETTY.toJson(payload)
        if (direct.length <= MAX_OUTPUT_CHARS) return direct

        // Step 1 — elide long strings. One 300 kB response body is the usual reason we are here.
        var elisions = ArrayList<Elision>()
        var text = PRETTY.toJson(elideStrings(payload, "", elisions))
        var notes: List<String> = shortenNotes(describe(elisions)) + retrievalHints(payload, elisions)

        // Step 2 — also cap arrays. A chatty app produces hundreds of flow summaries.
        //
        // Each attempt starts again from the ORIGINAL payload: capping first and eliding second is
        // what keeps the notes honest, since a note about flows[39].body would otherwise describe an
        // entry that this very step then removed.
        if (text.length + notesLength(notes) > MAX_OUTPUT_CHARS) {
            for (cap in ARRAY_CAPS) {
                val arrayNotes = ArrayList<String>()
                val capped = capArrays(payload, cap, "", arrayNotes)
                if (arrayNotes.isNotEmpty()) {
                    arrayNotes += "Ask for less rather than reading the tail: narrow the filter " +
                        "(host, path_contains, status_min), lower limit, or page with since_seq."
                }
                elisions = ArrayList()
                text = PRETTY.toJson(elideStrings(capped, "", elisions))
                // Hints are appended AFTER shortening: they name the call that retrieves what was
                // cut, and they used to be the first thing dropped once there were more than eight
                // reductions — exactly the answer that needed them most.
                notes = shortenNotes(describe(elisions) + arrayNotes) + retrievalHints(payload, elisions)
                if (text.length + notesLength(notes) <= MAX_OUTPUT_CHARS) break
            }
        }

        // Step 3 — the answer is one enormous scalar or a pathological shape. Cut, and say so loudly.
        val budget = MAX_OUTPUT_CHARS - notesLength(notes)
        if (text.length > budget) {
            val keep = maxOf(budget, 500)
            val dropped = text.length - keep
            text = text.take(keep)
            notes = notes + ("THE JSON ABOVE IS CUT OFF after $keep characters ($dropped more removed), " +
                "so it no longer parses. Do not treat a missing field as absent — ask for less data " +
                "(a narrower filter, a smaller limit, or include_body:\"none\") and call again.")
        }

        return text + notesBlock(notes)
    }

    /** Plain text answers (errors, hints) go out untouched unless they are themselves enormous. */
    /**
     * Render a payload that is only useful WHOLE, with no truncation at any size.
     *
     * The widening truncation in [render] is right for everything the model reads and reasons
     * about: a shortened flow list is still a flow list, and the footer says how to get the rest.
     * An export is not that. It is a single atomic document meant for another machine — cut it and
     * it stops being valid JSON, cannot be handed to import, and is no longer a backup. There is
     * also no smaller thing to ask for: one collection is already the minimum export accepts, and
     * seven rules were enough to blow the cap. Truncating it does not make it cheaper, it makes it
     * worthless, so this pays the context cost instead.
     */
    fun renderWhole(payload: JsonElement): String = PRETTY.toJson(payload)

    fun renderText(text: String): String =
        if (text.length <= MAX_OUTPUT_CHARS) {
            text
        } else {
            text.take(MAX_OUTPUT_CHARS - 200) +
                "\n\n[the MockkHttp bridge cut ${text.length - (MAX_OUTPUT_CHARS - 200)} characters from the end of this text]"
        }

    /** One string this renderer shortened: where it was, how much survived, how much there was. */
    private data class Elision(val path: String, val kept: Int, val total: Int)

    private fun describe(elisions: List<Elision>): List<String> =
        elisions.map { "${label(it.path)}: kept the first ${it.kept} of ${it.total} characters." }

    /**
     * How to fetch what was cut — named for the KIND of answer this is.
     *
     * A rule listing used to be footnoted with `mockkhttp_flows {"action":"get","flow_id":…}`. There
     * is no flow_id in a rule, so following the advice 404s; a model that trusts the footer then
     * concludes the data is gone. The shape is read off the payload's own keys and off the paths
     * that were actually shortened, so a mixed answer gets both lines and an unrecognised one gets
     * neither invented for it.
     */
    private fun retrievalHints(payload: JsonElement, elisions: List<Elision>): List<String> {
        if (elisions.isEmpty()) return emptyList()

        val roots = elisions.map { it.path.substringBefore('.').substringBefore('[') }.toSet()
        val top = payload.takeIf { it.isJsonObject }?.asJsonObject

        // A key only identifies the shape when it CARRIES something: `mocks export` answers
        // {"json":…, "collections":1, "rules":4}, where `rules` is a count. Reading that as a list
        // of rules would footnote an export with advice about rule ids it does not contain.
        fun carries(names: Set<String>): Boolean = names.any { name ->
            top?.get(name)?.let { it.isJsonArray || it.isJsonObject } == true
        }
        fun identifies(name: String): Boolean = top?.get(name)?.isJsonPrimitive == true

        val hints = ArrayList<String>()
        if (roots.any { it in RULE_ROOTS } || carries(RULE_ROOTS) || identifies("rule_id")) {
            hints += "A whole rule body comes back one rule at a time: " +
                "mockkhttp_mocks {\"action\":\"get\",\"rule_id\":\"<id>\"} — the rule_id of each rule is in " +
                "the answer above."
        }
        if (roots.any { it in FLOW_ROOTS } || carries(FLOW_ROOTS) || identifies("flow_id")) {
            hints += "A whole flow body comes back one flow at a time: " +
                "mockkhttp_flows {\"action\":\"get\",\"flow_id\":\"<id>\",\"max_body_chars\":262144}."
        }
        if (hints.isEmpty()) {
            hints += "Nothing here is addressable one item at a time, so ask for less instead: narrow the " +
                "arguments, or request the single item you need."
        }
        return hints
    }

    /** Forty identical "kept the first 2000 of 9000 characters" lines are noise, not information. */
    private fun shortenNotes(notes: List<String>): List<String> =
        if (notes.size <= 8) notes else notes.take(6) + "…and ${notes.size - 6} more reductions of the same kind."

    private fun elideStrings(element: JsonElement, path: String, elisions: MutableList<Elision>): JsonElement = when {
        element.isJsonObject -> JsonObject().also { copy ->
            val source = element.asJsonObject
            for ((key, value) in source.entrySet()) {
                copy.add(key, elideStrings(value, child(path, key), elisions))
                // The flag has to travel NEXT TO the value it describes. `body_truncated:false` from
                // the control plane is true — the PLUGIN did not truncate — and sitting beside a body
                // this renderer had just cut to 2 000 characters it read as "this body is complete".
                // Three truncations, three names: body_truncated (the control plane trimmed to
                // max_body_chars), body_truncated_by_retention (the bytes were dropped when the flow
                // was stored) and this one.
                if (wouldElide(value) && !source.has("${key}$BRIDGE_TRUNCATION_SUFFIX")) {
                    copy.addProperty("${key}$BRIDGE_TRUNCATION_SUFFIX", true)
                }
            }
        }

        element.isJsonArray -> JsonArray().also { copy ->
            element.asJsonArray.forEachIndexed { index, value ->
                copy.add(elideStrings(value, "$path[$index]", elisions))
            }
        }

        wouldElide(element) -> {
            val value = element.asString
            elisions += Elision(path, MAX_STRING_CHARS, value.length)
            JsonPrimitive(
                value.take(MAX_STRING_CHARS) +
                    "…[the MockkHttp bridge removed ${value.length - MAX_STRING_CHARS} characters here]"
            )
        }

        else -> element
    }

    /** A string long enough that [elideStrings] will shorten it. */
    private fun wouldElide(element: JsonElement): Boolean =
        element.isJsonPrimitive && element.asJsonPrimitive.isString && element.asString.length > MAX_STRING_CHARS

    private fun capArrays(element: JsonElement, cap: Int, path: String, notes: MutableList<String>): JsonElement = when {
        element.isJsonObject -> JsonObject().also { copy ->
            for ((key, value) in element.asJsonObject.entrySet()) {
                copy.add(key, capArrays(value, cap, child(path, key), notes))
            }
        }

        element.isJsonArray -> {
            val source = element.asJsonArray
            val copy = JsonArray()
            val kept = minOf(cap, source.size())
            for (index in 0 until kept) {
                copy.add(capArrays(source.get(index), cap, "$path[$index]", notes))
            }
            if (source.size() > kept) {
                notes += "${label(path)}: showing the first $kept of ${source.size()} entries."
                copy.add(JsonPrimitive("…${source.size() - kept} more entries removed by the MockkHttp bridge…"))
            }
            copy
        }

        else -> element
    }

    private fun child(path: String, key: String): String = if (path.isEmpty()) key else "$path.$key"

    private fun label(path: String): String = path.ifEmpty { "(the whole answer)" }

    private fun notesBlock(notes: List<String>): String {
        if (notes.isEmpty()) return ""
        return buildString {
            append("\n\n──── the MockkHttp bridge shortened this answer (cap ")
            append(MAX_OUTPUT_CHARS)
            append(" characters) ────\n")
            for (note in notes) append("• ").append(note).append('\n')
            append("Everything not listed above is complete.")
        }
    }

    private fun notesLength(notes: List<String>): Int = notesBlock(notes).length
}
