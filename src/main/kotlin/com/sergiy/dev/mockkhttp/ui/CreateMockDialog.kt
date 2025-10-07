package com.sergiy.dev.mockkhttp.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import com.sergiy.dev.mockkhttp.model.MatchType
import com.sergiy.dev.mockkhttp.model.ModifiedResponseData
import com.sergiy.dev.mockkhttp.model.QueryParam
import com.sergiy.dev.mockkhttp.model.StructuredUrl
import com.sergiy.dev.mockkhttp.store.MockkRulesStore
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer

/**
 * Dialog for creating/editing Mock Rules with structured URL matching.
 * Single scrollable view without tabs.
 */
class CreateMockDialog(
    private val project: Project,
    private val existingRule: MockkRulesStore.MockkRule? = null,
    private val initialFlow: com.sergiy.dev.mockkhttp.model.HttpFlowData? = null,
    private val targetPackageName: String? = null,     // Filter collections by package
    private val targetCollectionId: String? = null     // Pre-select collection
) : DialogWrapper(project) {

    private val logger = MockkHttpLogger.getInstance(project)
    private val mockkRulesStore = MockkRulesStore.getInstance(project)

    // Collection selector
    private val collectionComboBox: ComboBox<com.sergiy.dev.mockkhttp.model.MockkCollection>
    private val newCollectionButton: JButton

    // Request fields
    private val nameField: JBTextField
    private val methodComboBox: ComboBox<String>
    private val urlField: JBTextField
    private val queryParamsTableModel: QueryParamsTableModel
    private val queryParamsTable: JBTable

    // Response fields
    private val statusCodeField: JBTextField
    private val headersTextArea: JTextArea
    private val bodyTextArea: JTextArea

    // Search fields
    private val headersSearchField: JBTextField
    private val bodySearchField: JBTextField

    init {
        title = if (existingRule != null) "Edit Mock Rule" else "Create Mock Rule"

        // Initialize collection selector
        val availableCollections = if (targetPackageName != null) {
            mockkRulesStore.getCollectionsByPackage(targetPackageName)
        } else {
            mockkRulesStore.getAllCollections()
        }

        collectionComboBox = ComboBox<com.sergiy.dev.mockkhttp.model.MockkCollection>().apply {
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int,
                    isSelected: Boolean, cellHasFocus: Boolean
                ): java.awt.Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value is com.sergiy.dev.mockkhttp.model.MockkCollection) {
                        text = "${value.name} ${if (value.packageName.isNotEmpty()) "[${value.packageName}]" else ""}"
                    }
                    return this
                }
            }

            // Populate collections
            availableCollections.forEach { addItem(it) }

            // Pre-select collection if specified
            if (targetCollectionId != null) {
                val targetCollection = availableCollections.find { it.id == targetCollectionId }
                if (targetCollection != null) {
                    selectedItem = targetCollection
                }
            } else if (existingRule != null) {
                // If editing, select the rule's collection
                val ruleCollection = mockkRulesStore.getCollection(existingRule.collectionId)
                if (ruleCollection != null) {
                    selectedItem = ruleCollection
                }
            }
        }

        newCollectionButton = JButton("New Collection", AllIcons.General.Add).apply {
            toolTipText = "Create a new collection"
            addActionListener {
                createNewCollectionInline()
            }
        }

        // Initialize request fields
        nameField = JBTextField(existingRule?.name ?: "")
        methodComboBox = ComboBox(arrayOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")).apply {
            selectedItem = existingRule?.method ?: initialFlow?.request?.method ?: "GET"
        }

        // Parse URL to structured format
        val structuredUrl = when {
            existingRule != null -> {
                StructuredUrl(
                    existingRule.scheme,
                    existingRule.host,
                    existingRule.port,
                    existingRule.path,
                    existingRule.queryParams.toMutableList()
                )
            }
            initialFlow != null -> {
                StructuredUrl.fromUrl(initialFlow.request.url)
            }
            else -> StructuredUrl()
        }

        urlField = JBTextField(structuredUrl.toFullUrl()).apply {
            // Auto-parse URL when it changes
            document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = parseUrl()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = parseUrl()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = parseUrl()

                private fun parseUrl() {
                    try {
                        val parsed = StructuredUrl.fromUrl(text)
                        queryParamsTableModel.setParams(parsed.queryParams)
                    } catch (_: Exception) {
                        // Invalid URL, ignore
                    }
                }
            })
        }

        queryParamsTableModel = QueryParamsTableModel(structuredUrl.queryParams)
        queryParamsTable = JBTable(queryParamsTableModel).apply {
            setShowGrid(true)
            gridColor = JBColor.GRAY
            rowHeight = 25

            // Set column widths
            columnModel.getColumn(0).preferredWidth = 150  // Key
            columnModel.getColumn(1).preferredWidth = 200  // Value
            columnModel.getColumn(2).preferredWidth = 80   // Required
            columnModel.getColumn(3).preferredWidth = 100  // Match Type

            // Custom renderers
            columnModel.getColumn(2).cellRenderer = BooleanCellRenderer()
            columnModel.getColumn(2).cellEditor = DefaultCellEditor(JCheckBox())

            val matchTypeEditor = DefaultCellEditor(JComboBox(MatchType.entries.toTypedArray()))
            columnModel.getColumn(3).cellEditor = matchTypeEditor
        }

        // Initialize response fields
        statusCodeField = JBTextField((existingRule?.statusCode ?: initialFlow?.response?.statusCode ?: 200).toString())

        headersSearchField = JBTextField()
        headersTextArea = JTextArea(8, 60).apply {
            font = Font("Monospaced", Font.PLAIN, 12)
            lineWrap = false
            text = when {
                existingRule != null -> existingRule.headers.map { "${it.key}: ${it.value}" }.joinToString("\n")
                initialFlow?.response != null -> initialFlow.response.headers.map { "${it.key}: ${it.value}" }.joinToString("\n")
                else -> "Content-Type: application/json"
            }
        }

        bodySearchField = JBTextField()
        bodyTextArea = JTextArea(15, 60).apply {
            font = Font("Monospaced", Font.PLAIN, 12)
            lineWrap = false
            text = when {
                existingRule != null -> formatJsonIfPossible(existingRule.content)
                initialFlow?.response != null -> formatJsonIfPossible(initialFlow.response.content)
                else -> """{"mock": true}"""
            }
        }

        init()

        // Set dialog to open larger by default
        val screenSize = java.awt.Toolkit.getDefaultToolkit().screenSize
        val width = (screenSize.width * 0.8).toInt().coerceAtMost(1400)
        val height = (screenSize.height * 0.8).toInt().coerceAtMost(900)
        window?.setSize(width, height)
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10)
        }

        // === COLLECTION SELECTOR ===
        mainPanel.add(createCollectionSelectorPanel())
        mainPanel.add(Box.createVerticalStrut(10))

        // Separator
        mainPanel.add(JSeparator(SwingConstants.HORIZONTAL).apply {
            maximumSize = Dimension(Int.MAX_VALUE, 1)
        })
        mainPanel.add(Box.createVerticalStrut(10))

        // === REQUEST SECTION ===
        mainPanel.add(createSectionLabel("Request Configuration"))
        mainPanel.add(Box.createVerticalStrut(10))
        mainPanel.add(createBasicInfoPanel())
        mainPanel.add(Box.createVerticalStrut(10))
        mainPanel.add(createQueryParamsPanel())

        // Separator
        mainPanel.add(Box.createVerticalStrut(20))
        mainPanel.add(JSeparator(SwingConstants.HORIZONTAL).apply {
            maximumSize = Dimension(Int.MAX_VALUE, 1)
        })
        mainPanel.add(Box.createVerticalStrut(20))

        // === RESPONSE SECTION ===
        mainPanel.add(createSectionLabel("Response Configuration"))
        mainPanel.add(Box.createVerticalStrut(10))
        mainPanel.add(createStatusCodePanel())
        mainPanel.add(Box.createVerticalStrut(10))
        mainPanel.add(createResponseContentPanel())

        val scrollPane = JBScrollPane(mainPanel).apply {
            preferredSize = Dimension(1200, 800)
            border = JBUI.Borders.empty()
        }

        return scrollPane
    }

    private fun createSectionLabel(text: String): JComponent {
        return JBLabel(text).apply {
            font = font.deriveFont(Font.BOLD, 14f)
            icon = AllIcons.General.Settings
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
    }

    private fun createCollectionSelectorPanel(): JPanel {
        val panel = JPanel(GridBagLayout()).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        }

        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(5)
        }

        // Row 0: Collection label and combo box
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 0.0
        panel.add(JLabel("Collection:").apply {
            font = font.deriveFont(Font.BOLD)
        }, gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(collectionComboBox, gbc)

        gbc.gridx = 2
        gbc.weightx = 0.0
        panel.add(newCollectionButton, gbc)

        panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height)
        return panel
    }

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
            Messages.showErrorDialog(this.contentPane, "Collection name cannot be empty", "Error")
            return
        }

        val description = Messages.showInputDialog(
            this.contentPane,
            "Enter description (optional):",
            "New Collection",
            Messages.getQuestionIcon(),
            "",
            null
        ) ?: ""

        // Use targetPackageName if available, otherwise empty
        val packageName = targetPackageName ?: ""

        val newCollection = mockkRulesStore.addCollection(name, packageName, description)
        logger.info("Created new collection inline: $name")

        // Add to combo box and select it
        collectionComboBox.addItem(newCollection)
        collectionComboBox.selectedItem = newCollection
    }

    private fun createBasicInfoPanel(): JPanel {
        val panel = JPanel(GridBagLayout()).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        }

        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(5)
        }

        // Row 0: Rule Name
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 0.0
        panel.add(JLabel("Rule Name:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.gridwidth = 3
        nameField.emptyText.text = "My Mock Rule"
        panel.add(nameField, gbc)

        // Row 1: Method + URL
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.gridwidth = 1
        gbc.weightx = 0.0
        panel.add(JLabel("Method:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 0.0
        methodComboBox.preferredSize = Dimension(120, methodComboBox.preferredSize.height)
        panel.add(methodComboBox, gbc)

        gbc.gridx = 2
        gbc.weightx = 0.0
        panel.add(JLabel("URL:"), gbc)

        gbc.gridx = 3
        gbc.weightx = 1.0
        urlField.emptyText.text = "https://api.example.com/endpoint"
        panel.add(urlField, gbc)

        panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height)
        return panel
    }

    private fun createQueryParamsPanel(): JPanel {
        val panel = JPanel(BorderLayout(0, 5)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        }

        val label = JBLabel("Query parameters:").apply {
            font = font.deriveFont(Font.BOLD)
        }
        panel.add(label, BorderLayout.NORTH)

        val tableScrollPane = JBScrollPane(queryParamsTable).apply {
            preferredSize = Dimension(800, 150)
        }
        panel.add(tableScrollPane, BorderLayout.CENTER)

        // Toolbar
        val toolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JButton("Add", AllIcons.General.Add).apply {
                addActionListener { queryParamsTableModel.addParam() }
            })
            add(Box.createHorizontalStrut(5))
            add(JButton("Remove", AllIcons.General.Remove).apply {
                addActionListener {
                    val selectedRow = queryParamsTable.selectedRow
                    if (selectedRow >= 0) {
                        queryParamsTableModel.removeParam(selectedRow)
                    }
                }
            })
            add(Box.createHorizontalStrut(15))
            add(JLabel("<html><i>Tip: Uncheck 'Required' to match any value (wildcard)</i></html>").apply {
                font = font.deriveFont(Font.ITALIC, 11f)
            })
            add(Box.createHorizontalGlue())
        }
        panel.add(toolbar, BorderLayout.SOUTH)

        panel.maximumSize = Dimension(Int.MAX_VALUE, 200)
        return panel
    }

    private fun createStatusCodePanel(): JPanel {
        val panel = JPanel(GridBagLayout()).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        }

        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(5)
        }

        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 0.0
        panel.add(JLabel("Status Code:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 0.0
        statusCodeField.preferredSize = Dimension(80, statusCodeField.preferredSize.height)
        panel.add(statusCodeField, gbc)

        gbc.gridx = 2
        gbc.weightx = 1.0
        panel.add(Box.createHorizontalGlue(), gbc)

        panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height)
        return panel
    }

    private fun createResponseContentPanel(): JPanel {
        val panel = JPanel(GridBagLayout()).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        }

        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.BOTH
            insets = JBUI.insets(5)
            weighty = 1.0
        }

        // Left column: Headers
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 0.3
        val headersPanel = createSearchableTextArea(
            "Headers (one per line: Header: Value)",
            headersTextArea,
            headersSearchField
        )
        panel.add(headersPanel, gbc)

        // Right column: Body
        gbc.gridx = 1
        gbc.weightx = 0.7
        val bodyPanel = createSearchableTextArea(
            "Response Body",
            bodyTextArea,
            bodySearchField
        )
        panel.add(bodyPanel, gbc)

        panel.maximumSize = Dimension(Int.MAX_VALUE, 300)
        return panel
    }

    private fun createSearchableTextArea(label: String, textArea: JTextArea, searchField: JBTextField): JPanel {
        val panel = JPanel(BorderLayout(0, 5))

        // Highlighter for search matches
        val highlighter = textArea.highlighter
        val yellowHighlight = javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(
            JBColor.YELLOW
        )
        val greenHighlight = javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(
            JBColor(java.awt.Color(100, 200, 100), java.awt.Color(80, 160, 80))
        )

        // Track current matches
        val currentMatches = mutableListOf<Int>()
        var currentMatchIndex = -1
        val highlightTags = mutableListOf<Any>()

        // Search panel (hidden by default)
        val searchPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
            background = JBColor.namedColor("SearchEverywhere.SearchField.background", JBColor.PanelBackground)
            isVisible = false
        }

        // Counter label
        val counterLabel = JLabel("").apply {
            foreground = JBColor.GRAY
        }

        // Navigation buttons
        val prevButton = JButton(AllIcons.Actions.Back).apply {
            toolTipText = "Previous (Shift+Enter)"
            isFocusable = false
        }
        val nextButton = JButton(AllIcons.Actions.Forward).apply {
            toolTipText = "Next (Enter)"
            isFocusable = false
        }

        fun highlightCurrentMatch(queryLength: Int) {
            if (currentMatchIndex < 0 || currentMatchIndex >= currentMatches.size) return

            val matchPos = currentMatches[currentMatchIndex]

            // Remove old green highlight (current match indicator)
            highlightTags.firstOrNull()?.let { highlighter.removeHighlight(it) }

            // Add green highlight for current match
            try {
                val tag = highlighter.addHighlight(matchPos, matchPos + queryLength, greenHighlight)
                highlightTags[currentMatchIndex] = tag
            } catch (_: Exception) {
                // Ignore
            }

            // Scroll to match
            textArea.caretPosition = matchPos
            textArea.select(matchPos, matchPos + queryLength)

            // Update counter
            counterLabel.text = "${currentMatchIndex + 1} of ${currentMatches.size}"
        }

        fun findAllMatches(query: String) {
            // Clear previous highlights
            highlightTags.forEach { highlighter.removeHighlight(it) }
            highlightTags.clear()
            currentMatches.clear()
            currentMatchIndex = -1

            if (query.isEmpty()) {
                counterLabel.text = ""
                return
            }

            val text = textArea.text.lowercase()
            val queryLower = query.lowercase()
            var index = 0

            while (true) {
                index = text.indexOf(queryLower, index)
                if (index < 0) break
                currentMatches.add(index)

                // Highlight in yellow
                try {
                    val tag = highlighter.addHighlight(index, index + query.length, yellowHighlight)
                    highlightTags.add(tag)
                } catch (_: Exception) {
                    // Ignore
                }
                index += query.length
            }

            if (currentMatches.isNotEmpty()) {
                counterLabel.text = "${currentMatches.size} matches"
                currentMatchIndex = 0
                highlightCurrentMatch(query.length)
            } else {
                counterLabel.text = "No matches"
            }
        }

        fun goToNextMatch() {
            if (currentMatches.isEmpty()) return
            currentMatchIndex = (currentMatchIndex + 1) % currentMatches.size
            highlightCurrentMatch(searchField.text.length)
        }

        fun goToPrevMatch() {
            if (currentMatches.isEmpty()) return
            currentMatchIndex = if (currentMatchIndex <= 0) currentMatches.size - 1 else currentMatchIndex - 1
            highlightCurrentMatch(searchField.text.length)
        }

        prevButton.addActionListener { goToPrevMatch() }
        nextButton.addActionListener { goToNextMatch() }

        searchField.emptyText.text = "Search..."
        searchField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = findAllMatches(searchField.text)
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = findAllMatches(searchField.text)
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = findAllMatches(searchField.text)
        })

        val searchContent = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            background = searchPanel.background
            add(JLabel(AllIcons.Actions.Find))
            add(Box.createHorizontalStrut(5))
            add(searchField)
            add(Box.createHorizontalStrut(5))
            add(prevButton)
            add(nextButton)
            add(Box.createHorizontalStrut(10))
            add(counterLabel)
        }
        searchPanel.add(searchContent, BorderLayout.CENTER)

        val topPanel = JPanel(BorderLayout())
        topPanel.add(JBLabel(label).apply { font = font.deriveFont(Font.BOLD) }, BorderLayout.NORTH)
        topPanel.add(searchPanel, BorderLayout.SOUTH)

        panel.add(topPanel, BorderLayout.NORTH)
        panel.add(JBScrollPane(textArea), BorderLayout.CENTER)

        // Register Cmd+F / Ctrl+F
        val findAction = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                searchPanel.isVisible = true
                searchField.requestFocusInWindow()
                searchField.selectAll()
            }
        }
        val shortcut = KeyStroke.getKeyStroke(
            KeyEvent.VK_F,
            java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        )
        findAction.registerCustomShortcutSet(CustomShortcutSet(shortcut), panel)

        // Enter = next, Shift+Enter = prev
        searchField.addActionListener { goToNextMatch() }
        searchField.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "prev")
        searchField.actionMap.put("prev", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) = goToPrevMatch()
        })

        // Escape to close
        searchField.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close")
        searchField.actionMap.put("close", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                searchPanel.isVisible = false
                highlightTags.forEach { highlighter.removeHighlight(it) }
                highlightTags.clear()
                currentMatches.clear()
                searchField.text = ""
                textArea.requestFocusInWindow()
            }
        })

        return panel
    }

    private fun formatJsonIfPossible(content: String): String {
        if (content.isBlank()) return content
        return try {
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            val jsonElement = com.google.gson.JsonParser.parseString(content)
            gson.toJson(jsonElement)
        } catch (_: Exception) {
            content
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) {
            return ValidationInfo("Rule name is required", nameField)
        }

        if (urlField.text.isBlank()) {
            return ValidationInfo("URL is required", urlField)
        }

        val statusCode = statusCodeField.text.toIntOrNull()
        if (statusCode == null || statusCode < 100 || statusCode >= 600) {
            return ValidationInfo("Status code must be between 100 and 599", statusCodeField)
        }

        return null
    }

    override fun doOKAction() {
        // Parse URL
        val structuredUrl = StructuredUrl.fromUrl(urlField.text)
        structuredUrl.queryParams = queryParamsTableModel.getAllParams()

        // Parse headers
        val headers = mutableMapOf<String, String>()
        headersTextArea.text.lines().forEach { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                headers[parts[0].trim()] = parts[1].trim()
            }
        }

        // Get selected collection
        val selectedCollection = collectionComboBox.selectedItem as? com.sergiy.dev.mockkhttp.model.MockkCollection
        val collectionId = selectedCollection?.id ?: ""

        if (collectionId.isEmpty()) {
            Messages.showErrorDialog(
                this.contentPane,
                "Please select or create a collection first",
                "No Collection Selected"
            )
            return
        }

        if (existingRule != null) {
            mockkRulesStore.removeRule(existingRule)
        }

        mockkRulesStore.addRule(
            name = nameField.text.trim(),
            method = methodComboBox.selectedItem as String,
            structuredUrl = structuredUrl,
            mockResponse = ModifiedResponseData(
                statusCode = statusCodeField.text.toInt(),
                headers = headers,
                content = bodyTextArea.text
            ),
            collectionId = collectionId
        )

        logger.info("Mock rule ${if (existingRule != null) "updated" else "created"}: ${nameField.text} in collection: $collectionId")
        super.doOKAction()
    }

    /**
     * Table model for query parameters
     */
    private class QueryParamsTableModel(initialParams: List<QueryParam>) : AbstractTableModel() {
        private val params = initialParams.toMutableList()
        private val columnNames = arrayOf("Key", "Value", "Required", "Match Type")

        fun addParam() {
            params.add(QueryParam("", "", required = false, matchType = MatchType.WILDCARD))
            fireTableRowsInserted(params.size - 1, params.size - 1)
        }

        fun removeParam(index: Int) {
            if (index >= 0 && index < params.size) {
                params.removeAt(index)
                fireTableRowsDeleted(index, index)
            }
        }

        fun setParams(newParams: List<QueryParam>) {
            params.clear()
            params.addAll(newParams)
            fireTableDataChanged()
        }

        fun getAllParams(): MutableList<QueryParam> = params.toMutableList()

        override fun getRowCount(): Int = params.size
        override fun getColumnCount(): Int = columnNames.size
        override fun getColumnName(column: Int): String = columnNames[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val param = params[rowIndex]
            return when (columnIndex) {
                0 -> param.key
                1 -> param.value
                2 -> param.required
                3 -> param.matchType
                else -> ""
            }
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            val param = params[rowIndex]
            when (columnIndex) {
                0 -> param.key = aValue as String
                1 -> param.value = aValue as String
                2 -> param.required = aValue as Boolean
                3 -> param.matchType = aValue as MatchType
            }
            fireTableCellUpdated(rowIndex, columnIndex)
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true

        override fun getColumnClass(columnIndex: Int): Class<*> {
            return when (columnIndex) {
                2 -> Boolean::class.java
                3 -> MatchType::class.java
                else -> String::class.java
            }
        }
    }

    /**
     * Boolean cell renderer with centered checkbox
     */
    private class BooleanCellRenderer : JCheckBox(), TableCellRenderer {
        init {
            horizontalAlignment = CENTER
        }

        override fun getTableCellRendererComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): java.awt.Component {
            this.isSelected = value as? Boolean ?: false
            background = if (isSelected) table?.selectionBackground else table?.background
            return this
        }
    }
}
