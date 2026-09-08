package com.sergiy.dev.mockkhttp.store

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Persisted settings for the agent control plane.
 *
 * Application-level on purpose. The control server binds ONE loopback port per IDE process and
 * serves every open project through it, so "is an agent allowed to drive this IDE" is a property of
 * the process, not of a project. Putting it in the project-level [SettingsStore] would give two
 * open projects two contradictory answers about a single socket — and the first project to load
 * would silently win.
 *
 * Stored in `mockkhttp-agent.xml` under the IDE config directory, separate from the per-project
 * settings file so revoking access on one machine never travels in a project's `.idea`.
 */
@Service(Service.Level.APP)
@State(
    name = "MockkHttpAgentSettings",
    storages = [Storage("mockkhttp-agent.xml")]
)
class AgentSettingsStore : PersistentStateComponent<AgentSettingsStore.AgentState> {

    private val logger = Logger.getInstance(AgentSettingsStore::class.java)
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    data class AgentState(
        /** One of `full`, `read_only`, `off`. See [agentControl]. */
        var agentControl: String = FULL,

        /**
         * Whether an agent may read header values the plugin normally redacts (Authorization,
         * Cookie, api keys). Off by default: an agent's whole transcript is a plain-text log, and
         * a bearer token that ends up there outlives the session it came from.
         */
        var allowRevealSecrets: Boolean = false,

        /** Set once the user has seen the "an agent connected" notification, so it is not repeated. */
        var firstConnectionAcknowledged: Boolean = false
    )

    private var state = AgentState()

    companion object {
        const val FULL = "full"
        const val READ_ONLY = "read_only"
        const val OFF = "off"

        fun getInstance(): AgentSettingsStore =
            ApplicationManager.getApplication().getService(AgentSettingsStore::class.java)

        /** Normalises anything a settings file or an older build may hold into a known value. */
        fun normalize(value: String?): String = when (value?.lowercase(Locale.ROOT)?.trim()) {
            // Absent means a fresh install, which defaults to Full (decision D4).
            null, "", FULL, "read_write", "readwrite" -> FULL
            READ_ONLY, "readonly", "read-only" -> READ_ONLY
            OFF, "false", "disabled" -> OFF
            // Anything else is a hand-edited file or a value from a newer build. Fail closed, the
            // way ControlAuth.setAgentControl does — never read "unknown" as "writes allowed".
            else -> OFF
        }
    }

    override fun getState(): AgentState = state

    override fun loadState(loaded: AgentState) {
        state = loaded
        // Normalise on load: a hand-edited file, or a value written by a future build, must not
        // leave the auth layer comparing against a string it does not recognise — which would
        // read as "not OFF" and quietly grant access.
        state.agentControl = normalize(state.agentControl)

        // Secret reveal is deliberately session-scoped, and this is what makes that true rather
        // than merely intended: consent to expose captured Authorization/Cookie headers must be
        // given again after every restart. A durable "yes" one wired-up getter away from re-arming
        // itself is exactly the kind of switch nobody remembers leaving on.
        state.allowRevealSecrets = false

        logger.info("Agent control loaded: ${state.agentControl}")
    }

    /** `full` | `read_only` | `off`. Never returns anything else. */
    fun getAgentControl(): String = normalize(state.agentControl)

    fun setAgentControl(value: String) {
        val normalized = normalize(value)
        if (normalized == state.agentControl) return
        state.agentControl = normalized
        logger.info("Agent control set to: $normalized")
        listeners.forEach { listener ->
            try {
                listener(normalized)
            } catch (e: Exception) {
                logger.warn("Agent control listener failed", e)
            }
        }
    }

    fun isRevealSecretsAllowed(): Boolean = state.allowRevealSecrets

    fun setRevealSecretsAllowed(allowed: Boolean) {
        state.allowRevealSecrets = allowed
    }

    fun isFirstConnectionAcknowledged(): Boolean = state.firstConnectionAcknowledged

    fun setFirstConnectionAcknowledged(acknowledged: Boolean) {
        state.firstConnectionAcknowledged = acknowledged
    }

    /** Notified whenever [setAgentControl] actually changes the value. */
    fun addChangeListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeChangeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }
}
