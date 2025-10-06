package com.sergiy.dev.mockkhttp.adb

import com.android.ddmlib.IDevice
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import java.util.concurrent.TimeUnit

/**
 * Manager for handling installed applications on emulators.
 * Provides functionality to list, start, and stop apps.
 */
@Service(Service.Level.PROJECT)
class AppManager(project: Project) {

    private val logger = MockkHttpLogger.getInstance(project)
    private val emulatorManager = EmulatorManager.getInstance(project)
    
    companion object {
        fun getInstance(project: Project): AppManager {
            return project.getService(AppManager::class.java)
        }
        
        private const val SHELL_TIMEOUT_SECONDS = 30L
    }
    
    /**
     * Get list of installed applications on emulator.
     * @param serialNumber Emulator serial number
     * @param includeSystem Whether to include system apps
     */
    fun getInstalledApps(serialNumber: String, includeSystem: Boolean = false): List<AppInfo> {
        logger.info("📱 Listing installed apps on $serialNumber (includeSystem=$includeSystem)...")
        
        try {
            val device = getDevice(serialNumber)
            if (device == null) {
                logger.error("Device not found: $serialNumber")
                return emptyList()
            }
            
            // Get package list
            val receiver = EmulatorManager.CollectingOutputReceiver()
            val command = if (includeSystem) {
                "pm list packages"
            } else {
                "pm list packages -3"  // Only third-party apps
            }
            
            logger.debug("Executing command: $command")
            device.executeShellCommand(command, receiver, SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            val output = receiver.output
            val packageNames = output.lines()
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
            
            logger.debug("Found ${packageNames.size} package(s)")
            
            // Convert to AppInfo (we'll parse more details if needed)
            val apps = packageNames.map { packageName ->
                createAppInfo(device, packageName)
            }
            
            logger.info("✅ Retrieved ${apps.size} app(s) from $serialNumber")
            
            // Log some examples
            apps.take(3).forEach { app ->
                logger.debug("  - ${app.fullDescription}")
            }
            if (apps.size > 3) {
                logger.debug("  ... and ${apps.size - 3} more")
            }
            
            return apps
            
        } catch (e: Exception) {
            logger.error("Failed to get installed apps on $serialNumber", e)
            return emptyList()
        }
    }

    /**
     * Get IDevice from serial number.
     */
    private fun getDevice(serialNumber: String): IDevice? {
        val emulator = emulatorManager.getEmulator(serialNumber)
        if (emulator == null) {
            logger.warn("Emulator not found: $serialNumber")
            return null
        }
        
        if (!emulator.isOnline) {
            logger.warn("Emulator is not online: $serialNumber")
            return null
        }
        
        // Access the bridge through reflection or use the manager
        // For now, we'll get it from EmulatorManager
        return emulatorManager.getConnectedEmulators()
            .find { it.serialNumber == serialNumber }
            ?.let { _ ->
                // We need to access the actual IDevice
                // This is a bit hacky, but ddmlib doesn't expose it directly
                try {
                    val bridgeField = EmulatorManager::class.java.getDeclaredField("adbBridge")
                    bridgeField.isAccessible = true
                    val bridge = bridgeField.get(emulatorManager) as? com.android.ddmlib.AndroidDebugBridge
                    bridge?.devices?.find { it.serialNumber == serialNumber }
                } catch (e: Exception) {
                    logger.error("Failed to get IDevice for $serialNumber", e)
                    null
                }
            }
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
     * Create AppInfo from package name.
     * Parses package details if possible.
     */
    private fun createAppInfo(device: IDevice, packageName: String): AppInfo {
        try {
            // Get UID using reliable method
            val uid = getAppUid(device, packageName)

            // Get version info from dumpsys
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

            return AppInfo(
                packageName = packageName,
                appName = null,  // Would need to parse from AndroidManifest or use pm dump
                versionName = versionName,
                versionCode = versionCode,
                isSystemApp = false,  // We're only getting third-party apps
                uid = uid
            )

        } catch (e: Exception) {
            logger.debug("Failed to get details for $packageName: ${e.message}")
            return AppInfo(
                packageName = packageName,
                appName = null,
                versionName = null,
                versionCode = null,
                isSystemApp = false,
                uid = null
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


