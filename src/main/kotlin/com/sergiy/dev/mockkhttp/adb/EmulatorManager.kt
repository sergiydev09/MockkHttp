package com.sergiy.dev.mockkhttp.adb

import com.android.ddmlib.AdbInitOptions
import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import com.sergiy.dev.mockkhttp.store.SettingsStore
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Manager for detecting and managing Android emulators via ADB.
 * Handles ADB bridge initialization and emulator discovery.
 */
@Service(Service.Level.PROJECT)
class EmulatorManager(private val project: Project) : com.intellij.openapi.Disposable {

    private val logger = MockkHttpLogger.getInstance(project)

    @Volatile
    private var adbBridge: AndroidDebugBridge? = null

    // Read without the init lock by getConnectedDevices()/getDevice(), written under it.
    @Volatile
    private var isInitialized = false

    // Bumped once per completed initialize() attempt, under the lock. A caller that blocked on
    // that lock compares it against the snapshot it took *before* contending: if it moved, the
    // attempt it waited for is its answer, so it never spins up a second AndroidDebugBridge.
    @Volatile
    private var initAttempts = 0L

    @Volatile
    private var lastInitResult = false

    /**
     * Memoized ADB path plus the configured value it was resolved from.
     *
     * Resolving walks env vars, Android Studio preferences, Homebrew and PATH, and can shell out
     * to a login shell - dozens of filesystem hits for a value that only changes when the user
     * edits Settings. See [invalidateAdbPathCache].
     *
     * Path and source live in ONE object on purpose: as two independent @Volatile fields a
     * concurrent reader could see the new path paired with the old source, decide the cache was
     * stale and re-run the whole search. [failedAtMs] does the same job for a miss.
     */
    private data class AdbPathCache(
        val path: String?,
        val source: String?,
        val failedAtMs: Long = 0L
    )

    @Volatile
    private var adbPathCache: AdbPathCache? = null

    // Listeners are added from the UI while ddmlib fires callbacks from its own transport
    // thread; a plain ArrayList would eventually throw ConcurrentModificationException here.
    private val deviceChangeListeners = CopyOnWriteArrayList<() -> Unit>()

    private var deviceListener: AndroidDebugBridge.IDeviceChangeListener? = null

    companion object {
        /**
         * How long a FAILED adb resolution is remembered before searching again.
         *
         * Short enough that plugging in an SDK is noticed without restarting the IDE, long
         * enough that a device scan does not re-run a ~25s search for every command.
         */
        private const val FAILED_RESOLUTION_TTL_MS = 30_000L

        fun getInstance(project: Project): EmulatorManager {
            return project.getService(EmulatorManager::class.java)
        }

        private const val ADB_INIT_TIMEOUT_MS = 10000L

        // The child is already dead when we join its reader, so this is only a guard against a
        // reader that somehow never sees EOF - never a normal wait.
        private const val DRAIN_JOIN_TIMEOUT_MS = 2000L
    }

    /**
     * Initialize ADB bridge.
     * Must be called before any ADB operations.
     *
     * Idempotent and safe to call concurrently: the UI and an automated controller both drive
     * this. A caller that arrives while an attempt is in flight waits for it and reports that
     * attempt's verdict instead of creating a second AndroidDebugBridge.
     */
    fun initialize(): Boolean {
        // Snapshot BEFORE contending for the lock. That is what separates "I waited for someone
        // else's attempt" (take their result) from "I am a fresh call afterwards" (retry is
        // allowed, so the user can fix the ADB path and try again).
        val attemptsBefore = initAttempts
        return initializeLocked(attemptsBefore)
    }

    @Synchronized
    private fun initializeLocked(attemptsBefore: Long): Boolean {
        if (isInitialized && adbBridge != null) {
            logger.debug("ADB bridge already initialized")
            return true
        }

        if (initAttempts != attemptsBefore) {
            logger.debug("ADB initialization was performed by a concurrent caller (result=$lastInitResult)")
            return lastInitResult
        }

        val result = doInitialize()
        lastInitResult = result
        initAttempts++
        return result
    }

