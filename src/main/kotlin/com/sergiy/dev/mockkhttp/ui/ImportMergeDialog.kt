package com.sergiy.dev.mockkhttp.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.sergiy.dev.mockkhttp.store.MockkRulesStore
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.Action
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton

/**
 * Shown when an import file contains collections that already exist (matched by
 * name). Lets the user MERGE — add the missing rules, and decide what to do with
 * rules whose endpoint exists but whose response changed — or fall back to
 * importing everything as separate renamed copies (the old behavior).
 */
class ImportMergeDialog(
    project: Project,
    private val analysis: MockkRulesStore.ImportAnalysis
) : DialogWrapper(project) {

    enum class Mode { MERGE, SEPARATE_COPIES }

    var selectedMode: Mode = Mode.MERGE
        private set

    private val replaceRadio = JRadioButton("Replace with the imported version", true)
    private val keepBothRadio = JRadioButton("Keep both (imported copy added DISABLED — enable it when you want to use it)")
    private val skipRadio = JRadioButton("Skip them (keep my current version)")

    val selectedStrategy: MockkRulesStore.ChangedRuleStrategy
        get() = when {
            replaceRadio.isSelected -> MockkRulesStore.ChangedRuleStrategy.REPLACE
            keepBothRadio.isSelected -> MockkRulesStore.ChangedRuleStrategy.KEEP_BOTH
            else -> MockkRulesStore.ChangedRuleStrategy.SKIP
        }

    init {
        title = "Import: Collections Already Exist"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(560, 360)

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
        }

        content.add(JLabel("The file overlaps with collections you already have:").apply {
            font = font.deriveFont(Font.BOLD)
            alignmentX = Component.LEFT_ALIGNMENT
        })
        content.add(Box.createVerticalStrut(8))

        // Per-collection summary
        val summaryPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        for (diff in analysis.diffs) {
            val line = if (diff.existing == null) {
                "📁 ${diff.incoming.collection.name} — NEW collection (${diff.incoming.rules.size} rule(s))"
            } else {
                "📁 ${diff.incoming.collection.name} — ${diff.newRules.size} missing to add · " +
                        "${diff.changedRules.size} changed · ${diff.identicalRules.size} identical"
            }
            summaryPanel.add(JLabel(line).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                if (diff.existing != null && diff.changedRules.isNotEmpty()) {
                    icon = AllIcons.General.Warning
                }
            })
            // Name the changed rules so the user knows what will be affected
            diff.changedRules.take(5).forEach { (incoming, _) ->
                summaryPanel.add(JLabel("        ↻ ${incoming.name}  (${incoming.method} ${incoming.path})").apply {
                    foreground = JBColor.GRAY
                    alignmentX = Component.LEFT_ALIGNMENT
                })
            }
            if (diff.changedRules.size > 5) {
                summaryPanel.add(JLabel("        … and ${diff.changedRules.size - 5} more changed").apply {
                    foreground = JBColor.GRAY
                    alignmentX = Component.LEFT_ALIGNMENT
                })
            }
        }
        content.add(summaryPanel)
        content.add(Box.createVerticalStrut(12))

        // Strategy for changed rules (only meaningful if there are any)
        val hasChanged = analysis.diffs.any { it.changedRules.isNotEmpty() }
        if (hasChanged) {
            content.add(JLabel("Rules that exist but whose response CHANGED in the file:").apply {
                font = font.deriveFont(Font.BOLD)
                alignmentX = Component.LEFT_ALIGNMENT
            })
            ButtonGroup().apply {
                add(replaceRadio); add(keepBothRadio); add(skipRadio)
            }
            listOf(replaceRadio, keepBothRadio, skipRadio).forEach {
                it.alignmentX = Component.LEFT_ALIGNMENT
                content.add(it)
            }
            content.add(Box.createVerticalStrut(8))
        }

        content.add(JLabel("Merge adds the missing rules into your existing collections. Identical rules are never duplicated.").apply {
            foreground = JBColor.GRAY
            alignmentX = Component.LEFT_ALIGNMENT
        })

        panel.add(JBScrollPane(content).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        return panel
    }

    override fun createActions(): Array<Action> {
        val mergeAction = object : DialogWrapperAction("Merge") {
            init { putValue(DEFAULT_ACTION, true) }
            override fun doAction(e: java.awt.event.ActionEvent?) {
                selectedMode = Mode.MERGE
                close(OK_EXIT_CODE)
            }
        }
        val separateAction = object : DialogWrapperAction("Import as Separate Copies") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                selectedMode = Mode.SEPARATE_COPIES
                close(OK_EXIT_CODE)
            }
        }
        return arrayOf(mergeAction, separateAction, cancelAction)
    }
}
