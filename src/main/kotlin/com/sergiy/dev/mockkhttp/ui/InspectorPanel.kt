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
    private val okHttpInterceptorServer = OkHttpInterceptorServer.getInstance(project)
    private val flowStore = FlowStore.getInstance(project)

    // UI Components
    private val emulatorComboBox: ComboBox<EmulatorInfo>
    private val appComboBox: ComboBox<AppInfo>
    private val refreshAppsButton: JButton
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

        // Create mode buttons
        recordingButton = JToggleButton("Recording", AllIcons.Debugger.Db_set_breakpoint).apply {
            toolTipText = "Start/Stop Recording Mode"
            isEnabled = false
            addActionListener { toggleRecording() }
            addItemListener { _ ->
                if (isSelected) {
                    text = "Stop Recording"
                    icon = AllIcons.Actions.Suspend
                } else {
                    text = "Recording"
                    icon = AllIcons.Debugger.Db_set_breakpoint
                }
            }
        }

        debugButton = JToggleButton("Debug", AllIcons.Actions.StartDebugger).apply {
            toolTipText = "Start/Stop Debug Mode"
            isEnabled = false
            addActionListener { toggleDebug() }
            addItemListener { _ ->
                if (isSelected) {
                    text = "Stop Debug"
                    icon = AllIcons.Actions.Suspend
                } else {
                    text = "Debug"
                    icon = AllIcons.Actions.StartDebugger
                }
            }
        }

        mockkButton = JToggleButton("Mockk", AllIcons.Nodes.DataSchema).apply {
            toolTipText = "Start/Stop Mockk Mode (Auto-apply mock rules)"
            isEnabled = false
            addActionListener { toggleMockk() }
            addItemListener { _ ->
                if (isSelected) {
                    text = "Stop Mockk"
                    icon = AllIcons.Actions.Suspend
                } else {
                    text = "Mockk"
                    icon = AllIcons.Nodes.DataSchema
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

        flowStore.addFlowUpdatedListener { updatedFlow ->
            SwingUtilities.invokeLater {
                // Find and update the flow in the list
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

        // Top panel: Mode buttons + Emulator/App selectors
        val topPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5, 5, 0, 5)

            // Left side: Mode buttons
            val modeButtonsPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)

                add(recordingButton)
                add(Box.createHorizontalStrut(2))
                add(debugButton)
                add(Box.createHorizontalStrut(2))
                add(mockkButton)
                add(Box.createHorizontalStrut(15))
            }

            // Right side: Emulator and App selectors
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

            // Combine mode buttons and selectors
            val combinedPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(modeButtonsPanel)
                add(selectorsPanel)
            }

            add(combinedPanel, BorderLayout.CENTER)
        }

        // Center panel: Flow list
        val flowPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5)
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
        } else if (previousSelection != null && emulators.none { it.serialNumber == previousSelection.serialNumber }) {
            // Previously selected emulator is now disconnected
            logger.warn("⚠️ Previously selected emulator disconnected")
            selectedEmulator = null
            selectedApp = null

            // Stop interceptor if running
            if (currentMode != Mode.STOPPED) {
                logger.warn("⚠️ Stopping interceptor due to emulator disconnection")
                stop()
            }
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
        recordingButton.isEnabled = hasSelection || currentMode != Mode.STOPPED
        debugButton.isEnabled = hasSelection || currentMode != Mode.STOPPED
        mockkButton.isEnabled = hasSelection || currentMode != Mode.STOPPED
    }

    private fun toggleRecording() {
        if (recordingButton.isSelected) {
            // If another mode is active, switch to this mode
            if (currentMode != Mode.STOPPED && currentMode != Mode.RECORDING) {
                switchMode(Mode.RECORDING)
            } else {
                start(Mode.RECORDING)
            }
        } else {
            stop()
        }
    }

    private fun toggleDebug() {
        if (debugButton.isSelected) {
            // If another mode is active, switch to this mode
            if (currentMode != Mode.STOPPED && currentMode != Mode.DEBUG) {
                switchMode(Mode.DEBUG)
            } else {
                start(Mode.DEBUG)
            }
        } else {
            stop()
        }
    }

    private fun toggleMockk() {
        if (mockkButton.isSelected) {
            // If another mode is active, switch to this mode
            if (currentMode != Mode.STOPPED && currentMode != Mode.MOCKK) {
                switchMode(Mode.MOCKK)
            } else {
                start(Mode.MOCKK)
            }
        } else {
            stop()
        }
    }

    /**
     * Switch from current mode to a new mode without fully stopping the server.
     * Just changes the server's mode internally.
     */
    private fun switchMode(newMode: Mode) {
        object : Task.Backgroundable(project, "Switching to ${newMode.name} mode", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    logger.info("🔄 Switching from ${currentMode.name} to ${newMode.name} mode...")

                    // Map mode to OkHttpInterceptorServer.Mode
                    val serverMode = when (newMode) {
                        Mode.RECORDING -> OkHttpInterceptorServer.Mode.RECORDING
                        Mode.DEBUG -> OkHttpInterceptorServer.Mode.DEBUG
                        Mode.MOCKK -> OkHttpInterceptorServer.Mode.MOCKK
                        Mode.STOPPED -> throw Exception("Cannot switch to STOPPED mode")
                    }

                    // Change mode on the server (server stays running)
                    okHttpInterceptorServer.setMode(serverMode)

                    // Update UI
                    SwingUtilities.invokeLater {
                        currentMode = newMode
                        recordingButton.isSelected = (newMode == Mode.RECORDING)
                        debugButton.isSelected = (newMode == Mode.DEBUG)
                        mockkButton.isSelected = (newMode == Mode.MOCKK)

                        val statusText = when (newMode) {
                            Mode.RECORDING -> "Recording..."
                            Mode.DEBUG -> "Debug Mode"
                            Mode.MOCKK -> "Mockk Mode"
                            Mode.STOPPED -> "Stopped"
                        }
                        val statusColor = when (newMode) {
                            Mode.RECORDING -> JBColor.GREEN
                            Mode.DEBUG -> JBColor(java.awt.Color.CYAN, java.awt.Color.CYAN)
                            Mode.MOCKK -> JBColor.ORANGE
                            Mode.STOPPED -> JBColor.GRAY
                        }

                        updateStatus(statusText, statusColor)
                        updateButtonStates()
                    }

                    logger.info("✅ Switched to ${newMode.name} mode")

                } catch (e: Exception) {
                    logger.error("Failed to switch mode", e)
                }
            }
        }.queue()
    }

    private fun start(mode: Mode) {
        object : Task.Backgroundable(project, "Starting ${mode.name} Mode", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    logger.info("🔌 Starting ${mode.name} Mode...")

                    // Map mode to OkHttpInterceptorServer.Mode
                    val serverMode = when (mode) {
                        Mode.RECORDING -> OkHttpInterceptorServer.Mode.RECORDING
                        Mode.DEBUG -> OkHttpInterceptorServer.Mode.DEBUG
                        Mode.MOCKK -> OkHttpInterceptorServer.Mode.MOCKK
                        Mode.STOPPED -> throw Exception("Cannot start in STOPPED mode")
                    }

                    // Start OkHttpInterceptorServer with the selected mode
                    if (!okHttpInterceptorServer.start(serverMode)) {
                        throw Exception("Failed to start OkHttp Interceptor Server")
                    }

                    logger.info("✅ OkHttp Interceptor Server started on port ${OkHttpInterceptorServer.SERVER_PORT}")
                    logger.info("📱 Waiting for app connections...")
                    logger.info("   Make sure your app includes the MockkHttp Gradle plugin!")

                    // Update UI
                    SwingUtilities.invokeLater {
                        currentMode = mode
                        recordingButton.isSelected = (mode == Mode.RECORDING)
                        debugButton.isSelected = (mode == Mode.DEBUG)
                        mockkButton.isSelected = (mode == Mode.MOCKK)

                        val statusText = when (mode) {
                            Mode.RECORDING -> "Recording..."
                            Mode.DEBUG -> "Debug Mode"
                            Mode.MOCKK -> "Mockk Mode"
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
                    logger.error("Failed to start ${mode.name} mode", e)
                    okHttpInterceptorServer.stop()

                    SwingUtilities.invokeLater {
                        currentMode = Mode.STOPPED
                        recordingButton.isSelected = false
                        debugButton.isSelected = false
                        mockkButton.isSelected = false
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

                // Add badge based on type
                val badge = when {
                    value.modified -> "[DEBUG: Modified] "
                    value.mockApplied -> "[MOCK: ${value.mockRuleName}] "
                    else -> ""
                }

                // Show full URL with badge
                text = "$statusIcon $badge${value.request.method} ${value.request.url}"

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
    }
}
