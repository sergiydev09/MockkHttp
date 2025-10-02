package com.sergiy.dev.mockkhttp.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import java.awt.BorderLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.*

/**
 * Panel that displays all plugin logs with a copy button.
 * Auto-scrolls to the bottom when new logs arrive.
 */
class LogPanel(project: Project) : JPanel(BorderLayout()) {

    private val logger = MockkHttpLogger.getInstance(project)
    private val textArea: JTextArea = JTextArea().apply {
        isEditable = false
        lineWrap = false
        font = JBUI.Fonts.create("Monospaced", 12)
        margin = JBUI.insets(5)
    }
    private val scrollPane: JBScrollPane = JBScrollPane(textArea).apply {
        border = JBUI.Borders.empty()
    }

    init {
        
        // Toolbar with buttons
        val toolbar = createToolbar()
        
        // Layout
        add(toolbar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        
        // Load existing logs
        loadExistingLogs()
        
        // Listen for new logs
        logger.addListener { entry ->
            appendLog(entry.toString())
        }
        
        // Initial log
        logger.info("MockkHttp plugin initialized")
    }
    
    private fun createToolbar(): JPanel {
        val toolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(5)
        }
        
        // Copy button
        val copyButton = JButton("Copy All Logs").apply {
            addActionListener {
                copyLogsToClipboard()
            }
        }
        
        // Clear button
        val clearButton = JButton("Clear Logs").apply {
            addActionListener {
                clearLogs()
            }
        }
        
        // Auto-scroll checkbox
        val autoScrollCheckbox = JCheckBox("Auto-scroll", true).apply {
            addActionListener {
                // Auto-scroll is handled in appendLog
            }
        }
        
        toolbar.add(copyButton)
        toolbar.add(Box.createHorizontalStrut(5))
        toolbar.add(clearButton)
        toolbar.add(Box.createHorizontalStrut(5))
        toolbar.add(autoScrollCheckbox)
        toolbar.add(Box.createHorizontalGlue())
        
        // Add log count label
        val logCountLabel = JLabel("Logs: 0")
        toolbar.add(logCountLabel)
        
        // Update log count on log additions
        logger.addListener { _ ->
            SwingUtilities.invokeLater {
                logCountLabel.text = "Logs: ${logger.getAllLogs().size}"
            }
        }
        
        return toolbar
    }
    
    private fun loadExistingLogs() {
        val logs = logger.getAllLogs()
        if (logs.isNotEmpty()) {
            textArea.text = logs.joinToString("\n") { it.toString() }
            scrollToBottom()
        }
    }
    
    private fun appendLog(logText: String) {
        SwingUtilities.invokeLater {
            if (textArea.text.isNotEmpty()) {
                textArea.append("\n")
            }
            textArea.append(logText)
            
            // Auto-scroll to bottom
            scrollToBottom()
        }
    }
    
    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            textArea.caretPosition = textArea.document.length
        }
    }
    
    private fun copyLogsToClipboard() {
        val logs = logger.getLogsAsString()
        if (logs.isEmpty()) {
            showNotification("No logs to copy", MessageType.INFO)
            return
        }
        
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(logs), null)
            
            showNotification("Logs copied to clipboard (${logger.getAllLogs().size} entries)", MessageType.INFO)
            logger.info("Logs copied to clipboard")
        } catch (e: Exception) {
            logger.error("Failed to copy logs to clipboard", e)
            showNotification("Failed to copy logs: ${e.message}", MessageType.ERROR)
        }
    }
    
    private fun clearLogs() {
        val result = JOptionPane.showConfirmDialog(
            this,
            "Are you sure you want to clear all logs?",
            "Clear Logs",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        )
        
        if (result == JOptionPane.YES_OPTION) {
            logger.clear()
            textArea.text = ""
            logger.info("MockkHttp logs cleared")
            showNotification("Logs cleared", MessageType.INFO)
        }
    }
    
    private fun showNotification(message: String, type: MessageType) {
        JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(message, type, null)
            .setFadeoutTime(3000)
            .createBalloon()
            .show(RelativePoint.getNorthEastOf(this), Balloon.Position.atRight)
    }
}
