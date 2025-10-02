package com.sergiy.dev.mockkhttp.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.sergiy.dev.mockkhttp.adb.*
import com.sergiy.dev.mockkhttp.cert.CertificateManager
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import com.sergiy.dev.mockkhttp.model.HttpFlowData
import com.sergiy.dev.mockkhttp.proxy.MitmproxyClient
import com.sergiy.dev.mockkhttp.proxy.MitmproxyController
import com.sergiy.dev.mockkhttp.proxy.PluginHttpServer
import com.sergiy.dev.mockkhttp.proxy.ProxyFirewallManager
import com.sergiy.dev.mockkhttp.store.FlowStore
import com.sergiy.dev.mockkhttp.store.MockkRulesStore
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.io.File
import javax.swing.*

/**
 * Inspector panel with compact vertical controls and flow list.
 */
class InspectorPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val logger = MockkHttpLogger.getInstance(project)
    private val emulatorManager = EmulatorManager.getInstance(project)
    private val appManager = AppManager.getInstance(project)
    private val certificateManager = CertificateManager.getInstance(project)
    private val mitmproxyController = MitmproxyController.getInstance(project)
    private val pluginHttpServer = PluginHttpServer.getInstance(project)
    private val proxyFirewallManager = ProxyFirewallManager.getInstance(project)
    private val flowStore = FlowStore.getInstance(project)
    private val mockkRulesStore = MockkRulesStore.getInstance(project)

    // UI Components
    private val emulatorComboBox: ComboBox<EmulatorInfo>
    private val appComboBox: ComboBox<AppInfo>
    private val refreshAppsButton: JButton
    private val installCertButton: JButton
    private val recordingButton: JToggleButton
    private val debugButton: JToggleButton
    private val mockkButton: JToggleButton
    private val clearButton: JButton
    private val exportButton: JButton
    private val statusLabel: JLabel
    private val flowListModel: DefaultListModel<HttpFlowData>
    private val flowList: JBList<HttpFlowData>

    // State
    private var selectedEmulator: EmulatorInfo? = null
    private var selectedApp: AppInfo? = null
    private var currentMode: Mode = Mode.STOPPED
    private var mitmproxyClient: MitmproxyClient? = null

    enum class Mode {
        STOPPED,
        RECORDING,
        DEBUG,
        MOCKK
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
                        text = "${value.displayName} (API ${value.apiLevel})"
                    }
                    return this
                }
            }
            addActionListener { onEmulatorSelected() }
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

        // Create compact vertical buttons (Android Studio style - flat, no border)
        installCertButton = JButton(AllIcons.Actions.Install).apply {
            toolTipText = "Install Certificate"
            isEnabled = false
            isBorderPainted = false
            isFocusPainted = false
            isContentAreaFilled = false
            addActionListener { installCertificate() }
        }

        recordingButton = JToggleButton(AllIcons.Debugger.Db_set_breakpoint).apply {
            toolTipText = "Start/Stop Recording Mode"
            isEnabled = false
            isBorderPainted = false
            isFocusPainted = false
            isContentAreaFilled = false
            addActionListener { toggleRecording() }
            addItemListener { _ ->
                // Visual feedback when selected using background color only
                isContentAreaFilled = isSelected
                isBorderPainted = false  // Always keep border off
                background = if (isSelected) {
                    JBColor.namedColor("ActionButton.pressedBackground", JBColor(0x5394EC, 0x5C6164))
                } else {
                    null
                }
            }
        }

        debugButton = JToggleButton(AllIcons.Actions.StartDebugger).apply {
            toolTipText = "Start/Stop Debug Mode"
            isEnabled = false
            isBorderPainted = false
            isFocusPainted = false
            isContentAreaFilled = false
            addActionListener { toggleDebug() }
            addItemListener { _ ->
                // Visual feedback when selected using background color only
                isContentAreaFilled = isSelected
                isBorderPainted = false  // Always keep border off
                background = if (isSelected) {
                    JBColor.namedColor("ActionButton.pressedBackground", JBColor(0x5394EC, 0x5C6164))
                } else {
                    null
                }
            }
        }

        mockkButton = JToggleButton(AllIcons.Nodes.DataSchema).apply {
            toolTipText = "Start/Stop Mockk Mode (Auto-apply mock rules)"
            isEnabled = false
            isBorderPainted = false
            isFocusPainted = false
            isContentAreaFilled = false
            addActionListener { toggleMockk() }
            addItemListener { _ ->
                // Visual feedback when selected using background color only
                isContentAreaFilled = isSelected
                isBorderPainted = false  // Always keep border off
                background = if (isSelected) {
                    JBColor.namedColor("ActionButton.pressedBackground", JBColor(0x5394EC, 0x5C6164))
                } else {
                    null
                }
            }
        }

        clearButton = JButton(AllIcons.Actions.GC).apply {
            toolTipText = "Clear All Flows"
            isBorderPainted = false
            isFocusPainted = false
            isContentAreaFilled = false
            addActionListener { clearFlows() }
        }

        exportButton = JButton(AllIcons.ToolbarDecorator.Export).apply {
            toolTipText = "Export Flows"
            isBorderPainted = false
            isFocusPainted = false
            isContentAreaFilled = false
            addActionListener { exportFlows() }
        }

        statusLabel = JLabel("Ready").apply {
            foreground = JBColor.GRAY
        }

        // Flow list
        flowListModel = DefaultListModel()
        flowList = JBList(flowListModel).apply {
            cellRenderer = FlowListCellRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION

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

        // Listen to flow changes
        flowStore.addFlowAddedListener { flow ->
            SwingUtilities.invokeLater {
                flowListModel.addElement(flow)
            }
        }

        flowStore.addFlowsClearedListener {
            SwingUtilities.invokeLater {
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

        // Top panel: Horizontal selectors
        val topPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5, 5, 0, 5)

            val selectorsPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)

                add(JBLabel("Emulator: "))
                add(Box.createHorizontalStrut(5))
                add(emulatorComboBox.apply {
                    maximumSize = Dimension(250, preferredSize.height)
                })
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

            add(selectorsPanel, BorderLayout.CENTER)
        }

        // Left panel: Compact vertical buttons (fixed width, non-resizable like logcat)
        val buttonsPanel = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(2)
            preferredSize = Dimension(40, 0)
            minimumSize = Dimension(40, 0)
            maximumSize = Dimension(40, Int.MAX_VALUE)

            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = GridBagConstraints.RELATIVE
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                insets = JBUI.insets(2)
            }

            add(installCertButton, gbc)
            add(recordingButton, gbc)
            add(debugButton, gbc)
            add(mockkButton, gbc)

            // Separator
            gbc.insets = JBUI.insets(10, 2, 5, 2)
            add(JSeparator(), gbc)
            gbc.insets = JBUI.insets(2)

            add(clearButton, gbc)
            add(exportButton, gbc)

            // Spacer
            gbc.weighty = 1.0
            add(Box.createVerticalGlue(), gbc)
        }

        // Right panel: Flow list
        val flowPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5)
            add(JBScrollPane(flowList), BorderLayout.CENTER)
        }

        // Center panel with buttons and flows (no split pane, fixed layout)
        val centerPanel = JPanel(BorderLayout()).apply {
            add(buttonsPanel, BorderLayout.WEST)
            add(flowPanel, BorderLayout.CENTER)
        }

        // Bottom panel: Status label
        val bottomPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5, 10)
            add(statusLabel, BorderLayout.WEST)
        }

        // Main layout
        add(topPanel, BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)
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
        val emulators = emulatorManager.getConnectedEmulators()
        val previousSelection = selectedEmulator

        emulatorComboBox.removeAllItems()
        emulators.forEach { emulator ->
            emulatorComboBox.addItem(emulator)
        }

        // Try to restore previous selection if the emulator is still connected
        if (previousSelection != null && emulators.any { it.serialNumber == previousSelection.serialNumber }) {
            val index = emulators.indexOfFirst { it.serialNumber == previousSelection.serialNumber }
            if (index >= 0) {
                emulatorComboBox.selectedIndex = index
                logger.debug("Restored emulator selection: ${previousSelection.displayName}")
            }
        } else if (emulators.isNotEmpty() && selectedEmulator == null) {
            // Only auto-select first if nothing was selected before
            emulatorComboBox.selectedIndex = 0
            logger.debug("Auto-selected first emulator")
        }
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
        val apps = appManager.getInstalledApps(emulator.serialNumber, includeSystem = false)

        appComboBox.removeAllItems()
        apps.forEach { app ->
            appComboBox.addItem(app)
        }

        if (apps.isNotEmpty()) {
            appComboBox.selectedIndex = 0
        }
    }

    private fun onAppSelected() {
        selectedApp = appComboBox.selectedItem as? AppInfo
        updateButtonStates()
    }

    private fun updateButtonStates() {
        val hasSelection = selectedEmulator != null && selectedApp != null
        refreshAppsButton.isEnabled = selectedEmulator != null
        installCertButton.isEnabled = selectedEmulator != null && currentMode == Mode.STOPPED
        recordingButton.isEnabled = hasSelection
        debugButton.isEnabled = hasSelection
        mockkButton.isEnabled = hasSelection
    }

    private fun installCertificate() {
        val emulator = selectedEmulator ?: return

        object : Task.Backgroundable(project, "Installing certificate", false) {
            var result: CertificateManager.CertInstallResult? = null

            override fun run(indicator: ProgressIndicator) {
                result = certificateManager.installUserCertificate(emulator.serialNumber)
            }

            override fun onSuccess() {
                when (result) {
                    CertificateManager.CertInstallResult.INSTALLED_AUTOMATICALLY -> {
                        JOptionPane.showMessageDialog(
                            this@InspectorPanel,
                            "Certificate installed successfully!",
                            "Success",
                            JOptionPane.INFORMATION_MESSAGE
                        )
                    }
                    CertificateManager.CertInstallResult.REQUIRES_MANUAL_INSTALL -> {
                        JOptionPane.showMessageDialog(
                            this@InspectorPanel,
                            "Certificate copied to Downloads.\nPlease install manually:\n" +
                                    "Settings → Security → Install Certificate",
                            "Manual Installation Required",
                            JOptionPane.WARNING_MESSAGE
                        )
                    }
                    CertificateManager.CertInstallResult.FAILED -> {
                        JOptionPane.showMessageDialog(
                            this@InspectorPanel,
                            "Failed to install certificate",
                            "Error",
                            JOptionPane.ERROR_MESSAGE
                        )
                    }
                    null -> {}
                }
            }
        }.queue()
    }

    private fun toggleRecording() {
        when (currentMode) {
            Mode.STOPPED -> startProxy(Mode.RECORDING)
            Mode.RECORDING -> stopProxy()
            Mode.DEBUG -> switchToRecording()
            Mode.MOCKK -> switchToRecording()
        }
    }

    private fun toggleDebug() {
        when (currentMode) {
            Mode.STOPPED -> startProxy(Mode.DEBUG)
            Mode.DEBUG -> stopProxy()
            Mode.RECORDING -> switchToDebug()
            Mode.MOCKK -> switchToDebug()
        }
    }

    private fun toggleMockk() {
        when (currentMode) {
            Mode.STOPPED -> startProxy(Mode.MOCKK)
            Mode.MOCKK -> stopProxy()
            Mode.RECORDING -> switchToMockk()
            Mode.DEBUG -> switchToMockk()
        }
    }

    private fun startProxy(mode: Mode) {
        val emulator = selectedEmulator ?: return
        val app = selectedApp ?: return

        object : Task.Backgroundable(project, "Starting ${mode.name.lowercase()} mode", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    // Check mitmproxy
                    if (!certificateManager.isMitmproxyInstalled()) {
                        SwingUtilities.invokeLater {
                            JOptionPane.showMessageDialog(
                                this@InspectorPanel,
                                "mitmproxy is not installed.\nPlease install: brew install mitmproxy",
                                "Error",
                                JOptionPane.ERROR_MESSAGE
                            )
                        }
                        return
                    }

                    // Generate certificate
                    if (!certificateManager.generateCertificateIfNeeded()) {
                        throw Exception("Failed to generate certificate")
                    }

                    // Start plugin HTTP server
                    if (!pluginHttpServer.start()) {
                        throw Exception("Failed to start plugin HTTP server")
                    }

                    // Register flow callback - ALWAYS intercept, decide what to do based on mode
                    pluginHttpServer.registerFlowCallback("proxy") { flow ->
                        handleIncomingFlow(flow)
                    }

                    // Start mitmproxy with requested mode
                    val addonPath = getAddonScriptPath() ?: throw Exception("Addon script not found")
                    val mitmMode = when (mode) {
                        Mode.RECORDING -> MitmproxyController.Mode.RECORDING
                        Mode.DEBUG -> MitmproxyController.Mode.DEBUG
                        Mode.MOCKK -> MitmproxyController.Mode.MOCKK
                        Mode.STOPPED -> throw Exception("Cannot start in STOPPED mode")
                    }
                    val config = MitmproxyController.MitmproxyConfig(
                        mode = mitmMode,
                        addonScriptPath = addonPath
                    )

                    if (!mitmproxyController.start(config)) {
                        throw Exception("Failed to start mitmproxy")
                    }

                    // Configure iptables firewall for app-only filtering
                    indicator.text = "Configuring firewall rules..."
                    logger.info("Configuring iptables firewall for UID ${app.uid}...")

                    if (app.uid == null) {
                        throw Exception("App UID not available - cannot configure firewall")
                    }

                    if (!proxyFirewallManager.setupAppFirewall(emulator.serialNumber, app.uid)) {
                        throw Exception("Failed to configure firewall rules")
                    }

                    // Create client for resume commands
                    mitmproxyClient = MitmproxyClient(logger)

                    // Filtering is now handled in mitmproxy addon via filter_uid parameter
                    if (selectedApp?.uid != null) {
                        logger.info("🔍 Filtering enabled for ${selectedApp!!.packageName} (UID: ${selectedApp!!.uid})")
                    } else {
                        logger.info("ℹ️ No UID available, showing all flows")
                    }

                    // Update UI
                    SwingUtilities.invokeLater {
                        currentMode = mode
                        recordingButton.isSelected = (mode == Mode.RECORDING)
                        debugButton.isSelected = (mode == Mode.DEBUG)
                        mockkButton.isSelected = (mode == Mode.MOCKK)
                        val statusText = when (mode) {
                            Mode.RECORDING -> "Recording..."
                            Mode.DEBUG -> "Debug Mode Active"
                            Mode.MOCKK -> "Mockk Mode Active"
                            Mode.STOPPED -> "Stopped"
                        }
                        val statusColor = when (mode) {
                            Mode.RECORDING -> JBColor.GREEN
                            Mode.DEBUG -> JBColor(java.awt.Color.CYAN, java.awt.Color.CYAN)
                            Mode.MOCKK -> JBColor.ORANGE
                            Mode.STOPPED -> JBColor.GRAY
                        }
                        updateStatus(statusText, statusColor)
                        updateButtonStates()
                    }

                    logger.info("✅ ${mode.name} mode started")

                } catch (e: Exception) {
                    logger.error("Failed to start proxy", e)
                    mitmproxyController.stop()
                    pluginHttpServer.stop()

                    SwingUtilities.invokeLater {
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

    private fun handleIncomingFlow(flow: HttpFlowData) {
        // For now, disable filtering - show all flows
        // TODO: Implement proper filtering using process-based approach
        val app = selectedApp
        if (app != null) {
            logger.debug("Flow received: ${flow.request.method} ${flow.request.host} (filtering disabled)")
        }

        // Always show flows for now
        flowStore.addFlow(flow)

        when (currentMode) {
            Mode.RECORDING -> {
                // Recording mode: just capture flows, don't modify them
                // Flows are not intercepted in recording mode, so they complete immediately
                logger.debug("📝 Flow recorded: ${flow.request.method} ${flow.request.url}")
            }
            Mode.DEBUG -> {
                // Show dialog if paused
                if (flow.paused) {
                    SwingUtilities.invokeLater {
                        showDebugDialog(flow)
                    }
                } else {
                    logger.warn("⚠️ Received non-paused flow in DEBUG mode: ${flow.flowId}")
                }
            }
            Mode.MOCKK -> {
                // Mockk mode: mocks are applied by mitmproxy Python addon
                // Just log that a flow was received (with or without mock)
                if (flow.mockApplied) {
                    logger.info("📋 Received mocked flow: ${flow.mockRuleName}")
                } else {
                    logger.debug("📝 Flow recorded (no mock applied): ${flow.request.method} ${flow.request.url}")
                }
            }
            Mode.STOPPED -> {
                // Should not happen, but just in case
                logger.warn("⚠️ Received flow while in STOPPED mode: ${flow.flowId}")
            }
        }
    }

    private fun showDebugDialog(flow: HttpFlowData) {
        val dialog = DebugInterceptDialog(project, flow)
        if (dialog.showAndGet()) {
            val modifiedResponse = dialog.getModifiedResponse()
            val saveAsMock = dialog.shouldSaveAsMock()

            // Save as mock rule if requested
            if (saveAsMock && modifiedResponse != null) {
                val structuredUrl = com.sergiy.dev.mockkhttp.model.StructuredUrl.fromUrl(flow.request.url)
                mockkRulesStore.addRule(
                    name = dialog.getMockRuleName(),
                    method = flow.request.method,
                    structuredUrl = structuredUrl,
                    mockResponse = modifiedResponse
                )
                logger.info("💾 Saved as mock rule: ${dialog.getMockRuleName()}")
            }

            // Resume flow
            mitmproxyClient?.resumeFlow(flow.flowId, modifiedResponse)
            logger.info("✅ Flow resumed")
        } else {
            // User cancelled, resume with original
            mitmproxyClient?.resumeFlow(flow.flowId, null)
            logger.info("▶️ Flow resumed with original response")
        }
    }

    private fun switchToRecording() {
        logger.info("Switching to Recording Mode...")

        object : Task.Backgroundable(project, "Switching to Recording mode", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    // Stop current mitmproxy (keep HTTP server running)
                    mitmproxyController.stop()
                    Thread.sleep(500) // Wait for mitmproxy to stop

                    // Restart mitmproxy in RECORDING mode
                    restartMitmproxyWithMode(Mode.RECORDING)
                } catch (e: Exception) {
                    logger.error("Failed to switch to Recording mode", e)
                    SwingUtilities.invokeLater {
                        updateStatus("Error switching to Recording mode", JBColor.RED)
                    }
                }
            }
        }.queue()
    }

    private fun switchToDebug() {
        logger.info("Switching to Debug Mode...")

        object : Task.Backgroundable(project, "Switching to Debug mode", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    // Stop current mitmproxy (keep HTTP server running)
                    mitmproxyController.stop()
                    Thread.sleep(500) // Wait for mitmproxy to stop

                    // Restart mitmproxy in DEBUG mode
                    restartMitmproxyWithMode(Mode.DEBUG)
                } catch (e: Exception) {
                    logger.error("Failed to switch to Debug mode", e)
                    SwingUtilities.invokeLater {
                        updateStatus("Error switching to Debug mode", JBColor.RED)
                    }
                }
            }
        }.queue()
    }

    private fun switchToMockk() {
        logger.info("Switching to Mockk Mode...")

        object : Task.Backgroundable(project, "Switching to Mockk mode", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    // Stop current mitmproxy (keep HTTP server running)
                    mitmproxyController.stop()
                    Thread.sleep(500) // Wait for mitmproxy to stop

                    // Restart mitmproxy in MOCKK mode
                    restartMitmproxyWithMode(Mode.MOCKK)
                } catch (e: Exception) {
                    logger.error("Failed to switch to Mockk mode", e)
                    SwingUtilities.invokeLater {
                        updateStatus("Error switching to Mockk mode", JBColor.RED)
                    }
                }
            }
        }.queue()
    }

    private fun restartMitmproxyWithMode(mode: Mode) {
        val addonPath = getAddonScriptPath() ?: throw Exception("Addon script not found")
        val mitmMode = when (mode) {
            Mode.RECORDING -> MitmproxyController.Mode.RECORDING
            Mode.DEBUG -> MitmproxyController.Mode.DEBUG
            Mode.MOCKK -> MitmproxyController.Mode.MOCKK
            Mode.STOPPED -> throw Exception("Cannot start in STOPPED mode")
        }
        val config = MitmproxyController.MitmproxyConfig(
            mode = mitmMode,
            addonScriptPath = addonPath
        )

        if (!mitmproxyController.start(config)) {
            throw Exception("Failed to restart mitmproxy")
        }

        // Configure iptables firewall for app-only filtering
        val app = selectedApp
        val emulator = selectedEmulator
        if (app != null && emulator != null && app.uid != null) {
            if (!proxyFirewallManager.setupAppFirewall(emulator.serialNumber, app.uid)) {
                throw Exception("Failed to configure firewall rules")
            }
            SwingUtilities.invokeLater {
                logger.info("🔍 Filtering enabled for ${app.packageName} (UID: ${app.uid})")
            }
        }

        SwingUtilities.invokeLater {
            currentMode = mode
            when (mode) {
                Mode.RECORDING -> {
                    recordingButton.isSelected = true
                    debugButton.isSelected = false
                    mockkButton.isSelected = false
                    updateStatus("Recording...", JBColor.GREEN)
                }
                Mode.DEBUG -> {
                    recordingButton.isSelected = false
                    debugButton.isSelected = true
                    mockkButton.isSelected = false
                    updateStatus("Debug Mode Active", JBColor(java.awt.Color.CYAN, java.awt.Color.CYAN))
                }
                Mode.MOCKK -> {
                    recordingButton.isSelected = false
                    debugButton.isSelected = false
                    mockkButton.isSelected = true
                    updateStatus("Mockk Mode Active", JBColor.ORANGE)
                }
                Mode.STOPPED -> {}
            }
            logger.info("✅ ${mode.name} mode started")
        }
    }

    private fun stopProxy() {
        object : Task.Backgroundable(project, "Stopping proxy", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    mitmproxyController.stop()
                    pluginHttpServer.stop()

                    // Clean up firewall rules
                    selectedEmulator?.let { emulator ->
                        selectedApp?.uid?.let { uid ->
                            indicator.text = "Removing firewall rules..."
                            proxyFirewallManager.clearAppFirewall(emulator.serialNumber, uid)
                        }
                    }

                    SwingUtilities.invokeLater {
                        currentMode = Mode.STOPPED
                        recordingButton.isSelected = false
                        debugButton.isSelected = false
                        mockkButton.isSelected = false
                        updateStatus("Stopped", JBColor.GRAY)
                        updateButtonStates()
                    }

                    logger.info("✅ Stopped")

                } catch (e: Exception) {
                    logger.error("Error stopping proxy", e)
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

    private fun getAddonScriptPath(): String? {
        try {
            val resourcePath = "mitmproxy_addon/debug_interceptor.py"
            val inputStream = this.javaClass.classLoader.getResourceAsStream(resourcePath) ?: return null

            val tempFile = File.createTempFile("debug_interceptor", ".py")
            tempFile.deleteOnExit()

            inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            return tempFile.absolutePath
        } catch (e: Exception) {
            logger.error("Failed to extract addon script", e)
            return null
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
        // Select the item under cursor if not already selected
        val index = flowList.locationToIndex(e.point)
        if (index >= 0) {
            flowList.selectedIndex = index
            val selectedFlow = flowList.model.getElementAt(index)

            val popup = JPopupMenu()

            // "Create Mock Rule from Response" menu item
            if (selectedFlow.response != null) {
                val createMockItem = JMenuItem("Create Mock Rule from Response", AllIcons.Actions.MenuSaveall)
                createMockItem.addActionListener {
                    createMockFromFlow(selectedFlow)
                }
                popup.add(createMockItem)
            }

            // "View Details" menu item
            val detailsItem = JMenuItem("View Details", AllIcons.Actions.Preview)
            detailsItem.addActionListener {
                showFlowDetails(selectedFlow)
            }
            popup.add(detailsItem)

            // Show popup
            popup.show(e.component, e.x, e.y)
        }
    }

    private fun createMockFromFlow(flow: HttpFlowData) {
        val dialog = CreateMockDialog(project, initialFlow = flow)
        if (dialog.showAndGet()) {
            logger.info("Mock rule created from flow")
        }
    }

    /**
     * Custom cell renderer for flow list.
     */
    private class FlowListCellRenderer : DefaultListCellRenderer() {
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

                // Add mock badge if mock was applied
                val mockBadge = if (value.mockApplied) {
                    "[MOCK: ${value.mockRuleName}] "
                } else {
                    ""
                }

                // Show full URL with mock indicator
                text = "$statusIcon $mockBadge${value.request.method} ${value.request.url}"

                // Change foreground color for mocked flows
                if (value.mockApplied && !isSelected) {
                    foreground = JBColor.ORANGE
                }
            }

            return this
        }
    }
}
