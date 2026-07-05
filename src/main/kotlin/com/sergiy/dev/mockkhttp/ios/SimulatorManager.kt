package com.sergiy.dev.mockkhttp.ios

import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.sergiy.dev.mockkhttp.adb.AppInfo
import com.sergiy.dev.mockkhttp.adb.DevicePlatform
import com.sergiy.dev.mockkhttp.adb.EmulatorInfo
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import com.sergiy.dev.mockkhttp.proxy.GlobalOkHttpInterceptorServer
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manager for iOS Simulators (and, best effort, physical iOS devices).
 *
 * This is the iOS counterpart of [com.sergiy.dev.mockkhttp.adb.EmulatorManager] +
 * [com.sergiy.dev.mockkhttp.adb.AppManager], built on `xcrun simctl` / `xcrun devicectl`
 * instead of ADB.
 *
 * Key differences vs Android:
 * - The iOS Simulator shares the Mac's network stack: apps reach the plugin at
 *   127.0.0.1:9876 directly. NO port forwarding (adb reverse equivalent) is needed.
 * - MockkHttp detection uses a marker file in the app's data container
 *   (`Documents/.mockk_http`, written by the Flutter package) plus PING announcements
 *   received by the global server.
 * - Physical iOS devices are enumerated via `xcrun devicectl` (Xcode 15+) when possible;
 *   their apps can't always be listed, so PING-announced packages are used as fallback.
 */
@Service(Service.Level.PROJECT)
class SimulatorManager(project: Project) {

    private val logger = MockkHttpLogger.getInstance(project)

    companion object {
        private const val COMMAND_TIMEOUT_SECONDS = 15L

        fun getInstance(project: Project): SimulatorManager {
            return project.getService(SimulatorManager::class.java)
        }
    }

    @Volatile
    private var xcrunAvailable: Boolean? = null

    /**
     * Whether iOS tooling is available on this machine (macOS with Xcode command line tools).
     */
    fun isAvailable(): Boolean {
        val cached = xcrunAvailable
        if (cached != null) return cached

        val available = System.getProperty("os.name").contains("Mac", ignoreCase = true) &&
                File("/usr/bin/xcrun").exists()
        xcrunAvailable = available
        if (!available) {
            logger.debug("iOS tooling not available (not macOS or xcrun missing)")
        }
        return available
    }

    /**
     * List booted iOS Simulators via `xcrun simctl list devices booted --json`.
     *
     * Returns null when the tooling FAILED (timeout, crash, parse error) — callers must
     * treat that as "unknown", NOT as "no simulators", so a transient simctl failure never
     * makes a still-booted simulator look disconnected. An empty list is a genuine
     * "no booted simulators" answer.
     */
    fun getBootedSimulators(): List<EmulatorInfo>? {
        if (!isAvailable()) return emptyList()

        val json = runCommand(listOf("/usr/bin/xcrun", "simctl", "list", "devices", "booted", "--json"))
            ?: return null

        return try {
            val devicesByRuntime = JsonParser.parseString(json).asJsonObject
                .getAsJsonObject("devices") ?: return emptyList()

            val simulators = mutableListOf<EmulatorInfo>()
            for ((runtimeId, deviceArray) in devicesByRuntime.entrySet()) {
                // Only iOS runtimes (skip watchOS/tvOS/visionOS)
                if (!runtimeId.contains("SimRuntime.iOS")) continue
                val osVersion = runtimeId.substringAfter("SimRuntime.iOS-").replace('-', '.')

                for (element in deviceArray.asJsonArray) {
                    val device = element.asJsonObject
                    val state = device.get("state")?.asString ?: continue
                    if (state != "Booted") continue

                    val udid = device.get("udid")?.asString ?: continue
                    val name = device.get("name")?.asString ?: udid

                    simulators.add(
                        EmulatorInfo(
                            serialNumber = udid,
                            avdName = name,
                            apiLevel = osVersion.substringBefore('.').toIntOrNull() ?: 0,
                            isOnline = true,
                            architecture = System.getProperty("os.arch"),
                            manufacturer = "Apple",
                            model = device.get("deviceTypeIdentifier")?.asString,
                            isEmulator = true,
                            platform = DevicePlatform.IOS_SIMULATOR,
                            osVersion = osVersion
                        )
                    )
                }
            }

            if (simulators.isNotEmpty()) {
                logger.info("🍎 Found ${simulators.size} booted iOS Simulator(s)")
            }
            simulators
        } catch (e: Exception) {
            logger.warn("Failed to parse simctl device list: ${e.message}")
            null
        }
    }

