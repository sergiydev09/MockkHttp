package com.sergiy.dev.mockkhttp.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.sergiy.dev.mockkhttp.adb.AppInfo
import com.sergiy.dev.mockkhttp.adb.AppManager
import com.sergiy.dev.mockkhttp.adb.DevicePlatform
import com.sergiy.dev.mockkhttp.adb.EmulatorInfo
import com.sergiy.dev.mockkhttp.agent.InstanceRegistry
import com.sergiy.dev.mockkhttp.adb.EmulatorManager
import com.sergiy.dev.mockkhttp.ios.SimulatorManager
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import com.sergiy.dev.mockkhttp.proxy.GlobalOkHttpInterceptorServer
import com.sergiy.dev.mockkhttp.proxy.OkHttpInterceptorServer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The capture session, extracted from the Swing Inspector so that something other than a human
 * clicking **Start** can own it.
 *
 * ## Why this exists
 *
 * Everything the agent control plane can do — mock rules, flows, mode changes — is inert until a
 * capture session is running, and until this service the only way to start one was the Inspector's
 * Start button. An automated caller could therefore read state and write rules that could never
 * fire. This service owns `{targetDevice, targetApp, mode, running}` and performs exactly the
 * sequence `InspectorPanel.start()` performs, so both callers reach the same place:
 *
 * 1. `EmulatorManager.initialize()` — bring ADB up (skipped for an iOS-only target).
 * 2. `GlobalOkHttpInterceptorServer.ensureStarted()` — bind 9876 **before** the tunnel exists.
 *    Order matters: a tunnel with nothing behind it gives the app a refused connection, and both
 *    clients then back off for 15 s (`InspectorPanel.refreshApps`, same reasoning).
 * 3. `EmulatorManager.setupAdbReverse(serial, 9876, 9876)` for any Android target — fatal on a
 *    physical device (it has no other route to this Mac), a warning on an emulator (10.0.2.2 still
 *    works there).
 * 4. `OkHttpInterceptorServer.start(mode, packageFilter)`.
 *
 * ## One source of truth
 *
 * [state] does **not** report a field of its own for `running` / `mode` / `packageFilter`: it reads
 * them off the live `GlobalOkHttpInterceptorServer` registration, which is what the data plane
 * answers `CHECK_MOCK` from. A session started from the Inspector is therefore reported here as
 * running, and a session started here is visible to `ControlApi.status()` — the two planes cannot
 * drift apart. What this service adds on top is the context the registration does not carry: which
 * device the session targets, when it started, and who started it.
 *
 * ## Threading
 *
 * [startSession], [stopSession] and [listDevices] block on ADB (and on `simctl`/`devicectl`) and
 * must run on a background thread — a control-plane worker, or `Task.Backgroundable` from the UI.
 * They log a warning instead of freezing silently if called on the EDT. [state] is cheap and does
 * no I/O, so the UI may call it from the EDT as often as it likes.
 *
 * Listeners fire on **the thread that made the change**, which for an agent-driven change is a
 * control-plane worker. A Swing listener must therefore hop to the EDT itself:
 *
 * ```kotlin
 * CaptureSessionService.getInstance(project).addStateListener(toolWindow.disposable) { state ->
 *     SwingUtilities.invokeLater { render(state) }
 * }
 * ```
 */
@Service(Service.Level.PROJECT)
class CaptureSessionService(private val project: Project) : Disposable {

    // Resolved lazily, never in the constructor: this service is itself built by the platform
    // container, and a service that asks the container for another service while being
    // constructed is exactly what the platform tells you not to do.
    private val logger: MockkHttpLogger by lazy { MockkHttpLogger.getInstance(project) }
    private val emulatorManager: EmulatorManager by lazy { EmulatorManager.getInstance(project) }
    private val appManager: AppManager by lazy { AppManager.getInstance(project) }
    private val simulatorManager: SimulatorManager by lazy { SimulatorManager.getInstance(project) }
    private val interceptor: OkHttpInterceptorServer by lazy { OkHttpInterceptorServer.getInstance(project) }

    private val stateListeners = CopyOnWriteArrayList<StateListener>()

    /**
     * The device this project is pointed at. Survives a stop on purpose: it is what makes
     * `session/start` with no arguments work a second time, and what `session/restart` reuses.
     * Only ever written under [startLock].
     */
    @Volatile
    private var targetDevice: EmulatorInfo? = null

    /** The package filter this project is pointed at. Survives a stop, same reasoning. */
    @Volatile
    private var targetPackage: String? = null

    @Volatile
    private var startedAt: Long? = null

    @Volatile
    private var startedBy: SessionOrigin = SessionOrigin.UNKNOWN

    /**
     * Serialises start/stop so two callers cannot interleave the four steps above.
     *
     * A plain lock rather than `@Synchronized`: the whole point is that the section is long
     * (ADB init plus a tunnel plus a bind) and that no other method blocks on it — [state] must
     * stay answerable from the EDT while a start is in flight.
     */
    private val startLock = Any()

