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
    @Volatile
    private var refreshingDeviceSerial: String? = null

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
            addActionListener { onEmulatorSelected() }
        }

        refreshDevicesButton = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = "Refresh Devices (Android + iOS)"
            addActionListener { refreshEmulators() }
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
            addActionListener { refreshApps() }
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

            // Register device change listener for auto-refresh
            emulatorManager.addDeviceChangeListener {
                SwingUtilities.invokeLater {
                    logger.debug("Device change detected, refreshing emulators...")
                    refreshEmulators()
                }
            }

            refreshEmulators()
        } else {
            logger.error("❌ Failed to initialize ADB")
            updateStatus("ADB initialization failed", JBColor.RED)
        }
    }

    private fun refreshEmulators() {
        val previousSelection = selectedEmulator

        // Run ADB/simctl operations in background to avoid blocking EDT
        object : Task.Backgroundable(project, "Refreshing Devices...", false) {
            private var devices: List<EmulatorInfo> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                // Android (ADB) + iOS Simulators (simctl) + physical iOS devices (devicectl)
                devices = emulatorManager.getConnectedDevices() +
                        simulatorManager.getBootedSimulators() +
                        simulatorManager.getConnectedIosDevices()
            }

            override fun onSuccess() {
                // Update UI on EDT
                emulatorComboBox.removeAllItems()
                devices.forEach { device ->
                    emulatorComboBox.addItem(device)
                }

                // Try to restore previous selection if the device is still connected
                if (previousSelection != null && devices.any { it.serialNumber == previousSelection.serialNumber }) {
                    val index = devices.indexOfFirst { it.serialNumber == previousSelection.serialNumber }
                    if (index >= 0) {
                        emulatorComboBox.selectedIndex = index
                        logger.debug("Restored device selection: ${previousSelection.displayName}")
                    }
                } else if (devices.isNotEmpty() && selectedEmulator == null) {
                    // Only auto-select first if nothing was selected before
                    emulatorComboBox.selectedIndex = 0
                    logger.debug("Auto-selected first device")
                } else if (previousSelection != null && devices.none { it.serialNumber == previousSelection.serialNumber }) {
                    // Previously selected device is now disconnected
                    logger.warn("⚠️ Previously selected device disconnected")
                    selectedEmulator = null
                    selectedApp = null

                    // Stop interceptor if running
                    if (currentMode != Mode.STOPPED) {
                        logger.warn("⚠️ Stopping interceptor due to device disconnection")
                        stop()
                    }
                }
            }
        }.queue()
    }

    private fun onEmulatorSelected() {
        selectedEmulator = emulatorComboBox.selectedItem as? EmulatorInfo
        selectedEmulator?.let { emulator ->
            logger.info("📱 Emulator selected: ${emulator.fullDescription}")
            refreshApps()
        }
        updateButtonStates()
    }

    private fun refreshApps() {
        val emulator = selectedEmulator ?: return

        // Prevent duplicate scans for the same device
        if (refreshingDeviceSerial == emulator.serialNumber) {
            logger.debug("Already scanning apps on ${emulator.serialNumber}, skipping...")
            return
        }
        refreshingDeviceSerial = emulator.serialNumber

        // Disable button and show loading state
        refreshAppsButton.isEnabled = false
        appComboBox.removeAllItems()

        // Run ADB operation in background to avoid blocking EDT
        object : Task.Backgroundable(project, "Scanning Apps...", false) {
            private var mockkHttpApps: List<AppInfo> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Scanning for apps with MockkHttp..."

                mockkHttpApps = when (emulator.platform) {
                    // Android: getInstalledApps already returns only apps with MockkHttp
                    DevicePlatform.ANDROID ->
                        appManager.getInstalledApps(emulator.serialNumber, includeSystem = false)
                    // iOS Simulator: all user apps, MockkHttp-enabled ones flagged/sorted first
                    DevicePlatform.IOS_SIMULATOR ->
                        simulatorManager.getInstalledApps(emulator)
                    // Physical iOS device: devicectl best effort, PING-announced fallback
                    DevicePlatform.IOS_DEVICE ->
                        simulatorManager.getInstalledAppsPhysical(emulator)
                }
            }

            override fun onSuccess() {
                refreshingDeviceSerial = null

                // Update UI on EDT
                logger.info("🔍 Found ${mockkHttpApps.size} app(s) with MockkHttp")

                if (mockkHttpApps.isEmpty()) {
                    when (emulator.platform) {
                        DevicePlatform.ANDROID ->
                            logger.warn("⚠️ No apps with MockkHttp found. Make sure you've added the Gradle plugin to your app.")
                        DevicePlatform.IOS_SIMULATOR ->
                            logger.warn("⚠️ No user apps found on this simulator. Install your Flutter app first.")
                        DevicePlatform.IOS_DEVICE ->
                            logger.warn("⚠️ No apps detected. Start your Flutter app with MockkHttp.init(host: '<Mac LAN IP>') so it announces itself, then refresh.")
                    }
                } else {
                    mockkHttpApps.forEach { app ->
                        appComboBox.addItem(app)
                    }
                    appComboBox.selectedIndex = 0
                }

                // Re-enable button
                refreshAppsButton.isEnabled = selectedEmulator != null
            }

            override fun onThrowable(error: Throwable) {
                refreshingDeviceSerial = null
                logger.error("Failed to scan apps: ${error.message}")
                refreshAppsButton.isEnabled = selectedEmulator != null
            }
        }.queue()
    }

    private fun onAppSelected() {
        selectedApp = appComboBox.selectedItem as? AppInfo
        updateButtonStates()
        updatePackageFilterIfRunning()
    }

    private fun updateButtonStates() {
        val hasSelection = selectedEmulator != null && selectedApp != null
        val isRunning = currentMode != Mode.STOPPED

        refreshAppsButton.isEnabled = selectedEmulator != null
        recordingRadio.isEnabled = hasSelection
        mockkRadio.isEnabled = hasSelection
        debugCheckbox.isEnabled = hasSelection
        startStopButton.isEnabled = hasSelection

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

        val packageNameFilter = selectedApp?.packageName
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

    private fun stop() {
        object : Task.Backgroundable(project, "Stopping Interceptor Server", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    okHttpInterceptorServer.stop()
                    logger.info("🔌 Stopped OkHttp Interceptor Server")

                    // Clean up ADB reverse for physical ANDROID devices only
                    val device = selectedEmulator
                    if (device != null && device.platform == DevicePlatform.ANDROID && !device.isEmulator) {
                        emulatorManager.removeAdbReverse(device.serialNumber, OkHttpInterceptorServer.SERVER_PORT)
                    }

                    SwingUtilities.invokeLater {
                        currentMode = Mode.STOPPED
                        updateStatus("Stopped", JBColor.GRAY)
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

    private fun createMockFromFlow(flow: HttpFlowData) {
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
