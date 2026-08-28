package com.sergiy.dev.mockkhttp.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.sergiy.dev.mockkhttp.adb.*
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import com.sergiy.dev.mockkhttp.model.HttpFlowData
import com.sergiy.dev.mockkhttp.proxy.OkHttpInterceptorServer
import com.sergiy.dev.mockkhttp.store.FlowStore
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import javax.swing.*

/**
 * Inspector panel with compact vertical controls and flow list.
 * Uses OkHttpInterceptorServer only (no proxy mode).
 */
class InspectorPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val logger = MockkHttpLogger.getInstance(project)
    private val emulatorManager = EmulatorManager.getInstance(project)
    private val appManager = AppManager.getInstance(project)
    private val simulatorManager = com.sergiy.dev.mockkhttp.ios.SimulatorManager.getInstance(project)
    private val okHttpInterceptorServer = OkHttpInterceptorServer.getInstance(project)
    private val flowStore = FlowStore.getInstance(project)

    // UI Components
    private val emulatorComboBox: ComboBox<EmulatorInfo>
    private val refreshDevicesButton: JButton
    private val appComboBox: ComboBox<AppInfo>
    private val refreshAppsButton: JButton
    private val recordingRadio: JRadioButton
    private val mockkRadio: JRadioButton
    private val modeButtonGroup: ButtonGroup
    private val debugCheckbox: JCheckBox
    private val startStopButton: JButton
    private val clearButton: JButton
    private val exportButton: JButton
    private val statusLabel: JLabel
    private val searchField: JBTextField
    private val flowListModel: DefaultListModel<HttpFlowData>
    private val flowList: JBList<HttpFlowData>
    private val allFlows = mutableListOf<HttpFlowData>() // Keep all flows for filtering

    // State
    private var selectedEmulator: EmulatorInfo? = null
    private var selectedApp: AppInfo? = null
    private var currentMode: Mode = Mode.STOPPED
    private var searchQuery: String = ""

    // Serials whose app scan is in flight. A single-slot guard let A -> B -> A queue a second
    // concurrent scan of A, doubling the adb connections the USB dispatcher caps at 3.
    private val scanningSerials: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    // The serial the user picked. Survives disconnection - it is the only memory of that
    // choice, and without it a reconnect silently jumps to whatever sits at index 0.
    private var lastSelectedSerial: String? = null

    // Same for the app: an empty rescan nulls selectedApp, and without this the next scan
    // would silently repoint a live capture session at whatever app sorts first.
    private var lastSelectedPackage: String? = null

    // Device whose apps currently populate appComboBox / selectedApp.
    private var scannedAppsSerial: String? = null

    // Serials whose scan the user cancelled. Cancelling must stick: otherwise the next ADB
    // event immediately relaunches the very scan the user just stopped.
    private val autoScanOptOut: MutableSet<String> = HashSet()

    // Suppresses combo action events while refreshEmulators() rebuilds the device list, so
    // the transient auto-selection of index 0 can't queue an app scan for the wrong device.
    private var isRebuildingDeviceCombo = false

    // Same idea for the app combo: repopulating it must not look like the user deselecting
    // the app, which would drop the package filter of a live capture session.
    private var isRebuildingAppCombo = false

    // Re-entrancy guard for the device refresh task: a single USB flap makes ddmlib fire
    // disconnect + connect + N state changes, and each one used to queue another task.
    @Volatile
    private var refreshingDevices = false

    // A manual refresh that lands while another one is in flight must not lose its intent.
    @Volatile
    private var pendingManualRefresh = false

    /** Rolling event budget. EDT-only, so no synchronization needed. */
    private class ScanBudget(var windowStartMs: Long, var attempts: Int)

    // Cooldown anchored to the END of the last automatic scan of a serial, with exponential
    // backoff. Counting *events* per window (the previous design) cannot bound this loop at
    // all: a scan takes minutes, so the flap it provokes arrives long after the window has
    // rolled over and every attempt looks like the first one.
    private val lastAutoScanEndedMs = HashMap<String, Long>()
    private val consecutiveAutoScans = HashMap<String, Int>()

    // Second line of defence, still per event: the serial is not stable - with Wireless
    // Debugging adbd hands out a new port on every reconnect, so a per-serial cooldown alone
    // would be sidestepped by an endless supply of "new" devices.
    private val globalScanBudget = ScanBudget(0L, 0)

    // 0L = no burst pending. Bounds how long the debouncer may keep deferring a refresh.
    private var pendingSinceMs = 0L

    // Trailing-edge debouncer: collapses a burst of ddmlib device events into one refresh.
    // javax.swing.Timer (not com.intellij.util.Alarm) because it fires on the EDT and needs
    // no parent Disposable - InspectorPanel is a JPanel, not a Disposable.
    private val deviceChangeDebouncer = Timer(DEVICE_CHANGE_DEBOUNCE_MS) {
        doRefreshEmulators(auto = true)
    }.apply {
        isRepeats = false
    }

    private companion object {
        const val DEVICE_CHANGE_DEBOUNCE_MS = 1500

        // Cap on how long a burst may defer the refresh. Without it, a device flapping faster
        // than the debounce interval starves the timer and the list never refreshes at all -
        // which is precisely the "unstable ADB" case the debounce exists for.
        const val DEVICE_CHANGE_MAX_WAIT_MS = 6_000L

        // How many *automatic* scans of the same device we tolerate per window before handing
        // control back to the user (1 initial + 2 rescans). A legitimate cable replug still
        // works; an ADB that keeps flapping no longer re-triggers the scan forever.
        const val MAX_AUTO_SCANS_PER_WINDOW_GLOBAL = 6
        const val AUTO_SCAN_WINDOW_MS = 60_000L

        // Minimum quiet time after an automatic scan ends before another one may start. Each
        // consecutive automatic scan doubles it (up to the ceiling), so a permanently broken
        // device converges towards silence instead of rescanning forever.
        const val AUTO_SCAN_COOLDOWN_MS = 60_000L
        const val MAX_AUTO_SCAN_COOLDOWN_MS = 15 * 60_000L

        // TTL of the per-serial maps. Must exceed the largest decay threshold (2 x ceiling),
        // or purging would delete the backoff state before the decay branch could ever run -
        // silently resetting the ladder to zero instead of holding at the ceiling.
        const val AUTO_SCAN_STATE_TTL_MS = 2 * MAX_AUTO_SCAN_COOLDOWN_MS + 60_000L
    }

    enum class Mode {
        STOPPED,
        RECORDING,      // Recording without debug
        DEBUG,          // Recording + Debug
        MOCKK,          // Mockk without debug
        MOCKK_DEBUG     // Mockk + Debug
    }

    init {
        logger.info("Initializing Inspector Panel...")

        // Initialize combo boxes
        emulatorComboBox = ComboBox<EmulatorInfo>().apply {
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int,
                    isSelected: Boolean, cellHasFocus: Boolean
                ): java.awt.Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value is EmulatorInfo) {
                        val typeIcon = when (value.platform) {
                            DevicePlatform.ANDROID -> if (value.isEmulator) "📱" else "📲"
                            DevicePlatform.IOS_SIMULATOR -> "🍎"
                            DevicePlatform.IOS_DEVICE -> "🍏"
                        }
                        text = "$typeIcon ${value.displayName} (${value.versionLabel})"
                    }
                    return this
                }
            }
            addActionListener { onEmulatorSelected(auto = false) }
        }

        refreshDevicesButton = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = "Refresh Devices (Android + iOS)"
            addActionListener { refreshEmulators(auto = false) }
        }

        appComboBox = ComboBox<AppInfo>().apply {
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int,
                    isSelected: Boolean, cellHasFocus: Boolean
                ): java.awt.Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value is AppInfo) {
                        text = value.packageName
                    }
                    return this
                }
            }
            addActionListener { onAppSelected() }
        }

        refreshAppsButton = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = "Refresh Apps"
            isEnabled = false
            addActionListener { forceRefreshApps() }
        }

        // Create mode radio buttons
        recordingRadio = JRadioButton("Recording", AllIcons.Debugger.Db_set_breakpoint, true).apply {
            toolTipText = "Capture network traffic"
            isEnabled = false
            addActionListener { updateModeIfRunning() }
        }

        mockkRadio = JRadioButton("Mockk", AllIcons.Nodes.DataSchema, false).apply {
            toolTipText = "Apply mock rules from configured collections"
            isEnabled = false
            addActionListener { updateModeIfRunning() }
        }

        modeButtonGroup = ButtonGroup().apply {
            add(recordingRadio)
            add(mockkRadio)
        }

        debugCheckbox = JCheckBox("Debug", AllIcons.Actions.StartDebugger, false).apply {
            toolTipText = "Pause each request/response for manual inspection and editing"
            isEnabled = false
            addActionListener { updateModeIfRunning() }
        }

        startStopButton = JButton("Start", AllIcons.Actions.Execute).apply {
            toolTipText = "Start interceptor with selected mode"
            isEnabled = false
            addActionListener {
                if (currentMode == Mode.STOPPED) {
                    startInterceptor()
                } else {
                    stopInterceptor()
                }
            }
        }

        clearButton = JButton("Clear", AllIcons.Actions.GC).apply {
            toolTipText = "Clear All Flows"
            addActionListener { clearFlows() }
        }

        exportButton = JButton("Export", AllIcons.ToolbarDecorator.Export).apply {
            toolTipText = "Export Flows"
            addActionListener { exportFlows() }
        }

        statusLabel = JLabel("Ready").apply {
            foreground = JBColor.GRAY
        }

        // Search field
        searchField = JBTextField().apply {
            emptyText.text = "Search flows... (⌘F)"

            // Add listener for real-time filtering
            document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) {
                    filterFlows()
                }
                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) {
                    filterFlows()
                }
                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) {
                    filterFlows()
                }
            })
        }

        // Flow list
        flowListModel = DefaultListModel()
        flowList = JBList(flowListModel).apply {
            cellRenderer = FlowListCellRenderer()
            selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION

            // Add keyboard listener for Command+A (Select All) and Command+F (Search)
            addKeyListener(object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: java.awt.event.KeyEvent) {
                    when {
                        // Command+A (Mac) or Ctrl+A (Windows/Linux) - Select All
                        (e.isMetaDown || e.isControlDown) && e.keyCode == java.awt.event.KeyEvent.VK_A -> {
                            selectionModel.setSelectionInterval(0, model.size - 1)
                            e.consume()
                        }
                        // Command+F (Mac) or Ctrl+F (Windows/Linux) - Focus Search
                        (e.isMetaDown || e.isControlDown) && e.keyCode == java.awt.event.KeyEvent.VK_F -> {
                            searchField.requestFocusInWindow()
                            searchField.selectAll()
                            e.consume()
                        }
                    }
                }
            })

            // Add mouse listener for double-click and context menu
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.clickCount == 2) {
                        val selectedFlow = selectedValue
                        if (selectedFlow != null) {
                            showFlowDetails(selectedFlow)
                        }
                    }
                }

                override fun mousePressed(e: java.awt.event.MouseEvent) {
                    if (e.isPopupTrigger) {
                        showContextMenu(e)
                    }
                }

                override fun mouseReleased(e: java.awt.event.MouseEvent) {
                    if (e.isPopupTrigger) {
                        showContextMenu(e)
                    }
                }
            })
        }

        // Setup layout
        setupLayout()

        // Clear any previous listeners (IDE may create the tool window multiple times)
        flowStore.clearAllListeners()

        // Listen to flow changes
        flowStore.addFlowAddedListener { flow ->
            SwingUtilities.invokeLater {
                allFlows.add(flow)
                // Keep the local mirror bounded like the store, otherwise flows
                // evicted from FlowStore would still be pinned in memory here
                val maxFlows = flowStore.maxFlows()
                while (allFlows.size > maxFlows) {
                    val evicted = allFlows.removeAt(0)
                    flowListModel.removeElement(evicted)
                }
                // Apply filter
                if (matchesSearchQuery(flow, searchQuery)) {
                    flowListModel.addElement(flow)
                }
            }
        }

        flowStore.addFlowUpdatedListener { updatedFlow ->
            SwingUtilities.invokeLater {
                // Update in allFlows
                val indexInAll = allFlows.indexOfFirst { it.flowId == updatedFlow.flowId }
                if (indexInAll >= 0) {
                    allFlows[indexInAll] = updatedFlow
                }

                // Update in filtered list if present
                for (i in 0 until flowListModel.size()) {
                    val existingFlow = flowListModel.getElementAt(i)
                    if (existingFlow.flowId == updatedFlow.flowId) {
                        flowListModel.setElementAt(updatedFlow, i)
                        break
                    }
                }
            }
        }

        flowStore.addFlowsClearedListener {
            SwingUtilities.invokeLater {
                allFlows.clear()
                flowListModel.clear()
            }
        }

        // Initialize ADB
        SwingUtilities.invokeLater {
            initializeAdb()
        }

        logger.info("✅ Inspector Panel initialized")
    }

    private fun setupLayout() {
        border = JBUI.Borders.empty(5)

        // Top panel: Mode selection + Controls + Emulator/App selectors
        val topPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5, 5, 0, 5)

            // Left side: Mode selection (Radio buttons + Debug checkbox) + Start/Stop buttons
            val modePanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)

                // Mode radio buttons
                add(recordingRadio)
                add(Box.createHorizontalStrut(5))
                add(mockkRadio)
                add(Box.createHorizontalStrut(15))

                // Debug checkbox
                add(debugCheckbox)
                add(Box.createHorizontalStrut(15))

                // Start/Stop button
                add(startStopButton)
                add(Box.createHorizontalStrut(15))
            }

            // Right side: Emulator and App selectors
            val selectorsPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)

                add(JBLabel("Device: "))
                add(Box.createHorizontalStrut(5))
                add(emulatorComboBox.apply {
                    maximumSize = Dimension(250, preferredSize.height)
                })
                add(Box.createHorizontalStrut(5))
                add(refreshDevicesButton)
                add(Box.createHorizontalStrut(15))

                add(JBLabel("App: "))
                add(Box.createHorizontalStrut(5))
                add(appComboBox.apply {
                    maximumSize = Dimension(300, preferredSize.height)
                })
                add(Box.createHorizontalStrut(5))
                add(refreshAppsButton)

                add(Box.createHorizontalGlue())
            }

            // Combine mode panel and selectors
            val combinedPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(modePanel)
                add(selectorsPanel)
            }

            add(combinedPanel, BorderLayout.CENTER)
        }

        // Center panel: Search field + Flow list
        val flowPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5)

            // Search panel
            val searchPanel = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(0, 0, 5, 0)
                add(JBLabel("🔍 "), BorderLayout.WEST)
                add(searchField, BorderLayout.CENTER)
            }

            add(searchPanel, BorderLayout.NORTH)
            add(JBScrollPane(flowList), BorderLayout.CENTER)
        }

        // Bottom panel: Clear/Export buttons + Status label
        val bottomPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5, 10)

            // Left side: Clear and Export buttons
            val actionsPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(clearButton)
                add(Box.createHorizontalStrut(5))
                add(exportButton)
                add(Box.createHorizontalStrut(15))
            }

            add(actionsPanel, BorderLayout.WEST)
            add(statusLabel, BorderLayout.CENTER)
        }

        // Main layout
        add(topPanel, BorderLayout.NORTH)
        add(flowPanel, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)
    }

    private fun initializeAdb() {
        logger.info("⚙️ Initializing ADB...")
        updateStatus("Initializing ADB...", JBColor.ORANGE)

        if (emulatorManager.initialize()) {
            logger.info("✅ ADB initialized successfully")
            updateStatus("Ready", JBColor.GREEN)

            // Register device change listener for auto-refresh.
            // ddmlib fires disconnect + connect + several CHANGE_STATE callbacks for a single
            // USB flap, so debounce instead of queueing a refresh task per callback.
            emulatorManager.addDeviceChangeListener {
                SwingUtilities.invokeLater {
                    logger.debug("Device change detected, scheduling refresh...")
                    scheduleDeviceRefresh()
                }
            }

            refreshEmulators(auto = true)
        } else {
            logger.error("❌ Failed to initialize ADB")
            updateStatus("ADB initialization failed", JBColor.RED)
        }
    }

    /**
     * Trailing-edge debounce with a maximum wait: a burst collapses into one refresh, but a
     * device that keeps flapping still gets refreshed at least every DEVICE_CHANGE_MAX_WAIT_MS.
     */
    private fun scheduleDeviceRefresh() {
        val now = System.currentTimeMillis()
        if (pendingSinceMs == 0L) pendingSinceMs = now
        if (now - pendingSinceMs >= DEVICE_CHANGE_MAX_WAIT_MS) {
            deviceChangeDebouncer.stop()
            doRefreshEmulators(auto = true)
        } else {
            deviceChangeDebouncer.restart()
        }
    }

    /** Immediate entry point: startup and the manual refresh button. */
    private fun refreshEmulators(auto: Boolean) {
        deviceChangeDebouncer.stop()
        doRefreshEmulators(auto)
    }

    private fun doRefreshEmulators(auto: Boolean) {
        if (project.isDisposed) return
        if (refreshingDevices) {
            if (!auto) pendingManualRefresh = true
            // Don't drop the event, just re-arm: the device list may have changed again.
            // pendingSinceMs is deliberately kept, so the max-wait cap still applies.
            deviceChangeDebouncer.restart()
            return
        }
        refreshingDevices = true
        pendingSinceMs = 0L
        val userInitiated = !auto || pendingManualRefresh
        pendingManualRefresh = false

        val previousSelection = selectedEmulator
        val wantedSerial = previousSelection?.serialNumber ?: lastSelectedSerial

        // Run ADB/simctl operations in background to avoid blocking EDT
        object : Task.Backgroundable(project, "Refreshing Devices...", true) {
            private var androidDevices: List<EmulatorInfo> = emptyList()
            private var bootedSimulators: List<EmulatorInfo>? = emptyList()
            private var iosDevices: List<EmulatorInfo>? = emptyList()

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                // Android (ADB) + iOS Simulators (simctl) + physical iOS devices (devicectl).
                // The iOS enumerations return null on TOOLING failure (vs a genuine empty list).
                androidDevices = emulatorManager.getConnectedDevices()
                bootedSimulators = simulatorManager.getBootedSimulators()
                iosDevices = simulatorManager.getConnectedIosDevices()
            }

            override fun onSuccess() {
                // Update UI on EDT
                val iosEnumerationFailed = bootedSimulators == null || iosDevices == null
                var devices = androidDevices +
                        (bootedSimulators ?: emptyList()) +
                        (iosDevices ?: emptyList())

                // A transient simctl/devicectl failure must NOT look like a disconnection:
                // iOS capture runs over a plain socket and doesn't depend on that tooling.
                // Keep the previously selected iOS device in the list instead of stopping.
                if (previousSelection != null &&
                    previousSelection.platform != DevicePlatform.ANDROID &&
                    iosEnumerationFailed &&
                    devices.none { it.serialNumber == previousSelection.serialNumber }
                ) {
                    logger.warn("⚠️ iOS device enumeration failed transiently; keeping ${previousSelection.displayName} selected")
                    devices = devices + previousSelection
                }

                // Rebuild the combo with events suppressed, so the transient auto-selection of
                // the first added item can't queue an app scan for the wrong device.
                isRebuildingDeviceCombo = true
                try {
                    emulatorComboBox.removeAllItems()
                    devices.forEach { device ->
                        emulatorComboBox.addItem(device)
                    }

                    val restoreIndex = wantedSerial
                        ?.let { serial -> devices.indexOfFirst { it.serialNumber == serial } }
                        ?: -1
                    when {
                        restoreIndex >= 0 -> {
                            emulatorComboBox.selectedIndex = restoreIndex
                            logger.debug("Restored device selection: ${devices[restoreIndex].displayName}")
                        }
                        lastSelectedSerial == null && devices.isNotEmpty() -> {
                            emulatorComboBox.selectedIndex = 0
                            logger.debug("Auto-selected first device")
                        }
                        // A manual refresh is the user asking "show me what's there now", so
                        // it forgets a device that never came back. Without this escape hatch,
                        // Wireless Debugging (a fresh serial on every reconnect) would leave
                        // the combo unselected forever and the advice in the status bar -
                        // "press Refresh Devices" - could never work.
                        userInitiated && devices.isNotEmpty() -> {
                            lastSelectedSerial = null
                            emulatorComboBox.selectedIndex = 0
                            logger.info("📱 $wantedSerial is gone; selected ${devices[0].displayName} instead")
                        }
                        else -> {
                            // The chosen device is still missing: undo the implicit selection
                            // DefaultComboBoxModel makes when the first item is added, so we
                            // never silently jump to a different device.
                            emulatorComboBox.selectedIndex = -1
                            logger.debug("Waiting for $lastSelectedSerial to come back; no device auto-selected")
                        }
                    }
                } finally {
                    isRebuildingDeviceCombo = false
                }

                if (previousSelection != null && devices.none { it.serialNumber == previousSelection.serialNumber }) {
                    // Previously selected device is genuinely disconnected
                    logger.warn("⚠️ Previously selected device disconnected")
                    selectedApp = null

                    // A manual refresh may already have picked a replacement above; read it
                    // back from the combo so what we commit is what the user actually sees.
                    val replacement = emulatorComboBox.selectedItem as? EmulatorInfo
                    val notice = if (replacement == null) {
                        "Device disconnected - reconnect it, then press Refresh Devices"
                    } else {
                        null
                    }

                    // Stop interceptor if running. stop() finishes asynchronously and paints
                    // its own status, so hand it the notice instead of painting it here and
                    // watching "Stopped" overwrite it a moment later.
                    if (currentMode != Mode.STOPPED) {
                        logger.warn("⚠️ Stopping interceptor due to device disconnection")
                        // Always the device that just vanished - it is the one holding the
                        // adb reverse, and by the time stop() runs the selection may already
                        // point at the replacement.
                        if (notice != null) {
                            stop(notice, JBColor.ORANGE, device = previousSelection)
                        } else {
                            stop(device = previousSelection)
                        }
                    } else if (notice != null) {
                        updateStatus(notice, JBColor.ORANGE)
                    }

                    // The app list belonged to the device that just vanished. Clearing it
                    // keeps the combo from showing a "selected" app while selectedApp is null
                    // - which is what the user would otherwise see when the reconnect's
                    // auto-scan is suppressed. After stop(), so the package filter of a live
                    // session is never touched.
                    appComboBox.removeAllItems()
                    scannedAppsSerial = null

                    if (replacement == null) {
                        selectedEmulator = null
                        updateButtonStates()
                        return
                    }

                    // Fall through to the normal commit below: returning here would leave the
                    // combo showing the replacement while selectedEmulator stayed null - every
                    // control dead, right after the user pressed Refresh Devices.
                    logger.info("📱 ${previousSelection.displayName} is gone; switching to ${replacement.displayName}")
                }

                // Apply the effective selection exactly once (queues the app scan for the
                // right device only)
                onEmulatorSelected(auto = !userInitiated)
            }

            // Runs on the EDT for every outcome (success, failure and cancellation)
            override fun onFinished() {
                refreshingDevices = false
            }
        }.queue()
    }

    /**
     * @param auto true when the selection comes from an ADB/device event rather than from the
     *   user. Event-driven selections are rate-limited so a flapping ADB can't re-trigger the
     *   app scan forever.
     */
    private fun onEmulatorSelected(auto: Boolean = false) {
        if (isRebuildingDeviceCombo) return
        selectedEmulator = emulatorComboBox.selectedItem as? EmulatorInfo
        selectedEmulator?.let { emulator ->
            logger.info("📱 Emulator selected: ${emulator.fullDescription}")
            lastSelectedSerial = emulator.serialNumber      // never overwritten with null
            when {
                !auto -> {
                    clearAutoScanBrakes(emulator.serialNumber)   // the user picked this device
                    refreshApps(auto = false)
                }
                emulator.serialNumber in autoScanOptOut -> {
                    logger.debug("Auto-scan opted out for ${emulator.serialNumber} (user cancelled)")
                    updateStatus("App scan cancelled - press Refresh Apps", JBColor.ORANGE)
                }
                // A device that ddmlib still lists but that is offline can only produce an
                // empty scan. Skipping it keeps the app combo intact and costs no cooldown.
                !isDeviceReadyForScan(emulator) -> {
                    logger.debug("Device ${emulator.serialNumber} is not online yet - skipping auto-scan")
                    updateStatus("Device offline - waiting...", JBColor.ORANGE)
                }
                allowAutoRescan(emulator.serialNumber) -> refreshApps(auto = true)
                else -> {
                    logger.warn(
                        "⚠️ Auto-scan suppressed for ${emulator.serialNumber} (unstable ADB). " +
                        "Press 'Refresh Apps' to retry."
                    )
                    updateStatus("App scan paused (unstable ADB) - press Refresh Apps", JBColor.ORANGE)
                }
            }
        }
        updateButtonStates()
    }

    /**
     * Per-serial budget plus a global cap on *automatic* scans. The global cap matters because
     * the serial is not stable - Wireless Debugging assigns a new port on every reconnect, and
     * two serials taking turns would otherwise each get a fresh budget on every flap, leaving
     * the feedback loop uncapped.
     */
    private fun allowAutoRescan(serial: String): Boolean {
        val now = System.currentTimeMillis()

        // With Wireless Debugging every reconnect brings a brand-new serial, so drop entries
        // nobody has touched in a long time or the maps grow unbounded.
        lastAutoScanEndedMs.entries.removeIf { now - it.value > AUTO_SCAN_STATE_TTL_MS }
        consecutiveAutoScans.keys.retainAll(lastAutoScanEndedMs.keys)

        val lastEnd = lastAutoScanEndedMs[serial]
        if (lastEnd != null) {
            val cooldown = cooldownFor(consecutiveAutoScans[serial] ?: 0)
            when {
                // A full quiet stretch means things calmed down: forget the backoff, otherwise
                // a laptop that suspends a few times a day would end up with auto-scan off.
                now - lastEnd > 2 * cooldown -> consecutiveAutoScans.remove(serial)
                now - lastEnd < cooldown -> return false
            }
        }

        // Rolling event cap, on top of the per-serial cooldown.
        if (now - globalScanBudget.windowStartMs > AUTO_SCAN_WINDOW_MS) {
            globalScanBudget.windowStartMs = now
            globalScanBudget.attempts = 0
        }
        globalScanBudget.attempts += 1
        return globalScanBudget.attempts <= MAX_AUTO_SCANS_PER_WINDOW_GLOBAL
    }

    /** Android devices can linger in the list while offline; only ADB can tell us. */
    private fun isDeviceReadyForScan(emulator: EmulatorInfo): Boolean =
        emulator.platform != DevicePlatform.ANDROID ||
                emulatorManager.getDevice(emulator.serialNumber)?.isOnline == true

    private fun cooldownFor(consecutiveScans: Int): Long =
        minOf(AUTO_SCAN_COOLDOWN_MS shl minOf(consecutiveScans, 4), MAX_AUTO_SCAN_COOLDOWN_MS)

    /** Clears every automatic-scan brake for [serial]: the user is asking for a scan now. */
    private fun clearAutoScanBrakes(serial: String) {
        lastAutoScanEndedMs.remove(serial)
        consecutiveAutoScans.remove(serial)
        autoScanOptOut.remove(serial)
        globalScanBudget.windowStartMs = 0L
        globalScanBudget.attempts = 0
    }

    /** Manual "Refresh Apps": clears the backoff (and any cancellation opt-out) and rescans. */
    private fun forceRefreshApps() {
        val serial = selectedEmulator?.serialNumber
        if (serial != null && serial in scanningSerials) {
            logger.warn("⚠️ A scan is already running on $serial - cancel it first (Stop scan)")
            return
        }
        serial?.let { clearAutoScanBrakes(it) }
        refreshApps(auto = false)
    }

    /** @param auto true when triggered by an ADB event rather than by the user. */
    private fun refreshApps(auto: Boolean) {
        val emulator = selectedEmulator ?: return

        // Prevent duplicate scans for the same device (atomic claim: a single-slot guard let
        // A -> B -> A queue a second concurrent scan of A)
        if (!scanningSerials.add(emulator.serialNumber)) {
            logger.debug("Already scanning apps on ${emulator.serialNumber}, skipping...")
            return
        }

        // Disable button and show loading state. Suppress the combo's action event: an empty
        // combo would otherwise read as "user deselected the app" and drop the package filter
        // of a live capture session, turning this project into a catch-all.
        refreshAppsButton.isEnabled = false
        isRebuildingAppCombo = true
        try {
            appComboBox.removeAllItems()
        } finally {
            isRebuildingAppCombo = false
        }

        // The event is suppressed, so drop the stale app explicitly when the scan targets a
        // different device: otherwise Start would launch with the previous device's package.
        if (selectedApp != null && emulator.serialNumber != scannedAppsSerial) {
            selectedApp = null
            updateButtonStates()
        }
        scannedAppsSerial = emulator.serialNumber

        // Run ADB operation in background to avoid blocking EDT
        object : Task.Backgroundable(project, "Scanning apps on ${emulator.displayName}...", true) {
            private var mockkHttpApps: List<AppInfo> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Scanning for apps with MockkHttp..."

                mockkHttpApps = when (emulator.platform) {
                    // Android: getInstalledApps already returns only apps with MockkHttp
                    DevicePlatform.ANDROID ->
                        appManager.getInstalledApps(
                            emulator.serialNumber,
                            includeSystem = false,
                            // Plain boolean probe: the indicator is a ThreadLocal of *this*
                            // thread, so ProgressManager.checkCanceled() would be a silent
                            // no-op inside the scan's Dispatchers.IO coroutines.
                            isCancelled = { indicator.isCanceled },
                            // The app the user last worked with must never lose the pull
                            // budget race against packages that merely sort first.
                            priorityPackages = setOfNotNull(lastSelectedPackage),
                            onProgress = { done, total, pkg ->
                                indicator.isIndeterminate = false
                                indicator.fraction = done.toDouble() / total
                                indicator.text2 = "$done/$total - $pkg"
                            }
                        )
                    // iOS Simulator: all user apps, MockkHttp-enabled ones flagged/sorted first
                    DevicePlatform.IOS_SIMULATOR -> {
                        indicator.checkCanceled()
                        simulatorManager.getInstalledApps(emulator)
                    }
                    // Physical iOS device: devicectl best effort, PING-announced fallback
                    DevicePlatform.IOS_DEVICE -> {
                        indicator.checkCanceled()
                        simulatorManager.getInstalledAppsPhysical(emulator)
                    }
                }
            }

            override fun onSuccess() {
                // Discard stale results: the user (or a device refresh) may have switched
                // devices while this scan was running — never mix apps across devices.
                if (selectedEmulator?.serialNumber != emulator.serialNumber) {
                    logger.debug("Discarding app scan results for ${emulator.displayName} (no longer selected)")
                    return
                }

                // Update UI on EDT
                logger.info("🔍 Found ${mockkHttpApps.size} app(s) with MockkHttp")

                // Repopulate with events suppressed and reconcile once at the end: an
                // automatic rescan must not look like the user deselecting the app.
                // Restore by package name, remembered independently of selectedApp: an empty
                // rescan nulls the latter, and falling back to index 0 afterwards would
                // repoint a live capture session at a different app behind the user's back.
                val previousPackage = selectedApp?.packageName ?: lastSelectedPackage
                var leftUnselected = false
                isRebuildingAppCombo = true
                try {
                    // Clear at apply-time too: guards against results from an earlier scan of
                    // a different device having been applied in between.
                    appComboBox.removeAllItems()
                    mockkHttpApps.forEach { app ->
                        appComboBox.addItem(app)
                    }
                    if (mockkHttpApps.isNotEmpty()) {
                        val restore = mockkHttpApps.indexOfFirst { it.packageName == previousPackage }
                        appComboBox.selectedIndex = when {
                            restore >= 0 -> restore
                            // Results can be legitimately incomplete (pull budget, timeouts),
                            // so an automatic scan must never repoint the selection at some
                            // other app - and a live session must never be repointed at all.
                            previousPackage != null && (auto || currentMode != Mode.STOPPED) -> {
                                leftUnselected = true
                                -1
                            }
                            else -> 0
                        }
                    }
                } finally {
                    isRebuildingAppCombo = false
                }

                // An empty result means "this scan found nothing", not "the user deselected".
                // Keep a live session's app so Stop and the mode controls stay usable.
                if (mockkHttpApps.isNotEmpty() || currentMode == Mode.STOPPED) {
                    onAppSelected()
                }

                // Say why the combo has entries but nothing selected - otherwise the disabled
                // Start button looks like a bug rather than an incomplete scan.
                if (leftUnselected) {
                    logger.warn("⚠️ $previousPackage was not found in this scan - it may be incomplete")
                    updateStatus("$previousPackage not found in this scan - press Refresh Apps", JBColor.ORANGE)
                }

                if (mockkHttpApps.isEmpty()) {
                    when (emulator.platform) {
                        DevicePlatform.ANDROID ->
                            logger.warn("⚠️ No apps with MockkHttp found. Make sure you've added the Gradle plugin to your app.")
                        DevicePlatform.IOS_SIMULATOR ->
                            logger.warn("⚠️ No user apps found on this simulator. Install your Flutter app first.")
                        DevicePlatform.IOS_DEVICE ->
                            logger.warn("⚠️ No apps detected. Start your Flutter app with MockkHttp.init(host: '<Mac LAN IP>') so it announces itself, then refresh.")
                    }
                }
            }

            override fun onCancel() {
                logger.warn("⚠️ App scan cancelled by user on ${emulator.displayName}")
                // Make the cancellation stick: without this the next ADB event relaunches
                // exactly the scan the user just stopped. Cleared by "Refresh Apps".
                autoScanOptOut.add(emulator.serialNumber)
                updateStatus("App scan cancelled - press Refresh Apps", JBColor.ORANGE)
            }

            override fun onThrowable(error: Throwable) {
                logger.error("Failed to scan apps: ${error.message}")
            }

            // IntelliJ does NOT call onSuccess() when a task is cancelled. onFinished() runs
            // on the EDT for every outcome: releasing the guard only in onSuccess/onThrowable
            // would leave it stuck after a cancel, making refreshApps() return early forever
            // for this serial with the button permanently disabled.
            override fun onFinished() {
                scanningSerials.remove(emulator.serialNumber)

                // Charge the cooldown when the scan ENDS, and only for scans that actually
                // ran: events that bounced off the scanningSerials claim must cost nothing.
                if (auto) {
                    val serial = emulator.serialNumber
                    lastAutoScanEndedMs[serial] = System.currentTimeMillis()
                    consecutiveAutoScans[serial] = (consecutiveAutoScans[serial] ?: 0) + 1
                }

                // Don't advertise the button as ready while another device's scan is running.
                updateButtonStates()
            }
        }
            .setCancelText("Stop scan")
            .setCancelTooltipText("Cancel the MockkHttp app scan on this device")
            .queue()
    }

    private fun onAppSelected() {
        if (isRebuildingAppCombo) return
        selectedApp = appComboBox.selectedItem as? AppInfo
        selectedApp?.packageName?.let { lastSelectedPackage = it }   // never nulled
        updateButtonStates()
        updatePackageFilterIfRunning()
    }

    private fun updateButtonStates() {
        val hasSelection = selectedEmulator != null && selectedApp != null
        val isRunning = currentMode != Mode.STOPPED

        refreshAppsButton.isEnabled = selectedEmulator != null &&
                selectedEmulator?.serialNumber !in scanningSerials
        recordingRadio.isEnabled = hasSelection
        mockkRadio.isEnabled = hasSelection
        debugCheckbox.isEnabled = hasSelection
        // A running session must always be stoppable, even if a failed rescan wiped the app
        // selection - otherwise the interceptor keeps capturing with no way to turn it off.
        startStopButton.isEnabled = hasSelection || isRunning

        // Update button appearance based on state
        if (isRunning) {
            startStopButton.text = "Stop"
            startStopButton.icon = AllIcons.Actions.Suspend
            startStopButton.toolTipText = "Stop interceptor"
        } else {
            startStopButton.text = "Start"
            startStopButton.icon = AllIcons.Actions.Execute
            startStopButton.toolTipText = "Start interceptor with selected mode"
        }
    }

    private fun getCurrentSelectedMode(): Mode {
        return when {
            recordingRadio.isSelected && !debugCheckbox.isSelected -> Mode.RECORDING
            recordingRadio.isSelected && debugCheckbox.isSelected -> Mode.DEBUG
            mockkRadio.isSelected && !debugCheckbox.isSelected -> Mode.MOCKK
            mockkRadio.isSelected && debugCheckbox.isSelected -> Mode.MOCKK_DEBUG
            else -> Mode.STOPPED
        }
    }

    private fun updateModeIfRunning() {
        if (currentMode == Mode.STOPPED) return

        val newMode = getCurrentSelectedMode()
        if (newMode != currentMode && newMode != Mode.STOPPED) {
            // Update UI state
            currentMode = newMode

            val statusText = when (newMode) {
                Mode.RECORDING -> "Recording..."
                Mode.DEBUG -> "Debug Mode (Recording + Pause)"
                Mode.MOCKK -> "Mockk Mode"
                Mode.MOCKK_DEBUG -> "Mockk Debug Mode (Mock + Pause)"
                Mode.STOPPED -> "Stopped"
            }
            val statusColor = when (newMode) {
                Mode.RECORDING -> JBColor.GREEN
                Mode.DEBUG -> JBColor(java.awt.Color.CYAN, java.awt.Color.CYAN)
                Mode.MOCKK -> JBColor.ORANGE
                Mode.MOCKK_DEBUG -> JBColor(java.awt.Color.MAGENTA, java.awt.Color.MAGENTA)
                Mode.STOPPED -> JBColor.GRAY
            }

            updateStatus(statusText, statusColor)

            // Update server mode
            val serverMode = when (newMode) {
                Mode.RECORDING -> OkHttpInterceptorServer.Mode.RECORDING
                Mode.DEBUG -> OkHttpInterceptorServer.Mode.DEBUG
                Mode.MOCKK -> OkHttpInterceptorServer.Mode.MOCKK
                Mode.MOCKK_DEBUG -> OkHttpInterceptorServer.Mode.MOCKK_DEBUG
                Mode.STOPPED -> return
            }
            okHttpInterceptorServer.setMode(serverMode)
            logger.info("🔄 Mode changed to ${newMode.name} while running")
        }
    }

    private fun updatePackageFilterIfRunning() {
        if (currentMode == Mode.STOPPED) return

        // Never degrade a live session to catch-all: a null filter makes this project capture
        // every app's flows and steal them from other open projects. A transient rescan that
        // empties the combo must keep the previous filter instead.
        val packageNameFilter = selectedApp?.packageName ?: return
        okHttpInterceptorServer.setPackageNameFilter(packageNameFilter)
    }

    private fun startInterceptor() {
        val mode = getCurrentSelectedMode()
        if (mode == Mode.STOPPED) {
            logger.error("Invalid mode selection")
            return
        }
        start(mode)
    }

    private fun stopInterceptor() {
        stop()
    }


    private fun start(mode: Mode) {
        object : Task.Backgroundable(project, "Starting ${mode.name} Mode", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    logger.info("🔌 Starting ${mode.name} Mode...")

                    // Get package name filter from selected app
                    val packageNameFilter = selectedApp?.packageName
                    if (packageNameFilter != null) {
                        logger.info("📦 Filtering flows by package: $packageNameFilter")
                    } else {
                        logger.warn("⚠️  No app selected, will receive ALL flows from all apps")
                    }

                    // Set up ADB reverse for physical ANDROID devices only.
                    // iOS Simulators share the Mac's loopback (127.0.0.1 reaches the plugin
                    // directly), and physical iOS devices connect via the Mac's LAN IP —
                    // neither needs (nor supports) port forwarding.
                    val device = selectedEmulator
                    if (device != null && device.platform == DevicePlatform.ANDROID && !device.isEmulator) {
                        logger.info("📲 Physical Android device detected, setting up ADB reverse port forwarding...")
                        if (!emulatorManager.setupAdbReverse(device.serialNumber, OkHttpInterceptorServer.SERVER_PORT, OkHttpInterceptorServer.SERVER_PORT)) {
                            throw Exception("Failed to set up ADB reverse port forwarding. Make sure the device is connected via USB with USB debugging enabled.")
                        }
                    } else if (device != null && device.platform == DevicePlatform.IOS_DEVICE) {
                        logger.info("🍏 Physical iOS device: make sure the app was started with MockkHttp.init(host: '<this Mac's LAN IP>')")
                    }

                    // Map mode to OkHttpInterceptorServer.Mode
                    val serverMode = when (mode) {
                        Mode.RECORDING -> OkHttpInterceptorServer.Mode.RECORDING
                        Mode.DEBUG -> OkHttpInterceptorServer.Mode.DEBUG
                        Mode.MOCKK -> OkHttpInterceptorServer.Mode.MOCKK
                        Mode.MOCKK_DEBUG -> OkHttpInterceptorServer.Mode.MOCKK_DEBUG
                        Mode.STOPPED -> throw Exception("Cannot start in STOPPED mode")
                    }

                    // Start OkHttpInterceptorServer with the selected mode and package filter
                    if (!okHttpInterceptorServer.start(serverMode, packageNameFilter)) {
                        throw Exception("Failed to start OkHttp Interceptor Server")
                    }

                    logger.info("✅ OkHttp Interceptor Server started on port ${OkHttpInterceptorServer.SERVER_PORT}")
                    logger.info("📱 Waiting for app connections...")
                    logger.info("   Make sure your app includes the MockkHttp Gradle plugin!")

                    // Update UI
                    SwingUtilities.invokeLater {
                        currentMode = mode

                        val statusText = when (mode) {
                            Mode.RECORDING -> "Recording..."
                            Mode.DEBUG -> "Debug Mode (Recording + Pause)"
                            Mode.MOCKK -> "Mockk Mode"
                            Mode.MOCKK_DEBUG -> "Mockk Debug Mode (Mock + Pause)"
                            Mode.STOPPED -> "Stopped"
                        }
                        val statusColor = when (mode) {
                            Mode.RECORDING -> JBColor.GREEN
                            Mode.DEBUG -> JBColor(java.awt.Color.CYAN, java.awt.Color.CYAN)
                            Mode.MOCKK -> JBColor.ORANGE
                            Mode.MOCKK_DEBUG -> JBColor(java.awt.Color.MAGENTA, java.awt.Color.MAGENTA)
                            Mode.STOPPED -> JBColor.GRAY
                        }

                        updateStatus(statusText, statusColor)
                        updateButtonStates()
                    }

                    logger.info("✅ ${mode.name} mode started")

                } catch (e: Exception) {
                    logger.error("Failed to start ${mode.name} mode", e)
                    okHttpInterceptorServer.stop()

                    SwingUtilities.invokeLater {
                        currentMode = Mode.STOPPED
                        updateStatus("Error: ${e.message}", JBColor.RED)
                        updateButtonStates()

                        JOptionPane.showMessageDialog(
                            this@InspectorPanel,
                            "Failed to start: ${e.message}",
                            "Error",
                            JOptionPane.ERROR_MESSAGE
                        )
                    }
                }
            }
        }.queue()
    }

    /**
     * @param finalStatus overrides the "Stopped" status text. stop() finishes asynchronously,
     *   so a caller that paints its own message right after would be overwritten by this one.
     */
    private fun stop(
        finalStatus: String? = null,
        finalStatusColor: JBColor = JBColor.GRAY,
        device: EmulatorInfo? = selectedEmulator
    ) {
        object : Task.Backgroundable(project, "Stopping Interceptor Server", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    okHttpInterceptorServer.stop()
                    logger.info("🔌 Stopped OkHttp Interceptor Server")

                    // Clean up ADB reverse for physical ANDROID devices only. The device is
                    // captured on the EDT by the caller: re-reading selectedEmulator here would
                    // race with a device switch and tear down the *new* device's reverse.
                    if (device != null && device.platform == DevicePlatform.ANDROID && !device.isEmulator) {
                        emulatorManager.removeAdbReverse(device.serialNumber, OkHttpInterceptorServer.SERVER_PORT)
                    }

                    SwingUtilities.invokeLater {
                        currentMode = Mode.STOPPED
                        updateStatus(finalStatus ?: "Stopped", finalStatusColor)
                        updateButtonStates()
                    }

                    logger.info("✅ Stopped")

                } catch (e: Exception) {
                    logger.error("Error stopping interceptor", e)
                }
            }
        }.queue()
    }

    private fun clearFlows() {
        flowStore.clearAllFlows()
        logger.info("🗑️ Cleared all flows")
    }

    private fun exportFlows() {
        logger.info("📤 Export flows requested")

        val flows = flowStore.getAllFlows()
        if (flows.isEmpty()) {
            JOptionPane.showMessageDialog(
                this,
                "No flows to export. Start recording first.",
                "No Flows",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        // Show file chooser
        val fileChooser = JFileChooser()
        fileChooser.dialogTitle = "Export Flows to JSON"
        fileChooser.selectedFile = File("mockk-flows-${System.currentTimeMillis()}.json")
        fileChooser.fileFilter = object : javax.swing.filechooser.FileFilter() {
            override fun accept(f: File?): Boolean {
                return f?.isDirectory == true || f?.name?.endsWith(".json") == true
            }
            override fun getDescription(): String = "JSON files (*.json)"
        }

        val result = fileChooser.showSaveDialog(this)
        if (result == JFileChooser.APPROVE_OPTION) {
            val file = fileChooser.selectedFile
            try {
                // Convert flows to JSON
                val gson = com.google.gson.GsonBuilder()
                    .setPrettyPrinting()
                    .create()
                val json = gson.toJson(flows)

                // Write to file
                file.writeText(json)

                logger.info("✅ Exported ${flows.size} flows to ${file.absolutePath}")
                JOptionPane.showMessageDialog(
                    this,
                    "Successfully exported ${flows.size} flows to:\n${file.absolutePath}",
                    "Export Successful",
                    JOptionPane.INFORMATION_MESSAGE
                )
            } catch (e: Exception) {
                logger.error("Failed to export flows", e)
                JOptionPane.showMessageDialog(
                    this,
                    "Failed to export flows: ${e.message}",
                    "Export Failed",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
    }

    private fun updateStatus(message: String, color: JBColor) {
        SwingUtilities.invokeLater {
            statusLabel.text = message
            statusLabel.foreground = color
        }
    }

    /**
     * Show flow details dialog.
     */
    private fun showFlowDetails(flow: HttpFlowData) {
        val dialog = FlowDetailsDialog(project, flow)
        dialog.show()
    }

    private fun showContextMenu(e: java.awt.event.MouseEvent) {
        val index = flowList.locationToIndex(e.point)
        if (index >= 0) {
            // If clicked item is not selected, select only that item
            if (!flowList.isSelectedIndex(index)) {
                flowList.selectedIndex = index
            }

            val selectedFlows = flowList.selectedValuesList
            val actionGroup = DefaultActionGroup()

            if (selectedFlows.size == 1) {
                // Single selection - show standard options
                val selectedFlow = selectedFlows[0]

                // "Create Mock Rule from Response" menu item
                if (selectedFlow.response != null) {
                    actionGroup.add(object : AnAction("Create Mock Rule from Response", null, AllIcons.Actions.MenuSaveall) {
                        override fun actionPerformed(e: AnActionEvent) {
                            createMockFromFlow(selectedFlow)
                        }
                    })
                }

                // "View Details" menu item
                actionGroup.add(object : AnAction("View Details", null, AllIcons.Actions.Preview) {
                    override fun actionPerformed(e: AnActionEvent) {
                        showFlowDetails(selectedFlow)
                    }
                })

            } else if (selectedFlows.size > 1) {
                // Multiple selection - show batch create option
                val flowsWithResponse = selectedFlows.filter { it.response != null }

                if (flowsWithResponse.isNotEmpty()) {
                    actionGroup.add(object : AnAction(
                        "Create ${flowsWithResponse.size} Mock Rules from Selection",
                        null,
                        AllIcons.Actions.MenuSaveall
                    ) {
                        override fun actionPerformed(e: AnActionEvent) {
                            createMocksFromFlows(flowsWithResponse)
                        }
                    })
                }
            }

            // Show popup if it has items
            if (actionGroup.childrenCount > 0) {
                val popup = ActionManager.getInstance().createActionPopupMenu("InspectorPanel.ContextMenu", actionGroup)
                popup.component.show(e.component, e.x, e.y)
            }
        }
    }

    /**
     * Warn before creating mocks from flows whose stored body was truncated by the
     * retention cache — the mock would serve an incomplete body to the app.
     * Returns true to proceed.
     */
    private fun confirmTruncatedBodies(flows: List<HttpFlowData>): Boolean {
        val truncatedCount = flows.count { FlowStore.isBodyTruncated(it.response?.content) }
        if (truncatedCount == 0) return true

        val result = JOptionPane.showConfirmDialog(
            this,
            "$truncatedCount of ${flows.size} selected flow(s) have a body TRUNCATED by the cache\n" +
                    "(Settings → Cache → Max stored body size). A mock created from them would\n" +
                    "serve an incomplete body to your app.\n\n" +
                    "Tip: raise the limit and re-capture the call to mock the full body.\n\n" +
                    "Create the mock(s) anyway?",
            "Truncated Body",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        return result == JOptionPane.YES_OPTION
    }

    private fun createMockFromFlow(flow: HttpFlowData) {
        if (!confirmTruncatedBodies(listOf(flow))) return

        // Use selected app's package name to filter collections
        val packageName = selectedApp?.packageName
        val dialog = CreateMockDialog(
            project = project,
            initialFlow = flow,
            targetPackageName = packageName
        )
        if (dialog.showAndGet()) {
            logger.info("Mock rule created from flow")
        }
    }

    private fun createMocksFromFlows(flows: List<HttpFlowData>) {
        if (!confirmTruncatedBodies(flows)) return

        // Use selected app's package name to filter collections
        val packageName = selectedApp?.packageName
        val dialog = BatchCreateMockDialog(
            project = project,
            flows = flows,
            targetPackageName = packageName
        )
        if (dialog.showAndGet()) {
            logger.info("${flows.size} mock rules created from flows")
        }
    }

    private fun filterFlows() {
        searchQuery = searchField.text.trim()

        SwingUtilities.invokeLater {
            flowListModel.clear()

            // Add flows that match the search query
            for (flow in allFlows) {
                if (matchesSearchQuery(flow, searchQuery)) {
                    flowListModel.addElement(flow)
                }
            }
        }
    }

    private fun matchesSearchQuery(flow: HttpFlowData, query: String): Boolean {
        if (query.isEmpty()) return true

        val lowerQuery = query.lowercase()
        val flowText = buildString {
            append(flow.request.method)
            append(" ")
            append(flow.request.url)
            append(" ")
            if (flow.mockApplied && flow.mockRuleName != null) {
                append(flow.mockRuleName)
                append(" ")
            }
            if (flow.response != null) {
                append(flow.response.statusCode.toString())
            }
        }.lowercase()

        return flowText.contains(lowerQuery)
    }

    /**
     * Custom cell renderer for flow list with search highlighting.
     */
    private inner class FlowListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): java.awt.Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)

            if (value is HttpFlowData) {
                val statusIcon = when {
                    value.response == null -> "⏳"
                    value.response.statusCode in 200..299 -> "✅"
                    value.response.statusCode in 400..499 -> "⚠️"
                    value.response.statusCode >= 500 -> "❌"
                    else -> "●"
                }

                // Add badge based on type
                val badge = when {
                    value.modified -> "[DEBUG: Modified] "
                    value.mockApplied -> "[MOCK: ${value.mockRuleName}] "
                    else -> ""
                }

                // Build text with highlighting if search is active
                val baseText = "$statusIcon $badge${value.request.method} ${value.request.url}"

                text = if (searchQuery.isNotEmpty()) {
                    // Use HTML to highlight matching text
                    val highlightedText = highlightText(baseText, searchQuery)
                    "<html>$highlightedText</html>"
                } else {
                    baseText
                }

                // Change foreground color based on type
                if (!isSelected) {
                    foreground = when {
                        value.modified -> JBColor(java.awt.Color.CYAN, java.awt.Color.CYAN)
                        value.mockApplied -> JBColor.ORANGE
                        else -> list?.foreground ?: JBColor.foreground()
                    }
                }
            }

            return this
        }

        private fun highlightText(text: String, query: String): String {
            if (query.isEmpty()) return text

            // Escape HTML special characters
            var escaped = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")

            // Find and highlight all occurrences (case-insensitive)
            val pattern = Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
            escaped = pattern.replace(escaped) { matchResult ->
                "<span style='background-color: #FFFF00; color: #000000;'>${matchResult.value}</span>"
            }

            return escaped
        }
    }
}
