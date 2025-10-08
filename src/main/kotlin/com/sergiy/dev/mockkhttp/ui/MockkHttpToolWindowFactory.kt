package com.sergiy.dev.mockkhttp.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

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
        try {
            // Create main window content
            val toolWindowContent = MockkHttpToolWindow(project)
            
            // Create content and add to tool window
            val contentFactory = ContentFactory.getInstance()
            val content = contentFactory.createContent(toolWindowContent, "", false)
            
            toolWindow.contentManager.addContent(content)
            
        } catch (e: Exception) {
            throw e
        }
    }
    
    override fun shouldBeAvailable(project: Project): Boolean = true
}