    private fun doInitialize(): Boolean {
        logger.info("🔧 Initializing ADB bridge...")

        try {
            // Find ADB executable - first check settings, then auto-detect
            val adbPath = getConfiguredOrDetectedAdbPath()
            if (adbPath == null) {
                logger.warn("⚠️ ADB executable not found. Please configure it in Settings tab or install Android SDK Platform Tools.")
                return false
            }

            logger.debug("ADB found at: $adbPath")

            // Initialize ADB with modern AdbInitOptions (only if not already initialized)
            // AndroidDebugBridge.init() can only be called ONCE per JVM process
            // When multiple projects are open, only the first one should call init()
            try {
                val adbInitOptions = AdbInitOptions.builder()
                    .setClientSupportEnabled(false)
                    .useJdwpProxyService(false)
                    .build()

                AndroidDebugBridge.init(adbInitOptions)
                logger.debug("ADB initialized successfully with AdbInitOptions")
            } catch (e: IllegalStateException) {
                if (e.message?.contains("already been called") == true) {
                    logger.debug("ADB already initialized by another project (multi-project mode)")
                } else {
                    throw e
                }
            }

            // Create bridge with timeout to prevent hanging
            val bridge = AndroidDebugBridge.createBridge(
                adbPath,
                false,
                ADB_INIT_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )

            if (bridge == null) {
                logger.error("Failed to create ADB bridge")
                return false
            }
            adbBridge = bridge

            // Wait for bridge to connect
            logger.debug("Waiting for ADB bridge to connect...")
            val startTime = System.currentTimeMillis()
            while (!bridge.isConnected &&
                   System.currentTimeMillis() - startTime < ADB_INIT_TIMEOUT_MS) {
                Thread.sleep(100)
            }

            if (!bridge.isConnected) {
                logger.error("ADB bridge connection timeout")
                return false
            }

            // Wait for initial device list
            logger.debug("Waiting for device list...")
            var waited = 0L
            while (!bridge.hasInitialDeviceList() && waited < ADB_INIT_TIMEOUT_MS) {
                Thread.sleep(100)
                waited += 100
            }

            // Register device change listener
            deviceListener = object : AndroidDebugBridge.IDeviceChangeListener {
                override fun deviceConnected(device: IDevice?) {
                    logger.debug("Device connected: ${device?.serialNumber}")
                    notifyDeviceChange()
                }

                override fun deviceDisconnected(device: IDevice?) {
                    logger.debug("Device disconnected: ${device?.serialNumber}")
                    notifyDeviceChange()
                }

                override fun deviceChanged(device: IDevice?, changeMask: Int) {
                    // Only notify on significant changes
                    if (changeMask and IDevice.CHANGE_STATE != 0) {
                        logger.debug("Device state changed: ${device?.serialNumber}")
                        notifyDeviceChange()
                    }
                }
            }
            AndroidDebugBridge.addDeviceChangeListener(deviceListener)
            logger.debug("Device change listener registered")

            isInitialized = true
            logger.info("✅ ADB bridge initialized successfully")
            return true

        } catch (e: InterruptedException) {
            // Restore the flag: the caller (an IDE background task, or a controller's worker)
            // decides what cancellation means, and swallowing it here would hide it.
            Thread.currentThread().interrupt()
            logger.warn("⚠️ ADB bridge initialization interrupted")
            isInitialized = false
            return false
        } catch (e: Exception) {
            logger.error("Failed to initialize ADB bridge", e)
            isInitialized = false
            return false
        }
    }

    /**
     * The ADB executable this project actually uses: the Settings override when it is valid,
     * the auto-detected one otherwise. Memoized, so an automated controller can call it as
     * often as it likes to report which adb is driving the device.
     */
    fun getResolvedAdbPath(): String? = getConfiguredOrDetectedAdbPath()

    /**
     * Detach from ddmlib when the project closes.
     *
     * `AndroidDebugBridge.addDeviceChangeListener` registers into a STATIC list that outlives
     * every project. Nothing ever removed the listener, so each opened-and-closed project left
     * one behind holding this service — and through it the Project — alive for the rest of the
     * IDE session, and every device event went on being delivered to dead projects.
     */
    override fun dispose() {
        deviceListener?.let { listener ->
            try {
                AndroidDebugBridge.removeDeviceChangeListener(listener)
                logger.debug("Detached ddmlib device listener")
            } catch (e: Exception) {
                logger.warn("⚠️ Failed to detach ddmlib device listener", e)
            }
        }
        deviceListener = null
        deviceChangeListeners.clear()
    }

