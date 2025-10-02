package com.sergiy.dev.mockkhttp.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import com.sergiy.dev.mockkhttp.store.MockkRulesStore
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Panel for managing Mockk rules.
 */
class MockkRulesPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val logger = MockkHttpLogger.getInstance(project)
    private val mockkRulesStore = MockkRulesStore.getInstance(project)
    private val rulesListModel: DefaultListModel<MockkRulesStore.MockkRule>
    private val rulesList: JBList<MockkRulesStore.MockkRule>

    init {
        logger.info("Initializing Mockk Rules Panel...")

        // Rules list
        rulesListModel = DefaultListModel()
        rulesList = JBList(rulesListModel).apply {
            cellRenderer = RuleListCellRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION

            // Mouse listener for double-click (edit) and single-click on icon (toggle)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val index = locationToIndex(e.point)
                    if (index >= 0) {
                        val bounds = getCellBounds(index, index)
                        if (bounds != null && bounds.contains(e.point)) {
                            val rule = rulesListModel.getElementAt(index)

                            // Check if click is on the icon area (first 30 pixels)
                            val clickX = e.point.x - bounds.x
                            if (clickX < 30) {
                                // Toggle enabled/disabled
                                mockkRulesStore.setRuleEnabled(rule, !rule.enabled)
                                repaint()
                            } else if (e.clickCount == 2) {
                                // Double-click to edit
                                editSelectedRule()
                            }
                        }
                    }
                }
            })
        }

        // Load existing rules
        mockkRulesStore.getAllRules().forEach { rule ->
            rulesListModel.addElement(rule)
        }

        // Listen for new rules
        mockkRulesStore.addRuleAddedListener { rule ->
            SwingUtilities.invokeLater {
                rulesListModel.addElement(rule)
            }
        }

        mockkRulesStore.addRuleRemovedListener { rule ->
            SwingUtilities.invokeLater {
                rulesListModel.removeElement(rule)
            }
        }

        // Toolbar
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("New Mock Rule", AllIcons.General.Add).apply {
                addActionListener { createNewRule() }
            })
            add(JButton("Edit", AllIcons.Actions.Edit).apply {
                addActionListener { editSelectedRule() }
            })
            add(JButton("Delete", AllIcons.Actions.Cancel).apply {
                addActionListener { deleteSelectedRule() }
            })
        }

        // Layout
        border = JBUI.Borders.empty(10)
        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(rulesList), BorderLayout.CENTER)

        logger.info("✅ Mockk Rules Panel initialized")
    }

    private fun createNewRule() {
        val dialog = CreateMockDialog(project)
        if (dialog.showAndGet()) {
            logger.info("New mock rule created")
        }
    }

    private fun editSelectedRule() {
        val selectedRule = rulesList.selectedValue
        if (selectedRule != null) {
            val dialog = CreateMockDialog(project, selectedRule)
            if (dialog.showAndGet()) {
                logger.info("Mock rule edited: ${selectedRule.name}")
                // Refresh the list to show updated rule
                rulesList.repaint()
            }
        }
    }

    private fun deleteSelectedRule() {
        val selectedRule = rulesList.selectedValue
        if (selectedRule != null) {
            mockkRulesStore.removeRule(selectedRule)
        }
    }

    /**
     * Custom cell renderer with clickable icon for enable/disable.
     */
    private inner class RuleListCellRenderer : JPanel(BorderLayout()), ListCellRenderer<MockkRulesStore.MockkRule> {

        private val iconLabel = JBLabel()
        private val nameLabel = JBLabel()
        private val detailsLabel = JBLabel()

        init {
            border = JBUI.Borders.empty(5)

            val leftPanel = JPanel(BorderLayout())
            leftPanel.add(iconLabel, BorderLayout.WEST)
            leftPanel.add(nameLabel, BorderLayout.CENTER)

            add(leftPanel, BorderLayout.CENTER)
            add(detailsLabel, BorderLayout.SOUTH)
        }

        override fun getListCellRendererComponent(
            list: JList<out MockkRulesStore.MockkRule>?,
            value: MockkRulesStore.MockkRule?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            if (value != null) {
                // Set icon based on enabled state
                iconLabel.icon = if (value.enabled) {
                    AllIcons.Actions.Checked    // Enabled: checkmark
                } else {
                    AllIcons.Actions.Lightning  // Disabled: lightning (inactive/off)
                }

                // Add spacing after icon
                iconLabel.border = JBUI.Borders.emptyRight(5)

                nameLabel.text = value.name
                detailsLabel.text = "   ${value.method} ${value.getDisplayUrl()}"
                detailsLabel.foreground = if (value.enabled) {
                    JBColor.GRAY
                } else {
                    JBColor.GRAY.darker()
                }

                // Dim the name if disabled
                nameLabel.foreground = if (value.enabled) {
                    list?.foreground
                } else {
                    JBColor.GRAY
                }
            }

            background = if (isSelected) list?.selectionBackground else list?.background
            isOpaque = true

            return this
        }
    }
}