    companion object {
        /** Bounded probe: one `pm list packages` per device, not an APK scan. */
        private const val PACKAGE_PROBE_TIMEOUT_SECONDS = 10L

        fun getInstance(project: Project): CaptureSessionService =
            project.getService(CaptureSessionService::class.java)
    }

    // ========================================================================
    // Types
    // ========================================================================

    /** Who put the session in the state it is in. Reported so a UI can say "an agent did this". */
    enum class SessionOrigin {
        /** Started through this service by the agent control plane. */
        AGENT,

        /** Started through this service by the Inspector. */
        UI,

        /** Running, but nobody told this service — a legacy `OkHttpInterceptorServer.start()`. */
        UNKNOWN
    }

    /**
     * Everything a caller needs to render or reason about the session.
     *
     * [running], [mode] and [packageFilter] are read from the live registration; the rest is this
     * service's own bookkeeping. [device] is the device as it was observed when the session
     * started — `listDevices()` is the live view.
     */
    data class SessionState(
        val running: Boolean,
        val mode: OkHttpInterceptorServer.Mode?,
        val packageFilter: String?,
        val device: EmulatorInfo?,
        val targetPackage: String?,
        val startedAt: Long?,
        val startedBy: SessionOrigin,
        /** The adb this project would use, when it has already been resolved. Never resolved here. */
        val adbPath: String?
    )

    /** Where an app name came from, so a caller can tell a proof from a guess. */
    enum class AppsSource {
        /** The app spoke the MockkHttp protocol to this IDE. The strongest signal there is. */
        ANNOUNCED,

        /** A full device scan (`AppManager.getInstalledApps`) — minutes on a real phone. */
        DEVICE_SCAN,

        /** Nothing to report. */
        NONE
    }

    data class AppCandidate(
        val packageName: String,
        val appName: String?,
        /** The app has PINGed this IDE. Proof that it is instrumented, not an inference. */
        val announced: Boolean,
        /**
         * MockkHttp was detected in this app by any means — a PING, an APK marker, a simulator
         * marker file. Weaker than [announced], and the flag a device scan can actually produce.
         */
        val instrumented: Boolean,
        /** True/false when the device could be asked, null when it could not. */
        val installedOnDevice: Boolean?
    )

    data class DeviceCandidate(
        val device: EmulatorInfo,
        val apps: List<AppCandidate>,
        val appsSource: AppsSource,
        val note: String?
    ) {
        val serial: String get() = device.serialNumber
    }

    data class DeviceListing(
        val devices: List<DeviceCandidate>,
        /** Every package that has announced itself to this IDE, whatever device it is on. */
        val instrumentedPackages: List<String>,
        val adbAvailable: Boolean,
        val adbPath: String?,
        val iosToolingAvailable: Boolean,
        /** True when `simctl`/`devicectl` FAILED — which is not the same as "no iOS devices". */
        val iosEnumerationFailed: Boolean,
        val deepScanned: Boolean,
        val warnings: List<String>
    )

    /** Why a start could not happen. Each value maps to one actionable control-plane error. */
    enum class StartFailure {
        /** No adb executable anywhere: settings, ANDROID_HOME, the usual paths, PATH. */
        ADB_NOT_FOUND,

        /** adb was found but the bridge would not come up. */
        ADB_UNAVAILABLE,

        /** Nothing is connected — no ADB device, no booted simulator, no paired iPhone. */
        NO_DEVICES,

        /** A serial was named and no connected device has it. */
        DEVICE_NOT_FOUND,

        /** No serial was named and more than one device is connected. */
        AMBIGUOUS_DEVICE,

        /** No package was named and none could be inferred. */
        APP_NOT_CHOSEN,

        /** No package was named and more than one instrumented app is a candidate. */
        AMBIGUOUS_APP,

        /** `adb reverse` failed on a physical Android device, which has no other route here. */
        REVERSE_TUNNEL_FAILED,

        /** Port 9876 is held by another process — usually a second IDE with MockkHttp open. */
        INTERCEPTOR_UNAVAILABLE,

        /** The global server accepted nothing; `GlobalOkHttpInterceptorServer` logged why. */
        REGISTRATION_FAILED,

        /** The project was closed while the call was in flight. */
        PROJECT_DISPOSED
    }

    sealed class StartResult {

        /**
         * @param deviceResolvedBy `explicit` | `remembered` | `sole-device` | `only-device-with-app`
         * @param appResolvedBy `explicit` | `remembered` | `sole-announced-app` | `sole-app-on-device`
         */
        data class Started(
            val state: SessionState,
            val deviceResolvedBy: String,
            val appResolvedBy: String,
            val warnings: List<String>
        ) : StartResult()