    /**
     * Drop the memoized ADB path so the next call re-resolves from scratch.
     * Call this after the user edits the ADB path in Settings.
     */
    fun invalidateAdbPathCache() {
        val previous = adbPathCache?.path
        adbPathCache = null
        if (previous != null) {
            logger.debug("ADB path cache invalidated (was: $previous)")
        }
    }

    /** The resolved ADB path, without triggering a search. Null until one has succeeded. */
    fun getResolvedAdbPathOrNull(): String? = adbPathCache?.path

    /**
     * Get ADB path from settings if configured, otherwise auto-detect.
     * This is the preferred method to get the ADB path.
     *
     * Memoized: the full search is expensive enough that it must not run once per adb command.
     */
    fun getConfiguredOrDetectedAdbPath(): String? {
        val configuredPath = readConfiguredAdbPath()

        val cache = adbPathCache
        if (cache != null && cache.source == configuredPath) {
            val cached = cache.path
            if (cached != null) {
                // One stat against the dozens the search costs: an SDK that was moved or removed
                // mid-session is still noticed, everything else is served from the field.
                if (File(cached).canExecute()) return cached
                logger.warn("⚠️ Cached ADB path is no longer executable: $cached (re-resolving)")
                adbPathCache = null
            } else if (System.currentTimeMillis() - cache.failedAtMs < FAILED_RESOLUTION_TTL_MS) {
                // Remember the MISS too. The search ends in two 5s login shells and a 10s mdfind,
                // so on a machine with no SDK every single adb call used to pay ~25s over again.
                return null
            }
        }

        val resolved = when {
            configuredPath == null -> findAdbPath()
            File(configuredPath).let { it.exists() && it.canExecute() } -> {
                logger.info("✅ Using configured ADB path: $configuredPath")
                configuredPath
            }
            else -> {
                logger.warn("⚠️ Configured ADB path is invalid: $configuredPath (falling back to auto-detect)")
                findAdbPath()
            }
        }

        adbPathCache = AdbPathCache(
            path = resolved,
            source = configuredPath,
            failedAtMs = if (resolved == null) System.currentTimeMillis() else 0L
        )
        return resolved
    }

    /**
     * The path the user configured in Settings, or null when auto-detection should be used.
     * Never throws: the settings service can still be loading when the first scan fires.
     */
    private fun readConfiguredAdbPath(): String? {
        return try {
            SettingsStore.getInstance(project).getAdbPath()
        } catch (e: Exception) {
            logger.warn(
                "⚠️ Could not read the ADB path from settings " +
                "(${e.javaClass.simpleName}: ${e.message}) - falling back to auto-detection"
            )
            null
        }
    }

