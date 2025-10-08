package com.sergiy.dev.mockkhttp.adb

import com.android.ddmlib.IDevice
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/**
 * Manager for handling installed applications on emulators.
 * Provides functionality to list, start, and stop apps.
 */
@Service(Service.Level.PROJECT)
class AppManager(project: Project) {

    private val emulatorManager = EmulatorManager.getInstance(project)
    
    companion object {
        fun getInstance(project: Project): AppManager {
            return project.getService(AppManager::class.java)
        }
        
        private const val SHELL_TIMEOUT_SECONDS = 30L
    }
    
    /**
     * Get list of installed applications on emulator (ASYNC with coroutines - Problem #5).
     * Old: 50 apps × 5s = 250s sequentially
     * New: 50 apps in parallel = 5-10s total
     *
     * @param serialNumber Emulator serial number
     * @param includeSystem Whether to include system apps
     */
    suspend fun getInstalledApps(serialNumber: String, includeSystem: Boolean = false): List<AppInfo> = coroutineScope {

        try {
            val device = getDevice(serialNumber)
            if (device == null) {
                return@coroutineScope emptyList()
            }

            // Get package list (fast operation, ~1s)
            val packageNames = withContext(Dispatchers.IO) {
                val receiver = EmulatorManager.CollectingOutputReceiver()
                val command = if (includeSystem) {
                    "pm list packages"
                } else {
                    "pm list packages -3"  // Only third-party apps
                }

                device.executeShellCommand(command, receiver, SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)

                val output = receiver.output
                output.lines()
                    .filter { it.startsWith("package:") }
                    .map { it.removePrefix("package:").trim() }
            }


            // Fetch app info in PARALLEL using async (Problem #5 optimization)
            // This converts 250s sequential to 5-10s parallel
            val apps = packageNames.map { packageName ->
                async(Dispatchers.IO) {
                    createAppInfo(device, packageName)
                }
            }.awaitAll()


            // Log some examples
            apps.take(3).forEach { app ->
            }
            if (apps.size > 3) {
            }

            apps

        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get IDevice from serial number.
     */
    private fun getDevice(serialNumber: String): IDevice? {
        val emulator = emulatorManager.getEmulator(serialNumber)
        if (emulator == null) {
            return null
        }
        
        if (!emulator.isOnline) {
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
                        return uid
                    }
                }
            }

            return null

        } catch (e: Exception) {
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

        try {
            val device = getDevice(serialNumber)
            if (device == null) {
                return false
            }

            val receiver = EmulatorManager.CollectingOutputReceiver()
            device.executeShellCommand("am force-stop $packageName", receiver, 10, TimeUnit.SECONDS)

            val output = receiver.output
            if (output.contains("Error", ignoreCase = true)) {
                return false
            }

            return true

        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Start an app's main activity.
     */
    fun startApp(serialNumber: String, packageName: String): Boolean {

        try {
            val device = getDevice(serialNumber)
            if (device == null) {
                return false
            }

            val receiver = EmulatorManager.CollectingOutputReceiver()
            // Use monkey to launch the app (more reliable than getting main activity)
            device.executeShellCommand("monkey -p $packageName -c android.intent.category.LAUNCHER 1", receiver, 10, TimeUnit.SECONDS)

            val output = receiver.output
            if (output.contains("No activities found", ignoreCase = true) ||
                output.contains("Error", ignoreCase = true)) {
                return false
            }

            return true

        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Restart an app (force-stop then start).
     * Useful to clear network cache and force re-connection through proxy.
     */
    fun restartApp(serialNumber: String, packageName: String): Boolean {

        if (!forceStopApp(serialNumber, packageName)) {
            return false
        }

        // Wait briefly for app to fully stop
        Thread.sleep(1000)

        return startApp(serialNumber, packageName)
    }
}


