package com.sergiy.dev.mockkhttp.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.text.DefaultHighlighter

/**
 * Panel that displays all plugin logs with a copy button.
 * Auto-scrolls to the bottom when new logs arrive.
 * Includes search with yellow highlighting and match navigation.
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

    // Search state
    private val searchField: JBTextField
    private val matchCountLabel: JLabel
    private val prevButton: JButton
    private val nextButton: JButton
    private var searchMatches = mutableListOf<Int>()
    private var currentMatchIndex = -1
    private var autoScrollEnabled = true

    private val matchPainter = DefaultHighlighter.DefaultHighlightPainter(
        JBColor(Color(255, 255, 0), Color(100, 100, 0))
    )
    private val currentMatchPainter = DefaultHighlighter.DefaultHighlightPainter(
        JBColor(Color(255, 165, 0), Color(180, 120, 0))
    )

    init {
        // Init search components
        matchCountLabel = JLabel("").apply {
            foreground = JBColor.GRAY
        }

        prevButton = JButton(AllIcons.Actions.PreviousOccurence).apply {
            toolTipText = "Previous match (Shift+Enter)"
            isEnabled = false
            addActionListener { navigatePrev() }
        }

        nextButton = JButton(AllIcons.Actions.NextOccurence).apply {
            toolTipText = "Next match (Enter)"
            isEnabled = false
            addActionListener { navigateNext() }
        }

        searchField = JBTextField(18).apply {
            emptyText.text = "Search logs..."

            document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = onSearchChanged()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = onSearchChanged()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = onSearchChanged()
            })

            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when {
                        e.keyCode == KeyEvent.VK_ENTER && e.isShiftDown -> {
                            navigatePrev()
                            e.consume()
                        }
                        e.keyCode == KeyEvent.VK_ENTER -> {
                            navigateNext()
                            e.consume()
                        }
                        e.keyCode == KeyEvent.VK_ESCAPE -> {
                            text = ""
                            textArea.requestFocusInWindow()
                            e.consume()
                        }
                    }
                }
            })
        }

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

        // When logs are cleared from ANYWHERE (this tab's button, Settings → Cache),
        // release the text area's copy too — it was the dominant log memory holder
        logger.addClearListener {
            textArea.text = ""
            clearHighlights()
            searchMatches.clear()
            currentMatchIndex = -1
            matchCountLabel.text = if (searchField.text.isNotBlank()) "0 results" else ""
        }

        // Cmd+F shortcut on the textArea to focus search
        textArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if ((e.isMetaDown || e.isControlDown) && e.keyCode == KeyEvent.VK_F) {
                    searchField.requestFocusInWindow()
                    searchField.selectAll()
                    e.consume()
                }
            }
        })

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
            addActionListener { copyLogsToClipboard() }
        }

        // Clear button
        val clearButton = JButton("Clear Logs").apply {
            addActionListener { clearLogs() }
        }

        // Auto-scroll checkbox
        val autoScrollCheckbox = JCheckBox("Auto-scroll", true).apply {
            addActionListener {
                autoScrollEnabled = isSelected
                if (autoScrollEnabled && searchField.text.isBlank()) {
                    scrollToBottom()
                }
            }
        }

        // Log count label
        val logCountLabel = JLabel("Logs: 0")
        logger.addListener { _ ->
            SwingUtilities.invokeLater {
                logCountLabel.text = "Logs: ${logger.getAllLogs().size}"
            }
        }

        // Assemble toolbar
        toolbar.add(copyButton)
        toolbar.add(Box.createHorizontalStrut(5))
        toolbar.add(clearButton)
        toolbar.add(Box.createHorizontalStrut(5))
        toolbar.add(autoScrollCheckbox)
        toolbar.add(Box.createHorizontalStrut(15))

        // Search section
        toolbar.add(JLabel("Search: "))
        toolbar.add(Box.createHorizontalStrut(3))
        toolbar.add(searchField)
        toolbar.add(Box.createHorizontalStrut(3))
        toolbar.add(prevButton)
        toolbar.add(nextButton)
        toolbar.add(Box.createHorizontalStrut(5))
        toolbar.add(matchCountLabel)

        toolbar.add(Box.createHorizontalGlue())
        toolbar.add(logCountLabel)
        toolbar.add(Box.createHorizontalStrut(10))

        return toolbar
    }

    private fun onSearchChanged() {
        SwingUtilities.invokeLater {
            val query = searchField.text
            clearHighlights()
            searchMatches.clear()
            currentMatchIndex = -1

            if (query.isBlank()) {
                matchCountLabel.text = ""
                prevButton.isEnabled = false
                nextButton.isEnabled = false
                if (autoScrollEnabled) scrollToBottom()
                return@invokeLater
            }

            // Find all matches (case-insensitive)
            val text = textArea.text
            val lowerText = text.lowercase()
            val lowerQuery = query.lowercase()
            var idx = lowerText.indexOf(lowerQuery)
            while (idx >= 0) {
                searchMatches.add(idx)
                idx = lowerText.indexOf(lowerQuery, idx + 1)
            }

            // Highlight all matches in yellow
            for (pos in searchMatches) {
                try {
                    textArea.highlighter.addHighlight(pos, pos + query.length, matchPainter)
                } catch (_: Exception) {}
            }

            val hasMatches = searchMatches.isNotEmpty()
            prevButton.isEnabled = hasMatches
            nextButton.isEnabled = hasMatches

            if (hasMatches) {
                currentMatchIndex = 0
                refreshHighlights()
                scrollToMatch(0)
                matchCountLabel.text = "1/${searchMatches.size}"
                matchCountLabel.foreground = JBColor.foreground()
            } else {
                matchCountLabel.text = "0 results"
                matchCountLabel.foreground = JBColor.RED
            }
        }
    }

    private fun navigateNext() {
        if (searchMatches.isEmpty()) return
        currentMatchIndex = (currentMatchIndex + 1) % searchMatches.size
        refreshHighlights()
        scrollToMatch(currentMatchIndex)
        matchCountLabel.text = "${currentMatchIndex + 1}/${searchMatches.size}"
    }

    private fun navigatePrev() {
        if (searchMatches.isEmpty()) return
        currentMatchIndex = if (currentMatchIndex - 1 < 0) searchMatches.size - 1 else currentMatchIndex - 1
        refreshHighlights()
        scrollToMatch(currentMatchIndex)
        matchCountLabel.text = "${currentMatchIndex + 1}/${searchMatches.size}"
    }

    private fun refreshHighlights() {
        val query = searchField.text
        if (query.isBlank()) return

        textArea.highlighter.removeAllHighlights()
        for ((i, pos) in searchMatches.withIndex()) {
            try {
                val painter = if (i == currentMatchIndex) currentMatchPainter else matchPainter
                textArea.highlighter.addHighlight(pos, pos + query.length, painter)
            } catch (_: Exception) {}
        }
    }

    private fun scrollToMatch(index: Int) {
        if (index < 0 || index >= searchMatches.size) return
        try {
            val pos = searchMatches[index]
            // modelToView2D replaces the deprecated modelToView(int); .bounds gives a mutable Rectangle.
            val rect = textArea.modelToView2D(pos)?.bounds ?: return
            // Expand rect so the match is centered vertically
            val viewportHeight = scrollPane.viewport.height
            rect.y = maxOf(0, rect.y - viewportHeight / 2)
            rect.height = viewportHeight
            textArea.scrollRectToVisible(rect)
        } catch (_: Exception) {}
    }

    private fun clearHighlights() {
        textArea.highlighter.removeAllHighlights()
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
            // Bound the text area document: the logger caps at 1000 entries but this
            // UI text used to grow unbounded over long sessions
            if (textArea.document.length > 2_000_000) {
                textArea.replaceRange(null, 0, textArea.document.length - 1_000_000)
            }
            if (textArea.text.isNotEmpty()) {
                textArea.append("\n")
            }
            textArea.append(logText)

            // Re-apply search highlights if active
            if (searchField.text.isNotBlank()) {
                onSearchChanged()
            } else if (autoScrollEnabled) {
                scrollToBottom()
            }
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
            clearHighlights()
            searchMatches.clear()
            currentMatchIndex = -1
            matchCountLabel.text = if (searchField.text.isNotBlank()) "0 results" else ""
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
