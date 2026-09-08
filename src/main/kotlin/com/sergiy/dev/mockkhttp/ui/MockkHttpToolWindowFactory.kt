package com.sergiy.dev.mockkhttp.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger

/**
 * Factory for creating the MockkHttp Tool Window.
 * Registered in plugin.xml.
 *
 * Implements DumbAware to make the tool window available during project indexing.
 * This allows users to access MockkHttp functionality immediately after opening a project,
 * without waiting for the indexing process to complete.
 */
class MockkHttpToolWindowFactory : ToolWindowFactory, DumbAware {
    
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val logger = MockkHttpLogger.getInstance(project)
        logger.info("Creating MockkHttp Tool Window content...")
        
        try {
            // toolWindow.disposable is disposed when the tool window goes away — including on a
            // dynamic plugin unload (update or disable without restart). Panels that keep timers or
            // app-level listeners must hang off THAT, not off the project, or they outlive the
            // classloader they belong to and the IDE has to force a restart.
            val toolWindowContent = MockkHttpToolWindow(project, toolWindow.disposable)
            
            // Create content and add to tool window
            val contentFactory = ContentFactory.getInstance()
            val content = contentFactory.createContent(toolWindowContent, "", false)
            
            toolWindow.contentManager.addContent(content)
            
            logger.info("MockkHttp Tool Window content created successfully")
        } catch (e: Exception) {
            logger.error("Failed to create Tool Window content", e)
            throw e
        }
    }
    
    override fun shouldBeAvailable(project: Project): Boolean = true
}
