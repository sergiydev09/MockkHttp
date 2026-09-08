package com.sergiy.dev.mockkhttp.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.sergiy.dev.mockkhttp.agent.AgentAuditLog
import com.sergiy.dev.mockkhttp.control.AgentControlServer
import com.sergiy.dev.mockkhttp.control.dto.AGENT_CONTROL_OFF
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.Timer
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * What the agent is doing, shown where the human is already looking.
 *
 * When an agent drives a capture session it flips modes, changes mock rules and answers debug
 * intercepts — and the flows in the Inspector move on their own as a result. Without this strip the
 * only honest explanation for that lives in another tab, which nobody has open while watching
 * traffic. So the record of agent activity belongs next to the traffic it explains.
 *
 * ## Three questions, answered without opening anything
 *
 * 1. **Is an agent connected right now?** [AgentAuditLog.isActive] answers "in the last
 *    [AgentAuditLog.ACTIVE_WINDOW_MS]", and [AgentControlServer] answers the question behind it —
 *    a channel that is switched off or not listening can have no agent on it at all, and saying
 *    "no agent connected" there would send the user looking for the wrong fault.
 * 2. **What is the session doing?** The mode and the app filter, pushed in by the Inspector through
 *    [setSessionState] — including whether the mode was last changed by an agent rather than by the
 *    person reading the strip. This is the fact the user asked for by name: an agent switching
 *    RECORDING → MOCKK must be visible here immediately.
 * 3. **What calls has it made?** The audit table, newest first.
 *
 * The strip is always present. An earlier version made itself invisible until an agent called,
 * which cost nothing — and read, correctly, as "the feature does not work". It is one line until
 * there is something to list; the table appears on the first agent call (or on "Show calls") and is
 * capped at [MAX_VISIBLE_ROWS] so the flow list above stays the centrepiece.
 *
 * ## The token is never here
 *
 * [AgentAuditLog] never carries the bearer token, and neither does this view: it renders paths,
 * status codes, the port the control plane listens on and the human summary an entry was written
 * with. [AgentControlServer.binding] — the one accessor that exposes the token — is deliberately
 * never called from this file. Copy produces the same lines, so a screenshot or a paste of this
 * strip grants nobody access.
 *
 * ## Threading
 *
 * The audit listener fires on the control plane's worker threads, so it does the one thing that is
 * safe there — flip an [AtomicBoolean] — and every Swing touch happens on the EDT from the 1 s
 * timer. A bridge may make 600 calls a minute; one `invokeLater` per call to append a row is how a
 * tool window becomes the reason the IDE stutters, and coalescing costs at most a second of
 * latency on a strip nobody reads frame by frame. [setSessionState] is EDT-only and its caller
 * (the Inspector's own reconcile timer) is an EDT timer.
 */
class AgentActivityView(
    /**
     * Scoped to the tool window, NOT to the project.
     *
     * This view holds a perpetually repeating Swing Timer (owned by the app-wide static
     * TimerQueue) and a listener in an app-level service. On a dynamic plugin unload the tool
     * window is disposed but the project is not, so a project-parented timer would keep running on
     * a classloader that can never be collected — the IDE reports a leak and forces the very
     * restart the rest of this feature is written to avoid.
     */
    parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    /**
     * What the Inspector knows about the capture session, pushed in on the EDT.
     *
     * The view does not read the interceptor itself: the Inspector is the one component that
     * reconciles its own controls against the registration, and two readers of the same truth would
     * eventually disagree on screen.
     *
     * @param mode `RECORDING` | `DEBUG` | `MOCKK` | `MOCKK_DEBUG`, or null when nothing is running.
     * @param startedByAgent the session itself was started by an agent, not from the tool window.
     * @param agentChangedAtMs when an agent last changed the mode; 0 once the human takes the wheel.
     */
    data class SessionState(
        val running: Boolean = false,
        val mode: String? = null,
        val packageFilter: String? = null,
        val deviceLabel: String? = null,
        val startedByAgent: Boolean = false,
        val agentChangedAtMs: Long = 0L
    )

    private val log = Logger.getInstance(AgentActivityView::class.java)
    private val auditLog = AgentAuditLog.getInstance()

    // ---- Header (the whole component while collapsed) ----
    private val sessionLabel = JBLabel()
    private val agentLabel = JBLabel()
    private val detailLabel = JBLabel()
    private val toggleLink = ActionLink()
    private val copyLink = ActionLink()

    // ---- Body (appears on the first agent call, or on demand) ----
    private val auditModel = AuditTableModel()
    private val auditTable = JBTable(auditModel)
    private val bodyScroll: JBScrollPane

    /**
     * Set from control-plane worker threads, consumed on the EDT by [onTick]. Deliberately a flag
     * and not a per-entry thread hop — see the class doc.
     */
    private val dirty = AtomicBoolean(true)

    /** EDT-only snapshot of the ring, NEWEST FIRST. The header reads it without taking the lock. */
    private var rows: List<AgentAuditLog.Entry> = emptyList()

    /** EDT-only. Whatever the Inspector last told us about the session. */
    private var session = SessionState()

    private var expanded = false

    /**
     * What the user chose with Show/Hide, or null while they never touched it. Their choice wins
     * over the auto-expand below: a deliberate collapse must stay collapsed, and an expansion the
     * emptying of the ring undid must come back with the next call.
     */
    private var expansionChosenByUser: Boolean? = null

    private val refreshTimer = Timer(REFRESH_INTERVAL_MS) { onTick() }

    private companion object {
        const val REFRESH_INTERVAL_MS = 1_000

        /** How much of the ring this strip mirrors. The ring holds [AgentAuditLog.MAX_ENTRIES]. */
        const val MAX_ROWS = 200

        /** Hard ceiling on the expanded height. The flow list above is the centrepiece. */
        const val MAX_VISIBLE_ROWS = 5

        /** Ceiling for the one-line summary, so a verbose entry cannot widen the whole Inspector. */
        const val MAX_DETAIL_CHARS = 80

        val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

        val GREEN = JBColor(0x2E7D32, 0x81C784)
        val AMBER = JBColor(0xB26A00, 0xE8A33D)
        val CYAN = JBColor(0x00697A, 0x4DD0E1)
        val MAGENTA = JBColor(0x8E24AA, 0xCE93D8)

        val COLUMNS = arrayOf("Time", "Client", "Call", "Result", "What it did")

        /**
         * The last column takes whatever is left over, and it is deliberately "What it did": the
         * human sentence is what makes this a story rather than an HTTP dump, so it gets the slack.
         */
        val COLUMN_WIDTHS = intArrayOf(70, 110, 260, 70, 360)
    }

    init {
        border = JBUI.Borders.compound(
            // A hairline is what separates this from the flow list above without spending a row
            // on a titled border.
            JBUI.Borders.customLineTop(JBColor.border()),
            JBUI.Borders.empty(3, 5, 0, 5)
        )

        toggleLink.addActionListener {
            expansionChosenByUser = !expanded
            setExpanded(!expanded)
        }
        copyLink.text = "Copy"
        copyLink.toolTipText = "Copy the visible lines as text (no tokens, no bodies)"
        copyLink.addActionListener { copyVisibleLines() }

        add(buildHeader(), BorderLayout.NORTH)
        bodyScroll = buildBody()
        add(bodyScroll, BorderLayout.CENTER)

        // Register BEFORE subscribing: AgentAuditLog scopes every listener to a Disposable, and an
        // unparented one would never be removed. If the parent is already gone there is nothing
        // left to show anyway, so stay inert rather than leak a listener and a timer into it.
        val registered = try {
            Disposer.register(parentDisposable, this)
            true
        } catch (e: Exception) {
            log.warn("🤖 ⚠️ Agent activity view could not register for disposal; it stays inert", e)
            false
        }

        // Paint once no matter what: a strip that comes up blank is indistinguishable from a
        // broken one, and this is also the whole of the render path when registration failed.
        refreshData()
        refreshHeader()

        if (registered) {
            auditLog.addListener(this, AgentAuditLog.AuditListener { dirty.set(true) })
            refreshTimer.isRepeats = true
            refreshTimer.start()
        }
    }

    // ========================================================================
    // Construction
    // ========================================================================

    private fun buildHeader(): JPanel {
        detailLabel.border = JBUI.Borders.emptyLeft(8)

        // BoxLayout, not FlowLayout: these two labels are the fixed-width part of the line and
        // must keep their order and spacing when the tool window is narrow.
        val state = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(sessionLabel)
            add(Box.createHorizontalStrut(8))
            add(JBLabel("·").apply { foreground = JBColor.GRAY })
            add(Box.createHorizontalStrut(8))
            add(agentLabel)
        }

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            add(toggleLink)
            add(copyLink)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0)
            add(state, BorderLayout.WEST)
            // CENTER, so a long summary is clipped with an ellipsis instead of pushing the
            // controls off the edge of a narrow tool window.
            add(detailLabel, BorderLayout.CENTER)
            add(actions, BorderLayout.EAST)
        }
    }

    private fun buildBody(): JBScrollPane {
        auditTable.apply {
            setShowGrid(false)
            autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
            tableHeader.reorderingAllowed = false
            rowSelectionAllowed = true
            emptyText.text = "No agent calls recorded"
            setDefaultRenderer(Any::class.java, AuditCellRenderer())
        }
        for (column in COLUMN_WIDTHS.indices) {
            auditTable.columnModel.getColumn(column).preferredWidth = COLUMN_WIDTHS[column]
        }
        // The viewport size is what BorderLayout.CENTER will ask for, so it is the one knob that
        // decides how much the strip takes from the flow list. Kept in step with the row count by
        // updateBodyHeight().
        auditTable.preferredScrollableViewportSize = Dimension(JBUI.scale(240), auditTable.rowHeight * 3)

        return JBScrollPane(auditTable).apply {
            border = JBUI.Borders.emptyTop(4)
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            // The last column absorbs the slack, so the table always fits the viewport's width.
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            isVisible = false
        }
    }

    // ========================================================================
    // Session state (pushed in by the Inspector)
    // ========================================================================

    /**
     * Tell the strip what the capture session is doing. **EDT only.**
     *
     * Cheap and idempotent: the Inspector calls this on every reconcile tick, so this must do
     * nothing at all when nothing changed.
     */
    fun setSessionState(state: SessionState) {
        if (state == session) return
        session = state
        if (isShowing) refreshSessionLabel()
    }

    // ========================================================================
    // Live refresh
    // ========================================================================

    private fun onTick() {
        // Nothing to repaint while the Inspector sits behind another tab or the tool window is
        // collapsed; the dirty flag keeps the backlog and the next visible tick catches up.
        if (!isShowing) return

        if (dirty.get()) refreshData()
        refreshHeader()
    }

    /** Takes a fresh snapshot of the ring. Only ever called from the EDT. */
    private fun refreshData() {
        // Cleared first: an entry that lands between here and the snapshot is still captured, and
        // one that lands after it re-raises the flag for the next tick. Neither is lost.
        dirty.set(false)
        // Newest first — the question this table answers is "what did it just do", and an answer
        // that requires scrolling to the bottom is not an answer.
        rows = auditLog.entries(MAX_ROWS).asReversed()

        val followHead = expanded && isShowing && isScrolledToTop()
        auditModel.setRows(rows)
        updateBodyHeight()

        when {
            // An empty table is only wasted rows, whoever opened it.
            rows.isEmpty() -> setExpanded(false)
            // The first agent call earns the table. Opening it on the user's behalf is the
            // difference between "the agent did something, somewhere" and seeing what it was.
            expansionChosenByUser != false -> setExpanded(true)
        }

        // Follow the newest row only when the user was already at the top: yanking the view away
        // from a line somebody is reading is how a live log becomes unreadable.
        if (followHead && rows.isNotEmpty()) {
            auditTable.scrollRectToVisible(auditTable.getCellRect(0, 0, true))
        }
    }

    private fun refreshHeader() {
        refreshSessionLabel()
        refreshAgentLabel()
        refreshDetailLabel()
        updateLinks()
    }

    /** Question 2: what mode is the session in, and did an agent put it there? */
    private fun refreshSessionLabel() {
        val state = session
        // Two different claims, and the stronger one wins the badge: an agent that started the
        // whole session owns it, whoever last touched the mode.
        val agentDriven = state.startedByAgent || state.agentChangedAtMs > 0L
        sessionLabel.text = if (!state.running) {
            // agentChangedAtMs survives until the human acts, so while stopped it means exactly
            // one thing: the last change to this session was not made from this tool window.
            if (state.agentChangedAtMs > 0L) "○ No capture session (stopped by agent)" else "○ No capture session"
        } else {
            val target = state.packageFilter?.let { shortPackage(it) } ?: "all apps"
            val suffix = when {
                state.startedByAgent -> " (agent)"
                state.agentChangedAtMs > 0L -> " (set by agent)"
                else -> ""
            }
            "● ${modeText(state.mode)} · $target$suffix"
        }
        sessionLabel.foreground = when {
            // Amber first, running or not: "an agent stopped your capture" is exactly as worth
            // noticing as "an agent put you in MOCKK".
            agentDriven -> AMBER
            !state.running -> JBColor.GRAY
            else -> modeColor(state.mode)
        }
        sessionLabel.toolTipText = buildString {
            if (!state.running) {
                append("Nothing is being captured. Pick a device and an app, then press Start.")
                if (state.agentChangedAtMs > 0L) {
                    append(" An agent stopped this session at ")
                    append(formatTime(state.agentChangedAtMs))
                    append('.')
                }
            } else {
                append("Capturing in ")
                append(state.mode ?: "an unknown mode")
                append(" · app filter: ")
                append(state.packageFilter ?: "none (every app that connects)")
                state.deviceLabel?.let {
                    append(" · device: ")
                    append(it)
                }
                if (state.startedByAgent) {
                    append(" · an agent started this session, not the Inspector")
                }
                if (state.agentChangedAtMs > 0L) {
                    append(" · an agent set this mode at ")
                    append(formatTime(state.agentChangedAtMs))
                }
            }
        }
    }

    /** Question 1: is an agent connected right now — and is the channel even open? */
    private fun refreshAgentLabel() {
        val presence = readAgentPresence()
        val lastActivity = auditLog.lastActivityAtMs()

        // IDE_CLIENT entries are this plugin narrating itself (token rotated, control switched
        // off). They belong in the table, but calling the IDE "an agent" would be a lie.
        val clients = rows.asSequence()
            .map { it.client }
            .filter { it != AgentAuditLog.IDE_CLIENT }
            .distinct()
            .toList()
        val who = when {
            clients.isEmpty() -> "An agent"
            clients.size == 1 -> clients.first()
            else -> "${clients.size} agents"
        }

        when {
            presence == Presence.OFF -> {
                agentLabel.text = "🤖 Agent control off"
                agentLabel.foreground = JBColor.GRAY
                agentLabel.toolTipText =
                    "No agent can reach this IDE. Switch it on in MockkHttp → Settings → Agent Control."
            }

            presence == Presence.NOT_LISTENING -> {
                agentLabel.text = "🤖 Agent control not listening"
                agentLabel.foreground = AMBER
                agentLabel.toolTipText =
                    "Agent control is on but the local endpoint is not bound; Settings → Agent Control says why."
            }

            auditLog.isActive() -> {
                agentLabel.text = "🤖 $who is working here now"
                agentLabel.foreground = GREEN
                agentLabel.toolTipText =
                    "An automated caller has used this IDE in the last minute. Access is granted, restricted and revoked in Settings."
            }

            lastActivity > 0L -> {
                agentLabel.text = "🤖 $who · last call ${formatTime(lastActivity)}"
                agentLabel.foreground = JBColor.GRAY
                agentLabel.toolTipText =
                    "The channel is open and an agent has used it, but not in the last minute."
            }

            else -> {
                agentLabel.text = "🤖 No agent connected yet"
                agentLabel.foreground = JBColor.GRAY
                agentLabel.toolTipText =
                    "The control plane is listening on 127.0.0.1. Nothing has called it in this session."
            }
        }
    }

    private fun refreshDetailLabel() {
        val latest = rows.firstOrNull()
        if (latest == null) {
            detailLabel.text = "no agent calls yet — what one does will be listed here"
            detailLabel.foreground = JBColor.GRAY
            detailLabel.toolTipText = null
            return
        }

        detailLabel.text = buildString {
            append(callCountText())
            val mutations = rows.count { it.mutating }
            if (mutations > 0) append(", $mutations changed something")
            val refused = rows.count { it.outcome != AgentAuditLog.Outcome.OK }
            if (refused > 0) append(", $refused refused")
            append(" — ")
            append(describe(latest))
        }
        // A refused or failed call is the one thing on this strip that wants the user's eye even
        // when the table is collapsed.
        detailLabel.foreground =
            if (latest.outcome != AgentAuditLog.Outcome.OK) AMBER else JBColor.GRAY
        // One line, deliberately: a Swing tooltip does not honour \n without HTML, and these
        // strings carry paths and endpoint-written text that would then need escaping.
        detailLabel.toolTipText = buildString {
            append(formatTime(latest.timestampMs))
            append("  ")
            append(latest.client)
            append("  ")
            append(latest.method)
            append(' ')
            append(latest.path)
            latest.projectName?.let {
                append("  (")
                append(it)
                append(')')
            }
            append("  ·  ")
            append(latest.summary)
            latest.detail?.let {
                append(" — ")
                append(it)
            }
        }
    }

    private fun updateLinks() {
        // Nothing to show and nothing to copy: two dead links on a line that is otherwise honest.
        val hasRows = rows.isNotEmpty()
        toggleLink.isVisible = hasRows
        copyLink.isVisible = hasRows
        toggleLink.text = if (expanded) "Hide calls" else "Show calls"
        toggleLink.toolTipText = if (expanded) {
            "Collapse back to a single line. ✏️ marks a call that changed something."
        } else {
            "List the recent agent calls, newest first: when, who, what they touched, and whether it changed anything"
        }
    }

    private fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        bodyScroll.isVisible = value
        if (value) {
            // The table is kept in step even while collapsed (the header counts come from the same
            // snapshot), so this only has to size the body and show the newest line.
            updateBodyHeight()
            if (rows.isNotEmpty()) {
                auditTable.scrollRectToVisible(auditTable.getCellRect(0, 0, true))
            }
        }
        updateLinks()
        revalidate()
        repaint()
    }

    /**
     * Grow with the list, but never past [MAX_VISIBLE_ROWS]: two agent calls must not reserve five
     * rows of empty space, and two hundred must not swallow the flow list.
     */
    private fun updateBodyHeight() {
        val visibleRows = rows.size.coerceIn(1, MAX_VISIBLE_ROWS)
        val height = visibleRows * auditTable.rowHeight
        val current = auditTable.preferredScrollableViewportSize
        if (current != null && current.height == height) return

        auditTable.preferredScrollableViewportSize = Dimension(JBUI.scale(240), height)
        if (expanded) {
            // JScrollPane is a validate root, so revalidating the table alone would stop there and
            // the strip would keep asking its parent for the old height.
            revalidate()
        }
    }

    private fun isScrolledToTop(): Boolean {
        if (auditModel.rowCount == 0) return true
        return auditTable.visibleRect.y <= auditTable.rowHeight
    }

    // ========================================================================
    // Actions
    // ========================================================================

    /**
     * Exactly what is on screen, as text. The audit entries hold no tokens and no bodies, so this
     * is safe to paste into an issue — which is most of why it exists.
     */
    private fun copyVisibleLines() {
        val text = rows.joinToString("\n") { entry ->
            buildString {
                append(formatTime(entry.timestampMs))
                append("  ")
                append(entry.client)
                append("  ")
                append(entry.method)
                append(' ')
                append(entry.path)
                append("  ")
                append(outcomeText(entry))
                entry.projectName?.let {
                    append("  [")
                    append(it)
                    append(']')
                }
                append("  ")
                append(entry.summary)
                entry.detail?.let {
                    append(" — ")
                    append(it)
                }
            }
        }
        FlowCopyUtils.copyToClipboard(text)
    }

    // ========================================================================
    // Disposal
    // ========================================================================

    override fun dispose() {
        refreshTimer.stop()
        // The audit listener was registered with this view as its parent, so the platform removes
        // it as part of this very disposal.
    }

    // ========================================================================
    // Small helpers
    // ========================================================================

    /** Why there is no agent, when there is none. */
    private enum class Presence { OFF, NOT_LISTENING, LISTENING }

    /**
     * Reads only what is safe to paint: the mode and whether the socket is bound.
     * [AgentControlServer.binding] carries the token and is never called from the UI.
     */
    private fun readAgentPresence(): Presence = try {
        val server = AgentControlServer.getInstance()
        when {
            server.getAgentControl() == AGENT_CONTROL_OFF -> Presence.OFF
            server.isBound() -> Presence.LISTENING
            else -> Presence.NOT_LISTENING
        }
    } catch (e: Exception) {
        // A service that cannot be reached (IDE shutting down) must not take the strip — or the
        // EDT timer that paints it — down with it.
        log.debug("🤖 Agent control state unavailable", e)
        Presence.LISTENING
    }

    private fun modeText(mode: String?): String = when (mode) {
        "RECORDING" -> "Recording"
        "DEBUG" -> "Debug"
        "MOCKK" -> "Mockk"
        "MOCKK_DEBUG" -> "Mockk + Debug"
        else -> mode ?: "Running"
    }

    private fun modeColor(mode: String?): JBColor = when (mode) {
        "RECORDING" -> GREEN
        "DEBUG" -> CYAN
        "MOCKK" -> AMBER
        "MOCKK_DEBUG" -> MAGENTA
        else -> GREEN
    }

    /** `com.acme.weather_app` → `weather_app`. The whole name is one hover away. */
    private fun shortPackage(packageName: String): String =
        packageName.substringAfterLast('.').ifBlank { packageName }

    private fun callCountText(): String = when {
        rows.isEmpty() -> "no calls listed"
        // entries() clamps to the limit, so a full page means "at least this many".
        rows.size >= MAX_ROWS -> "$MAX_ROWS+ calls"
        rows.size == 1 -> "1 call"
        else -> "${rows.size} calls"
    }

    /**
     * The human sentence the entry was written with; the raw call is the fallback.
     *
     * Truncated, because this lands on a single line whose preferred width is what the Inspector
     * asks the tool window for. A summary is free-form text an endpoint wrote, so nothing stops it
     * from being a paragraph. The whole of it is one hover (or one "Show calls") away.
     */
    private fun describe(entry: AgentAuditLog.Entry): String {
        val what = entry.summary.ifBlank { "${entry.method} ${entry.path}" }
        val clipped = if (what.length > MAX_DETAIL_CHARS) what.take(MAX_DETAIL_CHARS).trimEnd() + "…" else what
        return if (entry.mutating) "✏️ $clipped" else clipped
    }

    private fun formatTime(epochMillis: Long): String = TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis))

    private fun outcomeText(entry: AgentAuditLog.Entry): String {
        val symbol = when (entry.outcome) {
            AgentAuditLog.Outcome.OK -> "✅"
            AgentAuditLog.Outcome.DENIED -> "⛔"
            AgentAuditLog.Outcome.ERROR -> "❌"
        }
        return if (entry.statusCode > 0) "$symbol ${entry.statusCode}" else symbol
    }

    // ========================================================================
    // Table
    // ========================================================================

    private inner class AuditTableModel : AbstractTableModel() {

        /** Newest first, exactly as [rows] holds them. */
        private var entries: List<AgentAuditLog.Entry> = emptyList()

        fun setRows(rows: List<AgentAuditLog.Entry>) {
            entries = rows
            fireTableDataChanged()
        }

        fun entryAt(row: Int): AgentAuditLog.Entry? = entries.getOrNull(row)

        override fun getRowCount(): Int = entries.size

        override fun getColumnCount(): Int = COLUMNS.size

        override fun getColumnName(column: Int): String = COLUMNS[column]

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val entry = entries.getOrNull(rowIndex) ?: return ""
            return when (columnIndex) {
                0 -> formatTime(entry.timestampMs)
                1 -> entry.client
                2 -> (if (entry.mutating) "✏️ " else "") + "${entry.method} ${entry.path}"
                3 -> outcomeText(entry)
                else -> entry.detail?.let { "${entry.summary} — $it" } ?: entry.summary
            }
        }
    }

    /** Colours a refused or failed call so it is findable in a wall of successful ones. */
    private inner class AuditCellRenderer : DefaultTableCellRenderer() {

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (!isSelected) {
                component.foreground = when (auditModel.entryAt(row)?.outcome) {
                    AgentAuditLog.Outcome.DENIED -> AMBER
                    AgentAuditLog.Outcome.ERROR -> JBColor.RED
                    else -> table.foreground
                }
            }
            return component
        }
    }
}
