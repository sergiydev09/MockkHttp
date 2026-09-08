package com.sergiy.dev.mockkhttp.startup

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import com.sergiy.dev.mockkhttp.adb.EmulatorManager
import com.sergiy.dev.mockkhttp.agent.BridgeVendor
import com.sergiy.dev.mockkhttp.agent.InstanceRegistry
import com.sergiy.dev.mockkhttp.control.AgentControlServer
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

        // Bring up the agent control plane. Application-level and idempotent, so opening a second
        // project is a no-op; this is simply the earliest hook the plugin has. Nothing called it
        // before, which meant the whole control plane compiled, shipped, and never ran.
        startAgentControlPlane(project, logger)

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
            // Use WARN instead of ERROR - ADB not being available is expected in CI/headless environments
            logger.warn("⚠️ ADB not found - user action required")
            showErrorNotification(
                project,
                "ADB Not Found",
                "MockkHttp could not find ADB. Please configure it manually in the Settings tab, or install Android SDK Platform Tools."
            )
        }
    }

    /**
     * Start the control plane and register this project in the discovery file.
     *
     * Never throws into the startup path: a failure here must leave the rest of the plugin working.
     * When agent control is off the server declines to bind and says so, which is not an error.
     */
    private fun startAgentControlPlane(project: Project, logger: MockkHttpLogger) {
        try {
            val registry = InstanceRegistry.getInstance()

            val server = AgentControlServer.getInstance()
            val binding = server.start()
            if (binding != null) {
                logger.info("🤖 Agent control plane ready at ${binding.baseUrl}")
            } else {
                val reason = server.getBindError() ?: "agent control is off"
                logger.info("🤖 Agent control plane not started: $reason")
            }

            // Vendor the bridge so `claude` can launch it. A missing jar is handled inside and
            // reported through lastError(), never thrown.
            val vendored = BridgeVendor.getInstance().vendor()
            if (vendored != null) {
                registry.setBridge(BridgeVendor.getInstance().jarPath(), vendored.sha256)
            } else {
                BridgeVendor.getInstance().lastError()?.let { logger.warn("⚠️ MCP bridge not vendored: $it") }
            }

            // The project list is rebuilt from ProjectManager on every refresh, so opening a
            // project only has to ask for one.
            registry.scheduleRefresh("project opened: ${project.name}")
        } catch (e: Exception) {
            logger.warn("⚠️ Could not start the agent control plane", e)
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