        /** A session was already running. Any requested mode / filter change has been applied. */
        data class AlreadyRunning(
            val state: SessionState,
            val warnings: List<String>
        ) : StartResult()

        /**
         * @param devices the candidates, when the failure is about choosing one
         * @param apps    the candidates, when the failure is about choosing an app
         */
        data class Failed(
            val reason: StartFailure,
            val message: String,
            val devices: List<DeviceCandidate> = emptyList(),
            val apps: List<AppCandidate> = emptyList()
        ) : StartResult()
    }

    data class StopResult(
        val wasRunning: Boolean,
        val state: SessionState,
        val warnings: List<String>
    )

    fun interface StateListener {
        fun onSessionStateChanged(state: SessionState)
    }

    // ========================================================================
    // State
    // ========================================================================

    /**
     * The session as it is right now.
     *
     * Cheap and I/O-free — a volatile map read plus this service's own fields — so the EDT may
     * call it. In particular it never resolves the adb path: [EmulatorManager.getResolvedAdbPath]
     * can spawn processes, so only the already-memoized value is reported.
     */
    fun state(): SessionState {
        val registration = registration()
        val adbPath = if (project.isDisposed) null else emulatorManager.getResolvedAdbPathOrNull()
        return SessionState(
            running = registration != null,
            mode = registration?.let { toServerMode(it.mode) },
            // While running, the live filter is the truth — the Inspector can have changed it.
            // While stopped, the remembered target is what the next start would use.
            packageFilter = registration?.packageNameFilter ?: targetPackage,
            device = targetDevice,
            targetPackage = targetPackage,
            startedAt = startedAt.takeIf { registration != null },
            startedBy = if (registration != null) startedBy else SessionOrigin.UNKNOWN,
            adbPath = adbPath
        )
    }

    /**
     * Observe every session change. [parent] scopes the subscription, so a rebuilt tool window
     * cannot leak a listener.
     *
     * The listener runs on the thread that made the change — see the class KDoc.
     */
    fun addStateListener(parent: Disposable, listener: StateListener) {
        stateListeners.add(listener)
        Disposer.register(parent) { stateListeners.remove(listener) }
    }

    // ========================================================================
    // Start / stop
    // ========================================================================

    /**
     * Start capturing, resolving the device and the app when they are not given.
     *
     * @param serial      an ADB serial or an iOS UDID. Null asks this service to resolve one: the
     *                    remembered target if it is still connected, otherwise the sole connected
     *                    device, otherwise the only device the chosen app is installed on.
     * @param packageName the package filter. Null asks this service to resolve one from the apps
     *                    that have announced themselves. **Nullable on purpose**: an agent has no
     *                    combo box, and "the only instrumented app on the only device" is a
     *                    resolution the caller cannot be expected to perform. Ambiguity is always
     *                    a typed failure listing the candidates, never a silent guess.
     * @param origin      who is starting this, for the UI to display.
     */
    /**
     * Record that the Inspector started this session, without re-doing the work.
     *
     * The UI's Start button drives OkHttpInterceptorServer directly and always has; rerouting it
     * through [startSession] would put the user's primary path through code written for agents, for
     * no behaviour they would notice. What was missing is only the provenance: without this, a
     * session a human started reported `started_by: unknown` and no device, so an agent reading
     * status could not tell a live human-driven session from a stale registration.
     */
    fun noteUiSession(device: EmulatorInfo?, packageName: String?) {
        // Same lock as startLocked/stopLocked: these four fields are one record, and an agent
        // start racing this call must not leave a device from one and a package from the other.
        synchronized(startLock) {
            startedBy = SessionOrigin.UI
            startedAt = System.currentTimeMillis()
            device?.let { targetDevice = it }
            packageName?.let { targetPackage = it }
        }
        notifyListeners(state())
    }

    fun startSession(
        serial: String? = null,
        packageName: String? = null,
        mode: OkHttpInterceptorServer.Mode = OkHttpInterceptorServer.Mode.RECORDING,
        origin: SessionOrigin = SessionOrigin.AGENT
    ): StartResult {
        warnIfOnEdt("startSession")
        if (project.isDisposed) {
            return StartResult.Failed(StartFailure.PROJECT_DISPOSED, "The project was closed.")
        }
        return synchronized(startLock) { startLocked(serial, packageName, mode, origin) }
    }

