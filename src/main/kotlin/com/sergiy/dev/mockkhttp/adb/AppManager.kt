package com.sergiy.dev.mockkhttp.adb

import com.android.ddmlib.IDevice
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/**
 * Manager for handling installed applications on emulators.
 * Provides functionality to list, start, and stop apps.
 */
@Service(Service.Level.PROJECT)
class AppManager(project: Project) {

    private val logger = MockkHttpLogger.getInstance(project)
    private val emulatorManager = EmulatorManager.getInstance(project)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scanDispatcher = Dispatchers.IO.limitedParallelism(10)

    companion object {
        fun getInstance(project: Project): AppManager {
            return project.getService(AppManager::class.java)
        }

        private const val SHELL_TIMEOUT_SECONDS = 30L
    }
    
    /**
     * Get list of installed applications on device that have MockkHttp.
     * Uses the proven per-app detection (pm path + grep -c) which works
     * reliably on both emulators and physical devices.
     * Only fetches details (dumpsys) for matching apps.
     * @param serialNumber Device serial number
     * @param includeSystem Whether to include system apps
     * @param showAllApps If true, returns ALL third-party apps (for Flutter or undetectable integrations)
     */
    fun getInstalledApps(serialNumber: String, includeSystem: Boolean = false, showAllApps: Boolean = false): List<AppInfo> {
        logger.info("📱 Scanning apps on $serialNumber (showAll=$showAllApps)...")

        // Ensure the global server is running so it can receive PING handshakes
        val globalServer = com.sergiy.dev.mockkhttp.proxy.GlobalOkHttpInterceptorServer.getInstance()
        globalServer.ensureStarted()

        try {
            val device = getDevice(serialNumber)
            if (device == null) {
                logger.error("Device not found: $serialNumber")
                return emptyList()
            }

            // Step 1: Get all third-party package names (single command)
            val receiver = EmulatorManager.CollectingOutputReceiver()
            val command = if (includeSystem) "pm list packages" else "pm list packages -3"
            device.executeShellCommand(command, receiver, 30, TimeUnit.SECONDS)

            val packageNames = receiver.output.lines()
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .filter { it.isNotBlank() }

            logger.info("📦 Found ${packageNames.size} packages")

            if (packageNames.isEmpty()) return emptyList()

            if (showAllApps) {
                // Return ALL third-party apps without MockkHttp detection
                logger.info("📋 Returning all ${packageNames.size} third-party apps")
                val apps = packageNames.map { pkg ->
                    createAppInfo(device, pkg, hasMockkHttp = false)
                }
                return apps
            }

            // Step 2: Check all apps in parallel (10 concurrent ADB connections)
            logger.info("🔍 Checking for MockkHttp in ${packageNames.size} packages...")
            val mockkHttpPackages = runBlocking {
                packageNames.map { pkg ->
                    async(scanDispatcher) {
                        if (hasMockkHttpInstalled(device, pkg)) pkg else null
                    }
                }.awaitAll().filterNotNull()
            }

            logger.info("🔍 Found ${mockkHttpPackages.size} app(s) with MockkHttp")

            // Step 3: Get details only for matching apps (dumpsys per match)
            val apps = mockkHttpPackages.map { pkg ->
                createAppInfo(device, pkg, hasMockkHttp = true)
            }

            apps.forEach { app ->
                logger.info("  ✅ ${app.packageName} (v${app.versionName ?: "?"})")
            }

            return apps

        } catch (e: Exception) {
            logger.error("Failed to get installed apps on $serialNumber", e)
            return emptyList()
        }
    }

    /**
     * Get IDevice from serial number.
     * Works with both emulators and physical devices.
     */
    private fun getDevice(serialNumber: String): IDevice? {
        val device = emulatorManager.getDevice(serialNumber)
        if (device == null) {
            logger.warn("Device not found: $serialNumber")
            return null
        }

        if (!device.isOnline) {
            logger.warn("Device is not online: $serialNumber")
            return null
        }

        return device
    }
    
