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
     */
    fun getInstalledApps(serialNumber: String, includeSystem: Boolean = false): List<AppInfo> {
        logger.info("📱 Scanning apps with MockkHttp on $serialNumber...")

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

            logger.info("📦 Found ${packageNames.size} packages, checking for MockkHttp...")

            if (packageNames.isEmpty()) return emptyList()

            // Step 2: Check all apps in parallel (10 concurrent ADB connections)
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
     * Returns true if the app contains the MockkHttpInterceptor class.
     * Uses multiple detection methods for compatibility with emulators and physical devices.
     */
    private fun hasMockkHttpInstalled(device: IDevice, packageName: String): Boolean {
        try {
            // Get APK path
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

            logger.debug("APK paths for $packageName: $apkPaths")

            // Try each APK path (split APKs may have multiple)
            for (apkPath in apkPaths) {
                // Method 1: grep the binary APK for the class name string (works on all devices)
                val grepReceiver = EmulatorManager.CollectingOutputReceiver()
                device.executeShellCommand(
                    "grep -c 'MockkHttpInterceptor' $apkPath",
                    grepReceiver,
                    10,
                    TimeUnit.SECONDS
                )

                val grepOutput = grepReceiver.output.trim()
                val matchCount = grepOutput.toIntOrNull() ?: 0
                if (matchCount > 0) {
                    logger.info("✅ $packageName HAS MockkHttp interceptor! (grep: $matchCount matches in $apkPath)")
                    return true
                }

                // Method 2: dexdump fallback (available on emulators and some devices)
                try {
                    val dexReceiver = EmulatorManager.CollectingOutputReceiver()
                    device.executeShellCommand(
                        "dexdump -f $apkPath 2>/dev/null | grep 'com.sergiy.dev.mockkhttp.interceptor.MockkHttpInterceptor'",
                        dexReceiver,
                        10,
                        TimeUnit.SECONDS
                    )

                    if (dexReceiver.output.contains("MockkHttpInterceptor")) {
                        logger.info("✅ $packageName HAS MockkHttp interceptor! (dexdump)")
                        return true
                    }
                } catch (e: Exception) {
                    logger.debug("dexdump not available for $apkPath: ${e.message}")
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


