package com.sergiy.dev.mockkhttp.adb

import com.android.ddmlib.IDevice
import com.android.ddmlib.ShellCommandUnresponsiveException
import com.android.ddmlib.SyncException
import com.android.ddmlib.SyncService
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manager for handling installed applications on emulators.
 * Provides functionality to list, start, and stop apps.
 */
@Service(Service.Level.PROJECT)
class AppManager(project: Project) {

    private val logger = MockkHttpLogger.getInstance(project)
    private val emulatorManager = EmulatorManager.getInstance(project)

    // A single USB transport cannot sustain 10 concurrent adb shell connections: that is half
    // of what saturates adbd on a physical device and makes it flap offline. Emulators talk
    // over loopback TCP and keep the original parallelism.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val emulatorScanDispatcher = Dispatchers.IO.limitedParallelism(10)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val usbScanDispatcher = Dispatchers.IO.limitedParallelism(3)

    companion object {
        fun getInstance(project: Project): AppManager {
            return project.getService(AppManager::class.java)
        }

        private const val SHELL_TIMEOUT_SECONDS = 30L

        // `grep -c` over an APK emits nothing until it finishes, so a short
        // maxTimeToOutputResponse is really a budget for scanning the whole file. Give the
        // command a hard cap plus a generous silence budget - it is abortable now via the
        // receiver's isCancelled() probe.
        private const val APK_GREP_MAX_TIMEOUT_S = 120L
        private const val APK_GREP_IDLE_TIMEOUT_S = 90L

        // Never drag hundreds of MB over USB just to look inside an APK.
        private const val MAX_APK_PULL_BYTES = 150L * 1024 * 1024

        // Hard ceiling on how many APKs a single scan may pull, whatever the heuristics say.
        // The probe below can legitimately answer UNKNOWN (old toybox, unreadable API level),
        // and failing open on every package is exactly the adbd saturation that started this:
        // this budget bounds the worst case by construction rather than by guesswork.
        private const val MAX_APK_PULLS_PER_SCAN = 8

        // Carved OUT of the budget above (not added to it), so the app the user actually cares
        // about is never crowded out by whatever packages happen to sort first.
        private const val RESERVED_APK_PULLS = 2

        // Method 4 only understands these two entries, so the probe must match nothing else:
        // `flutter_assets` would green-light the base.apk of every split-installed Flutter app,
        // whose libapp.so lives in split_config.<abi>.apk instead.
        private const val FLUTTER_PROBE_CMD_ARGS = "-e kernel_blob.bin -e libapp.so"

        private const val API_LEVEL_TIMEOUT_SECONDS = 5L

        private const val STREAM_SCAN_BLOCK_BYTES = 1 shl 20   // 1 MB
    }

    /** Mutable per-scan tallies. AppManager is a project service: never keep these as fields. */
    private class ScanTally {
        /** Detections that timed out or hit a transport error: retrying may change the answer. */
        val failures = AtomicInteger(0)

        /** Deep Flutter checks skipped because the APK was too big: deterministic, retry won't help. */
        val oversizedSkips = AtomicInteger(0)

        /** General-purpose share of the pull budget. */
        val pullsLeft = AtomicInteger(MAX_APK_PULLS_PER_SCAN - RESERVED_APK_PULLS)

        /** The rest, reserved for priority packages. Together they never exceed the ceiling. */
        val reservedPulls = AtomicInteger(RESERVED_APK_PULLS)

