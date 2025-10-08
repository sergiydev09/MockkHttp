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
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.sergiy.dev.mockkhttp.adb.*
import com.sergiy.dev.mockkhttp.model.HttpFlowData
import com.sergiy.dev.mockkhttp.proxy.OkHttpInterceptorServer
import com.sergiy.dev.mockkhttp.store.FlowStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import javax.swing.*

/**
 * Inspector panel with compact vertical controls and flow list.
 * Uses OkHttpInterceptorServer only (no proxy mode).
 */
class InspectorPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val emulatorManager = EmulatorManager.getInstance(project)
    private val appManager = AppManager.getInstance(project)
    private val okHttpInterceptorServer = OkHttpInterceptorServer.getInstance(project)
    private val flowStore = FlowStore.getInstance(project)

    // UI Components
    private val emulatorComboBox: ComboBox<EmulatorInfo>
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

    // Coroutine scope for UI updates (Problem #4 optimization)
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    enum class Mode {
        STOPPED,
        RECORDING,      // Recording without debug
        DEBUG,          // Recording + Debug
        MOCKK,          // Mockk without debug
        MOCKK_DEBUG     // Mockk + Debug
    }

    init {

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

        // Listen to flow changes using SharedFlow with batching and debouncing (Problem #4)
        // Old: 100 UI updates/second saturate EDT
        // New: Batched updates every 300ms (2-3 updates/second)
        setupFlowListeners()

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
        updateStatus("Initializing ADB...", JBColor.ORANGE)

        if (emulatorManager.initialize()) {
            updateStatus("Ready", JBColor.GREEN)

            // Register device change listener for auto-refresh
            emulatorManager.addDeviceChangeListener {
                SwingUtilities.invokeLater {
                    refreshEmulators()
                }
            }

            refreshEmulators()
        } else {
            updateStatus("ADB initialization failed", JBColor.RED)
        }
    }

    /**
     * Setup flow listeners using SharedFlow with batching and debouncing (Problem #4).
     * This prevents EDT saturation when receiving 100+ flows/second.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun setupFlowListeners() {
        // Flow Added Events: Buffer and debounce to batch updates
        uiScope.launch {
            flowStore.flowAddedEvents
                .buffer(capacity = 100)  // Buffer up to 100 events
                .debounce(300)  // Wait 300ms after last event before processing
                .collect { flow ->
                    SwingUtilities.invokeLater {
                        allFlows.add(flow)
                        // Apply filter
                        if (matchesSearchQuery(flow, searchQuery)) {
                            flowListModel.addElement(flow)
                        }
                    }
                }
        }

        // For better batching, also collect in chunks
        uiScope.launch {
            flowStore.flowAddedEvents
                .chunked(50, 500)  // Collect up to 50 flows or wait 500ms
                .collect { flows ->
                    if (flows.isNotEmpty()) {
                        SwingUtilities.invokeLater {
                            flows.forEach { flow ->
                                allFlows.add(flow)
                                if (matchesSearchQuery(flow, searchQuery)) {
                                    flowListModel.addElement(flow)
                                }
                            }
                        }
                    }
                }
        }

        // Flow Updated Events: Debounce updates
        uiScope.launch {
            flowStore.flowUpdatedEvents
                .debounce(200)  // Debounce updates
                .collect { updatedFlow ->
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
        }

        // Flow Cleared Events: No batching needed (infrequent)
        uiScope.launch {
            flowStore.flowsClearedEvents.collect {
                SwingUtilities.invokeLater {
                    allFlows.clear()
                    flowListModel.clear()
                }
            }
        }
    }

    /**
     * Helper extension for chunked flow collection with timeout.
     */
    private fun <T> Flow<T>.chunked(maxSize: Int, timeoutMs: Long): Flow<List<T>> = flow {
        val buffer = mutableListOf<T>()
        var lastEmitTime = System.currentTimeMillis()

        collect { value ->
            buffer.add(value)
            val now = System.currentTimeMillis()

            // Emit if buffer is full or timeout reached
            if (buffer.size >= maxSize || (now - lastEmitTime) >= timeoutMs) {
                emit(buffer.toList())
                buffer.clear()
                lastEmitTime = now
            }
        }

        // Emit remaining items
        if (buffer.isNotEmpty()) {
            emit(buffer.toList())
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
            }
        } else if (emulators.isNotEmpty() && selectedEmulator == null) {
            // Only auto-select first if nothing was selected before
            emulatorComboBox.selectedIndex = 0
        } else if (previousSelection != null && emulators.none { it.serialNumber == previousSelection.serialNumber }) {
            // Previously selected emulator is now disconnected
            selectedEmulator = null
            selectedApp = null

            // Stop interceptor if running
            if (currentMode != Mode.STOPPED) {
                stop()
            }
        }
    }

    private fun onEmulatorSelected() {
        selectedEmulator = emulatorComboBox.selectedItem as? EmulatorInfo
        selectedEmulator?.let { emulator ->
            refreshApps()
        }
        updateButtonStates()
    }

    private fun refreshApps() {
        val emulator = selectedEmulator ?: return

        // Use coroutine to fetch apps asynchronously (Problem #5)
        // Old: Blocked EDT for 250s (4+ minutes)
        // New: Non-blocking, 5-10s in background
        updateStatus("Loading apps...", JBColor.ORANGE)
        refreshAppsButton.isEnabled = false

        uiScope.launch(Dispatchers.IO) {
            try {
                // Fetch apps in background (suspending call, runs on IO dispatcher)
                val apps = appManager.getInstalledApps(emulator.serialNumber, includeSystem = false)

                // Update UI on EDT
                SwingUtilities.invokeLater {
                    appComboBox.removeAllItems()
                    apps.forEach { app ->
                        appComboBox.addItem(app)
                    }

                    if (apps.isNotEmpty()) {
                        appComboBox.selectedIndex = 0
                    }

                    updateStatus("Loaded ${apps.size} apps", JBColor.GREEN)
                    refreshAppsButton.isEnabled = true
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    updateStatus("Failed to load apps", JBColor.RED)
                    refreshAppsButton.isEnabled = true
                }
            }
        }
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
            // Show "Switching..." feedback first
            val switchingText = when (newMode) {
                Mode.RECORDING -> "Switching to Recording..."
                Mode.DEBUG -> "Switching to Debug Mode..."
                Mode.MOCKK -> "Switching to Mockk Mode..."
                Mode.MOCKK_DEBUG -> "Switching to Mockk Debug Mode..."
                Mode.STOPPED -> "Stopping..."
            }
            updateStatus(switchingText, JBColor.ORANGE)

            // Update UI state
            currentMode = newMode

            // Update server mode
            val serverMode = when (newMode) {
                Mode.RECORDING -> OkHttpInterceptorServer.Mode.RECORDING
                Mode.DEBUG -> OkHttpInterceptorServer.Mode.DEBUG
                Mode.MOCKK -> OkHttpInterceptorServer.Mode.MOCKK
                Mode.MOCKK_DEBUG -> OkHttpInterceptorServer.Mode.MOCKK_DEBUG
                Mode.STOPPED -> return
            }
            okHttpInterceptorServer.setMode(serverMode)

            // Show "Ready" feedback after a short delay
            Timer(300) {
                SwingUtilities.invokeLater {
                    val statusText = when (newMode) {
                        Mode.RECORDING -> "Recording..."
                        Mode.DEBUG -> "Debug Mode (Recording + Pause)"
                        Mode.MOCKK -> "Mockk Mode - Ready"
                        Mode.MOCKK_DEBUG -> "Mockk Debug Mode - Ready"
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
                }
            }.apply { isRepeats = false }.start()
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
            return
        }

        // Show "Starting..." feedback first
        val startingText = when (mode) {
            Mode.RECORDING -> "Starting Recording..."
            Mode.DEBUG -> "Starting Debug Mode..."
            Mode.MOCKK -> "Starting Mockk Mode..."
            Mode.MOCKK_DEBUG -> "Starting Mockk Debug Mode..."
            Mode.STOPPED -> "Starting..."
        }
        updateStatus(startingText, JBColor.ORANGE)

        start(mode)
    }

    private fun stopInterceptor() {
        updateStatus("Stopping...", JBColor.ORANGE)
        stop()
    }


    private fun start(mode: Mode) {
        object : Task.Backgroundable(project, "Starting ${mode.name} Mode", true) {
            override fun run(indicator: ProgressIndicator) {
                try {

                    // Get package name filter from selected app
                    val packageNameFilter = selectedApp?.packageName
                    if (packageNameFilter != null) {
                    } else {
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


                    // Update UI
                    SwingUtilities.invokeLater {
                        currentMode = mode
                        updateButtonStates()

                        // Show "Ready" feedback after a short delay
                        Timer(300) {
                            SwingUtilities.invokeLater {
                                val statusText = when (mode) {
                                    Mode.RECORDING -> "Recording..."
                                    Mode.DEBUG -> "Debug Mode (Recording + Pause)"
                                    Mode.MOCKK -> "Mockk Mode - Ready"
                                    Mode.MOCKK_DEBUG -> "Mockk Debug Mode - Ready"
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
                            }
                        }.apply { isRepeats = false }.start()
                    }


                } catch (e: Exception) {
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

                    SwingUtilities.invokeLater {
                        currentMode = Mode.STOPPED
                        updateStatus("Stopped", JBColor.GRAY)
                        updateButtonStates()
                    }


                } catch (e: Exception) {
                }
            }
        }.queue()
    }

    private fun clearFlows() {
        flowStore.clearAllFlows()
    }

    private fun exportFlows() {

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

                JOptionPane.showMessageDialog(
                    this,
                    "Successfully exported ${flows.size} flows to:\n${file.absolutePath}",
                    "Export Successful",
                    JOptionPane.INFORMATION_MESSAGE
                )
            } catch (e: Exception) {
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
            val popup = JPopupMenu()

            if (selectedFlows.size == 1) {
                // Single selection - show standard options
                val selectedFlow = selectedFlows[0]

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

            } else if (selectedFlows.size > 1) {
                // Multiple selection - show batch create option
                val flowsWithResponse = selectedFlows.filter { it.response != null }

                if (flowsWithResponse.isNotEmpty()) {
                    val batchCreateItem = JMenuItem(
                        "Create ${flowsWithResponse.size} Mock Rules from Selection",
                        AllIcons.Actions.MenuSaveall
                    )
                    batchCreateItem.addActionListener {
                        createMocksFromFlows(flowsWithResponse)
                    }
                    popup.add(batchCreateItem)
                }
            }

            // Show popup if it has items
            if (popup.componentCount > 0) {
                popup.show(e.component, e.x, e.y)
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
