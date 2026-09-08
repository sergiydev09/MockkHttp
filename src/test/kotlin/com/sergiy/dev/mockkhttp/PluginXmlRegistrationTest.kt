package com.sergiy.dev.mockkhttp

import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Guards the half of the wiring that lives in XML rather than in Kotlin.
 *
 * The agent control plane once shipped fully implemented and never ran: every class compiled, every
 * unit test passed, and nothing started it because `plugin.xml` carried no registration. Code-level
 * tests cannot see that — `AgentControlWiringTest` proves `start()` assembles a working server, but
 * not that anything ever calls it in a real IDE.
 *
 * So this asserts the declarations themselves, and it asserts the CLASSES EXIST, because the second
 * way this breaks is a rename that leaves a stale fully-qualified name in the XML: the IDE then logs
 * a class-not-found at startup and carries on with the feature silently absent.
 */
class PluginXmlRegistrationTest {

    private val descriptor: Element by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream(PLUGIN_XML)) {
            "$PLUGIN_XML is not on the test classpath — the plugin descriptor is missing from resources."
        }
        stream.use {
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = false }
                .newDocumentBuilder()
                .parse(it)
                .documentElement
        }
    }

    @Test
    fun `the startup activity that boots the control plane is registered`() {
        assertRegistered(
            tag = "postStartupActivity",
            attribute = "implementation",
            fqn = "com.sergiy.dev.mockkhttp.startup.MockkHttpStartupActivity",
            why = "nothing would ever call AgentControlServer.start(), so the control plane would " +
                    "compile, ship, and never bind — exactly the defect this test exists for"
        )
    }

    @Test
    fun `the project listener that keeps the discovery file honest is registered`() {
        assertRegistered(
            tag = "listener",
            attribute = "class",
            fqn = "com.sergiy.dev.mockkhttp.agent.InstanceFileProjectListener",
            why = "the instance file would keep advertising projects the IDE has already closed, and " +
                    "the MCP bridge would resolve an agent's working directory to a disposed project"
        )
    }

    @Test
    fun `the tool window that hosts the Agent tab is registered`() {
        assertRegistered(
            tag = "toolWindow",
            attribute = "factoryClass",
            fqn = "com.sergiy.dev.mockkhttp.ui.MockkHttpToolWindowFactory",
            why = "there would be no UI at all, including no way to switch agent control off"
        )
    }

    /** Every registered class must resolve: a rename that misses the XML fails only at runtime. */
    private fun assertRegistered(tag: String, attribute: String, fqn: String, why: String) {
        val nodes = descriptor.getElementsByTagName(tag)
        val declared = (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .mapNotNull { it.getAttribute(attribute).takeIf(String::isNotBlank) }

        assertTrue(
            "plugin.xml declares no <$tag $attribute=\"$fqn\">. Without it, $why.\n" +
                    "Declared instead: ${declared.ifEmpty { listOf("(none)") }}",
            fqn in declared
        )

        // Class.forName rather than a literal reference: the point is to catch the XML drifting
        // away from the code, which a compile-time reference could never detect.
        val resolved = runCatching { Class.forName(fqn) }.isSuccess
        assertTrue(
            "plugin.xml registers $fqn but that class does not exist — a rename left the descriptor " +
                    "behind. The IDE logs a class-not-found on startup and the feature is silently absent.",
            resolved
        )
    }

    private companion object {
        const val PLUGIN_XML = "/META-INF/plugin.xml"
    }
}
