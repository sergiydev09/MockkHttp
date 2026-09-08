package com.sergiy.dev.mockkhttp.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.sergiy.dev.mockkhttp.agent.AgentAuditLog
import com.sergiy.dev.mockkhttp.agent.BridgeVendor
import com.sergiy.dev.mockkhttp.agent.InstanceRegistry
import com.sergiy.dev.mockkhttp.agent.McpConfigWriter
import com.sergiy.dev.mockkhttp.control.AgentControlServer
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import com.sergiy.dev.mockkhttp.store.AgentSettingsStore
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.border.TitledBorder

/**
 * Everything about the agent control plane that is a *setting*, as one section of the Settings tab.
 *
 * The Agent tab used to own both halves of this feature. It no longer exists: what an agent may do
 * is configuration and belongs here, next to the other switches that change how the plugin behaves,
 * and what an agent actually *did* is traffic, so it is listed in the Inspector next to the app's
 * own calls. This class is the configuration half.
 *
 * The security story of the agent channel on a single-user box is not a permission prompt — it is
 * **visibility and reversibility**. So this section answers, without the user clicking anything: is
 * the control plane listening, on what port, what may an agent do through it, where are the two
 * files that make discovery work. Everything destructive is one button away, and every button says
 * what breaks when pressed.
 *
 * ## The token is never here
 *
 * The bearer token is a live credential. It is never rendered, never truncated into a "safe"
 * preview, and never copied to the clipboard — a screenshot of this screen must not grant anybody
 * access. The token travels exactly one way: `AgentControlServer` → `InstanceRegistry` → the 0600
 * instance file that the bridge reads at launch. This section only ever shows that file's *path*.
 *
 * ## Threading
 *
 * Every state read here is an in-memory read from an already thread-safe service, so the EDT does
 * them directly on a 1 s timer. Three things are not: [AgentControlServer.setAgentControl] and
 * [AgentControlServer.start] take the server's monitor and can spend ~2.5 s draining workers,
 * [BridgeVendor.vendor] and [McpConfigWriter.write] touch the disk, and the small "does this file
 * exist" probe stats `~/.mockkhttp` (which can be a network home directory). All of them run off
 * the EDT; the controls they own are disabled until they come back.
 */