    /** The body of [startSession], so the lock is one expression and every exit is a plain return. */
    private fun startLocked(
        serial: String?,
        packageName: String?,
        mode: OkHttpInterceptorServer.Mode,
        origin: SessionOrigin
    ): StartResult {
        val requestedPackage = packageName?.trim()?.takeIf { it.isNotEmpty() }
        val requestedSerial = serial?.trim()?.takeIf { it.isNotEmpty() }

        registration()?.let {
            return adoptRunningSession(it, requestedSerial, requestedPackage, mode)
        }

        val warnings = mutableListOf<String>()
        val listing = buildListing(deepScan = false)
        val resolved = when (val target = resolveTarget(listing, requestedSerial, requestedPackage)) {
            is Resolution.Failed -> return target.result
            is Resolution.Ok -> target
        }
        val device = resolved.device
        val pkg = resolved.packageName

        // Bind BEFORE opening the tunnel: an app that gets a refused connection through a tunnel
        // with nothing behind it backs off for 15 s, so the wrong order costs a whole retry cycle.
        val global = GlobalOkHttpInterceptorServer.getInstance()
        if (!global.ensureStarted()) {
            return StartResult.Failed(
                StartFailure.INTERCEPTOR_UNAVAILABLE,
                global.getBindError()
                    ?: "The interceptor could not listen on port ${GlobalOkHttpInterceptorServer.SERVER_PORT}."
            )
        }

        when (device.platform) {
            DevicePlatform.ANDROID -> {
                logger.info("📲 Opening the ADB reverse tunnel for ${device.displayName}...")
                val opened = emulatorManager.setupAdbReverse(
                    device.serialNumber,
                    OkHttpInterceptorServer.SERVER_PORT,
                    OkHttpInterceptorServer.SERVER_PORT
                )
                if (!opened) {
                    // Fatal on a phone: it has no other route to this machine. An emulator still
                    // reaches us on 10.0.2.2, so there the same failure is only a warning.
                    if (!device.isEmulator) {
                        return StartResult.Failed(
                            StartFailure.REVERSE_TUNNEL_FAILED,
                            "`adb reverse tcp:${OkHttpInterceptorServer.SERVER_PORT}` failed on " +
                                    "${device.displayName} (${device.serialNumber}), and a physical " +
                                    "device has no other route to this machine."
                        )
                    }
                    warnings += "adb reverse failed on the emulator; capture falls back to the " +
                            "10.0.2.2 route, which only works for apps that dial it."
                }
            }

            DevicePlatform.IOS_DEVICE ->
                warnings += "A physical iPhone reaches the plugin over the LAN: the app must have " +
                        "been started with MockkHttp.init(host: '<this Mac's LAN IP>')."

            // An iOS Simulator shares the Mac's loopback, so 127.0.0.1 already reaches us.
            DevicePlatform.IOS_SIMULATOR -> Unit
        }

        if (!interceptor.start(mode, pkg)) {
            // start() also returns false when this project is already registered, which here can
            // only mean the Inspector started a session while we were opening the tunnel.
            registration()?.let {
                return adoptRunningSession(it, requestedSerial, requestedPackage, mode)
            }
            return StartResult.Failed(
                StartFailure.REGISTRATION_FAILED,
                "The project could not register with the interceptor server. The IDE log has the reason."
            )
        }

        targetDevice = device
        targetPackage = pkg
        startedAt = System.currentTimeMillis()
        startedBy = origin

        if (resolved.appsSource != AppsSource.ANNOUNCED) {
            warnings += "$pkg has not announced itself to this IDE yet, so nothing proves it is " +
                    "instrumented. Launch it: a MockkHttp-enabled app PINGs on startup."
        }

        logger.info(
            "🤖 Capture session started by ${origin.name.lowercase()}: mode=${mode.name}, " +
                    "app=$pkg, device=${device.displayName} (${device.serialNumber})"
        )

        val state = state()
        notifyListeners(state)
        return StartResult.Started(state, resolved.deviceResolvedBy, resolved.appResolvedBy, warnings)
    }

    /**
     * Stop capturing.
     *
     * The `adb reverse` tunnel is deliberately LEFT OPEN, exactly as the Inspector's Stop does:
     * tearing it down makes a still-running app lose its route and back off for 15 s, so the app
     * then disappears from the next scan. The mapping costs nothing, dies with the device, and
     * degrades safely — with no server behind it adbd accepts and closes, and both clients require
     * a literal PONG before they consider the plugin connected.
     */
    fun stopSession(): StopResult {
        warnIfOnEdt("stopSession")
        return synchronized(startLock) { stopLocked() }
    }

    private fun stopLocked(): StopResult {
        val wasRunning = registration() != null
        if (wasRunning) {
            val who = if (startedBy == SessionOrigin.UNKNOWN) {
                "started outside this service"
            } else {
                "started by ${startedBy.name.lowercase()}"
            }
            interceptor.stop()
            logger.info("🤖 Capture session stopped (it was $who)")
        }
        startedAt = null
        startedBy = SessionOrigin.UNKNOWN

        val state = state()
        if (wasRunning) notifyListeners(state)
        return StopResult(
            wasRunning = wasRunning,
            state = state,
            warnings = if (wasRunning) emptyList() else listOf(
                "No capture session was running for this project, so nothing was stopped."
            )
        )
    }

    // ========================================================================
    // Mode and package filter
    // ========================================================================