        /** Deep checks skipped because the per-scan pull budget ran out. */
        val budgetSkips = AtomicInteger(0)
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
    fun getInstalledApps(
        serialNumber: String,
        includeSystem: Boolean = false,
        showAllApps: Boolean = false,
        isCancelled: () -> Boolean = { false },
        priorityPackages: Set<String> = emptySet(),
        onProgress: (done: Int, total: Int, packageName: String) -> Unit = { _, _, _ -> }
    ): List<AppInfo> {
        logger.info("📱 Scanning apps on $serialNumber (showAll=$showAllApps)...")

        // Per-invocation tallies: AppManager is a *project* service and two devices can be
        // scanned concurrently, so shared fields would clobber each other's counts.
        val tally = ScanTally()

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
            val receiver = EmulatorManager.CollectingOutputReceiver(isCancelled)
            val command = if (includeSystem) "pm list packages" else "pm list packages -3"
            device.executeShellCommand(command, receiver, 30, TimeUnit.SECONDS)

            if (isCancelled()) throw ProcessCanceledException()

            // Resolved once per scan, with a real wait: IDevice.getProperty() returns null
            // whenever the value is not *immediately* cached, so calling it per package would
            // read a congested device as "old Android" and fail open into a pull storm.
            val apiLevel = resolveApiLevel(device)

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

            // Step 2: Check all apps in parallel (throttled on USB, see dispatchers above)
            val dispatcher = if (device.isEmulator) emulatorScanDispatcher else usbScanDispatcher
            logger.info("🔍 Checking for MockkHttp in ${packageNames.size} packages...")
            val scanned = AtomicInteger(0)
            // Check the likely candidates first so they claim the pull budget before the
            // arbitrary order of `pm list packages` gets to spend it.
            val ordered = packageNames.sortedByDescending { it in priorityPackages }
            val mockkHttpPackages = runBlocking {
                ordered.map { pkg ->
                    async(dispatcher) {
                        if (isCancelled()) return@async null
                        // A sibling coroutine's PCE must not blow up awaitAll with an opaque
                        // exception: cooperate by yielding null, the check below is the verdict.
                        val hit = try {
                            hasMockkHttpInstalled(
                                device, pkg, isCancelled, apiLevel, tally,
                                isPriority = pkg in priorityPackages
                            )
                        } catch (e: ProcessCanceledException) {
                            false
                        }
                        onProgress(scanned.incrementAndGet(), ordered.size, pkg)
                        if (hit) pkg else null
                    }
                }.awaitAll().filterNotNull()
            }

            if (isCancelled()) throw ProcessCanceledException()

            logger.info("🔍 Found ${mockkHttpPackages.size} app(s) with MockkHttp")

            // Step 3: Get details only for matching apps (dumpsys per match)
            val apps = mockkHttpPackages.map { pkg ->
                createAppInfo(device, pkg, hasMockkHttp = true)
            }

            apps.forEach { app ->
                logger.info("  ✅ ${app.packageName} (v${app.versionName ?: "?"})")
            }

            // Report every kind of incomplete coverage, found apps or not: a partial scan that
            // happened to find something is still partial.
            val failures = tally.failures.get()
            if (failures > 0) {
                logger.warn(
                    "⚠️ Scan finished with $failures inconclusive package(s) - the ADB link may be " +
                    "unstable. This result is NOT reliable; press 'Refresh Apps' to retry."
                )
            }
            val oversized = tally.oversizedSkips.get()
            if (oversized > 0) {
                logger.warn(
                    "⚠️ Skipped the deep Flutter check on $oversized oversized APK(s). Retrying won't " +
                    "help: launch the app once so it announces itself, then press 'Refresh Apps'."
                )
            }
            val budgeted = tally.budgetSkips.get()
            if (budgeted > 0) {
                logger.warn(
                    "⚠️ Reached the per-scan limit of $MAX_APK_PULLS_PER_SCAN APK downloads; " +
                    "$budgeted package(s) were left unchecked. Launch your app once so it " +
                    "announces itself - that detection is instant and needs no download."
                )
            }

            return apps

        } catch (e: ProcessCanceledException) {
            // Let the platform route this to Task.onCancel() instead of reporting "0 apps found"
            logger.warn("⚠️ App scan cancelled on $serialNumber")
            throw e
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
            logger.warn("⚠️ Device $serialNumber is not online (state=${device.state}) - aborting scan")
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
    private fun hasMockkHttpInstalled(
        device: IDevice,
        packageName: String,
        isCancelled: () -> Boolean,
        apiLevel: Int?,
        tally: ScanTally,
        isPriority: Boolean
    ): Boolean {
        try {
            // Method 1: Check if app announced itself via PING handshake
            val globalServer = com.sergiy.dev.mockkhttp.proxy.GlobalOkHttpInterceptorServer.getInstance()
            if (globalServer.isKnownMockkHttpPackage(packageName)) {
                logger.info("✅ $packageName HAS MockkHttp (detected via PING handshake)")
                return true
            }

            // Method 2: Check marker file (Flutter apps write this on MockkHttp.init())
            val markerReceiver = EmulatorManager.CollectingOutputReceiver(isCancelled)
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
            val pathReceiver = EmulatorManager.CollectingOutputReceiver(isCancelled)
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
                if (isCancelled()) return false

                // Method 3: Grep APK binary for Android native class (uncompressed in dex).
                // Uses the 5-arg overload: (maxTimeout, maxTimeToOutputResponse, unit). The
                // 4-arg one only sets the *silence* budget, which for a `grep -c` that prints
                // nothing until it ends means the whole APK scan had to fit in 10s - and any
                // overrun silently marked the user's own app as "no MockkHttp".
                val grepReceiver = EmulatorManager.CollectingOutputReceiver(isCancelled)
                device.executeShellCommand(
                    "grep -c MockkHttpInterceptor $apkPath",
                    grepReceiver,
                    APK_GREP_MAX_TIMEOUT_S,
                    APK_GREP_IDLE_TIMEOUT_S,
                    TimeUnit.SECONDS
                )

                val matchCount = grepReceiver.output.trim().toIntOrNull() ?: 0
                if (matchCount > 0) {
                    logger.info("✅ $packageName HAS MockkHttp (Android native, grep: $matchCount matches)")
                    return true
                }

                // Method 3.5: cheap on-device probe before paying for a full APK pull.
                // Only a trustworthy NO skips the APK - a false negative would make the app
                // vanish from the combo, while the cost of an unnecessary pull is just time.
                val probe = flutterProbe(device, apkPath, isCancelled, apiLevel)
                if (probe == FlutterProbe.NO) {
                    logger.debug("📝 $packageName ($apkPath) has no Flutter payload - no pull needed")
                    continue
                }
                if (probe == FlutterProbe.UNKNOWN) {
                    logger.debug("❓ Flutter probe inconclusive for $packageName ($apkPath) - pulling anyway")
                }

                // Pulling hundreds of MB for every third-party app is what saturates adbd and
                // makes the device flap offline. Bound the transfer both ways: per APK...
                val apkBytes = remoteFileSize(device, apkPath, isCancelled)
                if (apkBytes > MAX_APK_PULL_BYTES) {
                    tally.oversizedSkips.incrementAndGet()
                    logger.warn(
                        "⚠️ Skipping deep Flutter check for $packageName: APK is " +
                        "${apkBytes / (1024 * 1024)} MB (over ${MAX_APK_PULL_BYTES / (1024 * 1024)} MB)"
                    )
                    continue
                }

                // ...and per scan, so no heuristic failing open can ever bring the pull storm
                // back. decrementAndGet is the claim: only a non-negative result may proceed,
                // and a failed claim is given straight back so the counter can't run away.
                fun claim(counter: AtomicInteger): Boolean {
                    if (counter.decrementAndGet() >= 0) return true
                    counter.incrementAndGet()
                    return false
                }

                val claimed = (isPriority && claim(tally.reservedPulls)) || claim(tally.pullsLeft)
                if (!claimed) {
                    tally.budgetSkips.incrementAndGet()
                    logger.debug("📦 Pull budget exhausted - skipping deep check for $packageName")
                    continue
                }

                // Method 4: Pull APK and check inside the ZIP for Flutter MockkHttp.
                // This catches Flutter apps where kernel_blob.bin (debug) or libapp.so (release)
                // contain "mockk_http" strings that are invisible to device-side grep.
                logger.info("🔍 Pulling APK for $packageName to check for Flutter MockkHttp...")
                if (checkFlutterApkForMockkHttp(device, apkPath, packageName, isCancelled)) {
                    return true
                }
            }

            logger.debug("📝 $packageName does NOT have MockkHttp")
            return false

        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: ShellCommandUnresponsiveException) {
            // A timeout is NOT the same as "this app has no MockkHttp" - say so out loud.
            tally.failures.incrementAndGet()
            logger.warn("⚠️ Detection timed out for $packageName - result is inconclusive")
            return false
        } catch (e: Exception) {
            tally.failures.incrementAndGet()
            logger.warn("⚠️ Detection failed for $packageName: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
    }

    private enum class FlutterProbe { YES, NO, UNKNOWN }

    /**
     * Cheap on-device test for "could Method 4 possibly conclude anything about this APK?".
     *
     * Matches only the two markers checkFlutterApkForMockkHttp actually inspects -
     * kernel_blob.bin (debug) and libapp.so (release/profile). Matching e.g. `flutter_assets`
     * would green-light pulling the base.apk of every split-installed Flutter app, whose
     * native libs live in split_config.<abi>.apk: the pull would be paid for and then
     * discarded. ZIP entry names are stored uncompressed, so device-side grep does see them.
     *
     * UNKNOWN means "this answer is not trustworthy" and must NOT skip the pull: only a
     * well-formed 0 from a grep we trust may rule an APK out.
     */
    private fun flutterProbe(
        device: IDevice,
        apkPath: String,
        isCancelled: () -> Boolean,
        apiLevel: Int?
    ): FlutterProbe {
        // toybox < 0.8.0 (Android <= 9) runs regexec() over a NUL-terminated line, so it stops
        // at the first NUL of a binary "line" and reports 0 for APKs that do contain the
        // marker. That 0 is not a verdict. An *unknown* API level is not treated as old: the
        // per-scan pull budget already bounds the damage, and assuming "old" on every device
        // whose property read was slow is how the pull storm came back.
        if (apiLevel != null && apiLevel < 29) return FlutterProbe.UNKNOWN

        // Timeouts and transport errors propagate on purpose: hasMockkHttpInstalled already
        // counts them as inconclusive. Turning them into UNKNOWN would fire a full pull at a
        // device that just stopped responding - exactly the saturation this guard prevents.
        val receiver = EmulatorManager.CollectingOutputReceiver(isCancelled)
        device.executeShellCommand(
            "grep -c $FLUTTER_PROBE_CMD_ARGS $apkPath",
            receiver,
            APK_GREP_MAX_TIMEOUT_S,
            APK_GREP_IDLE_TIMEOUT_S,
            TimeUnit.SECONDS
        )

        // A cancelled command returns normally with partial output, so there is no verdict to
        // read - and failing open into a hundreds-of-MB pull right as the user hits Cancel
        // would be the worst possible reading.
        if (isCancelled()) return FlutterProbe.NO

        // adb's shell protocol folds stderr into stdout ("grep: ...: Permission denied") and
        // it may arrive after the count: keep the last line that is nothing but digits.
        val count = receiver.output.lines()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() && it.all { c -> c.isDigit() } }
            ?.toIntOrNull()

        return when {
            count == null -> FlutterProbe.UNKNOWN   // grep missing / stderr / unparseable
            count > 0 -> FlutterProbe.YES
            else -> FlutterProbe.NO
        }
    }

    /**
     * API level of the device, or null when it cannot be read. Uses getSystemProperty (which
     * actually waits) rather than getProperty, whose contract is "null if not immediately
     * available" - a distinction that decides whether the Flutter probe is trusted.
     */
    private fun resolveApiLevel(device: IDevice): Int? {
        return try {
            device.getSystemProperty(IDevice.PROP_BUILD_API_LEVEL)
                .get(API_LEVEL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                ?.trim()
                ?.toIntOrNull()
        } catch (e: Exception) {
            logger.debug("Could not read API level of ${device.serialNumber}: ${e.message}")
            null
        }
    }

    /**
     * Size of a file on the device, or -1 when it cannot be determined (fail-open: an unknown
     * size never blocks the pull, so old devices without `stat` keep working).
     */
    private fun remoteFileSize(device: IDevice, apkPath: String, isCancelled: () -> Boolean): Long {
        return try {
            val receiver = EmulatorManager.CollectingOutputReceiver(isCancelled)
            device.executeShellCommand("stat -c %s $apkPath", receiver, 10, 5, TimeUnit.SECONDS)
            receiver.output.trim().toLongOrNull() ?: -1L
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            logger.debug("Could not stat $apkPath: ${e.message}")
            -1L
        }
    }

    /**
     * Pull APK from device, open as ZIP, and search compressed entries for MockkHttp strings.
     * This detects Flutter apps where kernel_blob.bin (debug) or libapp.so (release) contains
     * Dart strings that are compressed inside the APK and invisible to device-side grep.
     */
    private fun checkFlutterApkForMockkHttp(
        device: IDevice,
        apkPath: String,
        packageName: String,
        isCancelled: () -> Boolean
    ): Boolean {
        val tempFile = java.io.File.createTempFile("mockk_flutter_", ".apk")
        try {
            // device.pullFile() hands ddmlib a null progress monitor whose isCanceled() is
            // hardcoded to false, making the transfer uninterruptible. Drive the sync by hand
            // so the user's Cancel actually aborts a multi-hundred-MB pull.
            val sync = device.syncService ?: return false
            sync.use { s ->
                s.pullFile(apkPath, tempFile.absolutePath, object : SyncService.ISyncProgressMonitor {
                    override fun start(totalWork: Int) {}
                    override fun stop() {}
                    override fun startSubTask(name: String) {}
                    override fun advance(work: Int) {}
                    override fun isCanceled(): Boolean = isCancelled()
                })
            }

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
                    if (streamContainsString(zip.getInputStream(kernelEntry), "mockk_http", isCancelled)) {
                        logger.info("✅ $packageName HAS MockkHttp (Flutter debug, kernel_blob.bin)")
                        return true
                    }
                }

                // Check libapp.so (Flutter release/profile builds)
                if (libAppEntry != null) {
                    if (streamContainsString(zip.getInputStream(libAppEntry), "mockk_http", isCancelled)) {
                        logger.info("✅ $packageName HAS MockkHttp (Flutter release, ${libAppEntry.name})")
                        return true
                    }
                }
            }

            logger.debug("📝 $packageName is Flutter but does NOT contain MockkHttp")
            return false
        } catch (e: SyncException) {
            // Without this, a cancelled pull would decay into `false` and the sweep would
            // carry on through the remaining packages as if nothing happened.
            if (e.wasCanceled()) throw ProcessCanceledException()
            logger.warn("⚠️ Sync failed for $packageName: ${e.message}")
            return false
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            logger.warn("⚠️ Failed to check Flutter APK for $packageName: ${e.javaClass.simpleName}: ${e.message}")
            return false
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Search for an ASCII string while streaming, so a 100 MB kernel_blob.bin never lands in
     * the IDE heap in one piece. Each block keeps the last (target-1) bytes of the previous one
     * so a match straddling a block boundary is still found.
     */
    private fun streamContainsString(
        stream: java.io.InputStream,
        target: String,
        isCancelled: () -> Boolean
    ): Boolean {
        val targetBytes = target.toByteArray(Charsets.US_ASCII)
        val overlap = targetBytes.size - 1
        val buffer = ByteArray(STREAM_SCAN_BLOCK_BYTES + overlap)

        stream.use { input ->
            var carried = 0
            while (true) {
                if (isCancelled()) return false
                val read = input.read(buffer, carried, STREAM_SCAN_BLOCK_BYTES)
                if (read <= 0) return false

                val filled = carried + read
                if (indexOfBytes(buffer, filled, targetBytes) >= 0) return true

                carried = minOf(overlap, filled)
                System.arraycopy(buffer, filled - carried, buffer, 0, carried)
            }
        }
    }

    /** Index of [target] within the first [length] bytes of [haystack], or -1. */
    private fun indexOfBytes(haystack: ByteArray, length: Int, target: ByteArray): Int {
        if (target.size > length) return -1
        outer@ for (i in 0..length - target.size) {
            for (j in target.indices) {
                if (haystack[i + j] != target[j]) continue@outer
            }
            return i
        }
        return -1
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