    /**
     * Find ADB executable path.
     * Checks common locations for Android SDK across all platforms.
     * Made public so other components can use it.
     */
    fun findAdbPath(): String? {
        logger.info("🔍 Searching for ADB executable...")
        val triedPaths = mutableListOf<String>()
        val osName = System.getProperty("os.name").lowercase()
        val isWindows = osName.contains("windows")
        val isMacOS = osName.contains("mac")
        val adbExecutable = if (isWindows) "adb.exe" else "adb"
        val userHome = System.getProperty("user.home")

        // Helper function to check ADB path
        fun checkAdbPath(path: File, source: String): String? {
            val fullPath = path.absolutePath
            triedPaths.add("$source: $fullPath")
            if (path.exists() && path.canExecute()) {
                logger.info("✅ ADB found via $source: $fullPath")
                return fullPath
            }
            return null
        }

        // 1. Try ANDROID_HOME environment variable
        System.getenv("ANDROID_HOME")?.let { androidHome ->
            checkAdbPath(File(androidHome, "platform-tools/$adbExecutable"), "ANDROID_HOME")?.let { return it }
        }

        // 2. Try ANDROID_SDK_ROOT environment variable
        System.getenv("ANDROID_SDK_ROOT")?.let { androidSdkRoot ->
            checkAdbPath(File(androidSdkRoot, "platform-tools/$adbExecutable"), "ANDROID_SDK_ROOT")?.let { return it }
        }

        // 3. Try ANDROID_SDK environment variable (some setups use this)
        System.getenv("ANDROID_SDK")?.let { androidSdk ->
            checkAdbPath(File(androidSdk, "platform-tools/$adbExecutable"), "ANDROID_SDK")?.let { return it }
        }

        // 4. macOS: Try to get ANDROID_HOME from shell profile (IntelliJ doesn't inherit shell env vars when opened from Dock)
        // Order is load-bearing - the first shell that answers wins, and bash answered first
        // before these two identical blocks were folded into one loop.
        if (isMacOS) {
            for ((shell, label) in listOf("/bin/bash" to "bash", "/bin/zsh" to "zsh")) {
                // Run a login shell to get the proper environment variables
                val shellAndroidHome = runCommand(
                    listOf(shell, "-l", "-c", "echo \$ANDROID_HOME"),
                    5, TimeUnit.SECONDS, "$label ANDROID_HOME probe"
                )?.output?.trim().orEmpty()

                if (shellAndroidHome.isNotBlank() && shellAndroidHome != "\$ANDROID_HOME") {
                    checkAdbPath(
                        File(shellAndroidHome, "platform-tools/$adbExecutable"),
                        "Shell ANDROID_HOME ($label)"
                    )?.let { return it }
                }
            }
        }

        // 5. macOS: Try to read SDK path from Android Studio preferences
        if (isMacOS) {
            findSdkFromAndroidStudioPrefs(userHome)?.let { sdkPath ->
                checkAdbPath(File(sdkPath, "platform-tools/$adbExecutable"), "Android Studio preferences")?.let { return it }
            }
        }

        // 6. Platform-specific default locations
        val defaultPaths = if (isWindows) {
            listOf(
                File(userHome, "AppData/Local/Android/Sdk/platform-tools/$adbExecutable"),
                File("C:/Android/sdk/platform-tools/$adbExecutable"),
                File("C:/Users/${System.getProperty("user.name")}/AppData/Local/Android/Sdk/platform-tools/$adbExecutable"),
                File(System.getenv("LOCALAPPDATA") ?: "", "Android/Sdk/platform-tools/$adbExecutable"),
                File(System.getenv("ProgramFiles") ?: "", "Android/Android Studio/sdk/platform-tools/$adbExecutable"),
                File(System.getenv("ProgramFiles(x86)") ?: "", "Android/Android Studio/sdk/platform-tools/$adbExecutable")
            )
        } else if (!isMacOS) {
            // Linux
            listOf(
                File(userHome, "Android/Sdk/platform-tools/$adbExecutable"),
                File(userHome, "android-sdk/platform-tools/$adbExecutable"),
                File("/opt/android-sdk/platform-tools/$adbExecutable"),
                File("/usr/local/android-sdk/platform-tools/$adbExecutable"),
                File("/snap/android-studio/current/android-studio/sdk/platform-tools/$adbExecutable"),
                File(userHome, ".var/app/com.google.AndroidStudio/data/Android/Sdk/platform-tools/$adbExecutable")
            )
        } else {
            // macOS - most common location first
            listOf(
                // Standard macOS location (most common)
                File(userHome, "Library/Android/sdk/platform-tools/$adbExecutable"),
                // Android Studio bundled SDK
                File("/Applications/Android Studio.app/Contents/sdk/platform-tools/$adbExecutable"),
                // Alternative locations
                File("/opt/android-sdk/platform-tools/$adbExecutable"),
                File("/usr/local/share/android-sdk/platform-tools/$adbExecutable")
            )
        }

        for (path in defaultPaths) {
            checkAdbPath(path, "Default location")?.let { return it }
        }

        // 7. macOS: Check Homebrew locations (Intel and Apple Silicon)
        if (isMacOS) {
            findAdbInHomebrew(adbExecutable)?.let { path ->
                triedPaths.add("Homebrew: $path")
                logger.info("✅ ADB found via Homebrew: $path")
                return path
            }
        }

        // 8. Try PATH environment variable
        val pathEnv = System.getenv("PATH") ?: ""
        val pathSeparator = if (isWindows) ";" else ":"
        for (dir in pathEnv.split(pathSeparator)) {
            if (dir.isNotBlank()) {
                val adbPath = File(dir, adbExecutable)
                checkAdbPath(adbPath, "PATH")?.let { return it }
            }
        }

        // 9. macOS: Try 'which adb' with login shell (gets full PATH from shell profile)
        if (isMacOS) {
            findAdbWithLoginShell()?.let { path ->
                triedPaths.add("Login shell which: $path")
                logger.info("✅ ADB found via login shell: $path")
                return path
            }
        }

        // 10. macOS: Use Spotlight (mdfind) as last resort
        if (isMacOS) {
            findAdbWithSpotlight()?.let { path ->
                triedPaths.add("Spotlight (mdfind): $path")
                logger.info("✅ ADB found via Spotlight: $path")
                return path
            }
        }

        // 11. Try 'which adb' or 'where adb' command (non-login shell, may not work on macOS)
        val whichCommand = if (isWindows) listOf("cmd", "/c", "where", "adb") else listOf("which", "adb")
        val whichResult = runCommand(whichCommand, 5, TimeUnit.SECONDS, "which/where adb")
        if (whichResult != null && whichResult.exitCode == 0) {
            val firstLine = whichResult.output.trim().lines().firstOrNull()?.trim()
            if (!firstLine.isNullOrBlank()) {
                val adbPath = File(firstLine)
                if (adbPath.exists() && adbPath.canExecute()) {
                    logger.info("✅ ADB found via ${if (isWindows) "where" else "which"} command: ${adbPath.absolutePath}")
                    return adbPath.absolutePath
                }
            }
        }

        // Log all tried paths for debugging (use WARN level to avoid IDE issues in CI environments)
        logger.warn("⚠️ ADB not found. Tried the following locations:")
        triedPaths.forEach { logger.warn("  - $it") }
        logger.warn("💡 Solutions:")
        logger.warn("  1. Install Android SDK Platform Tools: brew install --cask android-platform-tools")
        logger.warn("  2. Set ANDROID_HOME in your shell profile (~/.zshrc or ~/.bash_profile):")
        logger.warn("     export ANDROID_HOME=\$HOME/Library/Android/sdk")
        logger.warn("     export PATH=\$PATH:\$ANDROID_HOME/platform-tools")
        logger.warn("  3. Restart Android Studio after setting environment variables")
        logger.warn("  4. Or launch Android Studio from terminal: open -a 'Android Studio'")

        return null
    }