    /**
     * Get the UID (User ID) for a specific package.
     * This UID is used to filter network traffic for the app.
     */
    fun getAppUid(device: IDevice, packageName: String): Int? {
        try {
            // Method 1: Try packages.list (most reliable)
            var receiver = EmulatorManager.CollectingOutputReceiver()
            device.executeShellCommand(
                "cat /data/system/packages.list | grep $packageName",
                receiver,
                5,
                TimeUnit.SECONDS
            )

            var output = receiver.output
            if (output.isNotBlank()) {
                // Format: package_name uid debuggable data_dir seinfo
                val parts = output.trim().split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val uid = parts[1].toIntOrNull()
                    if (uid != null) {
                        logger.info("✅ UID for $packageName: $uid (from packages.list)")
                        return uid
                    }
                }
            }

            // Method 2: Try dumpsys package (fallback)
            receiver = EmulatorManager.CollectingOutputReceiver()
            device.executeShellCommand(
                "dumpsys package $packageName",
                receiver,
                10,
                TimeUnit.SECONDS
            )

            output = receiver.output

            // Try different patterns
            val patterns = listOf(
                Regex("userId=(\\d+)"),
                Regex("appId=(\\d+)"),
                Regex("uid=(\\d+)")
            )

            for (pattern in patterns) {
                val match = pattern.find(output)
                if (match != null) {
                    val uid = match.groupValues[1].toIntOrNull()
                    if (uid != null) {
                        logger.info("✅ UID for $packageName: $uid (from dumpsys)")
                        return uid
                    }
                }
            }

            logger.error("❌ Could not find UID for $packageName")
            logger.debug("Dumpsys output (first 500 chars): ${output.take(500)}")
            return null

        } catch (e: Exception) {
            logger.error("Failed to get UID for $packageName", e)
            return null
        }
    }

    /**
     * Check if an app has MockkHttp interceptor installed.
     * Detects both Android native (OkHttp interceptor) and Flutter (Dart package) integrations.
     *
     * Detection methods (in order):
     * 1. PING handshake: App already announced itself to the plugin server
     * 2. Marker file: Flutter apps write /data/local/tmp/mockk_http_<package> on init
     * 3. APK grep: Search for "MockkHttpInterceptor" class in APK binary (Android native)
     * 4. Flutter APK: Pull APK, extract kernel_blob.bin from ZIP, search for "mockk_http" (Flutter debug)
     */
    private fun hasMockkHttpInstalled(device: IDevice, packageName: String): Boolean {
        try {
            // Method 1: Check if app announced itself via PING handshake
            val globalServer = com.sergiy.dev.mockkhttp.proxy.GlobalOkHttpInterceptorServer.getInstance()
            if (globalServer.isKnownMockkHttpPackage(packageName)) {
                logger.info("✅ $packageName HAS MockkHttp (detected via PING handshake)")
                return true
            }

            // Method 2: Check marker file (Flutter apps write this on MockkHttp.init())
            val markerReceiver = EmulatorManager.CollectingOutputReceiver()
            device.executeShellCommand(
                "test -f /data/local/tmp/mockk_http_$packageName && echo EXISTS",
                markerReceiver,
                3,
                TimeUnit.SECONDS
            )
            if (markerReceiver.output.trim() == "EXISTS") {
                logger.info("✅ $packageName HAS MockkHttp (Flutter, marker file detected)")
                return true
            }

            // Get APK paths
            val pathReceiver = EmulatorManager.CollectingOutputReceiver()
            device.executeShellCommand("pm path $packageName", pathReceiver, 5, TimeUnit.SECONDS)

            val apkPaths = pathReceiver.output
                .lines()
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }

            if (apkPaths.isEmpty()) {
                logger.debug("Could not find APK path for $packageName")
                return false
            }

            for (apkPath in apkPaths) {
                // Method 3: Grep APK binary for Android native class (uncompressed in dex)
                val grepReceiver = EmulatorManager.CollectingOutputReceiver()
                device.executeShellCommand(
                    "grep -c 'MockkHttpInterceptor' $apkPath",
                    grepReceiver,
                    10,
                    TimeUnit.SECONDS
                )

                val matchCount = grepReceiver.output.trim().toIntOrNull() ?: 0
                if (matchCount > 0) {
                    logger.info("✅ $packageName HAS MockkHttp (Android native, grep: $matchCount matches)")
                    return true
                }

                // Method 4: Pull APK and check inside the ZIP for Flutter MockkHttp.
                // This catches Flutter apps where kernel_blob.bin (debug) or libapp.so (release)
                // contain "mockk_http" strings that are invisible to device-side grep.
                logger.info("🔍 Pulling APK for $packageName to check for Flutter MockkHttp...")
                if (checkFlutterApkForMockkHttp(device, apkPath, packageName)) {
                    return true
                }
            }

            logger.debug("📝 $packageName does NOT have MockkHttp")
            return false

        } catch (e: Exception) {
            logger.debug("Failed to check MockkHttp for $packageName: ${e.message}")
            return false
        }
    }

    /**
     * Pull APK from device, open as ZIP, and search compressed entries for MockkHttp strings.
     * This detects Flutter apps where kernel_blob.bin (debug) or libapp.so (release) contains
     * Dart strings that are compressed inside the APK and invisible to device-side grep.
     */
    private fun checkFlutterApkForMockkHttp(device: IDevice, apkPath: String, packageName: String): Boolean {
        val tempFile = java.io.File.createTempFile("mockk_flutter_", ".apk")
        try {
            // Pull APK from device to local temp file
            device.pullFile(apkPath, tempFile.absolutePath)

            java.util.zip.ZipFile(tempFile).use { zip ->
                // Quick check: is this a Flutter app? (just read ZIP directory, no decompression)
                val hasKernelBlob = zip.getEntry("assets/flutter_assets/kernel_blob.bin") != null
                val libAppEntry = zip.entries().asSequence().find { it.name.endsWith("libapp.so") }
                val isFlutter = hasKernelBlob || libAppEntry != null

                if (!isFlutter) {
                    logger.info("📝 $packageName is not a Flutter app, skipping deep check")
                    return false
                }

                logger.info("🔍 $packageName is a Flutter app, searching for MockkHttp...")

                // Check kernel_blob.bin (Flutter debug builds)
                if (hasKernelBlob) {
                    val kernelEntry = zip.getEntry("assets/flutter_assets/kernel_blob.bin")!!
                    val bytes = zip.getInputStream(kernelEntry).readBytes()
                    if (bytesContainString(bytes, "mockk_http")) {
                        logger.info("✅ $packageName HAS MockkHttp (Flutter debug, kernel_blob.bin)")
                        return true
                    }
                }

                // Check libapp.so (Flutter release/profile builds)
                if (libAppEntry != null) {
                    val bytes = zip.getInputStream(libAppEntry).readBytes()
                    if (bytesContainString(bytes, "mockk_http")) {
                        logger.info("✅ $packageName HAS MockkHttp (Flutter release, ${libAppEntry.name})")
                        return true
                    }
                }
            }

            logger.debug("📝 $packageName is Flutter but does NOT contain MockkHttp")
            return false
        } catch (e: Exception) {
            logger.warn("⚠️ Failed to check Flutter APK for $packageName: ${e.javaClass.simpleName}: ${e.message}")
            return false
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Efficiently search for an ASCII string in a byte array without converting the entire array to String.
     */
    private fun bytesContainString(bytes: ByteArray, target: String): Boolean {
        val targetBytes = target.toByteArray(Charsets.US_ASCII)
        if (targetBytes.size > bytes.size) return false

        outer@ for (i in 0..bytes.size - targetBytes.size) {
            for (j in targetBytes.indices) {
                if (bytes[i + j] != targetBytes[j]) continue@outer
            }
            return true
        }
        return false
    }

    /**
     * Create AppInfo from package name.
     * @param hasMockkHttp Pre-determined MockkHttp status (from batch scan)
     */
    private fun createAppInfo(device: IDevice, packageName: String, hasMockkHttp: Boolean = false): AppInfo {
        try {
            // Get version info from dumpsys (single command for UID + version)
            val receiver = EmulatorManager.CollectingOutputReceiver()
            device.executeShellCommand(
                "dumpsys package $packageName",
                receiver,
                10,
                TimeUnit.SECONDS
            )

            val output = receiver.output

            val versionName = output.lines()
                .find { it.contains("versionName") }
                ?.substringAfter("versionName=")
                ?.trim()
                ?.takeWhile { !it.isWhitespace() }

            val versionCode = output.lines()
                .find { it.contains("versionCode") }
                ?.substringAfter("versionCode=")
                ?.split(Regex("\\s+"))?.firstOrNull()
                ?.toIntOrNull()

            // Extract UID from the same dumpsys output (avoids extra shell command)
            val uid = listOf(Regex("userId=(\\d+)"), Regex("appId=(\\d+)"), Regex("uid=(\\d+)"))
                .firstNotNullOfOrNull { it.find(output)?.groupValues?.get(1)?.toIntOrNull() }

            return AppInfo(
                packageName = packageName,
                appName = null,
                versionName = versionName,
                versionCode = versionCode,
                isSystemApp = false,
                uid = uid,
                hasMockkHttp = hasMockkHttp
            )

        } catch (e: Exception) {
            logger.debug("Failed to get details for $packageName: ${e.message}")
            return AppInfo(
                packageName = packageName,
                appName = null,
                versionName = null,
                versionCode = null,
                isSystemApp = false,
                uid = null,
                hasMockkHttp = hasMockkHttp
            )
        }
    }

    /**
     * Force-stop an app and optionally restart it.
     * This is useful for clearing app's network cache after proxy configuration changes.
     */
    fun forceStopApp(serialNumber: String, packageName: String): Boolean {
        logger.info("🔴 Force-stopping app: $packageName on $serialNumber")

        try {
            val device = getDevice(serialNumber)
            if (device == null) {
                logger.error("Device not found: $serialNumber")
                return false
            }

            val receiver = EmulatorManager.CollectingOutputReceiver()
            device.executeShellCommand("am force-stop $packageName", receiver, 10, TimeUnit.SECONDS)

            val output = receiver.output
            if (output.contains("Error", ignoreCase = true)) {
                logger.error("❌ Failed to force-stop: $output")
                return false
            }

            logger.info("✅ App force-stopped: $packageName")
            return true

        } catch (e: Exception) {
            logger.error("Failed to force-stop app", e)
            return false
        }
    }

    /**
     * Start an app's main activity.
     */
    fun startApp(serialNumber: String, packageName: String): Boolean {
        logger.info("▶️ Starting app: $packageName on $serialNumber")

        try {
            val device = getDevice(serialNumber)
            if (device == null) {
                logger.error("Device not found: $serialNumber")
                return false
            }

            val receiver = EmulatorManager.CollectingOutputReceiver()
            // Use monkey to launch the app (more reliable than getting main activity)
            device.executeShellCommand("monkey -p $packageName -c android.intent.category.LAUNCHER 1", receiver, 10, TimeUnit.SECONDS)

            val output = receiver.output
            if (output.contains("No activities found", ignoreCase = true) ||
                output.contains("Error", ignoreCase = true)) {
                logger.error("❌ Failed to start app: $output")
                return false
            }

            logger.info("✅ App started: $packageName")
            return true

        } catch (e: Exception) {
            logger.error("Failed to start app", e)
            return false
        }
    }

    /**
     * Restart an app (force-stop then start).
     * Useful to clear network cache and force re-connection through proxy.
     */
    fun restartApp(serialNumber: String, packageName: String): Boolean {
        logger.info("🔄 Restarting app: $packageName")

        if (!forceStopApp(serialNumber, packageName)) {
            return false
        }

        // Wait briefly for app to fully stop
        Thread.sleep(1000)

        return startApp(serialNumber, packageName)
    }
}


