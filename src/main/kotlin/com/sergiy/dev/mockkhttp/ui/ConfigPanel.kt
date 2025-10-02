package com.sergiy.dev.mockkhttp.ui

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import com.sergiy.dev.mockkhttp.settings.MockkHttpSettingsState
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Configuration panel for MockkHttp settings.
 * Displayed as a tab in the tool window.
 */
class ConfigPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val logger = MockkHttpLogger.getInstance(project)
    private val mitmproxyPathField: TextFieldWithBrowseButton
    private val certificatesPathField: TextFieldWithBrowseButton
    private val applyButton: JButton
    private val resetButton: JButton

    init {
        logger.info("Initializing Config Panel...")

        border = JBUI.Borders.empty(10)

        val formPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = GridBagConstraints.RELATIVE
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = JBUI.insets(5)
        }

        // Section title
        val titleLabel = JBLabel("Mitmproxy Configuration")
        titleLabel.font = titleLabel.font.deriveFont(titleLabel.font.size + 2.0f)
        formPanel.add(titleLabel, gbc)

        // Mitmproxy path field
        val mitmproxyLabel = JBLabel("Mitmproxy executable:")
        formPanel.add(mitmproxyLabel, gbc)

        mitmproxyPathField = TextFieldWithBrowseButton().apply {
            val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
                .withTitle("Select Mitmproxy Executable")
                .withDescription("Choose the mitmproxy or mitmdump executable file")

            addActionListener {
                FileChooser.chooseFile(descriptor, null, null) { file ->
                    text = file.path
                }
            }
        }
        formPanel.add(mitmproxyPathField, gbc)

        // Mitmproxy hint
        val mitmproxyHint = JBLabel(
            "<html><i>Auto-detected path will be shown. Common locations:<br>" +
                    "/opt/homebrew/bin/mitmproxy (Homebrew on Apple Silicon)<br>" +
                    "/usr/local/bin/mitmproxy (Homebrew on Intel)<br>" +
                    "~/.local/bin/mitmproxy (pipx)</i></html>"
        )
        mitmproxyHint.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        formPanel.add(mitmproxyHint, gbc)

        // Spacer
        gbc.insets = JBUI.insets(20, 5, 5, 5)
        formPanel.add(JPanel(), gbc)
        gbc.insets = JBUI.insets(5)

        // Certificates path field
        val certificatesLabel = JBLabel("Certificates directory:")
        formPanel.add(certificatesLabel, gbc)

        certificatesPathField = TextFieldWithBrowseButton().apply {
            val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
                .withTitle("Select Certificates Directory")
                .withDescription("Choose the mitmproxy certificates directory")

            addActionListener {
                FileChooser.chooseFile(descriptor, null, null) { file ->
                    text = file.path
                }
            }
        }
        formPanel.add(certificatesPathField, gbc)

        // Certificates hint
        val certificatesHint = JBLabel(
            "<html><i>Default: ~/.mitmproxy directory.<br>" +
                    "Custom path can be set via MITMPROXY_CONFDIR environment variable.</i></html>"
        )
        certificatesHint.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        formPanel.add(certificatesHint, gbc)

        // Vertical spacer (push content to top)
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        formPanel.add(JPanel(), gbc)

        // Buttons panel at bottom
        val buttonsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(10, 0, 0, 0)

            applyButton = JButton("Apply").apply {
                addActionListener { applySettings() }
            }

            resetButton = JButton("Reset").apply {
                addActionListener { loadSettings() }
            }

            add(applyButton)
            add(javax.swing.Box.createHorizontalStrut(5))
            add(resetButton)
            add(javax.swing.Box.createHorizontalGlue())
        }

        add(formPanel, BorderLayout.CENTER)
        add(buttonsPanel, BorderLayout.SOUTH)

        // Load current settings
        loadSettings()

        logger.info("✅ Config Panel initialized")
    }

    private fun loadSettings() {
        val settings = MockkHttpSettingsState.getInstance()

        // Auto-complete mitmproxy path if found and not already configured
        val mitmproxyPath = settings.customMitmproxyPath ?: findMitmproxyExecutable()
        mitmproxyPathField.text = mitmproxyPath ?: ""

        // Auto-complete certificates path with default or configured value
        val certificatesPath = settings.customCertificatesPath
            ?: System.getenv("MITMPROXY_CONFDIR")
            ?: (System.getProperty("user.home") + "/.mitmproxy")
        certificatesPathField.text = certificatesPath

        logger.info("Settings loaded")
    }

    private fun applySettings() {
        val settings = MockkHttpSettingsState.getInstance()

        // Validate mitmproxy path if provided
        val mitmproxyPath = mitmproxyPathField.text?.trim()?.takeIf { it.isNotEmpty() }
        if (mitmproxyPath != null) {
            val file = java.io.File(mitmproxyPath)
            if (!file.exists()) {
                logger.warn("⚠️ Mitmproxy executable path does not exist: $mitmproxyPath")
                javax.swing.JOptionPane.showMessageDialog(
                    this,
                    "The mitmproxy executable path does not exist:\n$mitmproxyPath\n\nPlease verify the path is correct.",
                    "Invalid Path",
                    javax.swing.JOptionPane.WARNING_MESSAGE
                )
                return
            }
            if (!file.canExecute()) {
                logger.warn("⚠️ Mitmproxy file is not executable: $mitmproxyPath")
                javax.swing.JOptionPane.showMessageDialog(
                    this,
                    "The file is not executable:\n$mitmproxyPath\n\nPlease ensure it has execute permissions.",
                    "Not Executable",
                    javax.swing.JOptionPane.WARNING_MESSAGE
                )
                return
            }
            // Check if it's actually mitmproxy/mitmdump
            val fileName = file.name
            if (!fileName.contains("mitmproxy") && !fileName.contains("mitmdump") && !fileName.contains("mitmweb")) {
                logger.warn("⚠️ File does not appear to be mitmproxy: $mitmproxyPath")
                val result = javax.swing.JOptionPane.showConfirmDialog(
                    this,
                    "The selected file does not appear to be mitmproxy:\n$fileName\n\nAre you sure you want to use this path?",
                    "Confirm Path",
                    javax.swing.JOptionPane.YES_NO_OPTION,
                    javax.swing.JOptionPane.QUESTION_MESSAGE
                )
                if (result != javax.swing.JOptionPane.YES_OPTION) {
                    return
                }
            }
        }

        // Validate certificates path if provided
        val certificatesPath = certificatesPathField.text?.trim()?.takeIf { it.isNotEmpty() }
        if (certificatesPath != null) {
            val file = java.io.File(certificatesPath)
            if (!file.exists()) {
                logger.info("ℹ️ Certificates directory does not exist, it will be created: $certificatesPath")
                // Try to create it
                try {
                    file.mkdirs()
                    logger.info("✅ Created certificates directory: $certificatesPath")
                } catch (e: Exception) {
                    logger.error("❌ Failed to create certificates directory", e)
                    javax.swing.JOptionPane.showMessageDialog(
                        this,
                        "Failed to create certificates directory:\n$certificatesPath\n\nError: ${e.message}",
                        "Directory Creation Failed",
                        javax.swing.JOptionPane.ERROR_MESSAGE
                    )
                    return
                }
            } else if (!file.isDirectory) {
                logger.warn("⚠️ Certificates path is not a directory: $certificatesPath")
                javax.swing.JOptionPane.showMessageDialog(
                    this,
                    "The certificates path must be a directory:\n$certificatesPath",
                    "Invalid Path",
                    javax.swing.JOptionPane.WARNING_MESSAGE
                )
                return
            }
        }

        // Save settings
        settings.customMitmproxyPath = mitmproxyPath
        settings.customCertificatesPath = certificatesPath

        logger.info("✅ Settings applied")
        javax.swing.JOptionPane.showMessageDialog(
            this,
            "Settings saved successfully!",
            "Success",
            javax.swing.JOptionPane.INFORMATION_MESSAGE
        )
    }

    /**
     * Find mitmproxy executable in common locations.
     */
    private fun findMitmproxyExecutable(): String? {
        val userHome = System.getProperty("user.home")
        val commonPaths = listOf(
            // Homebrew locations
            "/opt/homebrew/bin/mitmdump",      // Apple Silicon Homebrew
            "/opt/homebrew/bin/mitmproxy",
            "/usr/local/bin/mitmdump",         // Intel Homebrew
            "/usr/local/bin/mitmproxy",
            // pipx installations
            "$userHome/.local/bin/mitmdump",
            "$userHome/.local/bin/mitmproxy",
            // pip system installations
            "/usr/bin/mitmdump",
            "/usr/bin/mitmproxy"
        )

        // Check common locations first
        for (path in commonPaths) {
            val file = java.io.File(path)
            if (file.exists() && file.canExecute()) {
                return path
            }
        }

        // Try 'which' command as fallback
        try {
            val executables = listOf("mitmdump", "mitmproxy")
            for (executable in executables) {
                val process = ProcessBuilder("which", executable).start()
                val path = process.inputStream.bufferedReader().readLine()?.trim()
                process.waitFor()
                if (!path.isNullOrEmpty() && java.io.File(path).exists()) {
                    return path
                }
            }
        } catch (e: Exception) {
            // Ignore errors from 'which' command
        }

        return null
    }
}
