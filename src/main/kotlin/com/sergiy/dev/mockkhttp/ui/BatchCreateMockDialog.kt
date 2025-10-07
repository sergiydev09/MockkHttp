package com.sergiy.dev.mockkhttp.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import com.sergiy.dev.mockkhttp.model.HttpFlowData
import com.sergiy.dev.mockkhttp.model.MockkCollection
import com.sergiy.dev.mockkhttp.model.ModifiedResponseData
import com.sergiy.dev.mockkhttp.model.StructuredUrl
import com.sergiy.dev.mockkhttp.store.MockkRulesStore
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

/**
 * Dialog for creating multiple mock rules from selected flows.
 */
class BatchCreateMockDialog(
    private val project: Project,
    private val flows: List<HttpFlowData>,
    private val targetPackageName: String? = null
) : DialogWrapper(project) {

    private val logger = MockkHttpLogger.getInstance(project)
    private val mockkRulesStore = MockkRulesStore.getInstance(project)

    private val collectionComboBox: ComboBox<MockkCollection>
    private val newCollectionButton: JButton
    private val newCollectionNameField: JBTextField
    private val newCollectionPackageField: JBTextField
    private var createNewCollection = false
    private lateinit var mainPanel: JPanel
    private val collectionNameLabel = JBLabel("Target Collection:")
    private val newCollectionNameLabel = JBLabel("New Collection Name:")
    private val newCollectionPackageLabel = JBLabel("Package Name:")

    init {
        title = "Create ${flows.size} Mock Rules"

        // Initialize collection selector
        // Show all collections, but prioritize those matching the target package
        val allCollections = mockkRulesStore.getAllCollections()
        val availableCollections = if (targetPackageName != null && targetPackageName.isNotEmpty()) {
            // Show collections matching the package first, then generic ones (empty package)
            val matching = allCollections.filter { it.packageName == targetPackageName }
            val generic = allCollections.filter { it.packageName.isEmpty() }
            val others = allCollections.filter { it.packageName.isNotEmpty() && it.packageName != targetPackageName }
            matching + generic + others
        } else {
            allCollections
        }

        collectionComboBox = ComboBox<MockkCollection>().apply {
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int,
                    isSelected: Boolean, cellHasFocus: Boolean
                ): java.awt.Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value is MockkCollection) {
                        text = "${value.name} ${if (value.packageName.isNotEmpty()) "[${value.packageName}]" else ""}"
                    }
                    return this
                }
            }

            availableCollections.forEach { addItem(it) }

            if (availableCollections.isNotEmpty()) {
                selectedIndex = 0
            }
        }

        newCollectionButton = JButton("New Collection", AllIcons.General.Add).apply {
            addActionListener {
                createNewCollection = !createNewCollection
                updateCollectionFields()
            }
        }

        newCollectionNameField = JBTextField().apply {
            isVisible = false
        }

        newCollectionPackageField = JBTextField().apply {
            text = targetPackageName ?: ""
            isVisible = false
        }

        init()
    }

    override fun createCenterPanel(): JComponent {
        mainPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(5)
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }

        // Info label
        mainPanel.add(JBLabel("Creating ${flows.size} mock rules from selected flows:"), gbc)

        // Flow list preview (show first few)
        gbc.gridy++
        val previewPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5)
            val flowsToShow = flows.take(5)
            val text = flowsToShow.joinToString("\n") { "• ${it.request.method} ${it.request.url}" } +
                    if (flows.size > 5) "\n... and ${flows.size - 5} more" else ""
            add(JTextArea(text).apply {
                isEditable = false
                rows = minOf(6, flows.size + 1)
                background = mainPanel.background
            }, BorderLayout.CENTER)
        }
        mainPanel.add(previewPanel, gbc)

        // Collection selector
        gbc.gridy++
        mainPanel.add(collectionNameLabel, gbc)

        gbc.gridy++
        val collectionPanel = JPanel(BorderLayout()).apply {
            add(collectionComboBox, BorderLayout.CENTER)
            add(newCollectionButton, BorderLayout.EAST)
        }
        mainPanel.add(collectionPanel, gbc)

        // New collection fields (hidden by default)
        gbc.gridy++
        newCollectionNameLabel.isVisible = false
        mainPanel.add(newCollectionNameLabel, gbc)
        gbc.gridy++
        mainPanel.add(newCollectionNameField, gbc)

        gbc.gridy++
        newCollectionPackageLabel.isVisible = false
        mainPanel.add(newCollectionPackageLabel, gbc)
        gbc.gridy++
        mainPanel.add(newCollectionPackageField, gbc)

        return mainPanel
    }

    private fun updateCollectionFields() {
        collectionComboBox.isVisible = !createNewCollection
        newCollectionNameField.isVisible = createNewCollection
        newCollectionPackageField.isVisible = createNewCollection

        // Update labels visibility
        collectionNameLabel.isVisible = !createNewCollection
        newCollectionNameLabel.isVisible = createNewCollection
        newCollectionPackageLabel.isVisible = createNewCollection

        newCollectionButton.text = if (createNewCollection) "Use Existing" else "New Collection"
        newCollectionButton.icon = if (createNewCollection) AllIcons.Actions.Back else AllIcons.General.Add

        mainPanel.revalidate()
        mainPanel.repaint()
    }

    override fun doValidate(): ValidationInfo? {
        if (createNewCollection) {
            if (newCollectionNameField.text.isBlank()) {
                return ValidationInfo("Collection name cannot be empty", newCollectionNameField)
            }
        } else {
            if (collectionComboBox.selectedItem == null) {
                return ValidationInfo("Please select a collection or create a new one", collectionComboBox)
            }
        }
        return null
    }

    override fun doOKAction() {
        val collection = if (createNewCollection) {
            // Create new collection
            val name = newCollectionNameField.text.trim()
            val packageName = newCollectionPackageField.text.trim()

            val newCollection = mockkRulesStore.addCollection(name, packageName, description = "")
            logger.info("✅ Created new collection: $name")
            newCollection
        } else {
            collectionComboBox.selectedItem as? MockkCollection
        }

        if (collection == null) {
            Messages.showErrorDialog(project, "Failed to get/create collection", "Error")
            return
        }

        // Create mock rules for each flow
        var successCount = 0
        var errorCount = 0
        var skippedCount = 0

        // Track endpoints already processed in THIS batch to avoid creating duplicates
        // from the selected flows themselves (e.g., if user selected same endpoint 3 times)
        val processedEndpoints = mutableSetOf<Triple<String, String, String>>() // (method, host, path)

        // Get existing rules from collection (only matters for existing collections)
        val existingRules = mockkRulesStore.getRulesInCollection(collection.id)

        for (flow in flows) {
            try {
                val structuredUrl = StructuredUrl.fromUrl(flow.request.url)
                val endpointKey = Triple(flow.request.method, structuredUrl.host, structuredUrl.path)

                // Check if we already processed this endpoint in THIS batch
                if (processedEndpoints.contains(endpointKey)) {
                    logger.debug("Skipping duplicate in batch: ${flow.request.method} ${structuredUrl.host}${structuredUrl.path}")
                    skippedCount++
                    continue
                }

                // Check if a rule with the same endpoint already exists in the collection
                val existsInCollection = existingRules.any {
                    it.method == flow.request.method &&
                    it.host == structuredUrl.host &&
                    it.path == structuredUrl.path
                }

                if (existsInCollection) {
                    logger.debug("Skipping duplicate rule (exists in collection): ${flow.request.method} ${structuredUrl.host}${structuredUrl.path}")
                    skippedCount++
                    continue
                }

                // Generate a unique name for the mock rule
                val url = flow.request.url
                val pathSegment = try {
                    java.net.URL(url).path.split("/").lastOrNull { it.isNotEmpty() } ?: "endpoint"
                } catch (e: Exception) {
                    "endpoint"
                }
                val baseName = "${flow.request.method}_${pathSegment}"

                // Make name unique if it already exists in the collection
                var ruleName = baseName
                var counter = 1
                while (existingRules.any { it.name == ruleName }) {
                    ruleName = "${baseName}_${counter}"
                    counter++
                }

                mockkRulesStore.addRule(
                    name = ruleName,
                    method = flow.request.method,
                    structuredUrl = structuredUrl,
                    mockResponse = ModifiedResponseData(
                        statusCode = flow.response?.statusCode ?: 200,
                        headers = flow.response?.headers ?: emptyMap(),
                        content = flow.response?.content ?: ""
                    ),
                    collectionId = collection.id
                )

                // Mark this endpoint as processed
                processedEndpoints.add(endpointKey)
                successCount++
            } catch (e: Exception) {
                logger.error("Failed to create mock rule for ${flow.request.url}", e)
                errorCount++
            }
        }

        logger.info("✅ Created $successCount mock rules in collection '${collection.name}'")
        if (skippedCount > 0) {
            logger.info("⏭️ Skipped $skippedCount duplicate rules")
        }
        if (errorCount > 0) {
            logger.warn("⚠️ Failed to create $errorCount mock rules")
        }

        val message = buildString {
            append("Successfully created $successCount mock rule(s)")
            if (skippedCount > 0) {
                append("\nSkipped $skippedCount duplicate(s)")
            }
            if (errorCount > 0) {
                append("\nFailed: $errorCount")
            }
        }

        Messages.showInfoMessage(project, message, "Mock Rules Created")

        super.doOKAction()
    }
}
