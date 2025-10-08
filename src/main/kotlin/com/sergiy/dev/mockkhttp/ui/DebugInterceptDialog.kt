package com.sergiy.dev.mockkhttp.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.sergiy.dev.mockkhttp.model.HttpFlowData
import com.sergiy.dev.mockkhttp.model.ModifiedResponseData
import java.awt.*
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.event.DocumentListener
import javax.swing.event.DocumentEvent
import javax.swing.text.DefaultHighlighter
import javax.swing.text.Highlighter

/**
 * Dialog for intercepting and modifying HTTP responses in Debug Mode.
 * Shows request/response details and allows editing before forwarding to app.
 * Horizontal layout: Request (left) | Response (right, editable)
 */
class DebugInterceptDialog(
    project: Project,
    private val flow: HttpFlowData
) : DialogWrapper(project) {

    private val mockkRulesStore = com.sergiy.dev.mockkhttp.store.MockkRulesStore.getInstance(project)

    // Response editing fields
    private val statusCodeField: JTextField
    private val headersTextArea: JTextArea
    private val bodyTextArea: JTextArea

    // Save as Mockk Rule checkbox, name field, and collection selector
    private val saveAsMockkRuleCheckbox: JCheckBox
    private val mockRuleNameField: JBTextField
    private val collectionComboBox: ComboBox<com.sergiy.dev.mockkhttp.model.MockkCollection>
    private val newCollectionButton: JButton

    // Flag to track which action was taken
    private var actionTaken: ActionType = ActionType.CANCEL

    // Track if response was modified
    private var responseModified = false
    private lateinit var continueModifiedAction: ContinueModifiedAction

    // Search functionality for Request panel
    private lateinit var requestTextArea: JTextArea
    private lateinit var requestSearchField: JBTextField
    private lateinit var requestSearchPanel: JPanel
    private lateinit var requestMatchLabel: JLabel
    private var requestCurrentMatchIndex = -1
    private val requestMatchPositions = mutableListOf<Int>()

    // Search functionality for Response panel (searches across all editable fields)
    private lateinit var responseSearchField: JBTextField
    private lateinit var responseSearchPanel: JPanel
    private lateinit var responseMatchLabel: JLabel
    private var responseCurrentMatchIndex = -1
    private val responseMatchPositions = mutableListOf<Pair<JTextArea, Int>>() // Track (field, position)

    private val highlighter: Highlighter.HighlightPainter =
        DefaultHighlighter.DefaultHighlightPainter(JBColor.YELLOW)

    enum class ActionType {
        CONTINUE_REMOTE,    // Use original response
        CONTINUE_MODIFIED,  // Use modified response
        CANCEL             // Cancel request
    }

    init {
        title = "Debug Intercept: ${flow.request.method} ${flow.request.getShortUrl()}"

        // Initialize editing fields with original values
        statusCodeField = JTextField(flow.response?.statusCode?.toString() ?: "200")
        statusCodeField.font = Font("Monospaced", Font.PLAIN, 14)

        headersTextArea = JTextArea(15, 60)
        headersTextArea.font = Font("Monospaced", Font.PLAIN, 12)
        headersTextArea.text = formatHeaders(flow.response?.headers)

        bodyTextArea = JTextArea(20, 60)
        bodyTextArea.font = Font("Monospaced", Font.PLAIN, 12)
        bodyTextArea.text = formatJsonIfPossible(flow.response?.content ?: "")
        bodyTextArea.lineWrap = false

        // Initialize Save as Mockk Rule checkbox and name field
        saveAsMockkRuleCheckbox = JCheckBox("Save as Mockk Rule", AllIcons.Actions.MenuSaveall)
        saveAsMockkRuleCheckbox.toolTipText = "Save this response as a reusable mock rule (works with both buttons)"

        mockRuleNameField = JBTextField().apply {
            emptyText.text = "${flow.request.method} ${flow.request.getShortUrl()}"
            isEnabled = false // Disabled until checkbox is checked
        }

        // Initialize collection selector
        collectionComboBox = ComboBox<com.sergiy.dev.mockkhttp.model.MockkCollection>().apply {
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int,
                    isSelected: Boolean, cellHasFocus: Boolean
                ): Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value is com.sergiy.dev.mockkhttp.model.MockkCollection) {
                        text = "${value.name}${if (value.packageName.isNotEmpty()) " [${value.packageName}]" else ""}"
                    }
                    return this
                }
            }
            isEnabled = false

            // Load collections
            mockkRulesStore.getAllCollections().forEach { addItem(it) }

            // Pre-select first if available
            if (itemCount > 0) {
                selectedIndex = 0
            }
        }

        newCollectionButton = JButton("New", AllIcons.General.Add).apply {
            toolTipText = "Create a new collection"
            isEnabled = false
            addActionListener { createNewCollectionInline() }
        }

        // Enable/disable fields based on checkbox
        saveAsMockkRuleCheckbox.addItemListener {
            val enabled = saveAsMockkRuleCheckbox.isSelected
            mockRuleNameField.isEnabled = enabled
            collectionComboBox.isEnabled = enabled
            newCollectionButton.isEnabled = enabled
        }

        init()

        // Add listeners to track modifications AFTER init() so we have the button reference
        addModificationListeners()

    }

    private fun addModificationListeners() {
        val docListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) { onResponseModified() }
            override fun removeUpdate(e: DocumentEvent?) { onResponseModified() }
            override fun changedUpdate(e: DocumentEvent?) { onResponseModified() }
        }

        statusCodeField.document.addDocumentListener(docListener)
        headersTextArea.document.addDocumentListener(docListener)
        bodyTextArea.document.addDocumentListener(docListener)
    }

    private fun onResponseModified() {
        if (!responseModified) {
            responseModified = true
            // Enable the button by calling setEnabled on the action
            continueModifiedAction.setEnabled(true)
        }
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = Dimension(1200, 700)

        // Add warning label at top
        val warningPanel = JPanel(BorderLayout())
        warningPanel.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
        val warningLabel = JLabel("This request is paused. Edit the response and choose an action.", AllIcons.General.Warning, SwingConstants.LEFT)
        warningLabel.foreground = JBColor.ORANGE
        warningPanel.add(warningLabel, BorderLayout.CENTER)
        mainPanel.add(warningPanel, BorderLayout.NORTH)

        // Create horizontal split: Request (left) | Response (right)
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, createRequestPanel(), createResponsePanel())
        splitPane.resizeWeight = 0.4  // 40% for request, 60% for response
        splitPane.dividerLocation = 480

        mainPanel.add(splitPane, BorderLayout.CENTER)

        return mainPanel
    }

    /**
     * Create request panel (read-only).
     */
    private fun createRequestPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("📤 Request (Read-Only)")

        requestTextArea = JTextArea()
        requestTextArea.isEditable = false
        requestTextArea.font = Font("Monospaced", Font.PLAIN, 12)
        requestTextArea.text = buildRequestDetails()

        // Search panel (hidden by default)
        requestSearchPanel = createRequestSearchPanel()
        requestSearchPanel.isVisible = false

        val scrollPane = JBScrollPane(requestTextArea)

        panel.add(requestSearchPanel, BorderLayout.NORTH)
        panel.add(scrollPane, BorderLayout.CENTER)

        // Register Cmd+F / Ctrl+F to show search
        registerRequestFindShortcut(panel)

        return panel
    }

    /**
     * Create response panel (editable).
     */
    private fun createResponsePanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("📥 Response (Editable)")

        // Search panel (hidden by default)
        responseSearchPanel = createResponseSearchPanel()
        responseSearchPanel.isVisible = false

        // Status code panel
        val statusPanel = JPanel(BorderLayout())
        statusPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(5, 5, 5, 5),
            BorderFactory.createTitledBorder("Status Code")
        )
        statusPanel.add(statusCodeField, BorderLayout.NORTH)

        // Headers panel
        val headersPanel = JPanel(BorderLayout())
        headersPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(5, 5, 5, 5),
            BorderFactory.createTitledBorder("Headers (one per line: Key: Value)")
        )
        headersPanel.add(JBScrollPane(headersTextArea), BorderLayout.CENTER)

        // Body panel
        val bodyPanel = JPanel(BorderLayout())
        bodyPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(5, 5, 5, 5),
            BorderFactory.createTitledBorder("Body")
        )
        bodyPanel.add(JBScrollPane(bodyTextArea), BorderLayout.CENTER)

        // Combine panels
        val topPanel = JPanel(BorderLayout())
        topPanel.add(statusPanel, BorderLayout.NORTH)
        topPanel.add(headersPanel, BorderLayout.CENTER)

        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, topPanel, bodyPanel)
        splitPane.resizeWeight = 0.3

        // Create main content with search on top
        val contentPanel = JPanel(BorderLayout())
        contentPanel.add(responseSearchPanel, BorderLayout.NORTH)
        contentPanel.add(splitPane, BorderLayout.CENTER)

        panel.add(contentPanel, BorderLayout.CENTER)

        // Register Cmd+F / Ctrl+F to show search
        registerResponseFindShortcut(panel)

        return panel
    }

    override fun createActions(): Array<Action> {
        continueModifiedAction = ContinueModifiedAction()
        return arrayOf(
            ContinueRemoteAction(),
            continueModifiedAction,
            cancelAction
        )
    }

    override fun createSouthPanel(): JComponent {
        val southPanel = super.createSouthPanel()

        // Create a wrapper panel to add the checkbox
        val wrapperPanel = JPanel(BorderLayout())
        wrapperPanel.add(southPanel, BorderLayout.CENTER)

        // Add checkbox, name field, collection selector, and new collection button to the left
        val checkboxPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 5)).apply {
            add(saveAsMockkRuleCheckbox)
            add(JLabel("Name:"))
            add(mockRuleNameField)
            add(JLabel("Collection:"))
            add(collectionComboBox)
            add(newCollectionButton)
        }
        mockRuleNameField.preferredSize = Dimension(250, mockRuleNameField.preferredSize.height)
        collectionComboBox.preferredSize = Dimension(200, collectionComboBox.preferredSize.height)
        wrapperPanel.add(checkboxPanel, BorderLayout.WEST)

        return wrapperPanel
    }

    /**
     * Action to continue with remote (original) response.
     */
    private inner class ContinueRemoteAction : DialogWrapperAction(
        if (flow.mockApplied) "Continue with Mockk Response" else "Continue with Remote Response"
    ) {
        init {
            val description = if (flow.mockApplied) {
                "Forward the mocked response (from mock rule: ${flow.mockRuleName})"
            } else {
                "Forward the original response from the server"
            }
            putValue(SHORT_DESCRIPTION, description)
        }

        override fun doAction(e: java.awt.event.ActionEvent?) {
            val responseType = if (flow.mockApplied) "mocked" else "remote"
            actionTaken = ActionType.CONTINUE_REMOTE
            close(OK_EXIT_CODE)
        }
    }

    /**
     * Action to continue with modified response.
     * Only enabled after response has been modified.
     */
    private inner class ContinueModifiedAction : DialogWrapperAction("Continue with Modified Response") {
        init {
            putValue(SHORT_DESCRIPTION, "Forward the modified response to the app (enabled after editing)")
            putValue(DEFAULT_ACTION, true)
            // Start disabled, will be enabled when user modifies the response
            isEnabled = false
        }

        override fun doAction(e: java.awt.event.ActionEvent?) {
            actionTaken = ActionType.CONTINUE_MODIFIED
            close(OK_EXIT_CODE)
        }
    }

    /**
     * Get modified response data if user chose to continue with modified response.
     */
    fun getModifiedResponse(): ModifiedResponseData? {
        if (actionTaken != ActionType.CONTINUE_MODIFIED) {
            return null
        }

        return try {
            val statusCode = statusCodeField.text.toIntOrNull() ?: flow.response?.statusCode ?: 200
            val headers = parseHeaders(headersTextArea.text)
            val content = bodyTextArea.text

            ModifiedResponseData(
                statusCode = statusCode,
                headers = headers,
                content = content
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if user wants to save this as a Mockk rule.
     * Works with both Remote and Modified response buttons.
     */
    fun shouldSaveAsMock(): Boolean {
        return saveAsMockkRuleCheckbox.isSelected && actionTaken != ActionType.CANCEL
    }

    /**
     * Get the name for the mock rule.
     */
    fun getMockRuleName(): String {
        return mockRuleNameField.text.ifBlank {
            "${flow.request.method} ${flow.request.getShortUrl()}"
        }
    }

    /**
     * Get the selected collection for saving the mock.
     */
    fun getSelectedCollection(): com.sergiy.dev.mockkhttp.model.MockkCollection? {
        return collectionComboBox.selectedItem as? com.sergiy.dev.mockkhttp.model.MockkCollection
    }

    /**
     * Create a new collection inline (without leaving the dialog).
     */
    private fun createNewCollectionInline() {
        val name = Messages.showInputDialog(
            this.contentPane,
            "Enter collection name:",
            "New Collection",
            Messages.getQuestionIcon(),
            "",
            null
        ) ?: return

        if (name.isBlank()) {
            Messages.showErrorDialog(this.contentPane, "Collection name cannot be empty", "Invalid Name")
            return
        }

        val description = Messages.showInputDialog(
            this.contentPane,
            "Enter collection description (optional):",
            "Collection Description",
            Messages.getQuestionIcon(),
            "",
            null
        ) ?: ""

        // Create collection (no package filter in debug dialog - user can specify in CreateMockDialog later)
        val newCollection = mockkRulesStore.addCollection(name, "", description)

        // Add to combo box and select it
        collectionComboBox.addItem(newCollection)
        collectionComboBox.selectedItem = newCollection
    }

    /**
     * Build request details string.
     */
    private fun buildRequestDetails(): String {
        val sb = StringBuilder()

        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine("REQUEST")
        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine()
        sb.appendLine("${flow.request.method} ${flow.request.path}")
        sb.appendLine("Host: ${flow.request.host}")
        sb.appendLine()
        sb.appendLine("Headers:")
        flow.request.headers.forEach { (key, value) ->
            sb.appendLine("  $key: $value")
        }

        if (flow.request.content.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Body:")
            sb.appendLine(flow.request.content)
        }

        return sb.toString()
    }

    /**
     * Format headers map to string.
     */
    private fun formatHeaders(headers: Map<String, String>?): String {
        if (headers == null) return ""
        return headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    }

    /**
     * Parse headers string to map.
     */
    private fun parseHeaders(text: String): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        text.lines().forEach { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                headers[parts[0].trim()] = parts[1].trim()
            }
        }
        return headers
    }

    /**
     * Try to format content as JSON if it's valid JSON, otherwise return as-is.
     */
    private fun formatJsonIfPossible(content: String): String {
        if (content.isBlank()) return content

        return try {
            // Try to parse and pretty-print JSON
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            val jsonElement = com.google.gson.JsonParser.parseString(content)
            gson.toJson(jsonElement)
        } catch (_: Exception) {
            // Not valid JSON, return original
            content
        }
    }

    // ========== Request Search functionality ==========

    private fun createRequestSearchPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
        // Use theme-aware background color
        panel.background = JBColor.namedColor("SearchEverywhere.SearchField.background", JBColor.PanelBackground)

        requestSearchField = JBTextField().apply {
            emptyText.text = "Search (Enter: next, Shift+Enter: previous, Esc: close)"
            preferredSize = Dimension(500, preferredSize.height)
        }

        requestMatchLabel = JLabel("")

        // Layout
        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            background = panel.background
            add(Box.createHorizontalStrut(5))
            add(JLabel(AllIcons.Actions.Find))
            add(Box.createHorizontalStrut(5))
            add(requestSearchField)
            add(Box.createHorizontalStrut(10))
            add(requestMatchLabel)
            add(Box.createHorizontalGlue())
        }

        panel.add(contentPanel, BorderLayout.CENTER)

        // Perform search as user types
        requestSearchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) {
                performRequestSearch()
                updateRequestMatchLabel()
            }
            override fun removeUpdate(e: DocumentEvent?) {
                if (requestSearchField.text.isEmpty()) {
                    clearRequestHighlights()
                    requestMatchPositions.clear()
                    requestCurrentMatchIndex = -1
                    updateRequestMatchLabel()
                } else {
                    performRequestSearch()
                    updateRequestMatchLabel()
                }
            }
            override fun changedUpdate(e: DocumentEvent?) {}
        })

        // Enter = next, Shift+Enter = previous, Esc = close
        requestSearchField.addActionListener {
            if (requestMatchPositions.isNotEmpty()) {
                findRequestNext()
            }
        }

        requestSearchField.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "findPrevious")
        requestSearchField.actionMap.put("findPrevious", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                if (requestMatchPositions.isNotEmpty()) {
                    findRequestPrevious()
                }
            }
        })

        requestSearchField.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeSearch")
        requestSearchField.actionMap.put("closeSearch", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                hideRequestSearch()
            }
        })

        return panel
    }

    private fun registerRequestFindShortcut(panel: JPanel) {
        val findAction = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                showRequestSearch()
            }
        }

        // Cmd+F on Mac, Ctrl+F on Windows/Linux
        val shortcut = KeyStroke.getKeyStroke(
            KeyEvent.VK_F,
            Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        )
        findAction.registerCustomShortcutSet(CustomShortcutSet(shortcut), panel)
    }

    private fun showRequestSearch() {
        requestSearchPanel.isVisible = true
        requestSearchField.text = ""
        requestSearchField.requestFocusInWindow()
    }

    private fun hideRequestSearch() {
        requestSearchPanel.isVisible = false
        clearRequestHighlights()
        requestMatchPositions.clear()
        requestCurrentMatchIndex = -1
        requestTextArea.requestFocusInWindow()
    }

    private fun updateRequestMatchLabel() {
        requestMatchLabel.text = if (requestMatchPositions.isNotEmpty()) {
            "${requestCurrentMatchIndex + 1}/${requestMatchPositions.size}"
        } else if (requestSearchField.text.isNotEmpty()) {
            "No matches"
        } else {
            ""
        }
    }

    private fun performRequestSearch() {
        val query = requestSearchField.text
        if (query.isEmpty()) {
            clearRequestHighlights()
            requestMatchPositions.clear()
            requestCurrentMatchIndex = -1
            return
        }

        clearRequestHighlights()
        requestMatchPositions.clear()
        requestCurrentMatchIndex = -1

        val text = requestTextArea.text
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()

        var index = lowerText.indexOf(lowerQuery)
        while (index >= 0) {
            requestMatchPositions.add(index)
            try {
                requestTextArea.highlighter.addHighlight(index, index + query.length, highlighter)
            } catch (_: Exception) {
                // Ignore highlighting errors
            }
            index = lowerText.indexOf(lowerQuery, index + 1)
        }

        if (requestMatchPositions.isNotEmpty()) {
            requestCurrentMatchIndex = 0
            // Scroll to first match but don't steal focus from search field
            scrollToRequestMatch(0, stealFocus = false)
        }
    }

    private fun findRequestNext() {
        if (requestMatchPositions.isEmpty()) return
        requestCurrentMatchIndex = (requestCurrentMatchIndex + 1) % requestMatchPositions.size
        scrollToRequestMatch(requestCurrentMatchIndex, stealFocus = true)
        updateRequestMatchLabel()
    }

    private fun findRequestPrevious() {
        if (requestMatchPositions.isEmpty()) return
        requestCurrentMatchIndex = if (requestCurrentMatchIndex <= 0) requestMatchPositions.size - 1 else requestCurrentMatchIndex - 1
        scrollToRequestMatch(requestCurrentMatchIndex, stealFocus = true)
        updateRequestMatchLabel()
    }

    private fun scrollToRequestMatch(index: Int, stealFocus: Boolean = false) {
        if (index < 0 || index >= requestMatchPositions.size) return
        val position = requestMatchPositions[index]
        requestTextArea.caretPosition = position
        if (stealFocus) {
            requestTextArea.requestFocusInWindow()
        }
    }

    private fun clearRequestHighlights() {
        requestTextArea.highlighter.removeAllHighlights()
    }

    // ========== Response Search functionality ==========

    private fun createResponseSearchPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
        // Use theme-aware background color
        panel.background = JBColor.namedColor("SearchEverywhere.SearchField.background", JBColor.PanelBackground)

        responseSearchField = JBTextField().apply {
            emptyText.text = "Search across status, headers, and body (Enter: next, Shift+Enter: previous, Esc: close)"
            preferredSize = Dimension(500, preferredSize.height)
        }

        responseMatchLabel = JLabel("")

        // Layout
        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            background = panel.background
            add(Box.createHorizontalStrut(5))
            add(JLabel(AllIcons.Actions.Find))
            add(Box.createHorizontalStrut(5))
            add(responseSearchField)
            add(Box.createHorizontalStrut(10))
            add(responseMatchLabel)
            add(Box.createHorizontalGlue())
        }

        panel.add(contentPanel, BorderLayout.CENTER)

        // Perform search as user types
        responseSearchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) {
                performResponseSearch()
                updateResponseMatchLabel()
            }
            override fun removeUpdate(e: DocumentEvent?) {
                if (responseSearchField.text.isEmpty()) {
                    clearResponseHighlights()
                    responseMatchPositions.clear()
                    responseCurrentMatchIndex = -1
                    updateResponseMatchLabel()
                } else {
                    performResponseSearch()
                    updateResponseMatchLabel()
                }
            }
            override fun changedUpdate(e: DocumentEvent?) {}
        })

        // Enter = next, Shift+Enter = previous, Esc = close
        responseSearchField.addActionListener {
            if (responseMatchPositions.isNotEmpty()) {
                findResponseNext()
            }
        }

        responseSearchField.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "findPrevious")
        responseSearchField.actionMap.put("findPrevious", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                if (responseMatchPositions.isNotEmpty()) {
                    findResponsePrevious()
                }
            }
        })

        responseSearchField.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeSearch")
        responseSearchField.actionMap.put("closeSearch", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                hideResponseSearch()
            }
        })

        return panel
    }

    private fun registerResponseFindShortcut(panel: JPanel) {
        val findAction = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                showResponseSearch()
            }
        }

        // Cmd+F on Mac, Ctrl+F on Windows/Linux
        val shortcut = KeyStroke.getKeyStroke(
            KeyEvent.VK_F,
            Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        )
        findAction.registerCustomShortcutSet(CustomShortcutSet(shortcut), panel)
    }

    private fun showResponseSearch() {
        responseSearchPanel.isVisible = true
        responseSearchField.text = ""
        responseSearchField.requestFocusInWindow()
    }

    private fun hideResponseSearch() {
        responseSearchPanel.isVisible = false
        clearResponseHighlights()
        responseMatchPositions.clear()
        responseCurrentMatchIndex = -1
    }

    private fun updateResponseMatchLabel() {
        responseMatchLabel.text = if (responseMatchPositions.isNotEmpty()) {
            "${responseCurrentMatchIndex + 1}/${responseMatchPositions.size}"
        } else if (responseSearchField.text.isNotEmpty()) {
            "No matches"
        } else {
            ""
        }
    }

    /**
     * Search across all response fields (status, headers, body).
     */
    private fun performResponseSearch() {
        val query = responseSearchField.text
        if (query.isEmpty()) {
            clearResponseHighlights()
            responseMatchPositions.clear()
            responseCurrentMatchIndex = -1
            return
        }

        clearResponseHighlights()
        responseMatchPositions.clear()
        responseCurrentMatchIndex = -1

        val lowerQuery = query.lowercase()

        // Search in all editable fields
        val fieldsToSearch = listOf(
            headersTextArea,
            bodyTextArea
        )

        // Note: Status code field (JTextField) not included in search
        // as it's difficult to highlight and typically not needed

        // Search through text areas
        for (textArea in fieldsToSearch) {
            val text = textArea.text
            val lowerText = text.lowercase()

            var index = lowerText.indexOf(lowerQuery)
            while (index >= 0) {
                responseMatchPositions.add(Pair(textArea, index))
                try {
                    textArea.highlighter.addHighlight(index, index + query.length, highlighter)
                } catch (_: Exception) {
                    // Ignore highlighting errors
                }
                index = lowerText.indexOf(lowerQuery, index + 1)
            }
        }

        if (responseMatchPositions.isNotEmpty()) {
            responseCurrentMatchIndex = 0
            // Scroll to first match but don't steal focus from search field
            scrollToResponseMatch(0, stealFocus = false)
        }
    }

    private fun findResponseNext() {
        if (responseMatchPositions.isEmpty()) return
        responseCurrentMatchIndex = (responseCurrentMatchIndex + 1) % responseMatchPositions.size
        scrollToResponseMatch(responseCurrentMatchIndex, stealFocus = true)
        updateResponseMatchLabel()
    }

    private fun findResponsePrevious() {
        if (responseMatchPositions.isEmpty()) return
        responseCurrentMatchIndex = if (responseCurrentMatchIndex <= 0) responseMatchPositions.size - 1 else responseCurrentMatchIndex - 1
        scrollToResponseMatch(responseCurrentMatchIndex, stealFocus = true)
        updateResponseMatchLabel()
    }

    private fun scrollToResponseMatch(index: Int, stealFocus: Boolean = false) {
        if (index < 0 || index >= responseMatchPositions.size) return
        val (textArea, position) = responseMatchPositions[index]
        textArea.caretPosition = position
        if (stealFocus) {
            textArea.requestFocusInWindow()
        }
    }

    private fun clearResponseHighlights() {
        headersTextArea.highlighter.removeAllHighlights()
        bodyTextArea.highlighter.removeAllHighlights()
    }
}
