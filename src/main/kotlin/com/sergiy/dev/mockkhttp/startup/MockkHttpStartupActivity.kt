package com.sergiy.dev.mockkhttp.startup

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import com.sergiy.dev.mockkhttp.adb.EmulatorManager
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import com.sergiy.dev.mockkhttp.store.SettingsStore

/**
 * Startup activity that runs when a project is opened.
 * Validates ADB configuration and notifies the user if there are issues.
 */
class MockkHttpStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val logger = MockkHttpLogger.getInstance(project)
        logger.info("🚀 MockkHttp startup activity running...")

        // Small delay to ensure services are initialized
        kotlinx.coroutines.delay(1000)

        // Check ADB path
        val emulatorManager = EmulatorManager.getInstance(project)
        val settingsStore = SettingsStore.getInstance(project)

        // First, try to get configured path
        val configuredAdbPath = settingsStore.getAdbPath()

        if (configuredAdbPath != null) {
            // User has configured a path, validate it
            val validation = settingsStore.validateExecutablePath(configuredAdbPath)
            if (validation.isValid) {
                logger.info("✅ ADB configured and valid: $configuredAdbPath")
                showSuccessNotification(project, "ADB found at configured path")
                return
            } else {
                logger.warn("⚠️ Configured ADB path is invalid: $configuredAdbPath")
                showErrorNotification(
                    project,
                    "Configured ADB Path Invalid",
                    "The configured ADB path '$configuredAdbPath' is invalid: ${validation.message}. Please update it in Settings."
                )
                return
            }
        }

        // No configured path, try auto-detection
        logger.info("🔍 No ADB path configured, running auto-detection...")
        val detectedAdbPath = emulatorManager.findAdbPath()

        if (detectedAdbPath != null) {
            logger.info("✅ ADB auto-detected: $detectedAdbPath")
            showSuccessNotification(project, "ADB auto-detected at: $detectedAdbPath")
        } else {
            logger.error("❌ ADB not found - user action required")
            showErrorNotification(
                project,
                "ADB Not Found",
                "MockkHttp could not find ADB. Please configure it manually in the Settings tab, or install Android SDK Platform Tools."
            )
        }
    }

    private fun showSuccessNotification(project: Project, message: String) {
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("MockkHttp Notifications")
                .createNotification(
                    "MockkHttp Ready",
                    message,
                    NotificationType.INFORMATION
                )
                .notify(project)
        } catch (e: Exception) {
            // Notification group may not be registered yet, ignore
        }
    }

    private fun showErrorNotification(project: Project, title: String, message: String) {
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("MockkHttp Notifications")
                .createNotification(
                    title,
                    message,
                    NotificationType.WARNING
                )
                .addAction(object : AnAction("Open Settings") {
                    override fun actionPerformed(e: AnActionEvent) {
                        openMockkHttpSettings(project)
                    }
                })
                .notify(project)
        } catch (e: Exception) {
            // Notification group may not be registered yet, ignore
        }
    }

    private fun openMockkHttpSettings(project: Project) {
        // Open the MockkHttp tool window and switch to Settings tab
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindow = toolWindowManager.getToolWindow("MockkHttp")

        if (toolWindow != null) {
            toolWindow.show {
                // Find and select the Settings tab (index 3: Inspector=0, Mockk=1, Logs=2, Settings=3)
                val content = toolWindow.contentManager.contents.find { it.displayName == "Settings" }
                    ?: toolWindow.contentManager.contents.getOrNull(3)
                content?.let {
                    toolWindow.contentManager.setSelectedContent(it)
                }
            }
        }
    }
}