    /**
     * List physical iOS devices via `xcrun devicectl list devices` (Xcode 15+, best effort).
     * The device must be connected/paired; apps on it may not be listable.
     *
     * Returns null when the tooling FAILED (see [getBootedSimulators]); an empty list is a
     * genuine "no connected devices" answer.
     */
    fun getConnectedIosDevices(): List<EmulatorInfo>? {
        if (!isAvailable()) return emptyList()

        var outputFile: File? = null
        return try {
            outputFile = File.createTempFile("mockkhttp-devicectl", ".json")
            runCommand(
                listOf(
                    "/usr/bin/xcrun", "devicectl", "list", "devices",
                    "--json-output", outputFile.absolutePath
                )
            ) ?: return null

            if (!outputFile.exists() || outputFile.length() == 0L) return null

            val devices = JsonParser.parseString(outputFile.readText()).asJsonObject
                .getAsJsonObject("result")
                ?.getAsJsonArray("devices") ?: return null

            val result = mutableListOf<EmulatorInfo>()
            for (element in devices) {
                val device = element.asJsonObject
                val props = device.getAsJsonObject("deviceProperties") ?: continue
                val hardware = device.getAsJsonObject("hardwareProperties")
                val connection = device.getAsJsonObject("connectionProperties")

                val udid = hardware?.get("udid")?.asString
                    ?: device.get("identifier")?.asString ?: continue
                val name = props.get("name")?.asString ?: udid
                val osVersion = props.get("osVersionNumber")?.asString
                // devicectl also lists recently-paired-but-offline devices; keep only reachable ones
                val tunnelState = connection?.get("tunnelState")?.asString
                val online = tunnelState == null || !tunnelState.equals("unavailable", ignoreCase = true)

                result.add(
                    EmulatorInfo(
                        serialNumber = udid,
                        avdName = null,
                        apiLevel = osVersion?.substringBefore('.')?.toIntOrNull() ?: 0,
                        isOnline = online,
                        architecture = null,
                        manufacturer = "Apple",
                        model = name,
                        isEmulator = false,
                        platform = DevicePlatform.IOS_DEVICE,
                        osVersion = osVersion
                    )
                )
            }

            if (result.isNotEmpty()) {
                logger.info("🍏 Found ${result.size} physical iOS device(s)")
            }
            result
        } catch (e: Exception) {
            logger.debug("devicectl enumeration failed (best effort): ${e.message}")
            null
        } finally {
            outputFile?.delete()
        }
    }

    /**
     * List user apps installed on a simulator via `simctl listapps` (plist piped through
     * `plutil` to JSON). Apps with a MockkHttp marker file or a PING announcement are
     * flagged with hasMockkHttp.
     */
    fun getInstalledApps(simulator: EmulatorInfo): List<AppInfo> {
        if (!isAvailable()) return emptyList()

        // simctl listapps outputs an (old-style) plist; convert to JSON via plutil reading stdin
        val plist = runCommand(listOf("/usr/bin/xcrun", "simctl", "listapps", simulator.serialNumber))
            ?: return emptyList()
        val json = runCommand(listOf("/usr/bin/plutil", "-convert", "json", "-o", "-", "-"), stdin = plist)
            ?: return emptyList()

        return try {
            val apps = JsonParser.parseString(json).asJsonObject
            val knownPackages = GlobalOkHttpInterceptorServer.getInstance().getKnownMockkHttpPackages()

            val result = mutableListOf<AppInfo>()
            for ((bundleId, element) in apps.entrySet()) {
                val app = element.asJsonObject
                if (app.get("ApplicationType")?.asString != "User") continue

                val displayName = app.get("CFBundleDisplayName")?.asString
                    ?: app.get("CFBundleName")?.asString

                // Detection: marker file written by the Flutter package on first run,
                // or a PING announcement already received by the global server.
                // DataContainer is a file:// URL (possibly percent-encoded) — decode via URI.
                val hasMarker = app.get("DataContainer")?.asString
                    ?.let { url ->
                        try {
                            File(java.net.URI.create(url)).let { File(it, "Documents/.mockk_http").exists() }
                        } catch (_: Exception) {
                            false
                        }
                    }
                    ?: false
                val hasMockkHttp = hasMarker || bundleId in knownPackages

                result.add(
                    AppInfo(
                        packageName = bundleId,
                        appName = displayName,
                        versionName = app.get("CFBundleVersion")?.asString,
                        versionCode = null,
                        isSystemApp = false,
                        hasMockkHttp = hasMockkHttp
                    )
                )
            }

            logger.info("🔍 Found ${result.size} user app(s) on ${simulator.displayName} " +
                    "(${result.count { it.hasMockkHttp }} with MockkHttp)")
            // MockkHttp apps first, then alphabetical
            result.sortedWith(compareByDescending<AppInfo> { it.hasMockkHttp }.thenBy { it.packageName })
        } catch (e: Exception) {
            logger.warn("Failed to parse simulator app list: ${e.message}")
            emptyList()
        }
    }