    /**
     * Change the capture mode of a running session.
     *
     * A no-op while stopped — `OkHttpInterceptorServer.setMode` only reaches the registration when
     * it is running, and the next `start(mode, …)` takes its mode as an argument anyway. Callers
     * that must not silently lose a mode change check [SessionState.running] first.
     */
    fun setMode(mode: OkHttpInterceptorServer.Mode): SessionState {
        interceptor.setMode(mode)
        val state = state()
        notifyListeners(state)
        return state
    }

    /**
     * Point the session at [packageName].
     *
     * Applied to the live session when one is running, and remembered for the next start either
     * way. `null` clears the filter, which makes this project capture EVERY instrumented app and
     * steal flows from any other open project — the caller is expected to have said so explicitly.
     */
    fun setPackageFilter(packageName: String?): SessionState {
        val normalized = packageName?.trim()?.takeIf { it.isNotEmpty() }
        targetPackage = normalized
        if (registration() != null) {
            interceptor.setPackageNameFilter(normalized)
        }
        val state = state()
        notifyListeners(state)
        return state
    }

    /**
     * Point the session at a device without starting.
     *
     * @return false when no connected device has that serial; the target is left untouched.
     */
    fun setTargetDevice(serial: String): Boolean {
        warnIfOnEdt("setTargetDevice")
        val device = enumerateDevices().devices.find { it.serialNumber == serial } ?: return false
        synchronized(startLock) { targetDevice = device }
        notifyListeners(state())
        return true
    }

    // ========================================================================
    // Devices and apps
    // ========================================================================

    /**
     * Every connected device and, per device, the apps that are known to be instrumented.
     *
     * The strongest signal is `GlobalOkHttpInterceptorServer.getKnownMockkHttpPackages()` — an app
     * that has spoken the protocol to this IDE has *proven* it is instrumented, which no scan can
     * do better. Announced packages carry no device of their own, so each is attributed to a
     * device by asking that device whether it has the package installed (one `pm list packages`,
     * tens of milliseconds); a device that cannot be asked keeps the package as "unknown" rather
     * than dropping it.
     *
     * @param deepScan run `AppManager.getInstalledApps` when nothing has announced itself. That is
     *   the only expensive path here — **minutes** on a physical device — so it is opt-in and
     *   never taken while an announcement exists.
     */
    fun listDevices(deepScan: Boolean = false): DeviceListing {
        warnIfOnEdt("listDevices")
        return buildListing(deepScan)
    }

    /** The body of [listDevices]; [startLocked] reuses it without a second EDT warning. */
    private fun buildListing(deepScan: Boolean): DeviceListing {
        val scan = enumerateDevices()
        val announced = GlobalOkHttpInterceptorServer.getInstance().getKnownMockkHttpPackages()
        val warnings = scan.warnings.toMutableList()

        val candidates = scan.devices.map { device ->
            appsFor(device, announced, deepScan, scan.devices.size == 1)
        }

        if (announced.isEmpty()) {
            warnings += "No app has announced itself to this IDE yet. Launch the app under test — a " +
                    "MockkHttp-enabled build PINGs on startup and then appears here."
        }

        return DeviceListing(
            devices = candidates,
            instrumentedPackages = announced.toList(),
            adbAvailable = scan.adbReady,
            adbPath = scan.adbPath,
            iosToolingAvailable = scan.iosToolingAvailable,
            iosEnumerationFailed = scan.iosEnumerationFailed,
            deepScanned = deepScan && announced.isEmpty(),
            warnings = warnings
        )
    }

    private fun appsFor(
        device: EmulatorInfo,
        announced: Set<String>,
        deepScan: Boolean,
        soleDevice: Boolean
    ): DeviceCandidate {
        if (announced.isNotEmpty()) {
            val installed = installedPackagesOn(device)
            val apps = announced
                .map { pkg ->
                    AppCandidate(
                        packageName = pkg,
                        appName = null,
                        announced = true,
                        instrumented = true,
                        installedOnDevice = installed?.contains(pkg)
                    )
                }
                // Drop only what the device PROVED it does not have. An unknown stays, because a
                // package that announced itself is on *some* connected device.
                .filter { it.installedOnDevice != false }
            val note = when {
                installed != null -> null
                soleDevice -> "This device could not be asked which packages it has; with a single " +
                        "device connected, an announced app can only have come from it."
                else -> "This device could not be asked which packages it has, so these apps are " +
                        "listed on every device that could not be asked."
            }
            return DeviceCandidate(device, apps, AppsSource.ANNOUNCED, note)
        }

        if (!deepScan) {
            return DeviceCandidate(
                device, emptyList(), AppsSource.NONE,
                "Nothing has announced itself. Launch the app, or ask for a device scan — it reads " +
                        "every installed APK and takes minutes on a physical device."
            )
        }

        val scanned: List<AppInfo> = try {
            when (device.platform) {
                DevicePlatform.ANDROID -> appManager.getInstalledApps(
                    device.serialNumber,
                    includeSystem = false,
                    showAllApps = false,
                    isCancelled = { project.isDisposed }
                )

                DevicePlatform.IOS_SIMULATOR -> simulatorManager.getInstalledApps(device)
                DevicePlatform.IOS_DEVICE -> simulatorManager.getInstalledAppsPhysical(device)
            }
        } catch (e: Exception) {
            logger.warn("⚠️ Device scan failed on ${device.displayName}: ${e.message}", e)
            emptyList()
        }

        return DeviceCandidate(
            device = device,
            apps = scanned.map {
                AppCandidate(
                    packageName = it.packageName,
                    appName = it.appName,
                    announced = false,
                    // The Android scan returns only detected apps; the iOS one returns every user
                    // app and merely flags the detected ones, so the flag is what to carry.
                    instrumented = it.hasMockkHttp,
                    installedOnDevice = true
                )
            },
            appsSource = if (scanned.isEmpty()) AppsSource.NONE else AppsSource.DEVICE_SCAN,
            note = if (scanned.none { it.hasMockkHttp }) {
                "The scan detected MockkHttp in no app. A release build cannot be detected by " +
                        "scanning — launch it instead and let it announce itself."
            } else null
        )
    }

