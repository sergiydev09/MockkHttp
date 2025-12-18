package com.sergiy.dev.mockkhttp.adb

import com.android.ddmlib.AdbInitOptions
import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import com.sergiy.dev.mockkhttp.store.SettingsStore
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manager for detecting and managing Android emulators via ADB.
 * Handles ADB bridge initialization and emulator discovery.
 */
@Service(Service.Level.PROJECT)
class EmulatorManager(private val project: Project) {

    private val logger = MockkHttpLogger.getInstance(project)
    private var adbBridge: AndroidDebugBridge? = null
    private var isInitialized = false
    private val deviceChangeListeners = mutableListOf<() -> Unit>()
    private var deviceListener: AndroidDebugBridge.IDeviceChangeListener? = null

    companion object {
        fun getInstance(project: Project): EmulatorManager {
            return project.getService(EmulatorManager::class.java)
        }

        private const val ADB_INIT_TIMEOUT_MS = 10000L
    }

    /**
     * Initialize ADB bridge.
     * Must be called before any ADB operations.
     */
    fun initialize(): Boolean {
        logger.info("🔧 Initializing ADB bridge...")

        if (isInitialized && adbBridge != null) {
            logger.debug("ADB bridge already initialized")
            return true
        }

        try {
            // Find ADB executable - first check settings, then auto-detect
            val adbPath = getConfiguredOrDetectedAdbPath()
            if (adbPath == null) {
                logger.error("ADB executable not found. Please configure it in Settings tab or install Android SDK Platform Tools.")
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
            adbBridge = AndroidDebugBridge.createBridge(
                adbPath,
                false,
                ADB_INIT_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
            
            if (adbBridge == null) {
                logger.error("Failed to create ADB bridge")
                return false
            }
            
            // Wait for bridge to connect
            logger.debug("Waiting for ADB bridge to connect...")
            val startTime = System.currentTimeMillis()
            while (!adbBridge!!.isConnected && 
                   System.currentTimeMillis() - startTime < ADB_INIT_TIMEOUT_MS) {
                Thread.sleep(100)
            }
            
            if (!adbBridge!!.isConnected) {
                logger.error("ADB bridge connection timeout")
                return false
            }
            
            // Wait for initial device list
            logger.debug("Waiting for device list...")
            var waited = 0L
            while (!adbBridge!!.hasInitialDeviceList() && waited < ADB_INIT_TIMEOUT_MS) {
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

        } catch (e: Exception) {
            logger.error("Failed to initialize ADB bridge", e)
            isInitialized = false
            return false
        }
    }

    /**
     * Get ADB path from settings if configured, otherwise auto-detect.
     * This is the preferred method to get the ADB path.
     */
    fun getConfiguredOrDetectedAdbPath(): String? {
        // First, check if user has configured a path in settings
        try {
            val settingsStore = SettingsStore.getInstance(project)
            val configuredPath = settingsStore.getAdbPath()
            if (configuredPath != null) {
                val file = File(configuredPath)
                if (file.exists() && file.canExecute()) {
                    logger.info("✅ Using configured ADB path: $configuredPath")
                    return configuredPath
                } else {
                    logger.warn("⚠️ Configured ADB path is invalid: $configuredPath (falling back to auto-detect)")
                }
            }
        } catch (e: Exception) {
            logger.debug("Could not read settings (may be initializing): ${e.message}")
        }

        // Fall back to auto-detection
        return findAdbPath()
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
        if (isMacOS) {
            try {
                // Run a login shell to get the proper environment variables
                val shellEnvProcess = ProcessBuilder("/bin/bash", "-l", "-c", "echo \$ANDROID_HOME")
                    .redirectErrorStream(true)
                    .start()
                val shellAndroidHome = shellEnvProcess.inputStream.bufferedReader().readText().trim()
                shellEnvProcess.waitFor(5, TimeUnit.SECONDS)
                if (shellAndroidHome.isNotBlank() && shellAndroidHome != "\$ANDROID_HOME") {
                    checkAdbPath(File(shellAndroidHome, "platform-tools/$adbExecutable"), "Shell ANDROID_HOME (bash)")?.let { return it }
                }
            } catch (e: Exception) {
                logger.debug("Failed to get ANDROID_HOME from bash: ${e.message}")
            }

            // Also try zsh (default shell on modern macOS)
            try {
                val zshEnvProcess = ProcessBuilder("/bin/zsh", "-l", "-c", "echo \$ANDROID_HOME")
                    .redirectErrorStream(true)
                    .start()
                val zshAndroidHome = zshEnvProcess.inputStream.bufferedReader().readText().trim()
                zshEnvProcess.waitFor(5, TimeUnit.SECONDS)
                if (zshAndroidHome.isNotBlank() && zshAndroidHome != "\$ANDROID_HOME") {
                    checkAdbPath(File(zshAndroidHome, "platform-tools/$adbExecutable"), "Shell ANDROID_HOME (zsh)")?.let { return it }
                }
            } catch (e: Exception) {
                logger.debug("Failed to get ANDROID_HOME from zsh: ${e.message}")
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
        try {
            val command = if (isWindows) arrayOf("cmd", "/c", "where", "adb") else arrayOf("which", "adb")
            val process = Runtime.getRuntime().exec(command)
            val result = process.inputStream.bufferedReader().readText().trim()
            process.waitFor(5, TimeUnit.SECONDS)
            if (process.exitValue() == 0 && result.isNotBlank()) {
                val adbPath = File(result.lines().first())
                if (adbPath.exists() && adbPath.canExecute()) {
                    logger.info("✅ ADB found via ${if (isWindows) "where" else "which"} command: ${adbPath.absolutePath}")
                    return adbPath.absolutePath
                }
            }
        } catch (e: Exception) {
            logger.debug("Failed to run which/where command: ${e.message}")
        }

        // Log all tried paths for debugging
        logger.error("❌ ADB not found. Tried the following locations:")
        triedPaths.forEach { logger.error("  - $it") }
        logger.error("💡 Solutions:")
        logger.error("  1. Install Android SDK Platform Tools: brew install --cask android-platform-tools")
        logger.error("  2. Set ANDROID_HOME in your shell profile (~/.zshrc or ~/.bash_profile):")
        logger.error("     export ANDROID_HOME=\$HOME/Library/Android/sdk")
        logger.error("     export PATH=\$PATH:\$ANDROID_HOME/platform-tools")
        logger.error("  3. Restart Android Studio after setting environment variables")
        logger.error("  4. Or launch Android Studio from terminal: open -a 'Android Studio'")

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
            try {
                val process = ProcessBuilder(shell, "-l", "-c", "which adb")
                    .redirectErrorStream(true)
                    .start()
                val result = process.inputStream.bufferedReader().readText().trim()
                process.waitFor(5, TimeUnit.SECONDS)
                if (process.exitValue() == 0 && result.isNotBlank()) {
                    val adbPath = File(result)
                    if (adbPath.exists() && adbPath.canExecute()) {
                        return adbPath.absolutePath
                    }
                }
            } catch (e: Exception) {
                logger.debug("Failed to find adb with $shell: ${e.message}")
            }
        }
        return null
    }

    /**
     * Find ADB using Spotlight search (mdfind) - macOS only.
     */
    private fun findAdbWithSpotlight(): String? {
        try {
            val process = ProcessBuilder("mdfind", "-name", "adb", "-onlyin", "/")
                .redirectErrorStream(true)
                .start()
            val results = process.inputStream.bufferedReader().readLines()
            process.waitFor(10, TimeUnit.SECONDS)

            // Filter for actual adb executables in platform-tools
            for (line in results) {
                if (line.contains("platform-tools") && line.endsWith("/adb")) {
                    val adbPath = File(line)
                    if (adbPath.exists() && adbPath.canExecute()) {
                        return adbPath.absolutePath
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug("Failed to find adb with Spotlight: ${e.message}")
        }
        return null
    }
    
    /**
     * Get list of connected emulators.
     * Returns only emulators, not physical devices.
     */
    fun getConnectedEmulators(): List<EmulatorInfo> {
        logger.info("🔍 Detecting connected emulators...")
        
        if (!isInitialized) {
            logger.warn("ADB not initialized, attempting to initialize...")
            if (!initialize()) {
                logger.error("Cannot detect emulators: ADB initialization failed")
                return emptyList()
            }
        }
        
        try {
            val devices = adbBridge?.devices ?: emptyArray()
            logger.debug("Found ${devices.size} device(s) connected")
            
            val emulators = devices
                .filter { it.isEmulator }
                .map { device ->
                    logger.debug("Processing emulator: ${device.serialNumber}")
                    convertToEmulatorInfo(device)
                }
            
            logger.info("✅ Found ${emulators.size} emulator(s)")
            emulators.forEach { emulator ->
                logger.debug("  - ${emulator.fullDescription}")
            }
            
            return emulators
            
        } catch (e: Exception) {
            logger.error("Failed to get connected emulators", e)
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
            model = model
        )
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
    class CollectingOutputReceiver : com.android.ddmlib.IShellOutputReceiver {
        private val outputBuilder = StringBuilder()
        
        val output: String
            get() = outputBuilder.toString()
        
        override fun addOutput(data: ByteArray, offset: Int, length: Int) {
            outputBuilder.append(String(data, offset, length))
        }
        
        override fun flush() {
            // No-op
        }
        
        override fun isCancelled(): Boolean = false
    }
}
