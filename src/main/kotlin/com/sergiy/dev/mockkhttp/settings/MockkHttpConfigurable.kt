package com.sergiy.dev.mockkhttp.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings UI for MockkHttp plugin.
 * Allows users to configure custom paths for mitmproxy executable and certificates directory.
 */
class MockkHttpConfigurable : Configurable {

    private var settingsPanel: JPanel? = null
    private var mitmproxyPathField: TextFieldWithBrowseButton? = null
    private var certificatesPathField: TextFieldWithBrowseButton? = null

    override fun getDisplayName(): String = "MockkHttp"

    override fun createComponent(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(10)

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
        val titleLabel = JBLabel("Mitmproxy configuration")
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
            "<html><i>Leave empty for automatic detection. Common locations:<br>" +
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
            "<html><i>Leave empty to use default ~/.mitmproxy directory.<br>" +
                    "Custom path can be set via MITMPROXY_CONFDIR environment variable.</i></html>"
        )
        certificatesHint.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        formPanel.add(certificatesHint, gbc)

        // Vertical spacer (push content to top)
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        formPanel.add(JPanel(), gbc)

        panel.add(formPanel, BorderLayout.CENTER)
        settingsPanel = panel

        return panel
    }

    override fun isModified(): Boolean {
        val settings = MockkHttpSettingsState.getInstance()
        val mitmproxyPath = mitmproxyPathField?.text?.trim()?.takeIf { it.isNotEmpty() }
        val certificatesPath = certificatesPathField?.text?.trim()?.takeIf { it.isNotEmpty() }

        return mitmproxyPath != settings.customMitmproxyPath ||
                certificatesPath != settings.customCertificatesPath
    }

    override fun apply() {
        val settings = MockkHttpSettingsState.getInstance()
        settings.customMitmproxyPath = mitmproxyPathField?.text?.trim()?.takeIf { it.isNotEmpty() }
        settings.customCertificatesPath = certificatesPathField?.text?.trim()?.takeIf { it.isNotEmpty() }
    }

    override fun reset() {
        val settings = MockkHttpSettingsState.getInstance()

        // Auto-complete mitmproxy path if found and not already configured
        val mitmproxyPath = settings.customMitmproxyPath ?: findMitmproxyExecutable()
        mitmproxyPathField?.text = mitmproxyPath ?: ""

        // Auto-complete certificates path with default or configured value
        val certificatesPath = settings.customCertificatesPath
            ?: System.getenv("MITMPROXY_CONFDIR")
            ?: (System.getProperty("user.home") + "/.mitmproxy")
        certificatesPathField?.text = certificatesPath
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

    override fun disposeUIResources() {
        settingsPanel = null
        mitmproxyPathField = null
        certificatesPathField = null
    }
}
