package com.sergiy.dev.mockkhttp.model

import com.google.gson.annotations.SerializedName

/**
 * What a client library says about itself in the `client` object it attaches to every message
 * on port 9876 — CHECK_MOCK and FLOW alike. Optional on the wire: clients older than 1.8.0 send
 * nothing, and every field is nullable so a partial report from a newer client still parses.
 *
 * The `stats` are the client's own counters — flows sent, passes one layer yielded to another,
 * claims made and withdrawn — and they exist so that "one request, one flow" can be checked from
 * outside the app instead of trusted: the control plane shows the latest report as `client` on
 * `status` and on the flow listing.
 */
data class ClientReport(
    val library: String? = null,
    val version: String? = null,
    val platform: String? = null,
    val dedup: Dedup? = null,
    val caps: List<String>? = null,
    val stats: Map<String, Long>? = null,
    /** Changes every time the app process starts: its counters start from zero again. */
    @SerializedName("run_id") val runId: String? = null,
    /** Epoch millis when this run started counting. */
    @SerializedName("started_at") val startedAt: Long? = null,
    /**
     * The client's own message sequence, increasing with every report it builds. Every message
     * opens its own socket, so two can be served out of the order they were built; the server
     * keeps the highest seq per run and never lets an overtaken message roll the numbers back.
     */
    @SerializedName("seq") val seq: Long? = null
) {
    data class Dedup(
        val enabled: Boolean? = null,
        @SerializedName("window_ms") val windowMs: Int? = null,
        val controllable: Boolean? = null
    )
}