    // ========================================================================
    // Resolution
    // ========================================================================

    private sealed class Resolution {
        data class Ok(
            val device: EmulatorInfo,
            val packageName: String,
            val deviceResolvedBy: String,
            val appResolvedBy: String,
            val appsSource: AppsSource
        ) : Resolution()

        data class Failed(val result: StartResult.Failed) : Resolution()
    }

    /**
     * Turn "start a session" into a concrete (device, package) pair, or into a typed failure that
     * names the candidates. Never guesses: two plausible answers is a failure, not a coin flip.
     */
    private fun resolveTarget(
        listing: DeviceListing,
        requestedSerial: String?,
        requestedPackage: String?
    ): Resolution {
        if (listing.devices.isEmpty()) {
            val reason = when {
                !listing.adbAvailable && listing.adbPath == null -> StartFailure.ADB_NOT_FOUND
                !listing.adbAvailable -> StartFailure.ADB_UNAVAILABLE
                else -> StartFailure.NO_DEVICES
            }
            val message = when (reason) {
                StartFailure.ADB_NOT_FOUND ->
                    "No adb executable was found (settings, ANDROID_HOME, ANDROID_SDK_ROOT, the " +
                            "default SDK location, PATH), and no iOS device is available either."

                StartFailure.ADB_UNAVAILABLE ->
                    "adb was found at ${listing.adbPath} but the ADB bridge would not start."

                else -> "No device is connected: no ADB device, no booted iOS Simulator, no paired iPhone."
            }
            return Resolution.Failed(StartResult.Failed(reason, message))
        }

        // ---- device -----------------------------------------------------------------------
        val device: DeviceCandidate
        val deviceResolvedBy: String
        if (requestedSerial != null) {
            device = listing.devices.find { it.serial == requestedSerial }
                ?: return Resolution.Failed(
                    StartResult.Failed(
                        StartFailure.DEVICE_NOT_FOUND,
                        "No connected device has the serial '$requestedSerial'.",
                        devices = listing.devices
                    )
                )
            deviceResolvedBy = "explicit"
        } else {
            // A package narrows the choice before device count does: with a phone and an emulator
            // both plugged in, the device that actually has the app is not a guess.
            val wanted = requestedPackage ?: soleAnnouncedPackage(listing)
            val withApp = if (wanted == null) emptyList<DeviceCandidate>() else listing.devices.filter { candidate ->
                candidate.apps.any { it.packageName == wanted && it.installedOnDevice == true }
            }
            val remembered = targetDevice?.serialNumber?.let { serial ->
                listing.devices.find { it.serial == serial }
            }
            when {
                withApp.size == 1 -> {
                    device = withApp.first()
                    deviceResolvedBy = "only-device-with-app"
                }

                remembered != null -> {
                    device = remembered
                    deviceResolvedBy = "remembered"
                }

                listing.devices.size == 1 -> {
                    device = listing.devices.first()
                    deviceResolvedBy = "sole-device"
                }

                else -> return Resolution.Failed(
                    StartResult.Failed(
                        StartFailure.AMBIGUOUS_DEVICE,
                        "${listing.devices.size} devices are connected and none was named.",
                        devices = listing.devices
                    )
                )
            }
        }

        // ---- app --------------------------------------------------------------------------
        val onDevice = device.apps
        if (requestedPackage != null) {
            val known = onDevice.find { it.packageName == requestedPackage }
            return Resolution.Ok(
                device = device.device,
                packageName = requestedPackage,
                deviceResolvedBy = deviceResolvedBy,
                appResolvedBy = "explicit",
                // An explicit package is never refused for not being on the list: the list is only
                // as good as what has announced itself, and a filter is just a string comparison.
                appsSource = if (known?.announced == true) AppsSource.ANNOUNCED else AppsSource.NONE
            )
        }

        val remembered = targetPackage
        if (remembered != null) {
            return Resolution.Ok(
                device = device.device,
                packageName = remembered,
                deviceResolvedBy = deviceResolvedBy,
                appResolvedBy = "remembered",
                appsSource = if (onDevice.any { it.packageName == remembered && it.announced }) {
                    AppsSource.ANNOUNCED
                } else AppsSource.NONE
            )
        }

        val instrumented = onDevice.filter { it.instrumented }
        return when (instrumented.size) {
            1 -> Resolution.Ok(
                device = device.device,
                packageName = instrumented.first().packageName,
                deviceResolvedBy = deviceResolvedBy,
                appResolvedBy = if (device.appsSource == AppsSource.ANNOUNCED) {
                    "sole-announced-app"
                } else "sole-app-on-device",
                appsSource = device.appsSource
            )

            0 -> Resolution.Failed(
                StartResult.Failed(
                    StartFailure.APP_NOT_CHOSEN,
                    "No app has announced itself on ${device.device.displayName}, so there is nothing " +
                            "to filter on and nothing to infer from.",
                    devices = listing.devices
                )
            )

            else -> Resolution.Failed(
                StartResult.Failed(
                    StartFailure.AMBIGUOUS_APP,
                    "${instrumented.size} instrumented apps are candidates on " +
                            "${device.device.displayName} and none was named.",
                    devices = listing.devices,
                    apps = instrumented
                )
            )
        }
    }

