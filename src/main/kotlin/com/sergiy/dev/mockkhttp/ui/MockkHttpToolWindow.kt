package com.sergiy.dev.mockkhttp.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTabbedPane
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Main Tool Window content for MockkHttp plugin.
 * Contains tabs for Controls and Logs.
 */
class MockkHttpToolWindow(project: Project) : JPanel(BorderLayout()) {

    private val logger = MockkHttpLogger.getInstance(project)
    private val tabbedPane: JBTabbedPane
    
    init {
        logger.info("MockkHttpToolWindow initializing...")

        // Create tabbed pane
        tabbedPane = JBTabbedPane()

        // Add Inspector tab (combines Controls + Recording)
        try {
            val inspectorPanel = InspectorPanel(project)
            tabbedPane.addTab("Inspector", inspectorPanel)
            logger.debug("Inspector panel added successfully")
        } catch (e: Exception) {
            logger.error("Failed to create Inspector panel", e)
            throw e
        }

        // Add Mockk tab
        try {
            val mockkPanel = MockkRulesPanel(project)
            tabbedPane.addTab("Mockk", mockkPanel)
            logger.debug("Mockk panel added successfully")
        } catch (e: Exception) {
            logger.error("Failed to create Mockk panel", e)
            throw e
        }

        // Add Logs tab
        try {
            val logsPanel = LogPanel(project)
            tabbedPane.addTab("Logs", logsPanel)
            logger.debug("Logs panel added successfully")
        } catch (e: Exception) {
            logger.error("Failed to create Logs panel", e)
            throw e
        }

        // Add Config tab
        try {
            val configPanel = ConfigPanel(project)
            tabbedPane.addTab("Config", configPanel)
            logger.debug("Config panel added successfully")
        } catch (e: Exception) {
            logger.error("Failed to create Config panel", e)
            throw e
        }

        add(tabbedPane, BorderLayout.CENTER)

        logger.info("MockkHttpToolWindow initialized successfully")
    }
}
