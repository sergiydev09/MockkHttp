package com.sergiy.dev.mockkhttp.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.sergiy.dev.mockkhttp.model.HttpFlowData
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.text.DefaultHighlighter
import javax.swing.text.Highlighter

/**
 * Dialog to show complete flow details (read-only, copiable).
 * Includes search functionality with highlighting.
 */
class FlowDetailsDialog(
    project: Project,
    private val flow: HttpFlowData
) : DialogWrapper(project) {

    private lateinit var textArea: JTextArea
    private lateinit var searchField: JBTextField
    private lateinit var searchPanel: JPanel
    private lateinit var matchLabel: JLabel
    private val highlighter: Highlighter.HighlightPainter =
        DefaultHighlighter.DefaultHighlightPainter(JBColor(java.awt.Color(255, 255, 0, 128), java.awt.Color(255, 255, 0, 128)))
    private var currentMatchIndex = -1
    private val matchPositions = mutableListOf<Int>()

    init {
        title = "Flow Details: ${flow.request.method} ${flow.request.url}"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(900, 600)

        // Create text area with all flow details
        textArea = JTextArea().apply {
            isEditable = false
            font = Font("Monospaced", Font.PLAIN, 12)
            text = buildFlowDetails()
            lineWrap = false
            caretPosition = 0
        }

        val scrollPane = JBScrollPane(textArea)

        // Search panel at top (hidden by default)
        searchPanel = createSearchPanel()
        searchPanel.isVisible = false

        // Create vertical panel for search + mock banner
        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(searchPanel)

            // Add mock banner if mock was applied
            if (flow.mockApplied) {
                add(createMockBanner())
            }
        }

        panel.add(topPanel, BorderLayout.NORTH)
        panel.add(scrollPane, BorderLayout.CENTER)

        // Register Cmd+F / Ctrl+F to show search
        registerFindShortcut(panel)

        return panel
    }

    private fun createMockBanner(): JPanel {
        val banner = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.GRAY),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)
            )
            background = JBColor.namedColor("Banner.background", JBColor(0xFFF4E5, 0x4D3800))
        }

        val icon = JLabel(AllIcons.General.Information)
        val label = JLabel("Response from Mock Rule: ${flow.mockRuleName}").apply {
            foreground = JBColor.namedColor("Banner.foreground", JBColor.BLACK)
            font = font.deriveFont(Font.BOLD)
        }

        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            background = banner.background
            add(Box.createHorizontalStrut(5))
            add(icon)
            add(Box.createHorizontalStrut(10))
            add(label)
            add(Box.createHorizontalGlue())
        }

        banner.add(contentPanel, BorderLayout.CENTER)
        return banner
    }

    private fun createSearchPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
        // Use search field background color from IDE theme
        panel.background = JBColor.namedColor("SearchEverywhere.SearchField.background", JBColor.PanelBackground)

        searchField = JBTextField().apply {
            emptyText.text = "Search (Enter: next, Shift+Enter: previous, Esc: close)"
            preferredSize = Dimension(500, preferredSize.height)
        }

        matchLabel = JLabel("")

        // Layout
        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            background = panel.background
            add(Box.createHorizontalStrut(5))
            add(JLabel(AllIcons.Actions.Find))
            add(Box.createHorizontalStrut(5))
            add(searchField)
            add(Box.createHorizontalStrut(10))
            add(matchLabel)
            add(Box.createHorizontalGlue())
        }

        panel.add(contentPanel, BorderLayout.CENTER)

        // Perform search as user types
        searchField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) {
                performSearch()
                updateMatchLabel()
            }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) {
                if (searchField.text.isEmpty()) {
                    clearHighlights()
                    matchPositions.clear()
                    currentMatchIndex = -1
                    updateMatchLabel()
                } else {
                    performSearch()
                    updateMatchLabel()
                }
            }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) {}
        })

        // Enter = next, Shift+Enter = previous, Esc = close
        searchField.addActionListener {
            if (matchPositions.isNotEmpty()) {
                findNext()
            }
        }

        searchField.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "findPrevious")
        searchField.actionMap.put("findPrevious", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                if (matchPositions.isNotEmpty()) {
                    findPrevious()
                }
            }
        })

        searchField.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeSearch")
        searchField.actionMap.put("closeSearch", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                hideSearch()
            }
        })

        return panel
    }

    private fun updateMatchLabel() {
        matchLabel.text = if (matchPositions.isNotEmpty()) {
            "${currentMatchIndex + 1}/${matchPositions.size}"
        } else if (searchField.text.isNotEmpty()) {
            "No matches"
        } else {
            ""
        }
    }

    private fun registerFindShortcut(panel: JPanel) {
        val findAction = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                showSearch()
            }
        }

        // Cmd+F on Mac, Ctrl+F on Windows/Linux
        val shortcut = KeyStroke.getKeyStroke(
            KeyEvent.VK_F,
            java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        )
        findAction.registerCustomShortcutSet(CustomShortcutSet(shortcut), panel)
    }

    private fun showSearch() {
        searchPanel.isVisible = true
        searchField.text = ""
        searchField.requestFocusInWindow()
    }

    private fun hideSearch() {
        searchPanel.isVisible = false
        clearHighlights()
        matchPositions.clear()
        currentMatchIndex = -1
        textArea.requestFocusInWindow()
    }

    private fun performSearch() {
        val query = searchField.text
        if (query.isEmpty()) {
            clearHighlights()
            matchPositions.clear()
            currentMatchIndex = -1
            return
        }

        clearHighlights()
        matchPositions.clear()
        currentMatchIndex = -1

        val text = textArea.text
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()

        var index = lowerText.indexOf(lowerQuery)
        while (index >= 0) {
            matchPositions.add(index)
            try {
                textArea.highlighter.addHighlight(index, index + query.length, highlighter)
            } catch (_: Exception) {
                // Ignore highlighting errors
            }
            index = lowerText.indexOf(lowerQuery, index + 1)
        }

        if (matchPositions.isNotEmpty()) {
            currentMatchIndex = 0
            // Scroll to first match but don't steal focus from search field
            scrollToMatch(0, stealFocus = false)
        }
    }

    private fun findNext() {
        if (matchPositions.isEmpty()) return
        currentMatchIndex = (currentMatchIndex + 1) % matchPositions.size
        scrollToMatch(currentMatchIndex, stealFocus = true)
        updateMatchLabel()
    }

    private fun findPrevious() {
        if (matchPositions.isEmpty()) return
        currentMatchIndex = if (currentMatchIndex <= 0) matchPositions.size - 1 else currentMatchIndex - 1
        scrollToMatch(currentMatchIndex, stealFocus = true)
        updateMatchLabel()
    }

    private fun scrollToMatch(index: Int, stealFocus: Boolean = false) {
        if (index < 0 || index >= matchPositions.size) return
        val position = matchPositions[index]
        textArea.caretPosition = position
        if (stealFocus) {
            textArea.requestFocusInWindow()
        }
    }

    private fun clearHighlights() {
        textArea.highlighter.removeAllHighlights()
    }

    private fun buildFlowDetails(): String {
        val sb = StringBuilder()

        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine("FLOW INFORMATION")
        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine()
        sb.appendLine("Flow ID: ${flow.flowId}")
        sb.appendLine("Timestamp: ${flow.timestamp}")
        sb.appendLine("Duration: ${String.format("%.2f", flow.duration * 1000)} ms")
        sb.appendLine("Paused: ${flow.paused}")
        sb.appendLine()

        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine("REQUEST")
        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine()
        sb.appendLine("${flow.request.method} ${flow.request.path}")
        sb.appendLine("Host: ${flow.request.host}")
        sb.appendLine("URL: ${flow.request.url}")
        sb.appendLine()

        if (flow.request.headers.isNotEmpty()) {
            sb.appendLine("Headers:")
            flow.request.headers.forEach { (key, value) ->
                sb.appendLine("  $key: $value")
            }
            sb.appendLine()
        }

        if (flow.request.content.isNotEmpty()) {
            sb.appendLine("Body:")
            sb.appendLine(formatJsonIfPossible(flow.request.content))
            sb.appendLine()
        }

        if (flow.response != null) {
            sb.appendLine("═══════════════════════════════════════════════════════════")
            sb.appendLine("RESPONSE")
            sb.appendLine("═══════════════════════════════════════════════════════════")
            sb.appendLine()
            sb.appendLine("Status: ${flow.response.statusCode} ${flow.response.reason}")
            sb.appendLine("Content-Type: ${flow.response.getContentType()}")
            sb.appendLine()

            if (flow.response.headers.isNotEmpty()) {
                sb.appendLine("Headers:")
                flow.response.headers.forEach { (key, value) ->
                    sb.appendLine("  $key: $value")
                }
                sb.appendLine()
            }

            if (flow.response.content.isNotEmpty()) {
                sb.appendLine("Body:")
                sb.appendLine(formatJsonIfPossible(flow.response.content))
                sb.appendLine()
            }
        } else {
            sb.appendLine()
            sb.appendLine("═══════════════════════════════════════════════════════════")
            sb.appendLine("RESPONSE: Pending...")
            sb.appendLine("═══════════════════════════════════════════════════════════")
        }

        return sb.toString()
    }

    /**
     * Format JSON with pretty printing if valid, otherwise return as-is.
     */
    private fun formatJsonIfPossible(content: String): String {
        if (content.isBlank()) return content

        return try {
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            val jsonElement = com.google.gson.JsonParser.parseString(content)
            gson.toJson(jsonElement)
        } catch (_: Exception) {
            // Not valid JSON, return original
            content
        }
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }
}