    /**
     * Find SDK path from Android Studio preferences (macOS specific).
     */
    private fun findSdkFromAndroidStudioPrefs(userHome: String): String? {
        // Check various Android Studio config locations
        val configLocations = listOf(
            // New Android Studio (2020.3+)
            File(userHome, "Library/Application Support/Google/AndroidStudio"),
            // Older Android Studio
            File(userHome, "Library/Preferences/AndroidStudio"),
            // Preview versions
            File(userHome, "Library/Application Support/Google/AndroidStudioPreview")
        )

        for (baseDir in configLocations) {
            if (!baseDir.exists()) continue

            // Find the latest version directory
            val versionDirs = baseDir.listFiles { file -> file.isDirectory }
                ?.sortedByDescending { it.name }
                ?: continue

            for (versionDir in versionDirs) {
                // Try to find sdk path in jdk.table.xml
                val jdkTableFile = File(versionDir, "options/jdk.table.xml")
                if (jdkTableFile.exists()) {
                    try {
                        val content = jdkTableFile.readText()
                        // Look for Android SDK path in the XML
                        val sdkPathRegex = """<homePath[^>]*value="([^"]*Android[/\\]sdk[^"]*)"[^>]*/>""".toRegex(RegexOption.IGNORE_CASE)
                        sdkPathRegex.find(content)?.groupValues?.get(1)?.let { sdkPath ->
                            val expandedPath = sdkPath.replace("\$USER_HOME\$", userHome)
                            if (File(expandedPath).exists()) {
                                logger.debug("Found SDK path in Android Studio preferences: $expandedPath")
                                return expandedPath
                            }
                        }
                    } catch (e: Exception) {
                        logger.debug("Failed to read Android Studio preferences: ${e.message}")
                    }
                }

                // Also check for sdk.dir in idea.properties
                val ideaPropsFile = File(versionDir, "idea.properties")
                if (ideaPropsFile.exists()) {
                    try {
                        ideaPropsFile.readLines().forEach { line ->
                            if (line.startsWith("android.sdk.path=")) {
                                val sdkPath = line.substringAfter("=").trim()
                                if (File(sdkPath).exists()) {
                                    logger.debug("Found SDK path in idea.properties: $sdkPath")
                                    return sdkPath
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logger.debug("Failed to read idea.properties: ${e.message}")
                    }
                }
            }
        }
        return null
    }

    /**
     * Find ADB in Homebrew installation (handles both Intel and Apple Silicon).
     */
    private fun findAdbInHomebrew(adbExecutable: String): String? {
        // Homebrew paths for Intel and Apple Silicon Macs
        val homebrewPrefixes = listOf(
            "/opt/homebrew",        // Apple Silicon (M1/M2/M3)
            "/usr/local"            // Intel Macs
        )

        for (prefix in homebrewPrefixes) {
            // Check android-platform-tools cask (most common)
            val caskDir = File("$prefix/Caskroom/android-platform-tools")
            if (caskDir.exists()) {
                caskDir.listFiles { file -> file.isDirectory }
                    ?.sortedByDescending { it.name }
                    ?.forEach { versionDir ->
                        val adbPath = File(versionDir, "platform-tools/$adbExecutable")
                        if (adbPath.exists() && adbPath.canExecute()) {
                            return adbPath.absolutePath
                        }
                    }
            }

            // Check android-sdk cask
            val sdkCaskDir = File("$prefix/Caskroom/android-sdk")
            if (sdkCaskDir.exists()) {
                sdkCaskDir.listFiles { file -> file.isDirectory }
                    ?.sortedByDescending { it.name }
                    ?.forEach { versionDir ->
                        val adbPath = File(versionDir, "platform-tools/$adbExecutable")
                        if (adbPath.exists() && adbPath.canExecute()) {
                            return adbPath.absolutePath
                        }
                    }
            }

            // Check symlinked adb in Homebrew bin
            val binAdb = File("$prefix/bin/$adbExecutable")
            if (binAdb.exists() && binAdb.canExecute()) {
                return binAdb.absolutePath
            }
        }
        return null
    }

    /**
     * Find ADB using login shell (gets full PATH from user's shell profile).
     */
    private fun findAdbWithLoginShell(): String? {
        val shells = listOf("/bin/zsh", "/bin/bash")
        for (shell in shells) {
            val result = runCommand(
                listOf(shell, "-l", "-c", "which adb"),
                5, TimeUnit.SECONDS, "login-shell which adb ($shell)"
            ) ?: continue

            if (result.exitCode != 0) continue
            val candidate = result.output.trim().lines().firstOrNull()?.trim()
            if (candidate.isNullOrBlank()) continue

            val adbPath = File(candidate)
            if (adbPath.exists() && adbPath.canExecute()) {
                return adbPath.absolutePath
            }
        }
        return null
    }

    /**
     * Find ADB using Spotlight search (mdfind) - macOS only.
     */
    private fun findAdbWithSpotlight(): String? {
        val result = runCommand(
            listOf("mdfind", "-name", "adb", "-onlyin", "/"),
            10, TimeUnit.SECONDS, "Spotlight adb search"
        ) ?: return null

        // Filter for actual adb executables in platform-tools
        for (line in result.output.lines()) {
            if (line.contains("platform-tools") && line.endsWith("/adb")) {
                val adbPath = File(line)
                if (adbPath.exists() && adbPath.canExecute()) {
                    return adbPath.absolutePath
                }
            }
        }
        return null
    }

    /** Exit code plus merged stdout/stderr of an external command. */
    private data class CommandResult(val exitCode: Int, val output: String)

    /**
     * Run an external command and capture its merged output under a hard time budget.
     *
     * Every call site used to either read the pipe and then waitFor(), or waitFor() and then
     * read - both hang the caller for good if the child outgrows the OS pipe buffer or simply
     * keeps stdout open (`adb` forks a server that inherits it, `mdfind -onlyin /` can print
     * thousands of lines, a login shell may never exit). Draining on a separate thread while
     * the caller waits with a *bounded* waitFor is the only shape that cannot deadlock, and the
     * process is destroyed on the way out whatever happened.
     *
     * @return null when the command could not be started, timed out, or was interrupted -
     *   never a partial result that a caller might mistake for an answer.
     */
    private fun runCommand(
        command: List<String>,
        timeout: Long,
        unit: TimeUnit,
        purpose: String
    ): CommandResult? {
        var process: Process? = null
        try {
            val started = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            process = started

            // None of these commands read stdin; a child blocked on it would never reach EOF
            // on stdout, so close the write end immediately.
            try {
                started.outputStream.close()
            } catch (e: Exception) {
                logger.debug("Could not close stdin for $purpose: ${e.message}")
            }

            val output = StringBuilder()
            val drain = Thread({
                try {
                    started.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(output) { output.append(line).append('\n') }
                    }
                } catch (e: Exception) {
                    // Expected once the process is destroyed after a timeout: the pipe closes
                    // under the reader, and there is no verdict left to salvage anyway.
                    logger.debug("Output reader for $purpose stopped: ${e.message}")
                }
            }, "MockkHttp-cmd-$purpose")
            drain.isDaemon = true
            drain.start()

            if (!started.waitFor(timeout, unit)) {
                logger.warn("⚠️ Timed out after ${unit.toSeconds(timeout)}s running $purpose (${command.firstOrNull()})")
                return null
            }

            // The child is already gone, so EOF is imminent - this join is a guard, not a wait.
            drain.join(DRAIN_JOIN_TIMEOUT_MS)

            return CommandResult(started.exitValue(), synchronized(output) { output.toString() })

        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("⚠️ Interrupted while running $purpose")
            return null
        } catch (e: Exception) {
            logger.warn("⚠️ Failed to run $purpose (${command.firstOrNull()}): ${e.javaClass.simpleName}: ${e.message}")
            return null
        } finally {
            process?.destroyForcibly()
        }
    }

    /**
     * Get list of connected emulators.
     * Returns only emulators, not physical devices.
     */
    fun getConnectedEmulators(): List<EmulatorInfo> {
        return getConnectedDevices().filter { it.isEmulator }
    }

    /**
     * Get list of ALL connected devices (emulators + physical devices).
     */
    fun getConnectedDevices(): List<EmulatorInfo> {
        logger.info("🔍 Detecting connected devices...")

        if (!isInitialized) {
            logger.warn("ADB not initialized, attempting to initialize...")
            if (!initialize()) {
                logger.error("Cannot detect devices: ADB initialization failed")
                return emptyList()
            }
        }

        try {
            val devices = adbBridge?.devices ?: emptyArray()
            logger.debug("Found ${devices.size} device(s) connected")

            val deviceList = devices.map { device ->
                logger.debug("Processing device: ${device.serialNumber} (emulator=${device.isEmulator})")
                convertToEmulatorInfo(device)
            }

            val emulatorCount = deviceList.count { it.isEmulator }
            val physicalCount = deviceList.count { !it.isEmulator }
            logger.info("✅ Found $emulatorCount emulator(s) and $physicalCount physical device(s)")
            deviceList.forEach { device ->
                logger.debug("  - ${device.fullDescription}")
            }

            return deviceList

        } catch (e: Exception) {
            logger.error("Failed to get connected devices", e)
            return emptyList()
        }
    }
    
    /**
     * Get specific emulator by serial number.
     */
    fun getEmulator(serialNumber: String): EmulatorInfo? {
        logger.debug("Looking for emulator: $serialNumber")
        
        return getConnectedEmulators()
            .find { it.serialNumber == serialNumber }
            ?.also { logger.debug("Emulator found: ${it.fullDescription}") }
            ?: run {
                logger.warn("Emulator not found: $serialNumber")
                null
            }
    }
    
    /**
     * Get IDevice by serial number.
     * Returns the underlying IDevice for advanced operations.
     */
    fun getDevice(serialNumber: String): IDevice? {
        logger.debug("Getting device: $serialNumber")

        if (!isInitialized) {
            logger.warn("ADB not initialized, attempting to initialize...")
            if (!initialize()) {
                logger.error("Cannot get device: ADB initialization failed")
                return null
            }
        }

        return adbBridge?.devices?.find { it.serialNumber == serialNumber }
    }
    
    /**
     * Convert IDevice to EmulatorInfo.
     */
    private fun convertToEmulatorInfo(device: IDevice): EmulatorInfo {
        val apiLevel = try {
            device.getProperty(IDevice.PROP_BUILD_API_LEVEL)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            logger.warn("Failed to get API level for ${device.serialNumber}", e)
            0
        }

        // Use getProperty() instead of deprecated getAvdName()
        val avdName = device.getProperty("ro.kernel.qemu.avd_name") ?: device.serialNumber
        val architecture = device.getProperty(IDevice.PROP_DEVICE_CPU_ABI)
        val manufacturer = device.getProperty(IDevice.PROP_DEVICE_MANUFACTURER)
        val model = device.getProperty(IDevice.PROP_DEVICE_MODEL)
        
        return EmulatorInfo(
            serialNumber = device.serialNumber,
            avdName = avdName,
            apiLevel = apiLevel,
            isOnline = device.isOnline,
            architecture = architecture,
            manufacturer = manufacturer,
            model = model,
            isEmulator = device.isEmulator
        )
    }

    /**
     * Set up ADB reverse port forwarding for physical devices.
     * Maps device's localhost:remotePort to host's localhost:localPort.
     * This allows physical devices to reach the plugin server via localhost.
     */
    fun setupAdbReverse(serialNumber: String, remotePort: Int, localPort: Int): Boolean {
        val adbPath = getConfiguredOrDetectedAdbPath()
        if (adbPath == null) {
            logger.error("❌ Cannot set up ADB reverse: ADB path not found")
            return false
        }

        try {
            logger.info("🔄 Setting up ADB reverse: device:$remotePort -> host:$localPort (device=$serialNumber)")
            val result = runCommand(
                listOf(adbPath, "-s", serialNumber, "reverse", "tcp:$remotePort", "tcp:$localPort"),
                5, TimeUnit.SECONDS, "adb reverse (setup)"
            )
            val success = result != null && result.exitCode == 0

            if (success) {
                logger.info("✅ ADB reverse port forwarding active: device:$remotePort -> host:$localPort")
            } else {
                logger.error("❌ Failed to set up ADB reverse: ${result?.output?.trim() ?: "command did not complete"}")
            }
            return success
        } catch (e: Exception) {
            logger.error("❌ Failed to set up ADB reverse", e)
            return false
        }
    }

    /**
     * Remove ADB reverse port forwarding.
     */
    fun removeAdbReverse(serialNumber: String, remotePort: Int): Boolean {
        val adbPath = getConfiguredOrDetectedAdbPath()
        if (adbPath == null) {
            logger.warn("⚠️ Cannot remove ADB reverse for port $remotePort: ADB path not found")
            return false
        }

        try {
            logger.info("🔄 Removing ADB reverse for port $remotePort (device=$serialNumber)")
            val result = runCommand(
                listOf(adbPath, "-s", serialNumber, "reverse", "--remove", "tcp:$remotePort"),
                5, TimeUnit.SECONDS, "adb reverse (remove)"
            )

            // Previously this reported success even when adb had failed or never returned,
            // which is exactly the lie a blind caller cannot recover from.
            if (result == null || result.exitCode != 0) {
                logger.warn(
                    "⚠️ Failed to remove ADB reverse for port $remotePort: " +
                    (result?.output?.trim()?.takeIf { it.isNotEmpty() } ?: "command did not complete")
                )
                return false
            }

            logger.info("✅ ADB reverse removed for port $remotePort")
            return true
        } catch (e: Exception) {
            logger.error("Failed to remove ADB reverse", e)
            return false
        }
    }
    
    /**
     * Add listener for device changes (connect/disconnect).
     */
    fun addDeviceChangeListener(listener: () -> Unit) {
        deviceChangeListeners.add(listener)
    }

    /**
     * Notify all listeners of device changes.
     */
    private fun notifyDeviceChange() {
        // Notify listeners in a safe manner
        deviceChangeListeners.toList().forEach { listener ->
            try {
                listener()
            } catch (e: Exception) {
                logger.error("Error in device change listener", e)
            }
        }
    }

    /**
     * Simple output receiver for shell commands.
     * Made package-private for use in other managers.
     */
    class CollectingOutputReceiver(
        private val cancelled: () -> Boolean = { false }
    ) : com.android.ddmlib.IShellOutputReceiver {
        private val outputBuilder = StringBuilder()
        
        val output: String
            get() = outputBuilder.toString()
        
        override fun addOutput(data: ByteArray, offset: Int, length: Int) {
            outputBuilder.append(String(data, offset, length))
        }
        
        override fun flush() {
            // No-op
        }
        
        /**
         * ddmlib polls this inside its socket read loop while a shell command is running:
         * returning true breaks that loop and aborts the command *in flight*. Wiring it to
         * a constant `false` (as it was) makes every executeShellCommand uninterruptible.
         *
         * Must never throw - it is a plain boolean probe, never ProgressManager.checkCanceled().
         */
        override fun isCancelled(): Boolean = cancelled()
    }
}
