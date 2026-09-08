package com.sergiy.dev.mockkhttp.agent

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager
import com.sergiy.dev.mockkhttp.control.AgentControlServer
import com.sergiy.dev.mockkhttp.store.AgentSettingsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tells the user, once, that something started driving their IDE — and offers to stop it.
 *
 * This is a security affordance, not a status readout: the whole point is that it reaches someone
 * who is NOT looking at the MockkHttp tool window, because an unexpected agent connection is
 * precisely the case where nobody is watching the right panel. A balloon with a Revoke action does
 * that; a label inside a tab does not.
 *
 * It exists as a service rather than as panel code for the same reason the audit subscription does:
 * it must be live from the moment the control plane accepts its first call, whether or not any UI
 * has been built yet.
 */
@Service(Service.Level.APP)
class FirstConnectionNotifier {

    private val log = Logger.getInstance(FirstConnectionNotifier::class.java)

    companion object {
        private const val NOTIFICATION_GROUP = "MockkHttp Notifications"

        fun getInstance(): FirstConnectionNotifier =
            ApplicationManager.getApplication().getService(FirstConnectionNotifier::class.java)
    }

    /**
     * Subscribe for the lifetime of [server].
     *
     * Called from [AgentControlServer.startAuditing] so the subscription and the audit trail come
     * up together — anything registered later would miss the very connection worth announcing.
     */
    fun install(server: AgentControlServer) {
        AgentAuditLog.getInstance().addFirstConnectionListener(server) { connection ->
            // Fires on a control-plane worker thread; every Swing/notification call below has to
            // hop to the EDT itself.
            ApplicationManager.getApplication().invokeLater { announce(connection) }
        }
    }

    private fun announce(connection: AgentAuditLog.FirstConnection) {
        val settings = AgentSettingsStore.getInstance()
        val at = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(connection.atMs))
        val where = connection.projectName?.let { " on $it" } ?: ""

        val body = buildString {
            append("<b>${escape(connection.client)}</b> connected at $at$where and can now read ")
            append("captured traffic and change mock rules.<br><br>")
            append("If you started it — a <code>claude</code> session in the terminal, say — nothing to do. ")
            append("If you did not, revoke access now.")
        }

        val project = anyOpenProject()

        try {
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification("MockkHttp: an agent is driving this IDE", body, NotificationType.WARNING)

            notification.addAction(object : AnAction("Revoke Access") {
                override fun actionPerformed(e: AnActionEvent) {
                    revoke()
                    notification.expire()
                }
            })

            notification.addAction(object : AnAction("Open Settings") {
                override fun actionPerformed(e: AnActionEvent) {
                    openToolWindow(e.project ?: project)
                    notification.expire()
                }
            })

            notification.notify(project)
            settings.setFirstConnectionAcknowledged(true)
            log.info("🤖 ⚠️ Announced first agent connection from ${connection.client}")
        } catch (e: Exception) {
            // A notification group that is not registered yet must not take down the request that
            // triggered this. The audit trail already has the call either way.
            log.warn("Could not announce the first agent connection", e)
        }
    }

    /**
     * Turn the control plane off outright, rather than only rotating the token.
     *
     * Someone pressing Revoke on an unexpected connection wants it to stop, not to be re-established
     * by whatever is running as soon as it re-reads the discovery file. Turning access back on is
     * one radio button in Settings.
     */
    private fun revoke() {
        try {
            AgentControlServer.getInstance().setAgentControl(AgentSettingsStore.OFF)
            AgentAuditLog.getInstance().forgetClients()
            log.info("🤖 🛑 Agent access revoked from the first-connection notification")
        } catch (e: Exception) {
            log.warn("Could not revoke agent access", e)
        }
    }

    private fun openToolWindow(project: Project?) {
        val target = project ?: return
        try {
            ToolWindowManager.getInstance(target).getToolWindow("MockkHttp")?.show()
        } catch (e: Exception) {
            log.warn("Could not open the MockkHttp tool window", e)
        }
    }

    /** A balloon needs a project to attach to; any open one shows it. */
    private fun anyOpenProject(): Project? =
        ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
