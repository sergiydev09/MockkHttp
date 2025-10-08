package com.sergiy.dev.mockkhttp.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import com.sergiy.dev.mockkhttp.model.MockkCollection
import com.sergiy.dev.mockkhttp.store.MockkRulesStore
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*
import javax.swing.filechooser.FileFilter
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Panel for managing Mockk rules organized in collections.
 * Uses a tree structure: Collections → Rules
 */
class MockkRulesPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val logger = MockkHttpLogger.getInstance(project)
    private val mockkRulesStore = MockkRulesStore.getInstance(project)

    private val treeModel: DefaultTreeModel
    private val tree: Tree
    private val rootNode: DefaultMutableTreeNode

    // Node types
    sealed class TreeNode {
        data class CollectionNode(val collection: MockkCollection) : TreeNode()
        data class RuleNode(val rule: MockkRulesStore.MockkRule) : TreeNode()
    }

    init {
        logger.info("Initializing Mockk Rules Panel (Tree View)...")

        // Create tree model
        rootNode = DefaultMutableTreeNode("Collections")
        treeModel = DefaultTreeModel(rootNode)
        tree = Tree(treeModel).apply {
            cellRenderer = MockkTreeCellRenderer()
            isRootVisible = false
            showsRootHandles = true
            toolTipText = ""  // Enable tooltips

            // Mouse listener for click actions
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val path = getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val userObject = node.userObject as? TreeNode ?: return

                    // Get the bounds of the row
                    val bounds = getPathBounds(path) ?: return
                    val clickX = e.x - bounds.x

                    // Icon area is approximately first 20 pixels (folder/lightning icon)
                    val iconStart = 0
                    val iconEnd = 20

                    when (userObject) {
                        is TreeNode.CollectionNode -> {
                            if (clickX in iconStart..iconEnd && e.clickCount == 1) {
                                // Click on folder icon - toggle collection
                                toggleCollectionEnabled(userObject.collection)
                            } else if (e.clickCount == 2) {
                                // Double-click on name - edit
                                editCollection(userObject.collection)
                            }
                            // Single click on name = normal selection (handled by tree)
                        }
                        is TreeNode.RuleNode -> {
                            if (clickX in iconStart..iconEnd && e.clickCount == 1) {
                                // Click on lightning icon - toggle rule
                                toggleRuleEnabled(userObject.rule)
                            } else if (e.clickCount == 2) {
                                // Double-click on name - edit
                                editRule(userObject.rule)
                            }
                            // Single click on name = normal selection (handled by tree)
                        }
                    }
                }

                override fun mousePressed(e: MouseEvent) {
                    if (e.isPopupTrigger) {
                        showContextMenu(e)
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (e.isPopupTrigger) {
                        showContextMenu(e)
                    }
                }
            })
        }

        // Load existing collections and rules
        loadTreeData()

        // Clean up conflicts on initialization
        cleanupConflictsOnLoad()

        // Listen for new collections
        mockkRulesStore.addCollectionAddedListener { collection ->
            SwingUtilities.invokeLater {
                addCollectionToTree(collection)
            }
        }

        mockkRulesStore.addCollectionRemovedListener { collection ->
            SwingUtilities.invokeLater {
                removeCollectionFromTree(collection)
            }
        }

        // Listen for new/removed rules
        mockkRulesStore.addRuleAddedListener { rule ->
            SwingUtilities.invokeLater {
                addRuleToTree(rule)
            }
        }

        mockkRulesStore.addRuleRemovedListener { rule ->
            SwingUtilities.invokeLater {
                removeRuleFromTree(rule)
            }
        }

        // Toolbar
        val toolbar = createToolbar()

        // Layout
        border = JBUI.Borders.empty(10)
        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)

        logger.info("✅ Mockk Rules Panel initialized (Tree View)")
    }

    /**
     * Create the toolbar with all action buttons.
     */
    private fun createToolbar(): JPanel {
        val toolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        // First row: Collection actions
        val row1 = JPanel(FlowLayout(FlowLayout.LEFT, 5, 5)).apply {
            add(JButton("New Collection", AllIcons.General.Add).apply {
                toolTipText = "Create a new mock collection"
                addActionListener { createNewCollection() }
            })
            add(JButton("New Mock", AllIcons.Nodes.DataSchema).apply {
                toolTipText = "Create a new mock rule"
                addActionListener { createNewMock() }
            })
            add(JButton("Edit", AllIcons.Actions.Edit).apply {
                toolTipText = "Edit selected item"
                addActionListener { editSelected() }
            })
            add(JButton("Delete", AllIcons.Actions.Cancel).apply {
                toolTipText = "Delete selected item"
                addActionListener { deleteSelected() }
            })
        }

        // Second row: Import/Export/Duplicate
        val row2 = JPanel(FlowLayout(FlowLayout.LEFT, 5, 5)).apply {
            add(JButton("Duplicate", AllIcons.Actions.Copy).apply {
                toolTipText = "Duplicate selected rule to another collection"
                addActionListener { duplicateSelected() }
            })
            add(JButton("Import", AllIcons.ToolbarDecorator.Import).apply {
                toolTipText = "Import collection(s) from JSON file"
                addActionListener { importCollections() }
            })
            add(JButton("Export", AllIcons.ToolbarDecorator.Export).apply {
                toolTipText = "Export selected collection(s) to JSON file"
                addActionListener { exportSelected() }
            })
            add(JSeparator(SwingConstants.VERTICAL))
            add(JButton("Enable/Disable", AllIcons.Actions.Lightning).apply {
                toolTipText = "Toggle enabled state"
                addActionListener { toggleSelected() }
            })
        }

        toolbar.add(row1)
        toolbar.add(row2)
        return toolbar
    }

    /**
     * Save the current expansion state of the tree.
     */
    private fun saveExpansionState(): Set<String> {
        val expandedPaths = mutableSetOf<String>()
        for (i in 0 until tree.rowCount) {
            val path = tree.getPathForRow(i)
            if (tree.isExpanded(path)) {
                val node = path.lastPathComponent as? DefaultMutableTreeNode
                val nodeData = node?.userObject
                when (nodeData) {
                    is TreeNode.CollectionNode -> expandedPaths.add("collection:${nodeData.collection.id}")
                    is TreeNode.RuleNode -> expandedPaths.add("rule:${nodeData.rule.id}")
                }
            }
        }
        return expandedPaths
    }

    /**
     * Restore the expansion state of the tree.
     */
    private fun restoreExpansionState(expandedPaths: Set<String>) {
        for (i in 0 until tree.rowCount) {
            val path = tree.getPathForRow(i)
            val node = path.lastPathComponent as? DefaultMutableTreeNode
            val nodeData = node?.userObject
            val shouldExpand = when (nodeData) {
                is TreeNode.CollectionNode -> expandedPaths.contains("collection:${nodeData.collection.id}")
                is TreeNode.RuleNode -> expandedPaths.contains("rule:${nodeData.rule.id}")
                else -> false
            }
            if (shouldExpand) {
                tree.expandPath(path)
            }
        }
    }

    /**
     * Load all collections and rules into the tree.
     */
    private fun loadTreeData() {
        rootNode.removeAllChildren()

        val collections = mockkRulesStore.getAllCollections()
        for (collection in collections) {
            addCollectionToTree(collection)
        }

        treeModel.reload()

        // Expand all collections by default on first load
        for (i in 0 until tree.rowCount) {
            tree.expandRow(i)
        }
    }

    /**
     * Add a collection node to the tree.
     */
    private fun addCollectionToTree(collection: MockkCollection) {
        val collectionNode = DefaultMutableTreeNode(TreeNode.CollectionNode(collection))
        val index = rootNode.childCount
        rootNode.add(collectionNode)

        // Add its rules
        val rules = mockkRulesStore.getRulesInCollection(collection.id)
        for (rule in rules) {
            val ruleNode = DefaultMutableTreeNode(TreeNode.RuleNode(rule))
            collectionNode.add(ruleNode)
        }

        // Notify model of insertion instead of reload
        treeModel.nodesWereInserted(rootNode, intArrayOf(index))

        // Expand the new collection
        val path = TreePath(collectionNode.path)
        tree.expandPath(path)
    }

    /**
     * Remove a collection node from the tree.
     */
    private fun removeCollectionFromTree(collection: MockkCollection) {
        for (i in 0 until rootNode.childCount) {
            val node = rootNode.getChildAt(i) as DefaultMutableTreeNode
            val nodeData = node.userObject as? TreeNode.CollectionNode
            if (nodeData?.collection?.id == collection.id) {
                rootNode.remove(node)
                treeModel.nodesWereRemoved(rootNode, intArrayOf(i), arrayOf(node))
                break
            }
        }
    }

    /**
     * Add a rule node to its collection in the tree.
     */
    private fun addRuleToTree(rule: MockkRulesStore.MockkRule) {
        // Find the collection node
        for (i in 0 until rootNode.childCount) {
            val collectionNode = rootNode.getChildAt(i) as DefaultMutableTreeNode
            val nodeData = collectionNode.userObject as? TreeNode.CollectionNode
            if (nodeData?.collection?.id == rule.collectionId) {
                val ruleNode = DefaultMutableTreeNode(TreeNode.RuleNode(rule))
                val index = collectionNode.childCount
                collectionNode.add(ruleNode)
                treeModel.nodesWereInserted(collectionNode, intArrayOf(index))

                // Expand to show the new rule
                val path = TreePath(collectionNode.path)
                tree.expandPath(path)
                break
            }
        }
    }

    /**
     * Remove a rule node from the tree.
     */
    private fun removeRuleFromTree(rule: MockkRulesStore.MockkRule) {
        for (i in 0 until rootNode.childCount) {
            val collectionNode = rootNode.getChildAt(i) as DefaultMutableTreeNode
            for (j in 0 until collectionNode.childCount) {
                val ruleNode = collectionNode.getChildAt(j) as DefaultMutableTreeNode
                val nodeData = ruleNode.userObject as? TreeNode.RuleNode
                if (nodeData?.rule?.id == rule.id) {
                    collectionNode.remove(ruleNode)
                    treeModel.nodesWereRemoved(collectionNode, intArrayOf(j), arrayOf(ruleNode))
                    return
                }
            }
        }
    }

    // ========== ACTION METHODS ==========

    private fun createNewCollection() {
        val name = Messages.showInputDialog(
            this,
            "Enter collection name:",
            "New Collection",
            Messages.getQuestionIcon(),
            "",
            null
        ) ?: return

        if (name.isBlank()) {
            Messages.showErrorDialog(this, "Collection name cannot be empty", "Error")
            return
        }

        val description = Messages.showInputDialog(
            this,
            "Enter description (optional):",
            "New Collection",
            Messages.getQuestionIcon(),
            "",
            null
        ) ?: ""

        // For now, package name is empty - will be set when adding rules from specific app
        mockkRulesStore.addCollection(name, "", description)
        logger.info("Created new collection: $name")
    }

    private fun createNewMock() {
        // Get selected collection if any
        val selectedPath = tree.selectionPath
        var targetCollectionId = ""

        if (selectedPath != null) {
            val node = selectedPath.lastPathComponent as DefaultMutableTreeNode
            when (val nodeData = node.userObject) {
                is TreeNode.CollectionNode -> targetCollectionId = nodeData.collection.id
                is TreeNode.RuleNode -> {
                    // If rule selected, use its collection
                    targetCollectionId = nodeData.rule.collectionId
                }
            }
        }

        val dialog = CreateMockDialog(project, targetCollectionId = targetCollectionId)
        if (dialog.showAndGet()) {
            logger.info("New mock rule created")
        }
    }

    private fun editSelected() {
        val selectedPath = tree.selectionPath ?: return
        val node = selectedPath.lastPathComponent as DefaultMutableTreeNode

        when (val nodeData = node.userObject) {
            is TreeNode.CollectionNode -> editCollection(nodeData.collection)
            is TreeNode.RuleNode -> editRule(nodeData.rule)
        }
    }

    private fun editCollection(collection: MockkCollection) {
        val newName = Messages.showInputDialog(
            this,
            "Enter new name:",
            "Edit Collection",
            Messages.getQuestionIcon(),
            collection.name,
            null
        ) ?: return

        val newDescription = Messages.showInputDialog(
            this,
            "Enter new description:",
            "Edit Collection",
            Messages.getQuestionIcon(),
            collection.description,
            null
        ) ?: collection.description

        mockkRulesStore.updateCollection(
            collection.id,
            name = newName,
            description = newDescription
        )

        // Find and update the collection node
        for (i in 0 until rootNode.childCount) {
            val node = rootNode.getChildAt(i) as DefaultMutableTreeNode
            val nodeData = node.userObject as? TreeNode.CollectionNode
            if (nodeData?.collection?.id == collection.id) {
                // Update the user object with fresh data
                val updatedCollection = mockkRulesStore.getCollection(collection.id)
                if (updatedCollection != null) {
                    node.userObject = TreeNode.CollectionNode(updatedCollection)
                    treeModel.nodeChanged(node)
                }
                break
            }
        }

        logger.info("Updated collection: $newName")
    }

    private fun editRule(rule: MockkRulesStore.MockkRule) {
        val dialog = CreateMockDialog(project, existingRule = rule)
        if (dialog.showAndGet()) {
            logger.info("Mock rule edited: ${rule.name}")

            // Get the updated rule from store
            val updatedRule = mockkRulesStore.getAllRules().find { it.id == rule.id }
            val affectedCollectionIds = mutableSetOf<String>()

            if (updatedRule != null && updatedRule.enabled) {
                // Check for conflicts after editing
                val conflicts = findConflictingRules(updatedRule)
                if (conflicts.isNotEmpty()) {
                    logger.info("🔄 Disabling ${conflicts.size} conflicting rule(s) after editing '${updatedRule.name}'")
                    for (conflictingRule in conflicts) {
                        mockkRulesStore.setRuleEnabled(conflictingRule, false)
                        updateRuleNodeInTree(conflictingRule.id)
                        // Track affected collection
                        affectedCollectionIds.add(conflictingRule.collectionId)
                    }
                }

                // Track this rule's collection
                affectedCollectionIds.add(updatedRule.collectionId)
            }

            // Find and update the rule node
            for (i in 0 until rootNode.childCount) {
                val collectionNode = rootNode.getChildAt(i) as DefaultMutableTreeNode
                for (j in 0 until collectionNode.childCount) {
                    val ruleNode = collectionNode.getChildAt(j) as DefaultMutableTreeNode
                    val nodeData = ruleNode.userObject as? TreeNode.RuleNode
                    if (nodeData?.rule?.id == rule.id) {
                        // Update with fresh data from store
                        if (updatedRule != null) {
                            ruleNode.userObject = TreeNode.RuleNode(updatedRule)
                            treeModel.nodeChanged(ruleNode)
                        }
                        break
                    }
                }
            }

            // Sync all affected collections (auto-enable/disable based on rules)
            for (collectionId in affectedCollectionIds) {
                syncCollectionStateWithRules(collectionId)
            }
        }
    }

    private fun deleteSelected() {
        val selectedPath = tree.selectionPath ?: return
        val node = selectedPath.lastPathComponent as DefaultMutableTreeNode

        when (val nodeData = node.userObject) {
            is TreeNode.CollectionNode -> {
                val result = Messages.showYesNoDialog(
                    this,
                    "Delete collection '${nodeData.collection.name}' and all its rules?",
                    "Confirm Delete",
                    Messages.getQuestionIcon()
                )
                if (result == Messages.YES) {
                    mockkRulesStore.removeCollection(nodeData.collection, removeRules = true)
                }
            }
            is TreeNode.RuleNode -> {
                val result = Messages.showYesNoDialog(
                    this,
                    "Delete rule '${nodeData.rule.name}'?",
                    "Confirm Delete",
                    Messages.getQuestionIcon()
                )
                if (result == Messages.YES) {
                    mockkRulesStore.removeRule(nodeData.rule)
                }
            }
        }
    }

    private fun duplicateSelected() {
        val selectedPath = tree.selectionPath ?: return
        val node = selectedPath.lastPathComponent as DefaultMutableTreeNode
        val nodeData = node.userObject as? TreeNode.RuleNode ?: return

        // Show dialog to select target collection
        val collections = mockkRulesStore.getAllCollections()
        if (collections.isEmpty()) {
            Messages.showErrorDialog(this, "No collections available", "Error")
            return
        }

        val collectionNames = collections.map { it.name }.toTypedArray()
        val selectedIndex = Messages.showDialog(
            project,
            "Select target collection:",
            "Duplicate Rule",
            collectionNames,
            0,  // Default selection index
            Messages.getQuestionIcon()
        )

        if (selectedIndex >= 0) {
            val targetCollection = collections[selectedIndex]
            mockkRulesStore.duplicateRule(nodeData.rule, targetCollection.id)
            logger.info("Duplicated rule to collection: ${targetCollection.name}")
        }
    }

    private fun toggleSelected() {
        val selectedPath = tree.selectionPath ?: return
        val node = selectedPath.lastPathComponent as DefaultMutableTreeNode

        when (val nodeData = node.userObject) {
            is TreeNode.CollectionNode -> toggleCollectionEnabled(nodeData.collection)
            is TreeNode.RuleNode -> toggleRuleEnabled(nodeData.rule)
        }
    }

    private fun toggleCollectionEnabled(collection: MockkCollection) {
        val newEnabledState = !collection.enabled
        val affectedCollectionIds = mutableSetOf<String>()

        // Update collection state
        mockkRulesStore.updateCollection(collection.id, enabled = newEnabledState)

        // Get all rules in this collection
        val rules = mockkRulesStore.getRulesInCollection(collection.id)

        if (newEnabledState) {
            // Enabling collection: enable all its rules (and handle conflicts)
            for (rule in rules) {
                if (!rule.enabled) {
                    // Enable the rule
                    mockkRulesStore.setRuleEnabled(rule, true)

                    // Find and disable conflicts
                    val conflicts = findConflictingRules(rule)
                    for (conflictingRule in conflicts) {
                        mockkRulesStore.setRuleEnabled(conflictingRule, false)
                        updateRuleNodeInTree(conflictingRule.id)
                        // Track affected collection
                        affectedCollectionIds.add(conflictingRule.collectionId)
                    }

                    updateRuleNodeInTree(rule.id)
                }
            }
            logger.info("✅ Enabled collection '${collection.name}' and all its rules")
        } else {
            // Disabling collection: disable all its rules
            for (rule in rules) {
                if (rule.enabled) {
                    mockkRulesStore.setRuleEnabled(rule, false)
                    updateRuleNodeInTree(rule.id)
                }
            }
            logger.info("⚠️ Disabled collection '${collection.name}' and all its rules")
        }

        // Track this collection
        affectedCollectionIds.add(collection.id)

        // Sync all affected collections (auto-enable/disable based on rules)
        for (collectionId in affectedCollectionIds) {
            syncCollectionStateWithRules(collectionId)
        }
    }

    private fun toggleRuleEnabled(rule: MockkRulesStore.MockkRule) {
        val newEnabledState = !rule.enabled
        val affectedCollectionIds = mutableSetOf<String>()

        // If enabling the rule, find and disable conflicting rules in other collections
        if (newEnabledState) {
            val conflicts = findConflictingRules(rule)
            if (conflicts.isNotEmpty()) {
                logger.info("🔄 Disabling ${conflicts.size} conflicting rule(s) when enabling '${rule.name}'")
                for (conflictingRule in conflicts) {
                    mockkRulesStore.setRuleEnabled(conflictingRule, false)
                    // Update the conflicting rule's node
                    updateRuleNodeInTree(conflictingRule.id)
                    // Track affected collection
                    affectedCollectionIds.add(conflictingRule.collectionId)
                }
            }
        }

        // Toggle the target rule (SOLO esta norma, no todas las de la colección)
        mockkRulesStore.setRuleEnabled(rule, newEnabledState)

        // Update the rule node in tree
        updateRuleNodeInTree(rule.id)

        // Track this rule's collection
        affectedCollectionIds.add(rule.collectionId)

        // Sync all affected collections (this will auto-enable/disable collections based on rules)
        for (collectionId in affectedCollectionIds) {
            syncCollectionStateWithRules(collectionId)
        }
    }

    /**
     * Sync collection enabled state with its rules.
     * - If all rules are disabled → disable collection
     * - If at least one rule is enabled → enable collection
     */
    private fun syncCollectionStateWithRules(collectionId: String) {
        val collection = mockkRulesStore.getCollection(collectionId) ?: return
        val rules = mockkRulesStore.getRulesInCollection(collectionId)
        val hasEnabledRules = rules.any { it.enabled }

        // Update collection state if needed
        if (hasEnabledRules && !collection.enabled) {
            // At least one rule enabled → enable collection
            mockkRulesStore.updateCollection(collectionId, enabled = true)
            logger.debug("Auto-enabled collection '${collection.name}' (has active rules)")
        } else if (!hasEnabledRules && collection.enabled) {
            // All rules disabled → disable collection
            mockkRulesStore.updateCollection(collectionId, enabled = false)
            logger.debug("Auto-disabled collection '${collection.name}' (no active rules)")
        }

        // Update collection node in tree
        updateCollectionNodeInTree(collectionId)
    }

    /**
     * Clean up conflicts when the panel loads.
     * For each group of identical rules, keep only the first one enabled.
     */
    private fun cleanupConflictsOnLoad() {
        val allRules = mockkRulesStore.getAllRules()
        val enabledRules = allRules.filter { rule ->
            rule.enabled && mockkRulesStore.getCollection(rule.collectionId)?.enabled == true
        }

        val processedGroups = mutableSetOf<String>()
        var conflictsResolved = 0

        for (rule in enabledRules) {
            val ruleSignature = getRuleSignature(rule)
            if (processedGroups.contains(ruleSignature)) continue

            // Find all identical rules
            val identicalRules = enabledRules.filter {
                areRulesIdentical(rule, it)
            }.sortedBy { it.collectionId }

            if (identicalRules.size > 1) {
                // Keep first one enabled, disable the rest
                for (i in 1 until identicalRules.size) {
                    mockkRulesStore.setRuleEnabled(identicalRules[i], false)
                    updateRuleNodeInTree(identicalRules[i].id)
                    conflictsResolved++
                }
            }

            processedGroups.add(ruleSignature)
        }

        if (conflictsResolved > 0) {
            logger.info("🔄 Resolved $conflictsResolved conflicting rule(s) on load")

            // Refresh all nodes with FRESH data from store
            val allRulesMap = mockkRulesStore.getAllRules().associateBy { it.id }

            for (i in 0 until rootNode.childCount) {
                val collectionNode = rootNode.getChildAt(i) as DefaultMutableTreeNode
                val collectionData = collectionNode.userObject as? TreeNode.CollectionNode

                if (collectionData != null) {
                    // Update collection node with fresh data
                    val freshCollection = mockkRulesStore.getCollection(collectionData.collection.id)
                    if (freshCollection != null) {
                        collectionNode.userObject = TreeNode.CollectionNode(freshCollection)
                    }
                    treeModel.nodeChanged(collectionNode)
                }

                // Refresh all rule nodes with fresh data
                for (j in 0 until collectionNode.childCount) {
                    val ruleNode = collectionNode.getChildAt(j) as DefaultMutableTreeNode
                    val ruleData = ruleNode.userObject as? TreeNode.RuleNode

                    if (ruleData != null) {
                        val freshRule = allRulesMap[ruleData.rule.id]
                        if (freshRule != null) {
                            ruleNode.userObject = TreeNode.RuleNode(freshRule)
                        }
                        treeModel.nodeChanged(ruleNode)
                    }
                }
            }
        }

        // Sync all collections after cleanup
        val allCollections = mockkRulesStore.getAllCollections()
        for (collection in allCollections) {
            syncCollectionStateWithRules(collection.id)
        }
    }

    /**
     * Get a unique signature for a rule based on its request pattern.
     */
    private fun getRuleSignature(rule: MockkRulesStore.MockkRule): String {
        val params = rule.queryParams.sortedBy { it.key }.joinToString(",") {
            "${it.key}=${it.value}:${it.required}:${it.matchType}"
        }
        return "${rule.method}:${rule.scheme}:${rule.host}:${rule.path}:$params"
    }

    /**
     * Find all rules that conflict with the given rule (same request pattern).
     * Returns only enabled rules from other collections.
     */
    private fun findConflictingRules(rule: MockkRulesStore.MockkRule): List<MockkRulesStore.MockkRule> {
        val allRules = mockkRulesStore.getAllRules()
        return allRules.filter { otherRule ->
            // Skip self
            otherRule.id != rule.id &&
            // Only consider enabled rules
            otherRule.enabled &&
            // Only from other collections
            otherRule.collectionId != rule.collectionId &&
            // Check if the collection is enabled
            mockkRulesStore.getCollection(otherRule.collectionId)?.enabled == true &&
            // Check if requests are identical
            areRulesIdentical(rule, otherRule)
        }
    }

    /**
     * Check if two rules match the exact same request pattern.
     */
    private fun areRulesIdentical(rule1: MockkRulesStore.MockkRule, rule2: MockkRulesStore.MockkRule): Boolean {
        // Must have same method
        if (rule1.method != rule2.method) return false

        // Must have same scheme
        if (rule1.scheme != rule2.scheme) return false

        // Must have same host pattern
        if (rule1.host != rule2.host) return false

        // Must have same path pattern
        if (rule1.path != rule2.path) return false

        // Must have same query parameters
        if (rule1.queryParams.size != rule2.queryParams.size) return false

        // Sort and compare query params
        val sortedParams1 = rule1.queryParams.sortedBy { it.key }
        val sortedParams2 = rule2.queryParams.sortedBy { it.key }

        for (i in sortedParams1.indices) {
            val param1 = sortedParams1[i]
            val param2 = sortedParams2[i]
            if (param1.key != param2.key ||
                param1.value != param2.value ||
                param1.required != param2.required ||
                param1.matchType != param2.matchType) {
                return false
            }
        }

        // Rules are identical
        return true
    }

    /**
     * Update a specific rule node in the tree by its ID.
     */
    private fun updateRuleNodeInTree(ruleId: String) {
        for (i in 0 until rootNode.childCount) {
            val collectionNode = rootNode.getChildAt(i) as DefaultMutableTreeNode
            for (j in 0 until collectionNode.childCount) {
                val ruleNode = collectionNode.getChildAt(j) as DefaultMutableTreeNode
                val nodeData = ruleNode.userObject as? TreeNode.RuleNode
                if (nodeData?.rule?.id == ruleId) {
                    // Update with fresh data from store
                    val updatedRule = mockkRulesStore.getAllRules().find { it.id == ruleId }
                    if (updatedRule != null) {
                        ruleNode.userObject = TreeNode.RuleNode(updatedRule)
                        treeModel.nodeChanged(ruleNode)
                    }
                    return
                }
            }
        }
    }

    /**
     * Update a specific collection node in the tree by its ID.
     * This refreshes the "(X/Y active)" badge.
     */
    private fun updateCollectionNodeInTree(collectionId: String) {
        for (i in 0 until rootNode.childCount) {
            val collectionNode = rootNode.getChildAt(i) as DefaultMutableTreeNode
            val nodeData = collectionNode.userObject as? TreeNode.CollectionNode
            if (nodeData?.collection?.id == collectionId) {
                // Update with fresh data from store
                val updatedCollection = mockkRulesStore.getCollection(collectionId)
                if (updatedCollection != null) {
                    collectionNode.userObject = TreeNode.CollectionNode(updatedCollection)
                    treeModel.nodeChanged(collectionNode)
                }
                return
            }
        }
    }

    /**
     * Check if a rule has enabled duplicates in other enabled collections.
     */
    private fun hasEnabledDuplicatesInOtherCollections(rule: MockkRulesStore.MockkRule): Boolean {
        val allRules = mockkRulesStore.getAllRules()
        return allRules.any { otherRule ->
            otherRule.id != rule.id &&
            otherRule.enabled &&
            otherRule.collectionId != rule.collectionId &&
            mockkRulesStore.getCollection(otherRule.collectionId)?.enabled == true &&
            areRulesIdentical(rule, otherRule)
        }
    }

    /**
     * Check if a rule has ANY duplicates in other collections (enabled or disabled).
     */
    private fun hasAnyDuplicatesInOtherCollections(rule: MockkRulesStore.MockkRule): Boolean {
        val allRules = mockkRulesStore.getAllRules()
        return allRules.any { otherRule ->
            otherRule.id != rule.id &&
            otherRule.collectionId != rule.collectionId &&
            areRulesIdentical(rule, otherRule)
        }
    }

    private fun exportSelected() {
        val selectedPath = tree.selectionPath
        val collectionsToExport = if (selectedPath != null) {
            val node = selectedPath.lastPathComponent as DefaultMutableTreeNode
            when (val nodeData = node.userObject) {
                is TreeNode.CollectionNode -> listOf(nodeData.collection)
                is TreeNode.RuleNode -> {
                    // Export the collection containing this rule
                    val collection = mockkRulesStore.getCollection(nodeData.rule.collectionId)
                    if (collection != null) listOf(collection) else emptyList()
                }
                else -> emptyList()
            }
        } else {
            // No selection, export all
            mockkRulesStore.getAllCollections()
        }

        if (collectionsToExport.isEmpty()) {
            Messages.showErrorDialog(this, "No collections to export", "Error")
            return
        }

        // Show file chooser
        val fileChooser = JFileChooser()
        fileChooser.dialogTitle = "Export Collections to JSON"
        fileChooser.selectedFile = File("mockk-collections-${System.currentTimeMillis()}.json")
        fileChooser.fileFilter = object : FileFilter() {
            override fun accept(f: File?): Boolean {
                return f?.isDirectory == true || f?.name?.endsWith(".json") == true
            }
            override fun getDescription(): String = "JSON files (*.json)"
        }

        if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                val file = fileChooser.selectedFile
                val json = mockkRulesStore.exportCollections(collectionsToExport)
                file.writeText(json)

                logger.info("✅ Exported ${collectionsToExport.size} collection(s) to ${file.absolutePath}")
                Messages.showInfoMessage(
                    this,
                    "Successfully exported ${collectionsToExport.size} collection(s)",
                    "Export Successful"
                )
            } catch (e: Exception) {
                logger.error("Failed to export collections", e)
                Messages.showErrorDialog(this, "Failed to export: ${e.message}", "Export Failed")
            }
        }
    }

    private fun importCollections() {
        val fileChooser = JFileChooser()
        fileChooser.dialogTitle = "Import Collections from JSON"
        fileChooser.fileFilter = object : FileFilter() {
            override fun accept(f: File?): Boolean {
                return f?.isDirectory == true || f?.name?.endsWith(".json") == true
            }
            override fun getDescription(): String = "JSON files (*.json)"
        }

        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                val file = fileChooser.selectedFile
                val json = file.readText()

                val imported = mockkRulesStore.importCollections(json, renameOnConflict = true)

                logger.info("✅ Imported ${imported.size} collection(s)")
                Messages.showInfoMessage(
                    this,
                    "Successfully imported ${imported.size} collection(s)",
                    "Import Successful"
                )
            } catch (e: Exception) {
                logger.error("Failed to import collections", e)
                Messages.showErrorDialog(this, "Failed to import: ${e.message}", "Import Failed")
            }
        }
    }

    private fun showContextMenu(e: MouseEvent) {
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        tree.selectionPath = path

        val node = path.lastPathComponent as DefaultMutableTreeNode
        val nodeData = node.userObject as? TreeNode ?: return

        val popup = JPopupMenu()

        when (nodeData) {
            is TreeNode.CollectionNode -> {
                val collection = nodeData.collection
                popup.add(JMenuItem("Edit Collection").apply {
                    addActionListener { editCollection(collection) }
                })
                popup.add(JMenuItem("New Mock in this Collection").apply {
                    addActionListener {
                        val dialog = CreateMockDialog(project, targetCollectionId = collection.id)
                        dialog.showAndGet()
                    }
                })
                popup.addSeparator()
                popup.add(JMenuItem(if (collection.enabled) "Disable Collection" else "Enable Collection").apply {
                    addActionListener { toggleCollectionEnabled(collection) }
                })
                popup.add(JMenuItem("Export Collection").apply {
                    addActionListener {
                        tree.selectionPath = path
                        exportSelected()
                    }
                })
                popup.addSeparator()
                popup.add(JMenuItem("Delete Collection").apply {
                    addActionListener { deleteSelected() }
                })
            }
            is TreeNode.RuleNode -> {
                val rule = nodeData.rule
                popup.add(JMenuItem("Edit Rule").apply {
                    addActionListener { editRule(rule) }
                })
                popup.add(JMenuItem("Duplicate to...").apply {
                    addActionListener { duplicateSelected() }
                })
                popup.addSeparator()
                popup.add(JMenuItem(if (rule.enabled) "Disable Rule" else "Enable Rule").apply {
                    addActionListener { toggleRuleEnabled(rule) }
                })
                popup.addSeparator()
                popup.add(JMenuItem("Delete Rule").apply {
                    addActionListener { deleteSelected() }
                })
            }
        }

        popup.show(tree, e.x, e.y)
    }

    /**
     * Custom tree cell renderer with icons and badges.
     */
    private inner class MockkTreeCellRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: JTree?,
            value: Any?,
            sel: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ): Component {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)

            if (value is DefaultMutableTreeNode) {
                when (val nodeData = value.userObject) {
                    is TreeNode.CollectionNode -> {
                        val collection = nodeData.collection

                        // Get rules and check if any are enabled
                        val rules = mockkRulesStore.getRulesInCollection(collection.id)
                        val enabledRulesCount = rules.count { it.enabled }
                        val hasActiveRules = enabledRulesCount > 0

                        // ALWAYS show folder icon (never changes)
                        icon = AllIcons.Nodes.Folder

                        // Build text with badge
                        val badge = if (collection.packageName.isNotEmpty()) {
                            " [${collection.packageName}] ($enabledRulesCount/${rules.size} active)"
                        } else {
                            " ($enabledRulesCount/${rules.size} active)"
                        }

                        // Add "✗" prefix if no active rules
                        val prefix = if (!hasActiveRules) "✗ " else ""
                        text = prefix + collection.name + badge

                        // Tooltip
                        toolTipText = "Click on folder icon to toggle collection | Double-click name to edit"

                        // Color based on state: white/normal = active, gray = inactive
                        if (!sel) {
                            if (hasActiveRules) {
                                foreground = UIManager.getColor("Tree.foreground") ?: JBColor.WHITE
                                font = font.deriveFont(Font.BOLD)
                            } else {
                                // Inactive: gray color but NO italic (no tachado/rallito)
                                foreground = JBColor.GRAY
                                font = font.deriveFont(Font.PLAIN)
                            }
                        }
                    }
                    is TreeNode.RuleNode -> {
                        val rule = nodeData.rule

                        // Rule icon - same for all
                        icon = AllIcons.Actions.Lightning

                        // Check if this rule has duplicates in other collections
                        val hasDuplicates = hasEnabledDuplicatesInOtherCollections(rule)

                        // Build text with duplicate indicator
                        val duplicateIndicator = if (hasDuplicates && rule.enabled) {
                            " ⚠️ "
                        } else if (hasAnyDuplicatesInOtherCollections(rule)) {
                            " ⓘ "
                        } else {
                            ""
                        }

                        // Add "✗" prefix if disabled
                        val prefix = if (!rule.enabled) "✗ " else ""
                        text = "$prefix${rule.name}$duplicateIndicator - ${rule.method} ${rule.host}${rule.path}"

                        // Tooltip
                        toolTipText = "Click on icon to toggle rule | Double-click name to edit"

                        // Color based on state: white/normal = enabled, gray = disabled
                        if (!sel) {
                            if (rule.enabled) {
                                foreground = UIManager.getColor("Tree.foreground") ?: JBColor.WHITE
                            } else {
                                foreground = JBColor.GRAY
                                font = font.deriveFont(Font.ITALIC)
                            }
                        }
                    }
                }
            }

            return this
        }
    }
}
