package com.sergiy.dev.mockkhttp.store

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level store for the quick error-response presets shown in the Debug
 * intercept dialog. Presets are developer preferences (not project data), so they are
 * shared across all open projects and persisted to XML storage.
 */
@Service(Service.Level.APP)
@State(
    name = "MockkHttpErrorPresets",
    storages = [Storage("mockkHttpErrorPresets.xml")]
)
class ErrorPresetsStore : PersistentStateComponent<ErrorPresetsStore.State> {

    private var currentState = State(presets = defaultPresets(), customized = false)

    companion object {
        fun getInstance(): ErrorPresetsStore {
            return ApplicationManager.getApplication().getService(ErrorPresetsStore::class.java)
        }

        private const val JSON_HEADER = "Content-Type: application/json"

        /** Built-in presets. Restorable from the presets editor dialog. */
        fun defaultPresets(): MutableList<ErrorPreset> = mutableListOf(
            ErrorPreset("400", 400, JSON_HEADER, """{"error":"bad_request","message":"Invalid request parameters"}"""),
            ErrorPreset("401", 401, JSON_HEADER, """{"error":"unauthorized","message":"Authentication required"}"""),
            ErrorPreset("403", 403, JSON_HEADER, """{"error":"forbidden","message":"Access denied"}"""),
            ErrorPreset("404", 404, JSON_HEADER, """{"error":"not_found","message":"Resource not found"}"""),
            ErrorPreset("429", 429, "$JSON_HEADER\nRetry-After: 30", """{"error":"rate_limited","message":"Too many requests"}"""),
            ErrorPreset("500", 500, JSON_HEADER, """{"error":"internal_error","message":"Internal server error"}"""),
            ErrorPreset("503", 503, "$JSON_HEADER\nRetry-After: 60", """{"error":"service_unavailable","message":"Service temporarily unavailable"}"""),
            ErrorPreset("Empty", 200, JSON_HEADER, ""),
            ErrorPreset("Malformed", 200, JSON_HEADER, """{"broken": """)
        )
    }

    /**
     * A reusable canned response. [headers] uses the same "Key: Value" one-per-line format
     * as the Debug dialog's headers editor, so it can be dropped straight into the text area.
     */
    data class ErrorPreset(
        var name: String = "",
        var statusCode: Int = 500,
        var headers: String = JSON_HEADER,
        var body: String = ""
    )

    data class State(
        var presets: MutableList<ErrorPreset> = mutableListOf(),
        // Distinguishes "user deleted every preset" from "nothing persisted yet", so an
        // intentionally empty list survives IDE restarts instead of being re-seeded.
        var customized: Boolean = false
    )

    // While the user hasn't customized anything, serialize an all-default (empty) state so
    // nothing is written to disk and each startup re-seeds from defaultPresets() — that way
    // default-preset improvements in new plugin versions reach non-customized users.
    override fun getState(): State = if (currentState.customized) currentState else State()

    override fun loadState(state: State) {
        currentState = if (!state.customized && state.presets.isEmpty()) {
            State(presets = defaultPresets(), customized = false)
        } else {
            state
        }
    }

    fun getPresets(): List<ErrorPreset> = currentState.presets.toList()

    fun setPresets(presets: List<ErrorPreset>) {
        currentState.presets = presets.map { it.copy() }.toMutableList()
        currentState.customized = true
    }
}