    /**
     * List apps on a physical iOS device. Tries `devicectl device info apps`; if that
     * fails (device locked, older Xcode), falls back to PING-announced packages —
     * apps that have already connected to the plugin at least once.
     */
    fun getInstalledAppsPhysical(device: EmulatorInfo): List<AppInfo> {
        if (!isAvailable()) return pingKnownApps()

        var outputFile: File? = null
        try {
            outputFile = File.createTempFile("mockkhttp-deviceapps", ".json")
            runCommand(
                listOf(
                    "/usr/bin/xcrun", "devicectl", "device", "info", "apps",
                    "--device", device.serialNumber,
                    "--json-output", outputFile.absolutePath
                )
            )

            if (outputFile.exists() && outputFile.length() > 0L) {
                val apps = JsonParser.parseString(outputFile.readText()).asJsonObject
                    .getAsJsonObject("result")
                    ?.getAsJsonArray("apps")

                if (apps != null) {
                    val knownPackages = GlobalOkHttpInterceptorServer.getInstance().getKnownMockkHttpPackages()
                    val result = mutableListOf<AppInfo>()
                    for (element in apps) {
                        val app = element.asJsonObject
                        val bundleId = app.get("bundleIdentifier")?.asString ?: continue
                        if (app.get("appType")?.asString == "System") continue
                        result.add(
                            AppInfo(
                                packageName = bundleId,
                                appName = app.get("name")?.asString,
                                versionName = app.get("version")?.asString,
                                versionCode = null,
                                isSystemApp = false,
                                hasMockkHttp = bundleId in knownPackages
                            )
                        )
                    }
                    if (result.isNotEmpty()) {
                        logger.info("🔍 Found ${result.size} app(s) on ${device.displayName} via devicectl")
                        return result.sortedWith(
                            compareByDescending<AppInfo> { it.hasMockkHttp }.thenBy { it.packageName }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug("devicectl app listing failed (best effort): ${e.message}")
        } finally {
            outputFile?.delete()
        }

        logger.info("📡 Falling back to PING-announced apps for ${device.displayName}")
        return pingKnownApps()
    }

    /**
     * Apps known only through PING announcements (they connected to the plugin at least once).
     * Used when the device's app list can't be read directly.
     */
    private fun pingKnownApps(): List<AppInfo> {
        return GlobalOkHttpInterceptorServer.getInstance().getKnownMockkHttpPackages().map { pkg ->
            AppInfo(
                packageName = pkg,
                appName = null,
                versionName = null,
                versionCode = null,
                isSystemApp = false,
                hasMockkHttp = true
            )
        }
    }

    /**
     * Run a command with a timeout, optionally feeding [stdin]. Returns stdout, or null on
     * failure/timeout/non-zero exit.
     *
     * stdout is redirected to a temp file and stderr discarded so no pipe buffer can fill up
     * and deadlock the child, and so [Process.waitFor]'s timeout is the REAL bound (reading a
     * pipe before waitFor would block forever on a hung process).
     */
    private fun runCommand(command: List<String>, stdin: String? = null): String? {
        val stdoutFile = File.createTempFile("mockkhttp-cmd", ".out")
        return try {
            val process = ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.to(stdoutFile))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()

            try {
                if (stdin != null) {
                    process.outputStream.use { it.write(stdin.toByteArray()) }
                } else {
                    process.outputStream.close()
                }
            } catch (_: Exception) {
                // Child may have exited before consuming stdin — fine, judge by exit code
            }

            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                logger.warn("⏱️ Command timed out: ${command.joinToString(" ")}")
                return null
            }
            if (process.exitValue() != 0) {
                logger.debug("Command failed (${process.exitValue()}): ${command.joinToString(" ")}")
                return null
            }
            stdoutFile.readText()
        } catch (e: Exception) {
            logger.debug("Command error: ${command.joinToString(" ")} — ${e.message}")
            null
        } finally {
            stdoutFile.delete()
        }
    }
}
