package com.sergiy.dev.mockkhttp.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
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

        // Clear any previous listeners (IDE may create the tool window multiple times)
        mockkRulesStore.clearAllListeners()

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
            add(JSeparator(SwingConstants.VERTICAL))
            add(JButton("Delete All", AllIcons.Actions.GC).apply {
                toolTipText = "Delete ALL collections and their rules (useful after a duplicate import)"
                addActionListener { deleteAllCollections() }
            })
        }

        toolbar.add(row1)
        toolbar.add(row2)
        return toolbar
    }

    /**
     * Delete every collection and rule after explicit confirmation. Handy to clean up when an
     * import has duplicated many collections.
     */
    private fun deleteAllCollections() {
        val collectionCount = mockkRulesStore.getAllCollections().size
        if (collectionCount == 0) {
            Messages.showInfoMessage(this, "There are no collections to delete.", "Nothing to Delete")
            return
        }
        val ruleCount = mockkRulesStore.getAllRules().size
        val result = Messages.showYesNoDialog(
            this,
            "Delete ALL $collectionCount collection(s) and $ruleCount mock rule(s)?\nThis cannot be undone.",
            "Delete All Collections",
            Messages.getWarningIcon()
        )
        if (result == Messages.YES) {
            val (removedCollections, removedRules) = mockkRulesStore.removeAllCollections()
            loadTreeData()
            logger.info("🗑️ Deleted all collections ($removedCollections) and rules ($removedRules)")
        }
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
                // Check if rule already exists in the tree (addCollectionToTree may have loaded it)
                val alreadyExists = (0 until collectionNode.childCount).any { j ->
                    val existingNode = collectionNode.getChildAt(j) as DefaultMutableTreeNode
                    (existingNode.userObject as? TreeNode.RuleNode)?.rule?.id == rule.id
                }
                if (alreadyExists) return

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
            val allDisabledConflicts = mutableListOf<MockkRulesStore.MockkRule>()
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
                    allDisabledConflicts.addAll(conflicts)

                    updateRuleNodeInTree(rule.id)
                }
            }
            // Make the automatic resolution visible here too (not only on rule toggles)
            notifyConflictsDisabled("collection '${collection.name}'", allDisabledConflicts)
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
                // Make the automatic resolution visible to the user
                notifyConflictsDisabled("'${rule.name}'", conflicts)
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
        // The match modes are part of a rule's identity: api.example.com as EXACT and as REGEX
        // select different traffic, so two rules that differ only there are NOT duplicates.
        return "${rule.method}:${rule.scheme}:${rule.host}/${rule.hostMatch}:${rule.path}/${rule.pathMatch}:$params"
    }

    /**
     * Find all rules that conflict with the given rule (same request pattern).
     * Returns enabled rules from ANY collection (including the rule's own — e.g.
     * a "(imported)" copy added by a merge import next to the original).
     */
    private fun findConflictingRules(rule: MockkRulesStore.MockkRule): List<MockkRulesStore.MockkRule> {
        val allRules = mockkRulesStore.getAllRules()
        return allRules.filter { otherRule ->
            // Skip self
            otherRule.id != rule.id &&
            // Only consider enabled rules
            otherRule.enabled &&
            // Check if the collection is enabled
            mockkRulesStore.getCollection(otherRule.collectionId)?.enabled == true &&
            // Check if requests are identical
            areRulesIdentical(rule, otherRule)
        }
    }

    /** Escape user-controlled names before embedding them in HTML tooltips/notifications. */
    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * Human-readable, HTML-safe list of rules (any state) targeting the same
     * endpoint, e.g. "'login OK' (Auth, active), 'login 500' (Errors, inactive)".
     * Returns null when the rule has no duplicates.
     */
    private fun describeDuplicates(rule: MockkRulesStore.MockkRule): String? {
        val duplicates = mockkRulesStore.getAllRules().filter {
            it.id != rule.id && areRulesIdentical(rule, it)
        }
        if (duplicates.isEmpty()) return null
        return duplicates.joinToString(", ") { other ->
            val collectionName = mockkRulesStore.getCollection(other.collectionId)?.name ?: "?"
            "'${escapeHtml(other.name)}' (${escapeHtml(collectionName)}, ${if (other.enabled) "active" else "inactive"})"
        }
    }

    /**
     * Balloon notification so automatic conflict resolution is VISIBLE —
     * silently flipping other rules was confusing.
     */
    private fun notifyConflictsDisabled(enabledLabel: String, disabled: List<MockkRulesStore.MockkRule>) {
        if (disabled.isEmpty()) return
        val disabledList = disabled.joinToString(", ") { other ->
            val collectionName = mockkRulesStore.getCollection(other.collectionId)?.name ?: "?"
            "'${escapeHtml(other.name)}' (${escapeHtml(collectionName)})"
        }
        try {
            com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup("MockkHttp Notifications")
                .createNotification(
                    "Mock conflict resolved",
                    "Enabled ${escapeHtml(enabledLabel)} — disabled ${disabled.size} rule(s) answering the same endpoint: $disabledList",
                    com.intellij.notification.NotificationType.INFORMATION
                )
                .notify(project)
        } catch (_: Exception) {
            // Notification group unavailable — the log already records it
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

        // Must have same host pattern, compared the same way
        if (rule1.host != rule2.host) return false
        if (rule1.hostMatch != rule2.hostMatch) return false

        // Must have same path pattern, compared the same way
        if (rule1.path != rule2.path) return false
        if (rule1.pathMatch != rule2.pathMatch) return false

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

        // Use IntelliJ Platform FileSaverDialog for proper theme support
        // Try FileSaverDescriptorFactory (2024.3+, non-deprecated) with fallback to constructor (older versions)
        val descriptor = createFileSaverDescriptor(
            "Export Collections to JSON",
            "Select destination file for exported collections",
            "json"
        )

        val defaultName = "mockk-collections-${System.currentTimeMillis()}.json"
        val initialDir = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath(System.getProperty("user.home") ?: "")

        com.intellij.openapi.fileChooser.FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(initialDir, defaultName)?.let { fileWrapper ->
                try {
                    val file = fileWrapper.file
                    val json = mockkRulesStore.exportCollections(collectionsToExport)
                    file.writeText(json)

                    logger.info("✅ Exported ${collectionsToExport.size} collection(s) to ${file.absolutePath}")
                    Messages.showInfoMessage(
                        this,
                        "Successfully exported ${collectionsToExport.size} collection(s) to:\n${file.name}",
                        "Export Successful"
                    )

                    // Refresh VFS
                    com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                } catch (e: Exception) {
                    logger.error("Failed to export collections", e)
                    Messages.showErrorDialog(this, "Failed to export: ${e.message}", "Export Failed")
                }
            }
    }

    private fun importCollections() {
        // Use IntelliJ Platform FileChooser for proper theme support
        val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptor(
            true,   // chooseFiles
            false,  // chooseFolders
            false,  // chooseJars
            false,  // chooseJarsAsFiles
            false,  // chooseJarContents
            false   // chooseMultiple
        ).apply {
            title = "Import Collections from JSON"
            description = "Select JSON file containing exported collections"
            withFileFilter { it.extension == "json" }
        }

        val initialDir = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath(System.getProperty("user.home") ?: "")

        com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project, initialDir) { virtualFile ->
            try {
                val file = File(virtualFile.path)
                val json = file.readText()

                val analysis = mockkRulesStore.analyzeImport(json)

                if (!analysis.hasExistingCollections) {
                    // Nothing overlaps: plain import
                    val imported = mockkRulesStore.importCollections(json, renameOnConflict = true)
                    logger.info("✅ Imported ${imported.size} collection(s) from ${file.name}")
                    Messages.showInfoMessage(
                        this,
                        "Successfully imported ${imported.size} collection(s) from:\n${file.name}",
                        "Import Successful"
                    )
                } else {
                    // Some collections already exist: offer a merge instead of duplicating
                    val dialog = ImportMergeDialog(project, analysis)
                    if (!dialog.showAndGet()) return@chooseFile

                    when (dialog.selectedMode) {
                        ImportMergeDialog.Mode.MERGE -> {
                            val result = mockkRulesStore.applyMergeImport(analysis, dialog.selectedStrategy)
                            // Keep collection enabled-state consistent with their (possibly new) rules
                            mockkRulesStore.getAllCollections().forEach { syncCollectionStateWithRules(it.id) }
                            Messages.showInfoMessage(
                                this,
                                buildString {
                                    appendLine("Merge complete:")
                                    if (result.collectionsCreated > 0) appendLine("• ${result.collectionsCreated} new collection(s) created")
                                    appendLine("• ${result.rulesAdded} missing rule(s) added")
                                    if (result.rulesReplaced > 0) appendLine("• ${result.rulesReplaced} rule(s) replaced with the imported version")
                                    if (result.rulesKeptBoth > 0) appendLine("• ${result.rulesKeptBoth} changed rule(s) added alongside the existing ones")
                                    appendLine("• ${result.rulesSkipped} identical/skipped rule(s) left untouched")
                                },
                                "Import Merged"
                            )
                        }
                        ImportMergeDialog.Mode.SEPARATE_COPIES -> {
                            val imported = mockkRulesStore.importCollections(json, renameOnConflict = true)
                            Messages.showInfoMessage(
                                this,
                                "Imported ${imported.size} collection(s) as separate copies.",
                                "Import Successful"
                            )
                        }
                    }
                }

                // Refresh tree to show imported/merged collections
                SwingUtilities.invokeLater { loadTreeData() }
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

        val actionGroup = DefaultActionGroup()

        when (nodeData) {
            is TreeNode.CollectionNode -> {
                val collection = nodeData.collection

                actionGroup.add(object : AnAction("Edit Collection", null, AllIcons.Actions.Edit) {
                    override fun actionPerformed(e: AnActionEvent) {
                        editCollection(collection)
                    }
                })

                actionGroup.add(object : AnAction("New Mock in This Collection", null, AllIcons.General.Add) {
                    override fun actionPerformed(e: AnActionEvent) {
                        val dialog = CreateMockDialog(project, targetCollectionId = collection.id)
                        dialog.showAndGet()
                    }
                })

                actionGroup.addSeparator()

                actionGroup.add(object : AnAction(
                    if (collection.enabled) "Disable Collection" else "Enable Collection",
                    null,
                    if (collection.enabled) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
                ) {
                    override fun actionPerformed(e: AnActionEvent) {
                        toggleCollectionEnabled(collection)
                    }
                })

                actionGroup.add(object : AnAction("Export Collection", null, AllIcons.ToolbarDecorator.Export) {
                    override fun actionPerformed(e: AnActionEvent) {
                        tree.selectionPath = path
                        exportSelected()
                    }
                })

                actionGroup.addSeparator()

                actionGroup.add(object : AnAction("Delete Collection", null, AllIcons.Actions.Cancel) {
                    override fun actionPerformed(e: AnActionEvent) {
                        deleteSelected()
                    }
                })
            }
            is TreeNode.RuleNode -> {
                val rule = nodeData.rule

                actionGroup.add(object : AnAction("Edit Rule", null, AllIcons.Actions.Edit) {
                    override fun actionPerformed(e: AnActionEvent) {
                        editRule(rule)
                    }
                })

                actionGroup.add(object : AnAction("Duplicate To...", null, AllIcons.Actions.Copy) {
                    override fun actionPerformed(e: AnActionEvent) {
                        duplicateSelected()
                    }
                })

                actionGroup.addSeparator()

                actionGroup.add(object : AnAction(
                    if (rule.enabled) "Disable Rule" else "Enable Rule",
                    null,
                    if (rule.enabled) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
                ) {
                    override fun actionPerformed(e: AnActionEvent) {
                        toggleRuleEnabled(rule)
                    }
                })

                actionGroup.addSeparator()

                actionGroup.add(object : AnAction("Delete Rule", null, AllIcons.Actions.Cancel) {
                    override fun actionPerformed(e: AnActionEvent) {
                        deleteSelected()
                    }
                })
            }
        }

        val popup = ActionManager.getInstance().createActionPopupMenu("MockkRulesPanel.ContextMenu", actionGroup)
        popup.component.show(tree, e.x, e.y)
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

                        // Checkbox icon: makes the one-click toggle DISCOVERABLE
                        // (the click zone over the icon already toggles the collection)
                        icon = if (hasActiveRules) AllIcons.Diff.GutterCheckBoxSelected else AllIcons.Diff.GutterCheckBox

                        // Build text with badge
                        val badge = if (collection.packageName.isNotEmpty()) {
                            " [${collection.packageName}] ($enabledRulesCount/${rules.size} active)"
                        } else {
                            " ($enabledRulesCount/${rules.size} active)"
                        }

                        text = collection.name + badge

                        // Tooltip
                        toolTipText = "Click the checkbox to enable/disable the collection | Double-click name to edit"

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

                        // Checkbox icon: makes the one-click toggle DISCOVERABLE
                        icon = if (rule.enabled) AllIcons.Diff.GutterCheckBoxSelected else AllIcons.Diff.GutterCheckBox

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

                        text = "${rule.name}$duplicateIndicator - ${rule.method} ${rule.host}${rule.path}"

                        // Tooltip: name WHICH rules target the same endpoint, so the
                        // user can see at a glance who else answers this call
                        val duplicatesInfo = describeDuplicates(rule)
                        toolTipText = if (duplicatesInfo != null) {
                            "<html>Click the checkbox to enable/disable | Double-click to edit<br><b>Same endpoint as:</b> $duplicatesInfo</html>"
                        } else {
                            "Click the checkbox to enable/disable | Double-click name to edit"
                        }

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

    companion object {
        /**
         * Creates a FileSaverDescriptor using the modern non-deprecated API when available.
         * Uses FileSaverDescriptorFactory (IntelliJ 2024.3+) with fallback to constructor via reflection.
         * All access is via reflection to avoid deprecated API references in bytecode.
         */
        private fun createFileSaverDescriptor(
            title: String,
            description: String,
            extension: String?
        ): com.intellij.openapi.fileChooser.FileSaverDescriptor {
            // Try to use FileSaverDescriptorFactory (available in IntelliJ 2024.3+, non-deprecated)
            try {
                val factoryClass = Class.forName("com.intellij.openapi.fileChooser.FileSaverDescriptorFactory")
                val createMethod = factoryClass.getMethod("createSingleFileNoJarsDescriptor")
                val descriptor = createMethod.invoke(null) as com.intellij.openapi.fileChooser.FileSaverDescriptor

                // Configure the descriptor using fluent API
                descriptor.withTitle(title)
                descriptor.withDescription(description)

                // Add extension filter if specified
                if (extension != null) {
                    try {
                        val filterMethod = descriptor.javaClass.getMethod("withExtensionFilter", String::class.java)
                        filterMethod.invoke(descriptor, extension)
                    } catch (_: Exception) {
                        // withExtensionFilter not available in this version - OK
                    }
                }

                return descriptor
            } catch (_: Exception) {
                // FileSaverDescriptorFactory doesn't exist or error - use reflection fallback
            }

            // Fallback: Use constructor via reflection to avoid deprecated API reference in bytecode
            // This is needed for IntelliJ 2024.1.x/2024.2.x compatibility
            try {
                val descriptorClass = Class.forName("com.intellij.openapi.fileChooser.FileSaverDescriptor")
                val constructor = descriptorClass.getConstructor(String::class.java, String::class.java)
                return constructor.newInstance(title, description) as com.intellij.openapi.fileChooser.FileSaverDescriptor
            } catch (e: Exception) {
                // This should never happen, but provide a last resort
                throw IllegalStateException("Failed to create FileSaverDescriptor: ${e.message}", e)
            }
        }
    }
}
