package com.sergiy.dev.mockkhttp.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger

/**
 * Factory for creating the MockkHttp Tool Window.
 * Registered in plugin.xml.
 */
class MockkHttpToolWindowFactory : ToolWindowFactory {
    
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val logger = MockkHttpLogger.getInstance(project)
        logger.info("Creating MockkHttp Tool Window content...")
        
        try {
            // Create main window content
            val toolWindowContent = MockkHttpToolWindow(project)
            
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