    private fun soleAnnouncedPackage(listing: DeviceListing): String? =
        listing.instrumentedPackages.singleOrNull()

    /**
     * A session is already running: apply whatever the caller asked to change and report it.
     *
     * Reporting success here rather than a conflict is deliberate — "start it" on a running
     * session is asking for a state that already holds — but silently dropping a mode or a filter
     * the caller passed would be the "never ignore a parameter" rule broken, so both are applied
     * and both are named in the warnings.
     */
    private fun adoptRunningSession(
        registration: GlobalOkHttpInterceptorServer.ProjectRegistration,
        requestedSerial: String?,
        requestedPackage: String?,
        requestedMode: OkHttpInterceptorServer.Mode
    ): StartResult {
        val warnings = mutableListOf<String>()
        val liveMode = toServerMode(registration.mode)

        if (requestedPackage != null && requestedPackage != registration.packageNameFilter) {
            interceptor.setPackageNameFilter(requestedPackage)
            targetPackage = requestedPackage
            warnings += "A session was already running on ${registration.packageNameFilter ?: "every app"}; " +
                    "its package filter is now $requestedPackage."
        }
        if (liveMode != requestedMode) {
            interceptor.setMode(requestedMode)
            warnings += "A session was already running in ${liveMode.name}; it is now in ${requestedMode.name}."
        }
        if (requestedSerial != null && requestedSerial != targetDevice?.serialNumber) {
            warnings += "The running session cannot be moved to '$requestedSerial' — the adb reverse " +
                    "tunnel belongs to the device it was started on. Stop it first, then start again."
        }
        if (startedBy == SessionOrigin.UNKNOWN) {
            warnings += "This session was started outside the control plane (the Inspector's Start " +
                    "button), so the device it targets is not known here."
        }

        val state = state()
        if (warnings.isNotEmpty()) notifyListeners(state)
        return StartResult.AlreadyRunning(state, warnings)
    }

    // ========================================================================
    // Device enumeration
    // ========================================================================

    private data class DeviceScan(
        val devices: List<EmulatorInfo>,
        val adbReady: Boolean,
        val adbPath: String?,
        val iosToolingAvailable: Boolean,
        val iosEnumerationFailed: Boolean,
        val warnings: List<String>
    )

    /**
     * Android over ADB, iOS Simulators over `simctl`, physical iPhones over `devicectl` — the same
     * three sources the Inspector's device combo is built from.
     *
     * A failing ADB does not abort the enumeration: an iOS-only machine has no adb and must still
     * be able to drive a simulator. The iOS enumerations return null on TOOLING failure, which is
     * reported as such rather than collapsed into "no devices".
     */
    private fun enumerateDevices(): DeviceScan {
        val warnings = mutableListOf<String>()

        val adbReady = try {
            emulatorManager.initialize()
        } catch (e: Exception) {
            logger.warn("⚠️ ADB initialization threw", e)
            false
        }
        val adbPath = emulatorManager.getResolvedAdbPathOrNull()
        val androidDevices: List<EmulatorInfo> = if (adbReady) {
            try {
                emulatorManager.getConnectedDevices()
            } catch (e: Exception) {
                logger.warn("⚠️ Could not enumerate ADB devices", e)
                warnings += "ADB device enumeration failed: ${e.message}"
                emptyList()
            }
        } else {
            if (adbPath == null) {
                warnings += "No adb executable was found, so no Android device can be listed. " +
                        "Set it in MockkHttp → Settings, or install the Android SDK platform-tools."
            } else {
                warnings += "adb was found at $adbPath but the ADB bridge would not start."
            }
            emptyList()
        }

        val iosAvailable = simulatorManager.isAvailable()
        val simulators = simulatorManager.getBootedSimulators()
        val iosDevices = simulatorManager.getConnectedIosDevices()
        val iosFailed = simulators == null || iosDevices == null
        if (iosFailed) {
            warnings += "simctl/devicectl did not answer, so the iOS device list may be incomplete. " +
                    "That is a tooling failure, not proof that nothing is connected."
        }

        return DeviceScan(
            devices = androidDevices + (simulators ?: emptyList()) + (iosDevices ?: emptyList()),
            adbReady = adbReady,
            adbPath = adbPath,
            iosToolingAvailable = iosAvailable,
            iosEnumerationFailed = iosFailed,
            warnings = warnings
        )
    }

