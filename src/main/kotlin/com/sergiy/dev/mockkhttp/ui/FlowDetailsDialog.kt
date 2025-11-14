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
import com.intellij.util.ui.JBUI
import com.sergiy.dev.mockkhttp.model.HttpFlowData
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.text.DefaultHighlighter
import javax.swing.text.Highlighter

/**
 * Dialog to show flow details with search functionality.
 */
class FlowDetailsDialog(
    project: Project,
    private val flow: HttpFlowData
) : DialogWrapper(project) {

    private lateinit var searchField: JBTextField
    private lateinit var searchPanel: JPanel
    private lateinit var matchLabel: JLabel
    private val highlighter: Highlighter.HighlightPainter =
        DefaultHighlighter.DefaultHighlightPainter(JBColor(java.awt.Color(255, 255, 0, 128), java.awt.Color(255, 255, 0, 128)))
    private var currentMatchIndex = -1
    private val matchPositions = mutableListOf<Int>()

    // All text areas for search across sections
    private val searchableTextAreas = mutableListOf<JTextArea>()

    init {
        title = "Flow Details: ${flow.request.method} ${extractPath(flow.request.url)}"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = Dimension(900, 700)

        // Create search panel (hidden by default)
        searchPanel = createSearchPanel()
        searchPanel.isVisible = false

        // Create top panel with search + mock banner
        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(searchPanel)
            if (flow.mockApplied) {
                add(createMockBanner())
            }
        }

        // Create main content panel
        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)

        // Flow Information
        contentPanel.add(createSectionHeader("Flow Information"))
        contentPanel.add(createInfoPanel())
        contentPanel.add(Box.createVerticalStrut(15))

        // Request
        contentPanel.add(createSectionHeader("Request"))
        contentPanel.add(createRequestPanel())
        contentPanel.add(Box.createVerticalStrut(15))

        // Response
        if (flow.response != null) {
            contentPanel.add(createSectionHeader("Response"))
            contentPanel.add(createResponsePanel())
        } else {
            contentPanel.add(createSectionHeader("Response"))
            contentPanel.add(createLabel("Response pending or not available", JBColor.GRAY))
        }

        val scrollPane = JBScrollPane(contentPanel).apply {
            border = JBUI.Borders.empty()
        }

        mainPanel.add(topPanel, BorderLayout.NORTH)
        mainPanel.add(scrollPane, BorderLayout.CENTER)

        // Register Cmd+F / Ctrl+F to show search
        registerFindShortcut(mainPanel)

        return mainPanel
    }

    private fun createSectionHeader(text: String): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5, 0)
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            add(JLabel(text).apply {
                font = font.deriveFont(Font.BOLD, 14f)
                foreground = JBColor.namedColor("Label.foreground", JBColor.foreground())
            }, BorderLayout.WEST)
            add(JSeparator(), BorderLayout.SOUTH)
        }
    }

    private fun createLabel(text: String, color: JBColor? = null): JLabel {
        return JLabel(text).apply {
            font = Font("Monospaced", Font.PLAIN, 12)
            if (color != null) foreground = color
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
        }
    }

    private fun createInfoPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.alignmentX = java.awt.Component.LEFT_ALIGNMENT

        panel.add(createLabel("Flow ID: ${flow.flowId}"))
        panel.add(createLabel("Timestamp: ${flow.timestamp}"))
        panel.add(createLabel("Duration: ${String.format("%.2f ms", flow.duration * 1000)}"))
        panel.add(createLabel("Paused: ${flow.paused}"))

        return panel
    }

    private fun createRequestPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.alignmentX = java.awt.Component.LEFT_ALIGNMENT

        // Method
        panel.add(createLabel("Method: ${flow.request.method}").apply {
            foreground = when (flow.request.method) {
                "GET" -> JBColor.GREEN.darker()
                "POST" -> JBColor.BLUE
                "PUT" -> JBColor.ORANGE
                "DELETE" -> JBColor.RED
                else -> JBColor.GRAY
            }
        })

        // URL
        panel.add(createLabel("URL: ${flow.request.url}"))
        panel.add(Box.createVerticalStrut(10))

        // Headers - as normal labels, not textarea
        if (flow.request.headers.isNotEmpty()) {
            panel.add(createLabel("Headers (${flow.request.headers.size}):").apply {
                font = font.deriveFont(Font.BOLD)
            })
            panel.add(Box.createVerticalStrut(5))
            flow.request.headers.forEach { (key, value) ->
                panel.add(createLabel("  $key: $value"))
            }
            panel.add(Box.createVerticalStrut(10))
        }

        // Body - ONLY this should be in a textarea
        if (flow.request.content.isNotEmpty()) {
            panel.add(createLabel("Body (${flow.request.content.length} bytes):").apply {
                font = font.deriveFont(Font.BOLD)
            })
            panel.add(Box.createVerticalStrut(5))
            val formattedContent = formatJsonIfPossible(flow.request.content)
            val bodyArea = createReadOnlyTextArea(formattedContent, rows = 15)
            panel.add(JBScrollPane(bodyArea).apply {
                maximumSize = Dimension(Int.MAX_VALUE, 400)
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty()
            })
        }

        return panel
    }

    private fun createResponsePanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.alignmentX = java.awt.Component.LEFT_ALIGNMENT

        val response = flow.response!!

        // Status
        panel.add(createLabel("Status: ${response.statusCode} ${response.reason}").apply {
            foreground = when (response.statusCode) {
                in 200..299 -> JBColor.GREEN.darker()
                in 300..399 -> JBColor.BLUE
                in 400..499 -> JBColor.ORANGE
                in 500..599 -> JBColor.RED
                else -> JBColor.GRAY
            }
            font = font.deriveFont(Font.BOLD)
        })

        panel.add(createLabel("Content-Type: ${response.getContentType() ?: "unknown"}"))
        panel.add(Box.createVerticalStrut(10))

        // Headers - as normal labels, not textarea
        if (response.headers.isNotEmpty()) {
            panel.add(createLabel("Headers (${response.headers.size}):").apply {
                font = font.deriveFont(Font.BOLD)
            })
            panel.add(Box.createVerticalStrut(5))
            response.headers.forEach { (key, value) ->
                panel.add(createLabel("  $key: $value"))
            }
            panel.add(Box.createVerticalStrut(10))
        }

        // Body - ONLY this should be in a textarea
        if (response.content.isNotEmpty()) {
            panel.add(createLabel("Body (${response.content.length} bytes):").apply {
                font = font.deriveFont(Font.BOLD)
            })
            panel.add(Box.createVerticalStrut(5))
            val formattedContent = formatJsonIfPossible(response.content)
            val bodyArea = createReadOnlyTextArea(formattedContent, rows = 20)
            panel.add(JBScrollPane(bodyArea).apply {
                maximumSize = Dimension(Int.MAX_VALUE, 500)
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty()
            })
        }

        return panel
    }

    private fun createReadOnlyTextArea(text: String, rows: Int): JTextArea {
        return JTextArea(text).apply {
            isEditable = false
            lineWrap = false
            font = Font("Monospaced", Font.PLAIN, 12)
            background = UIManager.getColor("TextArea.background")
            border = JBUI.Borders.empty(8)
            this.rows = rows
            caretPosition = 0
            searchableTextAreas.add(this)
        }
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
        panel.background = JBColor.namedColor("SearchEverywhere.SearchField.background", JBColor.PanelBackground)

        searchField = JBTextField().apply {
            emptyText.text = "Search (Enter: next, Shift+Enter: previous, Esc: close)"
            preferredSize = Dimension(500, preferredSize.height)
        }

        matchLabel = JLabel("")

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

        // Search as user types
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

        val lowerQuery = query.lowercase()

        // Search across all text areas
        for (textArea in searchableTextAreas) {
            val text = textArea.text
            val lowerText = text.lowercase()

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
        }

        if (matchPositions.isNotEmpty()) {
            currentMatchIndex = 0
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

        // Find which text area contains this match and scroll to it
        for (textArea in searchableTextAreas) {
            if (textArea.text.isNotEmpty()) {
                try {
                    textArea.caretPosition = matchPositions[index]
                    if (stealFocus) {
                        textArea.requestFocusInWindow()
                    }
                    break
                } catch (_: Exception) {
                    // Continue to next text area
                }
            }
        }
    }

    private fun clearHighlights() {
        searchableTextAreas.forEach { it.highlighter.removeAllHighlights() }
    }

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

    private fun extractPath(url: String): String {
        return try {
            java.net.URI.create(url).path
        } catch (_: Exception) {
            url
        }
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }
}
