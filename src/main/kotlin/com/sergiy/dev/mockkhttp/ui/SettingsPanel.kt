package com.sergiy.dev.mockkhttp.ui

import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.sergiy.dev.mockkhttp.adb.EmulatorManager
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import com.sergiy.dev.mockkhttp.store.SettingsStore
import java.awt.*
import java.io.File
import javax.swing.*
import javax.swing.border.TitledBorder

/**
 * Settings panel for configuring plugin paths and options.
 */
class SettingsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val logger = MockkHttpLogger.getInstance(project)
    private val settingsStore = SettingsStore.getInstance(project)
    private val emulatorManager = EmulatorManager.getInstance(project)

    // Path fields
    private val adbPathField: TextFieldWithBrowseButton
    private val androidSdkPathField: TextFieldWithBrowseButton

    // Status labels
    private val adbStatusLabel: JBLabel
    private val sdkStatusLabel: JBLabel
    private val detectedAdbLabel: JBLabel
    private val detectedSdkLabel: JBLabel

    // Options
    private val portField: JBTextField
    private val verboseLoggingCheckbox: JCheckBox
    private val autoStartCheckbox: JCheckBox

    // Cache management
    private val maxFlowsField: JBTextField
    private val maxBodyKbField: JBTextField
    private val cacheUsageLabel: JBLabel

    init {
        logger.info("Initializing Settings Panel...")

        border = JBUI.Borders.empty(10)

        // Main content panel with scroll
        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(5)
        }

        // ========== PATHS SECTION ==========
        val pathsPanel = JPanel(GridBagLayout()).apply {
            border = createSectionBorder("Paths Configuration")
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(5)
            anchor = GridBagConstraints.WEST
        }

        // ADB Path
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        pathsPanel.add(JBLabel("ADB Path:"), gbc)

        adbPathField = TextFieldWithBrowseButton().apply {
            text = settingsStore.getAdbPath() ?: ""
            addActionListener {
                val descriptor = createExecutableChooserDescriptor()
                val chooser = FileChooserFactory.getInstance().createFileChooser(descriptor, project, this)
                val toSelect = if (text.isNotBlank()) com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(text) else null
                val files = chooser.choose(project, *listOfNotNull(toSelect).toTypedArray())
                files.firstOrNull()?.let { file ->
                    text = file.path
                }
            }
            textField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = validateAdbPath()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = validateAdbPath()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = validateAdbPath()
            })
        }
        gbc.gridx = 1; gbc.weightx = 1.0
        pathsPanel.add(adbPathField, gbc)

        adbStatusLabel = JBLabel().apply {
            font = font.deriveFont(Font.ITALIC, 11f)
        }
        gbc.gridx = 2; gbc.weightx = 0.0
        pathsPanel.add(adbStatusLabel, gbc)

        // Detected ADB
        detectedAdbLabel = JBLabel().apply {
            font = font.deriveFont(10f)
            foreground = JBColor.GRAY
        }
        gbc.gridx = 1; gbc.gridy = 1; gbc.gridwidth = 2
        pathsPanel.add(detectedAdbLabel, gbc)
        gbc.gridwidth = 1

        // Android SDK Path
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        pathsPanel.add(JBLabel("Android SDK Path:"), gbc)

        androidSdkPathField = TextFieldWithBrowseButton().apply {
            text = settingsStore.getAndroidSdkPath() ?: ""
            addActionListener {
                val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                descriptor.title = "Select Android SDK Directory"
                descriptor.description = "Choose the Android SDK directory"
                val chooser = FileChooserFactory.getInstance().createFileChooser(descriptor, project, this)
                val toSelect = if (text.isNotBlank()) com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(text) else null
                val files = chooser.choose(project, *listOfNotNull(toSelect).toTypedArray())
                files.firstOrNull()?.let { file ->
                    text = file.path
                }
            }
            textField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = validateSdkPath()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = validateSdkPath()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = validateSdkPath()
            })
        }
        gbc.gridx = 1; gbc.weightx = 1.0
        pathsPanel.add(androidSdkPathField, gbc)

        sdkStatusLabel = JBLabel().apply {
            font = font.deriveFont(Font.ITALIC, 11f)
        }
        gbc.gridx = 2; gbc.weightx = 0.0
        pathsPanel.add(sdkStatusLabel, gbc)

        // Detected SDK
        detectedSdkLabel = JBLabel().apply {
            font = font.deriveFont(10f)
            foreground = JBColor.GRAY
        }
        gbc.gridx = 1; gbc.gridy = 3; gbc.gridwidth = 2
        pathsPanel.add(detectedSdkLabel, gbc)
        gbc.gridwidth = 1

        // Buttons row
        val pathButtonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))

        val detectButton = JButton("Auto-Detect Paths").apply {
            addActionListener { autoDetectPaths() }
        }
        pathButtonsPanel.add(detectButton)

        val clearAdbButton = JButton("Clear ADB").apply {
            addActionListener {
                adbPathField.text = ""
                saveAdbPath()
            }
        }
        pathButtonsPanel.add(clearAdbButton)

        val clearSdkButton = JButton("Clear SDK").apply {
            addActionListener {
                androidSdkPathField.text = ""
                saveSdkPath()
            }
        }
        pathButtonsPanel.add(clearSdkButton)

        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 3
        pathsPanel.add(pathButtonsPanel, gbc)
        gbc.gridwidth = 1

        contentPanel.add(pathsPanel)
        contentPanel.add(Box.createVerticalStrut(15))

        // ========== SERVER SECTION ==========
        val serverPanel = JPanel(GridBagLayout()).apply {
            border = createSectionBorder("Interceptor Server")
            alignmentX = Component.LEFT_ALIGNMENT
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        serverPanel.add(JBLabel("Server Port:"), gbc)

        portField = JBTextField(settingsStore.getInterceptorPort().toString(), 6).apply {
            document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = validatePort()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = validatePort()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = validatePort()
            })
        }
        gbc.gridx = 1; gbc.weightx = 0.0
        serverPanel.add(portField, gbc)

        val portHintLabel = JBLabel("(1024-65535, default: 9876)").apply {
            font = font.deriveFont(10f)
            foreground = JBColor.GRAY
        }
        gbc.gridx = 2
        serverPanel.add(portHintLabel, gbc)

        contentPanel.add(serverPanel)
        contentPanel.add(Box.createVerticalStrut(15))

        // ========== OPTIONS SECTION ==========
        val optionsPanel = JPanel(GridBagLayout()).apply {
            border = createSectionBorder("Options")
            alignmentX = Component.LEFT_ALIGNMENT
        }

        verboseLoggingCheckbox = JCheckBox("Enable verbose logging").apply {
            isSelected = settingsStore.isVerboseLogging()
            addActionListener { saveVerboseLogging() }
        }
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 3
        optionsPanel.add(verboseLoggingCheckbox, gbc)

        autoStartCheckbox = JCheckBox("Auto-start interceptor when project opens").apply {
            isSelected = settingsStore.isAutoStartInterceptor()
            addActionListener { saveAutoStart() }
        }
        gbc.gridy = 1
        optionsPanel.add(autoStartCheckbox, gbc)

        contentPanel.add(optionsPanel)
        contentPanel.add(Box.createVerticalStrut(15))

        // ========== CACHE & MEMORY SECTION ==========
        // Long sessions used to accumulate unbounded flow bodies + logs in IDE
        // memory, which slowed down Android Studio. These limits keep it snappy.
        val cachePanel = JPanel(GridBagLayout()).apply {
            border = createSectionBorder("Cache & Memory")
            alignmentX = Component.LEFT_ALIGNMENT
        }

        gbc.gridwidth = 1
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        cachePanel.add(JBLabel("Max retained flows:"), gbc)
        maxFlowsField = JBTextField(settingsStore.getMaxFlowsRetained().toString(), 6)
        gbc.gridx = 1; gbc.weightx = 0.0
        cachePanel.add(maxFlowsField, gbc)
        gbc.gridx = 2; gbc.weightx = 1.0
        cachePanel.add(JBLabel("(oldest flows are dropped beyond this)").apply {
            foreground = JBColor.GRAY
        }, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        cachePanel.add(JBLabel("Max stored body size (KB):"), gbc)
        maxBodyKbField = JBTextField(settingsStore.getMaxStoredBodyKb().toString(), 6)
        gbc.gridx = 1; gbc.weightx = 0.0
        cachePanel.add(maxBodyKbField, gbc)
        gbc.gridx = 2; gbc.weightx = 1.0
        cachePanel.add(JBLabel("(larger bodies are truncated in the flow list)").apply {
            foreground = JBColor.GRAY
        }, gbc)

        cacheUsageLabel = JBLabel(" ")
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2
        cachePanel.add(cacheUsageLabel, gbc)

        val cacheButtonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
            add(JButton("Refresh Usage").apply {
                addActionListener { refreshCacheUsage() }
            })
            add(JButton("Clear Flows && Logs").apply {
                toolTipText = "Free memory now: clears the intercepted flow list and the plugin logs (mock rules are NOT touched)"
                addActionListener { clearCaches() }
            })
        }
        gbc.gridx = 2; gbc.gridy = 2; gbc.gridwidth = 1
        cachePanel.add(cacheButtonsPanel, gbc)
        gbc.gridwidth = 1

        contentPanel.add(cachePanel)
        contentPanel.add(Box.createVerticalStrut(15))

        // ========== HELP SECTION ==========
        val helpPanel = JPanel(BorderLayout()).apply {
            border = createSectionBorder("Troubleshooting")
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val helpText = """
            <html>
            <body style="font-family: Dialog; font-size: 11px; padding: 5px;">
            <p><b>ADB not found?</b></p>
            <ul>
                <li>Install Android SDK Platform Tools</li>
                <li>Set ANDROID_HOME in your shell profile (~/.zshrc or ~/.bash_profile)</li>
                <li>Or manually set the path above</li>
            </ul>
            <p><b>Common ADB locations:</b></p>
            <ul>
                <li><code>~/Library/Android/sdk/platform-tools/adb</code> (macOS)</li>
                <li><code>%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe</code> (Windows)</li>
                <li><code>~/Android/Sdk/platform-tools/adb</code> (Linux)</li>
            </ul>
            <p><b>Tip:</b> Leave paths empty to use auto-detection.</p>
            </body>
            </html>
        """.trimIndent()

        val helpLabel = JLabel(helpText)
        helpPanel.add(helpLabel, BorderLayout.CENTER)

        contentPanel.add(helpPanel)
        contentPanel.add(Box.createVerticalGlue())

        // Scroll pane
        val scrollPane = JBScrollPane(contentPanel).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        add(scrollPane, BorderLayout.CENTER)

        // Apply button at bottom
        val bottomPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        val applyButton = JButton("Apply Changes").apply {
            addActionListener { applyAllChanges() }
        }
        bottomPanel.add(applyButton)
        add(bottomPanel, BorderLayout.SOUTH)

        // Initial validation
        SwingUtilities.invokeLater {
            validateAdbPath()
            validateSdkPath()
            updateDetectedPaths()
            refreshCacheUsage()
        }

        logger.info("✅ Settings Panel initialized")
    }

    private fun createSectionBorder(title: String): TitledBorder {
        return BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(),
            title
        ).apply {
            titleFont = titleFont.deriveFont(Font.BOLD)
        }
    }

    private fun createExecutableChooserDescriptor(): FileChooserDescriptor {
        return FileChooserDescriptor(true, false, false, false, false, false).apply {
            title = "Select Executable"
            description = "Choose an executable file"
        }
    }

    private fun validateAdbPath() {
        val path = adbPathField.text.trim()
        val result = settingsStore.validateExecutablePath(path)

        if (path.isBlank()) {
            adbStatusLabel.text = "Auto-detect"
            adbStatusLabel.foreground = JBColor.GRAY
        } else if (result.isValid) {
            adbStatusLabel.text = "✓ Valid"
            adbStatusLabel.foreground = JBColor(0x2E7D32, 0x81C784)
        } else {
            adbStatusLabel.text = "✗ ${result.message}"
            adbStatusLabel.foreground = JBColor.RED
        }
    }

    private fun validateSdkPath() {
        val path = androidSdkPathField.text.trim()
        val result = settingsStore.validateDirectoryPath(path)

        if (path.isBlank()) {
            sdkStatusLabel.text = "Auto-detect"
            sdkStatusLabel.foreground = JBColor.GRAY
        } else if (result.isValid) {
            // Also check if it looks like an SDK directory
            val platformTools = File(path, "platform-tools")
            if (platformTools.exists()) {
                sdkStatusLabel.text = "✓ Valid SDK"
                sdkStatusLabel.foreground = JBColor(0x2E7D32, 0x81C784)
            } else {
                sdkStatusLabel.text = "⚠ Missing platform-tools"
                sdkStatusLabel.foreground = JBColor.ORANGE
            }
        } else {
            sdkStatusLabel.text = "✗ ${result.message}"
            sdkStatusLabel.foreground = JBColor.RED
        }
    }

    private fun validatePort() {
        val portText = portField.text.trim()
        val port = portText.toIntOrNull()

        if (port == null || port !in 1024..65535) {
            portField.background = JBColor(0xFFCDD2, 0x5D4037)
        } else {
            portField.background = UIManager.getColor("TextField.background")
        }
    }

    private fun updateDetectedPaths() {
        // Show auto-detected ADB path
        val detectedAdb = emulatorManager.findAdbPath()
        if (detectedAdb != null) {
            detectedAdbLabel.text = "Auto-detected: $detectedAdb"
            detectedAdbLabel.foreground = JBColor(0x2E7D32, 0x81C784)
        } else {
            detectedAdbLabel.text = "Auto-detection failed - please set path manually"
            detectedAdbLabel.foreground = JBColor.RED
        }

        // Show auto-detected SDK path (from ADB path)
        if (detectedAdb != null) {
            val sdkPath = File(detectedAdb).parentFile?.parentFile?.absolutePath
            if (sdkPath != null && File(sdkPath, "platform-tools").exists()) {
                detectedSdkLabel.text = "Auto-detected: $sdkPath"
                detectedSdkLabel.foreground = JBColor(0x2E7D32, 0x81C784)
            } else {
                detectedSdkLabel.text = "SDK path not detected"
                detectedSdkLabel.foreground = JBColor.GRAY
            }
        } else {
            detectedSdkLabel.text = "SDK path not detected"
            detectedSdkLabel.foreground = JBColor.GRAY
        }
    }

    private fun autoDetectPaths() {
        logger.info("🔍 Running auto-detection...")

        val detectedAdb = emulatorManager.findAdbPath()
        if (detectedAdb != null) {
            adbPathField.text = detectedAdb
            logger.info("✅ ADB auto-detected: $detectedAdb")

            // Also detect SDK from ADB path
            val sdkPath = File(detectedAdb).parentFile?.parentFile?.absolutePath
            if (sdkPath != null && File(sdkPath, "platform-tools").exists()) {
                androidSdkPathField.text = sdkPath
                logger.info("✅ SDK auto-detected: $sdkPath")
            }
        } else {
            JOptionPane.showMessageDialog(
                this,
                "ADB could not be auto-detected.\n\nPlease install Android SDK Platform Tools or set the path manually.",
                "Auto-Detection Failed",
                JOptionPane.WARNING_MESSAGE
            )
        }

        updateDetectedPaths()
    }

    private fun saveAdbPath() {
        val path = adbPathField.text.trim()
        settingsStore.setAdbPath(path)
        validateAdbPath()
    }

    private fun saveSdkPath() {
        val path = androidSdkPathField.text.trim()
        settingsStore.setAndroidSdkPath(path)
        validateSdkPath()
    }

    private fun savePort() {
        val port = portField.text.trim().toIntOrNull() ?: 9876
        settingsStore.setInterceptorPort(port)
    }

    private fun saveVerboseLogging() {
        settingsStore.setVerboseLogging(verboseLoggingCheckbox.isSelected)
    }

    private fun saveAutoStart() {
        settingsStore.setAutoStartInterceptor(autoStartCheckbox.isSelected)
    }

    private fun saveCacheSettings() {
        maxFlowsField.text.trim().toIntOrNull()?.let { settingsStore.setMaxFlowsRetained(it) }
        maxBodyKbField.text.trim().toIntOrNull()?.let { settingsStore.setMaxStoredBodyKb(it) }
        // Reflect the clamped values back into the fields
        maxFlowsField.text = settingsStore.getMaxFlowsRetained().toString()
        maxBodyKbField.text = settingsStore.getMaxStoredBodyKb().toString()
    }

    private fun refreshCacheUsage() {
        val flowStore = com.sergiy.dev.mockkhttp.store.FlowStore.getInstance(project)
        val memoryMb = flowStore.getEstimatedMemoryBytes() / (1024.0 * 1024.0)
        cacheUsageLabel.text = String.format(
            "In memory: %d flow(s) ≈ %.1f MB · %d log entries",
            flowStore.getFlowCount(), memoryMb, logger.getLogCount()
        )
    }

    private fun clearCaches() {
        com.sergiy.dev.mockkhttp.store.FlowStore.getInstance(project).clearAllFlows()
        logger.clear()
        refreshCacheUsage()
    }

    private fun applyAllChanges() {
        logger.info("⚙️ Applying all settings changes...")

        saveAdbPath()
        saveSdkPath()
        savePort()
        saveVerboseLogging()
        saveAutoStart()
        saveCacheSettings()

        JOptionPane.showMessageDialog(
            this,
            "Settings saved successfully.\n\nNote: Some changes may require restarting the interceptor to take effect.",
            "Settings Saved",
            JOptionPane.INFORMATION_MESSAGE
        )

        logger.info("✅ All settings saved")
    }
}