    /**
     * The package names installed on [device], or null when the device cannot be asked.
     *
     * One `pm list packages` — no `-3` filter, so a package that happens to be preinstalled is not
     * mistaken for absent, and no APK is read. Android only: `simctl`/`devicectl` can answer the
     * same question but not in tens of milliseconds, and this runs on every device listing.
     */
    private fun installedPackagesOn(device: EmulatorInfo): Set<String>? {
        if (device.platform != DevicePlatform.ANDROID) return null
        return try {
            val adbDevice = emulatorManager.getDevice(device.serialNumber) ?: return null
            if (!adbDevice.isOnline) return null
            val receiver = EmulatorManager.CollectingOutputReceiver { project.isDisposed }
            adbDevice.executeShellCommand(
                "pm list packages", receiver, PACKAGE_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS
            )
            receiver.output.lines()
                .asSequence()
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .filter { it.isNotEmpty() }
                .toSet()
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            logger.debug("Could not list packages on ${device.serialNumber}: ${e.message}")
            null
        }
    }

    // ========================================================================
    // Plumbing
    // ========================================================================

    private fun registration(): GlobalOkHttpInterceptorServer.ProjectRegistration? {
        if (project.isDisposed) return null
        val id = project.locationHash
        return GlobalOkHttpInterceptorServer.getInstance().getRegisteredProjects()
            .find { it.projectId == id }
    }

    private fun toServerMode(mode: GlobalOkHttpInterceptorServer.InterceptMode): OkHttpInterceptorServer.Mode =
        when (mode) {
            GlobalOkHttpInterceptorServer.InterceptMode.RECORDING -> OkHttpInterceptorServer.Mode.RECORDING
            GlobalOkHttpInterceptorServer.InterceptMode.DEBUG -> OkHttpInterceptorServer.Mode.DEBUG
            GlobalOkHttpInterceptorServer.InterceptMode.MOCKK -> OkHttpInterceptorServer.Mode.MOCKK
            GlobalOkHttpInterceptorServer.InterceptMode.MOCKK_DEBUG -> OkHttpInterceptorServer.Mode.MOCKK_DEBUG
        }

    private fun notifyListeners(state: SessionState) {
        for (listener in stateListeners) {
            try {
                listener.onSessionStateChanged(state)
            } catch (e: Exception) {
                // A listener is a UI detail; it must never be able to fail a session start.
                logger.warn("⚠️ A session state listener threw", e)
            }
        }
        // The discovery file publishes this project's session (running, mode, filter, device) and
        // is the FIRST thing an agent reads. It was written at IDE start and never again, so it
        // said "nothing is capturing" for the whole life of a MOCKK session (audit round 5, Z).
        try {
            InstanceRegistry.getInstance().scheduleRefresh("session changed: ${project.name}")
        } catch (e: Exception) {
            logger.debug("Could not schedule an instance-file refresh after a session change: ${e.message}")
        }
    }

    /**
     * These calls block on ADB. They are headless — no dialogs — but running one ON the EDT
     * freezes the IDE, so make it visible instead of leaving it to look like a hang. Never fatal:
     * the call still works, just noisily. (Same guard as `AppManager.warnIfOnEdt`.)
     */
    private fun warnIfOnEdt(operation: String) {
        if (ApplicationManager.getApplication()?.isDispatchThread == true) {
            logger.warn(
                "⚠️ $operation was called on the EDT; it blocks on ADB and will freeze the IDE. " +
                        "Call it from Task.Backgroundable or a control-plane thread."
            )
        }
    }

    /**
     * Drop the listeners when the project closes.
     *
     * The session itself is NOT stopped here: `OkHttpInterceptorServer.dispose()` already
     * unregisters the project from the global server, and doing it twice would race with it.
     */
    override fun dispose() {
        stateListeners.clear()
        targetDevice = null
        startedAt = null
    }
}
