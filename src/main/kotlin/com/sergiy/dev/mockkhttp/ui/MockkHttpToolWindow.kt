package com.sergiy.dev.mockkhttp.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Main Tool Window content for MockkHttp plugin.
 * Contains tabs for Controls and Logs.
 */
class MockkHttpToolWindow(project: Project) : JPanel(BorderLayout()) {

    private val tabbedPane: JBTabbedPane
    
    init {

        // Create tabbed pane
        tabbedPane = JBTabbedPane()

        // Add Inspector tab (combines Controls + Recording)
        try {
            val inspectorPanel = InspectorPanel(project)
            tabbedPane.addTab("Inspector", inspectorPanel)
        } catch (e: Exception) {
            throw e
        }

        // Add Mockk tab
        try {
            val mockkPanel = MockkRulesPanel(project)
            tabbedPane.addTab("Mockk", mockkPanel)
        } catch (e: Exception) {
            throw e
        }

        // Add Help tab
        try {
            val helpPanel = HelpPanel(project)
            tabbedPane.addTab("Help", helpPanel)
        } catch (e: Exception) {
            throw e
        }

        add(tabbedPane, BorderLayout.CENTER)

    }
}
