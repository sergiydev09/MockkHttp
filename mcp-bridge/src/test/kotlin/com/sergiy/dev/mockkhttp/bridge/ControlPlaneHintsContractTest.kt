package com.sergiy.dev.mockkhttp.bridge

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The distinction the fourth audit round asked to have written down instead of re-derived each
 * time: **which REST routes can reach an MCP client, and does the bridge translate every one of
 * them?**
 *
 * The answer this test enforces is the simplest one — all of them. Every `VERB /v1/…` or
 * `VERB …/route` that appears in a string of the plugin's control plane (hints, warnings, notes,
 * route lists, docs pages) must come out of [RestClient.toolSpeak] as a tool call, with no verb
 * left glued in front and no `/v1/` or `…/` surviving. A new hint that writes a route the table
 * does not know fails here, in this module, before it ever reaches a model.
 *
 * Reads the plugin sources next door; skipped when they are not there (a published jar has no
 * sibling source tree).
 */
class ControlPlaneHintsContractTest {

    private val client = RestClient()

    private val routeInProse = Regex("""(?:GET|POST|PUT|PATCH|DELETE)\s+(?:/v1/|…/)""")

    @Test
    fun `every route the control plane writes into a string is translated by the bridge`() {
        val roots = listOf(
            File("../src/main/kotlin/com/sergiy/dev/mockkhttp/control"),
            File("../src/main/kotlin/com/sergiy/dev/mockkhttp/session")
        )
        assumeTrue(roots.all { it.isDirectory }, "plugin sources not present next to the bridge")

        val leaks = ArrayList<String>()
        var checked = 0
        for (root in roots) {
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    // Comments never reach a client; strings do. (`GET /v1/audit` lives in a KDoc.)
                    val trimmed = line.trim()
                    if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) return@forEachIndexed
                    if (!routeInProse.containsMatchIn(line)) return@forEachIndexed
                    checked++
                    val spoken = client.toolSpeak(line)
                    if (routeInProse.containsMatchIn(spoken) || Regex("""(?:GET|POST|PUT|PATCH|DELETE)\s+mockkhttp_""").containsMatchIn(spoken)) {
                        leaks += "${file.name}:${index + 1}: ${line.trim().take(160)}\n      -> ${spoken.trim().take(160)}"
                    }
                }
            }
        }

        assertTrue(checked > 30, "the sweep found only $checked route strings; the regex or the path is wrong")
        assertTrue(leaks.isEmpty(), "${leaks.size} route string(s) the bridge cannot translate:\n" + leaks.joinToString("\n"))
    }
}