class AgentSettingsSection(
    private val project: Project,
    /**
     * Scoped to the tool window, NOT to the project.
     *
     * This section owns a perpetually repeating Swing Timer, held by the app-wide static
     * TimerQueue, plus a listener in an app-level service. On a dynamic plugin unload the tool
     * window is disposed but the project is not, so parenting to the project kept the timer running
     * on a classloader that could never be collected — the IDE reports a leak and forces the
     * restart that the rest of this feature's disposal is written to avoid.
     */
    parentDisposable: Disposable
) : JPanel(GridBagLayout()), Disposable {

    private val logger = MockkHttpLogger.getInstance(project)

    // ---- Control plane status ----
    private val statusLabel = JBLabel()
    private val endpointLabel = JBLabel()
    private val bindErrorLabel = JBLabel()
    private val retryBindButton: JButton

    // ---- Access mode ----
    private val fullRadio = JBRadioButton("Full — read and write")
    private val readOnlyRadio = JBRadioButton("Read-only — reads are served, every change is refused (403)")
    private val offRadio = JBRadioButton("Off — the port is closed and discovery is withdrawn")
    private val revealSecretsCheckbox = JCheckBox("Allow an agent to read redacted header values (Authorization, Cookie, API keys)")
    private val modeStatusLabel = JBLabel()

    // ---- .mcp.json ----
    private val mcpPathLabel = JBLabel()
    private val mcpStateLabel = JBLabel()
    private val mcpResultLabel = JBLabel()
    private val writeMcpButton: JButton
    private val copyMcpCommandButton: JButton

    // ---- Bridge ----
    private val bridgeLauncherLabel = JBLabel()
    private val bridgeJarLabel = JBLabel()
    private val bridgeStateLabel = JBLabel()
    private val bridgeErrorLabel = JBLabel()
    private val revendorButton: JButton

    // ---- Instance file ----
    private val instancePathLabel = JBLabel()
    private val instanceStateLabel = JBLabel()
    private val instanceWarningLabel = JBLabel()

    // ---- Revoke ----
    private val revokeButton: JButton
    private val revokeResultLabel = JBLabel()

    /** Guards [scheduleProbe] against piling up probes if the disk is slow. */
    private val probeRunning = AtomicBoolean(false)

    /** Set when something happened that certainly changed the files on disk. */
    private val probeRequested = AtomicBoolean(true)

    // Filesystem facts, written by the background probe and read by the EDT.
    @Volatile
    private var bridgeJarPresent = false

    @Volatile
    private var bridgeLauncherPresent = false

    @Volatile
    private var instanceFilePresent = false

    @Volatile
    private var mcpConfigState: McpConfigState = McpConfigState.UNKNOWN

    /** True while a blocking action owns the controls. Nothing may be clicked twice. */
    private var busy = false

    /** True only while a mode change is in flight, so the timer does not snap the radios back. */
    private var applyingMode = false

    private var ticks = 0

    private val refreshTimer = Timer(REFRESH_INTERVAL_MS) { onTick() }

    /**
     * The persisted mode can change from outside this section (a status-bar Revoke, another
     * project's Settings tab). Rather than hop threads from the store's listener, mark the
     * filesystem dirty: switching off deletes the instance file, so the next tick re-probes and
     * repaints anyway.
     */
    private val persistedModeListener: (String) -> Unit = { probeRequested.set(true) }

    private enum class McpConfigState { UNKNOWN, ABSENT, PRESENT_WITH_ENTRY, PRESENT_WITHOUT_ENTRY, UNREADABLE, NO_BASE_PATH }

    companion object {
        private const val REFRESH_INTERVAL_MS = 1_000

        /** The probe stats the disk, so it runs every fifth tick rather than every one. */
        private const val PROBE_EVERY_TICKS = 5

        /** Ceiling for the cosmetic `.mcp.json` peek. Anything bigger was not hand-written. */
        private const val MAX_PROBE_BYTES = 512L * 1024L

        private val GREEN = JBColor(0x2E7D32, 0x81C784)
        private val AMBER = JBColor(0xB26A00, 0xE8A33D)
    }

    init {
        logger.info("Initializing Agent settings section...")

        border = createSectionBorder("AI Agent Access (Claude Code / MCP)")
        alignmentX = Component.LEFT_ALIGNMENT

        retryBindButton = JButton("Start / retry bind").apply {
            toolTipText = "Bind the loopback control plane again after a failure"
            addActionListener { retryBind() }
        }
        writeMcpButton = JButton("Write .mcp.json into the project root").apply {
            addActionListener { writeMcpConfig() }
        }
        copyMcpCommandButton = JButton("Copy 'claude mcp add' command").apply {
            toolTipText = "For mixed macOS/Windows teams, or a `claude` started outside this project"
            addActionListener { copyMcpAddCommand() }
        }
        revendorButton = JButton("Re-install bridge").apply {
            toolTipText = "Copy the bridge jar and its launcher into ~/.mockkhttp/bin again"
            addActionListener { revendorBridge() }
        }
        revokeButton = JButton("Revoke token").apply {
            addActionListener { revokeToken() }
        }

        val gbc = constraints()
        addStatusRows(gbc)
        addModeRows(gbc)
        addMcpRows(gbc)
        addBridgeRows(gbc)
        addDiscoveryRows(gbc)
        addRevokeRows(gbc)

        // Parent this section BEFORE subscribing: an unparented listener would never be removed,
        // which is exactly the leak the older clearAllListeners() pattern in this codebase was
        // papering over.
        try {
            Disposer.register(parentDisposable, this)
        } catch (e: Exception) {
            logger.warn("⚠️ Agent settings section could not register for disposal; its timer will live until the IDE closes", e)
        }

        AgentSettingsStore.getInstance().addChangeListener(persistedModeListener)

        // The persisted value is the honest starting point; from the next tick on, the live server
        // is the source of truth (it is seeded from this same store when it is first created).
        syncModeSelection(AgentSettingsStore.getInstance().getAgentControl())
        revealSecretsCheckbox.isSelected = AgentControlServer.getInstance().isRevealSecretsAllowed()

        refreshStatus()
        scheduleProbe()

        refreshTimer.isRepeats = true
        refreshTimer.start()

        logger.info("✅ Agent settings section initialized")
    }

    // ========================================================================
    // Layout
    // ========================================================================

    private fun addStatusRows(gbc: GridBagConstraints) {
        addRow(subHeader("Control plane", first = true), gbc)

        statusLabel.font = statusLabel.font.deriveFont(Font.BOLD)
        addRow(statusLabel, gbc)
        addRow(endpointLabel, gbc)
        addRow(bindErrorLabel, gbc)
        addRow(
            hint(
                "The control plane listens on 127.0.0.1 only, on a port the operating system picks. " +
                        "Nothing on your network can reach it, and no setting opens it up."
            ),
            gbc
        )
        addRow(
            hint("Every call an agent makes is listed in the Inspector, next to the traffic your app produced."),
            gbc
        )
        addRow(buttonRow(retryBindButton, JButton("Refresh").apply {
            addActionListener {
                probeRequested.set(true)
                onTick()
            }
        }), gbc)
    }

    private fun addModeRows(gbc: GridBagConstraints) {
        addRow(subHeader("What an agent may do"), gbc)

        ButtonGroup().apply {
            add(fullRadio)
            add(readOnlyRadio)
            add(offRadio)
        }
        fullRadio.toolTipText = "Read flows and change mock rules, capture mode and debug intercepts"
        readOnlyRadio.toolTipText = "Serve reads; refuse every mutating call with 403"
        offRadio.toolTipText = "Close the socket and delete the discovery file"

        fullRadio.addActionListener { onModeClicked(AgentSettingsStore.FULL) }
        readOnlyRadio.addActionListener { onModeClicked(AgentSettingsStore.READ_ONLY) }
        offRadio.addActionListener { onModeClicked(AgentSettingsStore.OFF) }

        addRow(fullRadio, gbc)
        addRow(readOnlyRadio, gbc)
        addRow(offRadio, gbc)
        addRow(
            hint(
                "Off closes the port outright — there is nothing left to talk to, not merely a refusal. " +
                        "Switching back on binds a new port; a bridge that was running must be restarted."
            ),
            gbc
        )
        addRow(modeStatusLabel, gbc)

        revealSecretsCheckbox.toolTipText =
            "Off by default. An agent transcript is a plain-text log, and a bearer token that lands in one outlives the session."
        revealSecretsCheckbox.addActionListener { onRevealSecretsToggled() }
        addRow(revealSecretsCheckbox, gbc)
        addRow(
            hint(
                "Header values like Authorization and Cookie are captured credentials, so they are replaced by " +
                        "<redacted> before an agent ever sees them. This switch is deliberately NOT restored when the " +
                        "IDE restarts: you re-arm it when you need it, for as long as you need it."
            ),
            gbc
        )
    }

    private fun addMcpRows(gbc: GridBagConstraints) {
        addRow(subHeader("Claude Code setup (${McpConfigWriter.CONFIG_FILE_NAME})"), gbc)

        addRow(mcpPathLabel, gbc)
        addRow(mcpStateLabel, gbc)
        addRow(buttonRow(writeMcpButton, copyMcpCommandButton), gbc)
        addRow(mcpResultLabel, gbc)
        addRow(
            hint(
                "If the project already has a ${McpConfigWriter.CONFIG_FILE_NAME}, it is merged, not replaced: only the " +
                        "\"${McpConfigWriter.SERVER_KEY}\" entry under \"mcpServers\" is written, every other server and key " +
                        "survives verbatim, and the change is a single undo step in the editor. A file that is not valid " +
                        "JSON is refused rather than overwritten — fix the syntax and click again. .gitignore is never " +
                        "touched: whether this file is committed is your team's call."
            ),
            gbc
        )
        addRow(
            hint(
                "The written entry contains no port, no token and no project id — the bridge discovers all three at " +
                        "launch — so it is safe to commit and it survives IDE restarts. Mixed macOS/Windows team? The " +
                        "command path differs per OS: either have each developer press this button and gitignore the file, " +
                        "or use the 'claude mcp add' command instead."
            ),
            gbc
        )
    }

    private fun addBridgeRows(gbc: GridBagConstraints) {
        addRow(subHeader("MCP bridge"), gbc)

        addRow(bridgeLauncherLabel, gbc)
        addRow(bridgeJarLabel, gbc)
        addRow(bridgeStateLabel, gbc)
        addRow(bridgeErrorLabel, gbc)
        addRow(buttonRow(revendorButton), gbc)
        addRow(
            hint(
                "Claude Code launches this jar, not the IDE. It is copied out of the plugin on every start and run by " +
                        "the JBR this IDE already ships, so there is no Node, no npm and no JAVA_HOME to get right."
            ),
            gbc
        )
    }

    private fun addDiscoveryRows(gbc: GridBagConstraints) {
        addRow(subHeader("Discovery file"), gbc)

        addRow(instancePathLabel, gbc)
        addRow(instanceStateLabel, gbc)
        addRow(instanceWarningLabel, gbc)
        addRow(
            hint(
                "This is how a bridge finds this IDE: it holds the port and the access token, and it is written " +
                        "readable by you alone. Do not share it, and do not paste its contents anywhere — the token in it " +
                        "is the access. It is deleted the moment agent control is switched off."
            ),
            gbc
        )
    }

    private fun addRevokeRows(gbc: GridBagConstraints) {
        addRow(subHeader("Revoke"), gbc)

        addRow(
            hint(
                "Revoking generates a brand-new token and rewrites the discovery file. Every agent that is connected " +
                        "right now loses access on its very next call and cannot get it back by retrying — the developer " +
                        "must restart `claude` so the bridge re-reads the file. Nothing else changes: the port stays, the " +
                        "capture session keeps running, and your flows, mock rules and settings are untouched. Use it when " +
                        "an agent you did not start has called in, or when you simply want the current session to stop."
            ),
            gbc
        )
        addRow(buttonRow(revokeButton), gbc)
        addRow(revokeResultLabel, gbc)
    }

    // ========================================================================
    // Live refresh
    // ========================================================================

    private fun onTick() {
        ticks++
        // Nothing to paint while the Settings tab is hidden — and no reason to stat the disk for it.
        if (!isShowing) return

        refreshStatus()
        if (probeRequested.getAndSet(false) || ticks % PROBE_EVERY_TICKS == 0) {
            scheduleProbe()
        }
    }

    /** Every read here is an in-memory read from a thread-safe service: cheap enough for the EDT. */
    private fun refreshStatus() {
        val server = AgentControlServer.getInstance()
        val registry = InstanceRegistry.getInstance()
        val mode = server.getAgentControl()
        val bound = server.isBound()
        val port = server.getPort()

        if (!applyingMode) {
            syncModeSelection(mode)
            revealSecretsCheckbox.isSelected = server.isRevealSecretsAllowed()
        }

        when {
            mode == AgentSettingsStore.OFF -> {
                statusLabel.text = "⛔ Off — the control plane is closed, nothing can connect"
                statusLabel.foreground = JBColor.GRAY
            }

            bound -> {
                statusLabel.text = "✅ Listening on ${AgentControlServer.BIND_HOST}:$port · instance ${server.instanceId}"
                statusLabel.foreground = GREEN
            }

            else -> {
                statusLabel.text = "❌ Not listening"
                statusLabel.foreground = JBColor.RED
            }
        }

        val baseUrl = server.getBaseUrl()
        endpointLabel.text = html(
            if (baseUrl != null) {
                "API: $baseUrl · the access token is never shown here — it lives only in the discovery file below."
            } else {
                "API: not bound."
            }
        )

        val bindError = server.getBindError()
        val showBindError = !bound && bindError != null && mode != AgentSettingsStore.OFF
        bindErrorLabel.isVisible = showBindError
        if (showBindError) {
            bindErrorLabel.text = html("Bind error: $bindError")
            bindErrorLabel.foreground = JBColor.RED
        }

        // ---- .mcp.json ----
        val basePath = project.basePath
        mcpPathLabel.text = html(
            if (basePath != null) {
                "Target: ${Paths.get(basePath).resolve(McpConfigWriter.CONFIG_FILE_NAME)}"
            } else {
                "This project has no base directory, so there is nowhere to put ${McpConfigWriter.CONFIG_FILE_NAME}."
            }
        )
        when (mcpConfigState) {
            McpConfigState.PRESENT_WITH_ENTRY -> {
                mcpStateLabel.text = "✅ The file exists and already has a \"${McpConfigWriter.SERVER_KEY}\" entry."
                mcpStateLabel.foreground = GREEN
            }

            McpConfigState.PRESENT_WITHOUT_ENTRY -> {
                mcpStateLabel.text = "⚠️ The file exists but has no MockkHttp entry — writing merges into it."
                mcpStateLabel.foreground = AMBER
            }

            McpConfigState.ABSENT -> {
                mcpStateLabel.text = "The file does not exist yet — writing creates it."
                mcpStateLabel.foreground = JBColor.GRAY
            }

            McpConfigState.UNREADABLE -> {
                mcpStateLabel.text = "⚠️ The file exists but could not be read from here."
                mcpStateLabel.foreground = AMBER
            }

            McpConfigState.NO_BASE_PATH -> {
                mcpStateLabel.text = "⚠️ Open the app's repository as the project root to use this button."
                mcpStateLabel.foreground = AMBER
            }

            McpConfigState.UNKNOWN -> {
                mcpStateLabel.text = "Checking…"
                mcpStateLabel.foreground = JBColor.GRAY
            }
        }

        // ---- Bridge ----
        val vendor = BridgeVendor.getInstance()
        bridgeLauncherLabel.text = html("Launcher (what ${McpConfigWriter.CONFIG_FILE_NAME} runs): ${vendor.launcherPath()}")
        bridgeJarLabel.text = html("Bridge jar: ${vendor.jarPath()}")
        val sha = vendor.lastResult()?.sha256
        when {
            bridgeJarPresent && bridgeLauncherPresent -> {
                bridgeStateLabel.text = "✅ Installed" + (sha?.let { " · sha256 ${it.take(12)}…" } ?: "")
                bridgeStateLabel.foreground = GREEN
            }

            bridgeJarPresent -> {
                bridgeStateLabel.text = "⚠️ The jar is there but its launcher is missing — Claude Code has nothing to run."
                bridgeStateLabel.foreground = AMBER
            }

            else -> {
                bridgeStateLabel.text = "❌ Not on disk"
                bridgeStateLabel.foreground = JBColor.RED
            }
        }
        val bridgeError = vendor.lastError()
        bridgeErrorLabel.isVisible = bridgeError != null
        if (bridgeError != null) {
            bridgeErrorLabel.text = html(bridgeError)
            bridgeErrorLabel.foreground = AMBER
        }

        // ---- Instance file ----
        instancePathLabel.text = html("File: ${registry.instanceFile()}")
        val refusal = registry.publishRefusal()
        val permission = registry.permissionWarning()
        when {
            refusal != null -> {
                instanceStateLabel.text = "❌ Not published — no agent can discover this IDE."
                instanceStateLabel.foreground = JBColor.RED
            }

            registry.isPublished() && instanceFilePresent -> {
                instanceStateLabel.text = "✅ Published"
                instanceStateLabel.foreground = GREEN
            }

            registry.isPublished() -> {
                instanceStateLabel.text = "⚠️ Published, but the file is not on disk right now — it is rewritten on the next refresh."
                instanceStateLabel.foreground = AMBER
            }

            else -> {
                instanceStateLabel.text = "Not published (nothing is advertised while agent control is off or unbound)."
                instanceStateLabel.foreground = JBColor.GRAY
            }
        }
        val warning = refusal ?: permission
        instanceWarningLabel.isVisible = warning != null
        if (warning != null) {
            instanceWarningLabel.text = html(warning)
            instanceWarningLabel.foreground = if (refusal != null) JBColor.RED else AMBER
        }

        updateEnablement()
    }

    private fun updateEnablement() {
        val mode = AgentControlServer.getInstance().getAgentControl()
        val bound = AgentControlServer.getInstance().isBound()

        fullRadio.isEnabled = !busy
        readOnlyRadio.isEnabled = !busy
        offRadio.isEnabled = !busy
        revealSecretsCheckbox.isEnabled = !busy && mode != AgentSettingsStore.OFF
        retryBindButton.isEnabled = !busy && !bound && mode != AgentSettingsStore.OFF
        writeMcpButton.isEnabled = !busy && project.basePath != null
        copyMcpCommandButton.isEnabled = !busy
        revendorButton.isEnabled = !busy
        revokeButton.isEnabled = !busy && bound
    }

    private fun syncModeSelection(mode: String) {
        when (mode) {
            AgentSettingsStore.READ_ONLY -> if (!readOnlyRadio.isSelected) readOnlyRadio.isSelected = true
            AgentSettingsStore.OFF -> if (!offRadio.isSelected) offRadio.isSelected = true
            else -> if (!fullRadio.isSelected) fullRadio.isSelected = true
        }
    }

    // ========================================================================
    // Actions
    // ========================================================================

    private fun onModeClicked(value: String) {
        if (busy) return
        val current = AgentControlServer.getInstance().getAgentControl()
        if (current == value) return

        busy = true
        applyingMode = true
        modeStatusLabel.text = "Applying…"
        modeStatusLabel.foreground = JBColor.GRAY
        updateEnablement()

        // setAgentControl() is @Synchronized and, for anything that lands on "off", stops the
        // server: it drains its workers for up to 1.5 s and then interrupts them for another 1 s.
        // On the EDT that is a two-and-a-half second frozen IDE every time a radio button is
        // clicked, so it goes to a pooled thread and the radios stay disabled until it returns.
        object : Task.Backgroundable(project, "Applying MockkHttp agent access", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                AgentControlServer.getInstance().setAgentControl(value)
            }

            override fun onFinished() {
                busy = false
                applyingMode = false
                modeStatusLabel.text = ""
                probeRequested.set(true)
                refreshStatus()
                logger.info("🤖 Agent access set to '$value' from Settings")
            }
        }.queue()
    }

    private fun onRevealSecretsToggled() {
        val allowed = revealSecretsCheckbox.isSelected
        // Both writes are in-memory: the store persists the choice, the server is what the request
        // path actually reads. Neither blocks, so neither needs a background thread.
        AgentSettingsStore.getInstance().setRevealSecretsAllowed(allowed)
        AgentControlServer.getInstance().setRevealSecrets(allowed)
        logger.info(
            if (allowed) "🤖 ⚠️ Secret reveal ENABLED — captured credentials can now reach an agent transcript"
            else "🤖 Secret reveal disabled"
        )
    }

    private fun retryBind() {
        if (busy) return
        busy = true
        updateEnablement()

        // start() takes the same monitor as stop() and can wait on a teardown that is in flight.
        object : Task.Backgroundable(project, "Starting MockkHttp agent control plane", false) {
            private var bound = false

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                // Deliberately discards the returned binding: it carries the token, and nothing
                // holding the token may ever end up on a Swing component.
                bound = AgentControlServer.getInstance().start() != null
            }

            override fun onFinished() {
                busy = false
                probeRequested.set(true)
                refreshStatus()
                if (!bound) {
                    logger.warn("⚠️ Agent control plane still not bound: ${AgentControlServer.getInstance().getBindError()}")
                }
            }
        }.queue()
    }

    private fun writeMcpConfig() {
        if (busy) return
        busy = true
        updateEnablement()
        mcpResultLabel.text = ""

        // Reads the file, refreshes the VFS and writes through the editor. All of that is disk work.
        object : Task.Backgroundable(project, "Writing ${McpConfigWriter.CONFIG_FILE_NAME}", false) {
            private var result: McpConfigWriter.WriteResult? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                result = McpConfigWriter.getInstance().write(project)
            }

            override fun onFinished() {
                busy = false
                probeRequested.set(true)
                showMcpResult(result)
                refreshStatus()
            }
        }.queue()
    }

    private fun showMcpResult(result: McpConfigWriter.WriteResult?) {
        when (result) {
            is McpConfigWriter.WriteResult.Written -> {
                val verb = if (result.created) "Created" else "Updated"
                mcpResultLabel.text = html(
                    "✅ $verb ${result.path} — restart `claude` (or /mcp reconnect) so it picks up the entry."
                )
                mcpResultLabel.foreground = GREEN
                logger.info("🤖 ✅ ${verb.lowercase()} ${result.path}")
            }

            is McpConfigWriter.WriteResult.Unchanged -> {
                mcpResultLabel.text = html("✅ ${result.path} already had the right entry — nothing was touched.")
                mcpResultLabel.foreground = GREEN
            }

            is McpConfigWriter.WriteResult.Failed -> {
                mcpResultLabel.text = html("❌ ${result.reason}")
                mcpResultLabel.foreground = JBColor.RED
                logger.warn("🤖 ⚠️ ${McpConfigWriter.CONFIG_FILE_NAME} not written: ${result.reason}")
                Messages.showErrorDialog(
                    project,
                    listOfNotNull(result.reason, result.hint).joinToString("\n\n"),
                    "MockkHttp — ${McpConfigWriter.CONFIG_FILE_NAME} Not Written"
                )
            }

            null -> {
                mcpResultLabel.text = html("❌ Writing ${McpConfigWriter.CONFIG_FILE_NAME} produced no result.")
                mcpResultLabel.foreground = JBColor.RED
            }
        }
    }

    private fun copyMcpAddCommand() {
        // Only a launcher path — no port, no token. Safe on a clipboard and safe in a chat.
        val command = McpConfigWriter.getInstance().claudeMcpAddCommand()
        FlowCopyUtils.copyToClipboard(command)
        mcpResultLabel.text = html("Copied: $command")
        mcpResultLabel.foreground = GREEN
    }

    private fun revendorBridge() {
        if (busy) return
        busy = true
        updateEnablement()

        object : Task.Backgroundable(project, "Installing the MockkHttp MCP bridge", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                // Copies a ~2 MB jar and writes a launcher script: file I/O, never on the EDT.
                BridgeVendor.getInstance().vendor()
            }

            override fun onFinished() {
                busy = false
                probeRequested.set(true)
                refreshStatus()
            }
        }.queue()
    }

    private fun revokeToken() {
        if (busy) return
        val confirmed = Messages.showYesNoDialog(
            project,
            "A new access token is generated and the discovery file is rewritten.\n\n" +
                    "Every agent connected right now loses access on its next call and cannot retry its way back in — " +
                    "restart `claude` so the bridge re-reads the file.\n\n" +
                    "The port, the capture session, your flows, mock rules and settings are all untouched.",
            "Revoke MockkHttp Agent Token",
            "Revoke",
            "Cancel",
            Messages.getWarningIcon()
        )
        if (confirmed != Messages.YES) return

        busy = true
        updateEnablement()
        revokeResultLabel.text = ""

        object : Task.Backgroundable(project, "Revoking the MockkHttp agent token", false) {
            private var republished = false
            private var wasBound = false

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val server = AgentControlServer.getInstance()
                // The fresh token is deliberately dropped on the floor here. It travels to the
                // bridge through the 0600 instance file and nowhere else — never through this UI.
                server.rotateToken()

                val binding = server.binding()
                wasBound = binding != null
                republished = if (binding != null) {
                    // Rewriting the file is disk work, which is the other reason this is not on the
                    // EDT. Without it the old token stays advertised and revocation is a no-op.
                    InstanceRegistry.getInstance().setControlEndpoint(
                        InstanceRegistry.ControlEndpoint(
                            baseUrl = binding.baseUrl,
                            token = binding.token,
                            agentControl = binding.agentControl
                        )
                    )
                } else {
                    false
                }

                // Forget who has called so the next connection is announced again — a new client
                // taking the place of the one just cut off is exactly what the user must be told.
                val auditLog = AgentAuditLog.getInstance()
                auditLog.forgetClients()
                auditLog.note("token/revoke", "Token revoked from Settings — connected bridges must reconnect")
            }

            override fun onFinished() {
                busy = false
                probeRequested.set(true)
                revokeResultLabel.text = html(
                    when {
                        republished -> "✅ Token revoked and re-advertised. Connected agents are locked out; restart `claude` to reconnect."
                        wasBound -> "⚠️ Token revoked, but the discovery file could not be rewritten — no agent can reconnect until it is."
                        else -> "✅ Token revoked. Nothing is advertised while the control plane is not listening."
                    }
                )
                revokeResultLabel.foreground = if (republished || !wasBound) GREEN else AMBER
                logger.info("🤖 Agent token revoked from Settings")
                refreshStatus()
            }
        }.queue()
    }

    // ========================================================================
    // Background probe
    // ========================================================================

    /**
     * Answer the "is it actually there?" questions off the EDT.
     *
     * A pooled thread rather than [Task.Backgroundable]: this runs every few seconds, and a
     * progress bar flickering in the status bar once per tick would be worse than the freeze it
     * avoids. The work is three `stat`s and one small read — but `~/.mockkhttp` can live on a
     * network home directory, where a `stat` is a round trip.
     */
    private fun scheduleProbe() {
        if (!probeRunning.compareAndSet(false, true)) return
        try {
            AppExecutorUtil.getAppExecutorService().execute {
                try {
                    probe()
                } catch (e: Exception) {
                    logger.debug("Agent settings probe failed: ${e.message}")
                } finally {
                    probeRunning.set(false)
                }
            }
        } catch (e: Exception) {
            // The pool is gone (IDE shutting down). The next tick will try again, or never.
            probeRunning.set(false)
        }
    }

    private fun probe() {
        val vendor = BridgeVendor.getInstance()
        bridgeJarPresent = isFile(vendor.jarPath())
        bridgeLauncherPresent = isFile(vendor.launcherPath())
        instanceFilePresent = isFile(InstanceRegistry.getInstance().instanceFile())

        val basePath = project.basePath
        mcpConfigState = if (basePath == null) {
            McpConfigState.NO_BASE_PATH
        } else {
            val config = Paths.get(basePath).resolve(McpConfigWriter.CONFIG_FILE_NAME)
            try {
                when {
                    !Files.isRegularFile(config) -> McpConfigState.ABSENT
                    // A config nobody would hand-write this big is not worth reading to answer a
                    // cosmetic question; the writer itself re-reads whatever is there.
                    Files.size(config) > MAX_PROBE_BYTES -> McpConfigState.PRESENT_WITHOUT_ENTRY
                    Files.readString(config).contains("\"${McpConfigWriter.SERVER_KEY}\"") ->
                        McpConfigState.PRESENT_WITH_ENTRY

                    else -> McpConfigState.PRESENT_WITHOUT_ENTRY
                }
            } catch (e: Exception) {
                McpConfigState.UNREADABLE
            }
        }
    }

    private fun isFile(path: Path): Boolean = try {
        Files.isRegularFile(path)
    } catch (e: Exception) {
        false
    }

    // ========================================================================
    // Disposal
    // ========================================================================

    override fun dispose() {
        refreshTimer.stop()
        try {
            AgentSettingsStore.getInstance().removeChangeListener(persistedModeListener)
        } catch (e: Exception) {
            // The application container can already be gone during shutdown; nothing left to leak.
        }
    }

    // ========================================================================
    // Small helpers
    // ========================================================================

    private fun createSectionBorder(title: String): TitledBorder =
        BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), title).apply {
            titleFont = titleFont.deriveFont(Font.BOLD)
        }

    private fun constraints(): GridBagConstraints = GridBagConstraints().apply {
        fill = GridBagConstraints.HORIZONTAL
        insets = JBUI.insets(5)
        anchor = GridBagConstraints.WEST
        gridx = 0
        gridy = 0
        weightx = 1.0
    }

    private fun addRow(component: JComponent, gbc: GridBagConstraints) {
        add(component, gbc)
        gbc.gridy++
    }

    /**
     * The whole agent channel is one settings section, so its six groups are bold headers inside a
     * single titled border rather than six nested ones — nesting etched borders three deep is how a
     * settings screen starts looking like a form somebody bolted on.
     */
    private fun subHeader(title: String, first: Boolean = false): JComponent {
        val label = JBLabel(title).apply { font = font.deriveFont(Font.BOLD) }
        if (first) return label

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(8)
            add(JSeparator(SwingConstants.HORIZONTAL), BorderLayout.NORTH)
            add(label.apply { border = JBUI.Borders.emptyTop(6) }, BorderLayout.CENTER)
        }
    }

    private fun buttonRow(vararg buttons: JButton): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
            buttons.forEach { add(it) }
        }

    private fun hint(text: String): JBLabel = JBLabel(html(text)).apply {
        foreground = JBColor.GRAY
        font = font.deriveFont(11f)
    }

    /**
     * Swing labels do not wrap, and every string here is either a long absolute path or a sentence
     * explaining a consequence. A width-constrained HTML body wraps both; escaping is not optional,
     * since these strings carry paths and error messages the user did not write.
     */
    private fun html(text: String): String =
        "<html><body style='width:540px'>" + escapeHtml(text) + "</body></html>"

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
