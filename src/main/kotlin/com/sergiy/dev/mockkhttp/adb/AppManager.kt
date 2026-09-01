package com.sergiy.dev.mockkhttp.adb

import com.android.ddmlib.IDevice
import com.android.ddmlib.ShellCommandUnresponsiveException
import com.android.ddmlib.SyncException
import com.android.ddmlib.SyncService
import com.intellij.openapi.application.ApplicationManager
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

    /** Per-serial `unzip` availability. Immutable for a given device, so cache it forever. */
    private val unzipAvailability = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

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

        // Ceiling on a single APK transfer. Measured on a Pixel 7a over USB 2.0: a 165 MB APK
        // pulls in ~5 s at 32 MB/s, so the earlier 150 MB cap was guarding against a cost that
        // does not exist - and it silently skipped the deep Flutter check for exactly the kind
        // of app this feature targets (a debug build with a 116 MB kernel_blob.bin). What
        // actually bounds the damage is MAX_APK_PULLS_PER_SCAN plus a cancellable transfer.
        private const val MAX_APK_PULL_BYTES = 1024L * 1024 * 1024

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

        // Must match MockkHttpCore.markerFileName in the mockk_http Dart package.
        private const val SANDBOX_MARKER_NAME = "mockk_http.marker"

        // Entries `unzip -p` inflates for Method 3. Quoted so the DEVICE shell does not glob
        // them against the host filesystem, and unzip itself expands the wildcards.
        private const val UNZIP_ENTRY_PATTERNS =
            "'assets/flutter_assets/kernel_blob.bin' 'lib/*/libapp.so' 'classes*.dex'"

        private const val API_LEVEL_TIMEOUT_SECONDS = 5L

        private const val STREAM_SCAN_BLOCK_BYTES = 1 shl 20   // 1 MB

        // `am force-stop` returns before the process is actually reaped, so a relaunch fired
        // immediately after can attach to the dying instance.
        private const val APP_RESTART_SETTLE_MS = 1000L

        // adb takes a shell command *string*, not an argv, so a package name coming from an
        // automated caller is pasted straight into a device-side shell. Real package names are
        // only letters, digits, underscores and dots; anything else is a typo or an injection.
        private val PACKAGE_NAME_PATTERN = Regex("[A-Za-z0-9_.]{1,255}")
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

            // NOTE: showAllApps deliberately does NOT skip detection. Returning an unmarked
            // list would leave the user guessing which of 80 packages is theirs; instead we
            // detect as usual and widen the RESULT, keeping the hits flagged and sorted first
            // - the same contract SimulatorManager has always had on iOS.

            // Step 2: Check all apps in parallel (throttled on USB, see dispatchers above)
            val dispatcher = if (device.isEmulator) emulatorScanDispatcher else usbScanDispatcher

            // A debug build is the only kind that can plausibly embed MockkHttp, and `run-as`
            // answers "is this package debuggable?" in ~50 ms without transferring anything.
            // On a real phone this is what separates the two or three apps under development
            // from the Play Store apps that would otherwise burn the whole pull budget.
            val debuggable = findDebuggablePackages(device, packageNames, dispatcher, isCancelled)
            if (debuggable.isNotEmpty()) {
                logger.info("🐛 ${debuggable.size} debuggable package(s): ${debuggable.joinToString()}")
            } else {
                logger.warn(
                    "⚠️ No debuggable packages found. A release build cannot be detected unless it " +
                    "announces itself - launch the app while the plugin is running."
                )
            }

            logger.info("🔍 Checking for MockkHttp in ${packageNames.size} packages...")
            val scanned = AtomicInteger(0)
            // Check the likely candidates first so they claim the pull budget before the
            // arbitrary order of `pm list packages` gets to spend it. Rank: the app the user
            // last worked with, then anything debuggable, then the rest.
            val ordered = packageNames.sortedByDescending { pkg ->
                (if (pkg in priorityPackages) 2 else 0) + (if (pkg in debuggable) 1 else 0)
            }
            val mockkHttpPackages = runBlocking {
                ordered.map { pkg ->
                    async(dispatcher) {
                        if (isCancelled()) return@async null
                        // A sibling coroutine's PCE must not blow up awaitAll with an opaque
                        // exception: cooperate by yielding null, the check below is the verdict.
                        val hit = try {
                            hasMockkHttpInstalled(
                                device, pkg, isCancelled, apiLevel, tally,
                                // Debuggable packages share the reserved pull slots: they are
                                // the only realistic candidates, so they must never lose the
                                // budget race to a Play Store app that merely sorts first.
                                isPriority = pkg in priorityPackages || pkg in debuggable
                            )
                        } catch (e: ProcessCanceledException) {
                            logger.debug("Detection cancelled for $pkg")
                            false
                        }
                        onProgress(scanned.incrementAndGet(), ordered.size, pkg)
                        if (hit) pkg else null
                    }
                }.awaitAll().filterNotNull()
            }

            if (isCancelled()) throw ProcessCanceledException()

            logger.info("🔍 Found ${mockkHttpPackages.size} app(s) with MockkHttp")

            // Step 3: Get details for the matches (dumpsys per match). With showAllApps the
            // non-matching packages are appended, flagged false and sorted last, so the user
            // can still pick an app the detection missed.
            val hits = mockkHttpPackages.toSet()
            val apps = if (showAllApps) {
                logger.info("📋 Listing all ${packageNames.size} third-party apps (${hits.size} detected)")
                packageNames
                    .sortedWith(compareByDescending<String> { it in hits }.thenBy { it })
                    .map { pkg -> createAppInfo(device, pkg, hasMockkHttp = pkg in hits) }
            } else {
                mockkHttpPackages.map { pkg ->
                    createAppInfo(device, pkg, hasMockkHttp = true)
                }
            }

            apps.filter { it.hasMockkHttp }.forEach { app ->
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

            // Method 2b: in-sandbox marker, read through run-as.
            // /data/local/tmp is drwxrwx--x shell:shell on a production device, so an app can
            // only write the marker above on an emulator. Since 1.7.0 the Flutter package also
            // drops one inside its own sandbox, which run-as can read for any debuggable build
            // - ~90 ms, no transfer, and it works while the app is not even running.
            val sandboxReceiver = EmulatorManager.CollectingOutputReceiver(isCancelled)
            device.executeShellCommand(
                "run-as $packageName sh -c 'cat cache/$SANDBOX_MARKER_NAME 2>/dev/null || " +
                    "cat files/$SANDBOX_MARKER_NAME 2>/dev/null'",
                sandboxReceiver,
                10, 5,
                TimeUnit.SECONDS
            )
            if (sandboxReceiver.output.contains("flutter:")) {
                logger.info("✅ $packageName HAS MockkHttp (Flutter, sandbox marker via run-as)")
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

                // Method 3: decompress the interesting entries ON THE DEVICE and grep those.
                //
                // The previous `grep -c MockkHttpInterceptor <apk>` could never match: every
                // AGP-built APK stores classes.dex as Defl:N, and a Flutter debug build stores
                // kernel_blob.bin the same way, so neither marker exists as plain bytes
                // anywhere in the file. That is why NATIVE apps were missed too, not just
                // Flutter ones - the whole method was reading compressed data as if it were
                // text and concluding "no MockkHttp".
                //
                // `unzip -p` inflates the entries in place: measured on a Pixel 7a, 23 hits in
                // ~2 s on a 165 MB APK whose kernel_blob.bin alone is 116 MB - and zero bytes
                // over USB, versus a multi-hundred-MB pull.
                when (inspectApkOnDevice(device, apkPath, isCancelled)) {
                    true -> {
                        logger.info("✅ $packageName HAS MockkHttp (inspected on device: $apkPath)")
                        return true
                    }
                    // Inspected and conclusively absent - no reason to pull it.
                    false -> continue
                    // No usable `unzip` on this device: fall through to the pull path below.
                    null -> {}
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

    /**
     * Packages whose APK is marked debuggable, decided by `run-as <pkg> true`: it succeeds only
     * for a debuggable app and otherwise prints "run-as: package not debuggable: <pkg>".
     *
     * This is a *ranking* signal, never an exclusion one - a release build could still embed
     * MockkHttp, so non-debuggable packages are still checked, just last. Failing open (empty
     * set) simply restores the previous ordering.
     */
    private fun findDebuggablePackages(
        device: IDevice,
        packageNames: List<String>,
        dispatcher: CoroutineDispatcher,
        isCancelled: () -> Boolean
    ): Set<String> {
        return try {
            runBlocking {
                packageNames.map { pkg ->
                    async(dispatcher) {
                        if (isCancelled()) return@async null
                        try {
                            val receiver = EmulatorManager.CollectingOutputReceiver(isCancelled)
                            device.executeShellCommand(
                                "run-as $pkg true 2>/dev/null && echo DEBUGGABLE",
                                receiver, 10, 5, TimeUnit.SECONDS
                            )
                            if (receiver.output.contains("DEBUGGABLE")) pkg else null
                        } catch (e: ProcessCanceledException) {
                            logger.debug("run-as probe cancelled for $pkg")
                            null
                        } catch (e: Exception) {
                            // Old devices without run-as, or a package that vanished mid-scan.
                            logger.debug("run-as probe failed for $pkg: ${e.message}")
                            null
                        }
                    }
                }.awaitAll().filterNotNull().toSet()
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            logger.debug("Debuggable-package probe failed: ${e.message}")
            emptySet()
        }
    }

    /**
     * Inflate the entries that can carry a MockkHttp marker and grep them, all on the device.
     *
     * Covers every integration in one command:
     * - `assets/flutter_assets/kernel_blob.bin` — Flutter debug
     * - `lib/&#42;/libapp.so` — Flutter release/profile (AOT)
     * - `classes&#42;.dex` — native Android (the injected OkHttp interceptor)
     *
     * @return true/false when the APK was actually inspected, or null when the device has no
     *   usable `unzip` (pre-Android-10), so the caller can fall back to pulling the APK.
     *   Never returns false on an error: an unreadable APK is not proof of absence.
     */
    private fun inspectApkOnDevice(
        device: IDevice,
        apkPath: String,
        isCancelled: () -> Boolean
    ): Boolean? {
        if (!deviceHasUnzip(device, isCancelled)) return null

        val receiver = EmulatorManager.CollectingOutputReceiver(isCancelled)
        device.executeShellCommand(
            "unzip -p '$apkPath' $UNZIP_ENTRY_PATTERNS 2>/dev/null | " +
                "grep -c -e mockk_http -e MockkHttpInterceptor",
            receiver,
            APK_GREP_MAX_TIMEOUT_S,
            APK_GREP_IDLE_TIMEOUT_S,
            TimeUnit.SECONDS
        )

        // A cancelled command returns normally with partial output; there is no verdict to read.
        if (isCancelled()) return false

        // adb folds stderr into stdout, and it can arrive after the count: keep the last line
        // that is nothing but digits. `grep -c` with no match prints 0 and exits 1 - that is a
        // real answer, not a failure.
        val count = receiver.output.lines()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() && it.all { c -> c.isDigit() } }
            ?.toIntOrNull()
            ?: return null   // Unparseable: treat as "could not inspect", never as absent.

        return count > 0
    }

    /** Cached per device: `unzip` ships with Android 10+, and the answer cannot change. */
    private fun deviceHasUnzip(device: IDevice, isCancelled: () -> Boolean): Boolean =
        unzipAvailability.getOrPut(device.serialNumber) {
            try {
                val receiver = EmulatorManager.CollectingOutputReceiver(isCancelled)
                device.executeShellCommand("which unzip", receiver, 10, 5, TimeUnit.SECONDS)
                receiver.output.contains("unzip").also {
                    if (!it) logger.warn("⚠️ ${device.serialNumber} has no `unzip`; falling back to APK downloads")
                }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                logger.debug("Could not probe for unzip: ${e.message}")
                false
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
            // A caller driving this blind gets an entry with every field null; without this
            // line there is nothing anywhere saying why.
            logger.warn(
                "⚠️ Could not read details for $packageName " +
                "(${e.javaClass.simpleName}: ${e.message}) - returning a bare entry"
            )
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
     * Guard for the lifecycle calls below: they are the surface an automated controller drives,
     * so they must refuse a malformed name instead of pasting it into a device shell.
     */
    private fun isValidPackageName(packageName: String, operation: String): Boolean {
        if (packageName.isNotBlank() && PACKAGE_NAME_PATTERN.matches(packageName)) return true
        logger.warn("⚠️ Cannot $operation: '$packageName' is not a valid package name")
        return false
    }

    /**
     * The lifecycle calls below block on ADB for as long as the device takes to answer. They
     * are headless - no dialogs, no Messages, nothing that needs the UI thread - but running
     * one ON the EDT freezes the IDE, so make that visible instead of leaving it to look like
     * a hang. Never fatal: an existing caller keeps working, just noisily.
     */
    private fun warnIfOnEdt(operation: String) {
        if (ApplicationManager.getApplication()?.isDispatchThread == true) {
            logger.warn(
                "⚠️ $operation was called on the EDT; it blocks on ADB and will freeze the IDE. " +
                "Call it from Task.Backgroundable or a controller thread."
            )
        }
    }

    /**
     * Force-stop an app and optionally restart it.
     * This is useful for clearing app's network cache after proxy configuration changes.
     *
     * Headless and blocking: safe to call off the EDT from an automated controller.
     */
    fun forceStopApp(serialNumber: String, packageName: String): Boolean {
        logger.info("🔴 Force-stopping app: $packageName on $serialNumber")
        warnIfOnEdt("forceStopApp")
        if (!isValidPackageName(packageName, "force-stop")) return false

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
     *
     * Headless and blocking: safe to call off the EDT from an automated controller.
     */
    fun startApp(serialNumber: String, packageName: String): Boolean {
        logger.info("▶️ Starting app: $packageName on $serialNumber")
        warnIfOnEdt("startApp")
        if (!isValidPackageName(packageName, "start app")) return false

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
     *
     * Headless and blocking (it sleeps between the two halves): call it off the EDT.
     */
    fun restartApp(serialNumber: String, packageName: String): Boolean {
        logger.info("🔄 Restarting app: $packageName")
        // forceStopApp/startApp each warn on their own; restarting must not emit three.

        if (!forceStopApp(serialNumber, packageName)) {
            return false
        }

        // Wait briefly for app to fully stop
        try {
            Thread.sleep(APP_RESTART_SETTLE_MS)
        } catch (e: InterruptedException) {
            // A controller cancelling here leaves the app stopped, not restarted. Report that
            // and hand the interrupt back instead of relaunching against the caller's wishes.
            Thread.currentThread().interrupt()
            logger.warn("⚠️ Restart of $packageName interrupted while it was stopping - the app is left stopped")
            return false
        }

        return startApp(serialNumber, packageName)
    }
}


