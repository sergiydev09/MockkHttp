package com.sergiy.dev.mockkhttp.store

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger

/**
 * Store for plugin settings including paths configuration.
 * Settings are persisted to XML storage.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "MockkHttpSettings",
    storages = [Storage("mockkHttpSettings.xml")]
)
class SettingsStore(private val project: Project) : PersistentStateComponent<SettingsStore.State> {

    private val logger = MockkHttpLogger.getInstance(project)
    private var currentState = State()
    private val settingsChangedListeners = mutableListOf<() -> Unit>()

    companion object {
        fun getInstance(project: Project): SettingsStore {
            return project.getService(SettingsStore::class.java)
        }
    }

    /**
     * State for persistence.
     */
    data class State(
        // ADB path - empty means auto-detect
        var adbPath: String = "",

        // Android SDK path - empty means auto-detect
        var androidSdkPath: String = "",

        // Interceptor server port
        var interceptorPort: Int = 9876,

        // Enable verbose logging
        var verboseLogging: Boolean = false,

        // Auto-start interceptor when project opens
        var autoStartInterceptor: Boolean = false
    )

    override fun getState(): State {
        return currentState
    }

    override fun loadState(state: State) {
        currentState = state
        logger.info("⚙️ Settings loaded from storage")
        logger.debug("   ADB path: ${if (state.adbPath.isBlank()) "(auto-detect)" else state.adbPath}")
        logger.debug("   Android SDK: ${if (state.androidSdkPath.isBlank()) "(auto-detect)" else state.androidSdkPath}")
        logger.debug("   Interceptor port: ${state.interceptorPort}")
    }

    // ========== GETTERS ==========

    /**
     * Get the configured ADB path, or null if auto-detect should be used.
     */
    fun getAdbPath(): String? {
        return currentState.adbPath.takeIf { it.isNotBlank() }
    }

    /**
     * Get the configured Android SDK path, or null if auto-detect should be used.
     */
    fun getAndroidSdkPath(): String? {
        return currentState.androidSdkPath.takeIf { it.isNotBlank() }
    }

    /**
     * Get the interceptor server port.
     */
    fun getInterceptorPort(): Int {
        return currentState.interceptorPort
    }

    /**
     * Check if verbose logging is enabled.
     */
    fun isVerboseLogging(): Boolean {
        return currentState.verboseLogging
    }

    /**
     * Check if auto-start interceptor is enabled.
     */
    fun isAutoStartInterceptor(): Boolean {
        return currentState.autoStartInterceptor
    }

    // ========== SETTERS ==========

    /**
     * Set the ADB path. Empty string means auto-detect.
     */
    fun setAdbPath(path: String) {
        currentState.adbPath = path.trim()
        logger.info("⚙️ ADB path ${if (path.isBlank()) "reset to auto-detect" else "set to: $path"}")
        notifySettingsChanged()
    }

    /**
     * Set the Android SDK path. Empty string means auto-detect.
     */
    fun setAndroidSdkPath(path: String) {
        currentState.androidSdkPath = path.trim()
        logger.info("⚙️ Android SDK path ${if (path.isBlank()) "reset to auto-detect" else "set to: $path"}")
        notifySettingsChanged()
    }

    /**
     * Set the interceptor server port.
     */
    fun setInterceptorPort(port: Int) {
        if (port in 1024..65535) {
            currentState.interceptorPort = port
            logger.info("⚙️ Interceptor port set to: $port")
            notifySettingsChanged()
        } else {
            logger.warn("⚠️ Invalid port: $port (must be between 1024 and 65535)")
        }
    }

    /**
     * Set verbose logging enabled/disabled.
     */
    fun setVerboseLogging(enabled: Boolean) {
        currentState.verboseLogging = enabled
        logger.info("⚙️ Verbose logging ${if (enabled) "enabled" else "disabled"}")
        notifySettingsChanged()
    }

    /**
     * Set auto-start interceptor enabled/disabled.
     */
    fun setAutoStartInterceptor(enabled: Boolean) {
        currentState.autoStartInterceptor = enabled
        logger.info("⚙️ Auto-start interceptor ${if (enabled) "enabled" else "disabled"}")
        notifySettingsChanged()
    }

    // ========== LISTENERS ==========

    /**
     * Add listener for settings changes.
     */
    fun addSettingsChangedListener(listener: () -> Unit) {
        settingsChangedListeners.add(listener)
    }

    /**
     * Remove listener for settings changes.
     */
    fun removeSettingsChangedListener(listener: () -> Unit) {
        settingsChangedListeners.remove(listener)
    }

    private fun notifySettingsChanged() {
        settingsChangedListeners.forEach {
            try {
                it()
            } catch (e: Exception) {
                logger.error("Error in settings change listener", e)
            }
        }
    }

    // ========== VALIDATION ==========

    /**
     * Validate that a path points to an existing executable file.
     */
    fun validateExecutablePath(path: String): PathValidationResult {
        if (path.isBlank()) {
            return PathValidationResult(true, "Auto-detect will be used")
        }

        val file = java.io.File(path)
        return when {
            !file.exists() -> PathValidationResult(false, "File does not exist")
            !file.isFile -> PathValidationResult(false, "Path is not a file")
            !file.canExecute() -> PathValidationResult(false, "File is not executable")
            else -> PathValidationResult(true, "Valid executable")
        }
    }

    /**
     * Validate that a path points to an existing directory.
     */
    fun validateDirectoryPath(path: String): PathValidationResult {
        if (path.isBlank()) {
            return PathValidationResult(true, "Auto-detect will be used")
        }

        val file = java.io.File(path)
        return when {
            !file.exists() -> PathValidationResult(false, "Directory does not exist")
            !file.isDirectory -> PathValidationResult(false, "Path is not a directory")
            else -> PathValidationResult(true, "Valid directory")
        }
    }

    /**
     * Result of path validation.
     */
    data class PathValidationResult(
        val isValid: Boolean,
        val message: String
    )
}
