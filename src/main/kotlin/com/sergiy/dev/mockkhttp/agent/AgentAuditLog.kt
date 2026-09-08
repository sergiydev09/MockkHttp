package com.sergiy.dev.mockkhttp.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * The record of everything an automated caller did, and the thing that makes agent control
 * defensible on a single-user box (plan §7.9).
 *
 * The threat model is explicit that a same-uid process is out of scope — it can already read
 * `~/.ssh` and write `.git/hooks/pre-commit`. What is *not* acceptable is an agent quietly
 * rewriting the responses a debug build sees with nobody able to say what happened. So every call
 * is attributable (`X-MockkHttp-Client`), visible (Logs tab + Agent tab), and the first call from
 * an unseen client raises a balloon with **Revoke**.
 *
 * ## Scope
 *
 * This class is data only. It holds the ring, mirrors into [MockkHttpLogger] and fires events; it
 * builds no UI and it revokes nothing. `AgentAuditPanel`, the status-bar widget and the balloon
 * consume [addListener] / [addFirstConnectionListener]; `ControlAuth.rotateToken()` is what a
 * Revoke action actually calls, and it should call [forgetClients] afterwards so the next
 * connection is announced again.
 *
 * Every method is safe to call from the control plane's worker threads.
 */
@Service(Service.Level.APP)
class AgentAuditLog {

    private val log = Logger.getInstance(AgentAuditLog::class.java)

    /**
     * Ring guarded by [ringLock]. Deliberately an [ArrayDeque] and not a `CopyOnWriteArrayList`:
     * the audit ring is written on the request path, and copying 500 entries per call would put a
     * measurable cost on every agent request. Readers take a snapshot instead.
     */
    private val ringLock = Any()
    private val ring = ArrayDeque<Entry>(MAX_ENTRIES)

    private val sequence = AtomicLong(0)

    /** client name -> epoch millis of its first call. Drives the first-connection balloon. */
    private val seenClients = ConcurrentHashMap<String, Long>()

    private val listeners = CopyOnWriteArrayList<AuditListener>()
    private val firstConnectionListeners = CopyOnWriteArrayList<FirstConnectionListener>()

    @Volatile
    private var lastActivityAtMs: Long = 0L

    /**
     * The most recent first-connection event, kept so a panel or widget that registers *after* the
     * bridge has already called in can still show the Revoke affordance. Cleared by [forgetClients].
     */
    @Volatile
    private var lastFirstConnection: FirstConnection? = null

    companion object {
        /** Plan §6: a 500-entry ring. Old entries are dropped, never written to disk. */
        const val MAX_ENTRIES: Int = 500

        /** How recently a call must have arrived for the status-bar widget to light up (§7.9). */
        const val ACTIVE_WINDOW_MS: Long = 60_000L

        /** Author of entries this plugin records about itself (token rotation, mode changes). */
        const val IDE_CLIENT: String = "mockkhttp-ide"

        fun getInstance(): AgentAuditLog =
            ApplicationManager.getApplication().getService(AgentAuditLog::class.java)
    }

    /** How a call ended. Drives both the mirrored log level and the Agent tab's colouring. */
    enum class Outcome { OK, DENIED, ERROR }

    /**
     * One line of the audit trail.
     *
     * [summary] is written for a human reading the Agent tab ("enabled rule 'login 503'"), not for
     * a machine. [detail] carries whatever would not fit — never a token, never a response body.
     */
    data class Entry(
        val seq: Long,
        val timestampMs: Long,
        val client: String,
        val method: String,
        val path: String,
        val projectId: String?,
        val projectName: String?,
        val mutating: Boolean,
        val outcome: Outcome,
        val statusCode: Int,
        val summary: String,
        val detail: String? = null
    )

    /** Everything a first-connection balloon needs. The Revoke action itself lives in the UI. */
    data class FirstConnection(
        val client: String,
        val atMs: Long,
        val projectName: String?
    )

    fun interface AuditListener {
        fun onEntry(entry: Entry)
    }

    fun interface FirstConnectionListener {
        fun onFirstConnection(connection: FirstConnection)
    }

    // ------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------

    /**
     * Record one control-plane call.
     *
     * Reads are recorded too — the Agent tab is far less useful if it cannot show that an agent
     * read 40 flows — but only mutations reach the Logs tab at INFO; reads are mirrored at DEBUG so
     * a polling bridge cannot bury a human's own logs.
     */
    fun record(
        client: String,
        method: String,
        path: String,
        outcome: Outcome,
        statusCode: Int,
        summary: String,
        mutating: Boolean = false,
        projectId: String? = null,
        projectName: String? = null,
        detail: String? = null
    ): Entry {
        val now = System.currentTimeMillis()
        val entry = Entry(
            seq = sequence.incrementAndGet(),
            timestampMs = now,
            client = client,
            method = method,
            path = path,
            projectId = projectId,
            projectName = projectName,
            mutating = mutating,
            outcome = outcome,
            statusCode = statusCode,
            summary = summary,
            detail = detail
        )

        synchronized(ringLock) {
            ring.addLast(entry)
            while (ring.size > MAX_ENTRIES) {
                ring.removeFirst()
            }
        }
        lastActivityAtMs = now

        mirrorToProjectLogs(entry)
        notifyEntry(entry)
        announceIfFirstContact(client, projectName, now)
        return entry
    }

    /**
     * Record something the *plugin* did to the agent channel — token rotated, control switched
     * off, discovery refused. Same ring, so the Agent tab reads as one story.
     */
    fun note(action: String, summary: String, outcome: Outcome = Outcome.OK): Entry =
        record(
            client = IDE_CLIENT,
            method = "IDE",
            path = action,
            outcome = outcome,
            statusCode = 0,
            summary = summary,
            mutating = true
        )

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /** Newest entries last. [limit] is clamped to the ring size. */
    fun entries(limit: Int = MAX_ENTRIES): List<Entry> {
        val snapshot = synchronized(ringLock) { ring.toList() }
        if (limit >= snapshot.size) return snapshot
        return snapshot.subList(snapshot.size - limit.coerceAtLeast(0), snapshot.size)
    }

    /** Epoch millis of the last recorded call, or 0 when nothing has ever called in. */
    fun lastActivityAtMs(): Long = lastActivityAtMs

    /** True when a client called within [ACTIVE_WINDOW_MS] — the status-bar widget's lit state. */
    fun isActive(withinMs: Long = ACTIVE_WINDOW_MS): Boolean {
        val last = lastActivityAtMs
        return last > 0L && System.currentTimeMillis() - last <= withinMs
    }

    /** Client names seen since the last [forgetClients], with the epoch millis of first contact. */
    fun knownClients(): Map<String, Long> = HashMap(seenClients)

    /** The most recent first-connection event, for a UI that attached late. */
    fun pendingFirstConnection(): FirstConnection? = lastFirstConnection

    /**
     * Forget which clients have been seen, so the next call from any of them balloons again.
     * Call this right after rotating the token: the old bridges are dead, and the user must be
     * told when a new one takes their place.
     */
    fun forgetClients() {
        seenClients.clear()
        lastFirstConnection = null
    }

    /** Drop the ring. The Agent tab's "Clear" button; does not affect [knownClients]. */
    fun clear() {
        synchronized(ringLock) { ring.clear() }
    }

    // ------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------

    /**
     * Listen for every recorded entry until [parent] is disposed.
     *
     * The [Disposable] scoping is not optional: a tool-window panel that outlives its project in
     * this list keeps the whole project tree alive, which is the exact leak the plugin's older
     * `clearAllListeners()` pattern was covering up.
     */
    fun addListener(parent: Disposable, listener: AuditListener) {
        listeners.add(listener)
        registerRemoval(parent) { listeners.remove(listener) }
    }

    fun addFirstConnectionListener(parent: Disposable, listener: FirstConnectionListener) {
        firstConnectionListeners.add(listener)
        registerRemoval(parent) { firstConnectionListeners.remove(listener) }
    }

    private fun registerRemoval(parent: Disposable, removal: () -> Unit) {
        try {
            Disposer.register(parent, Disposable { removal() })
        } catch (e: Exception) {
            // Already-disposed parent: never leave the listener behind.
            removal()
            log.debug("🤖 Audit listener parent was already disposed", e)
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun announceIfFirstContact(client: String, projectName: String?, now: Long) {
        if (client == IDE_CLIENT) return
        if (seenClients.putIfAbsent(client, now) != null) return

        val connection = FirstConnection(client = client, atMs = now, projectName = projectName)
        lastFirstConnection = connection
        log.info("🤖 ✅ First agent connection from '$client'")

        for (listener in firstConnectionListeners) {
            try {
                listener.onFirstConnection(connection)
            } catch (e: Exception) {
                log.warn("🤖 ⚠️ First-connection listener failed", e)
            }
        }
    }

    private fun notifyEntry(entry: Entry) {
        for (listener in listeners) {
            try {
                listener.onEntry(entry)
            } catch (e: Exception) {
                log.warn("🤖 ⚠️ Audit listener failed", e)
            }
        }
    }

    /**
     * Mirror into the Logs tab the user already reads, under the `🤖 AGENT` prefix so the channel
     * can be found with one search.
     */
    private fun mirrorToProjectLogs(entry: Entry) {
        val line = buildString {
            append("🤖 AGENT ")
            append(entry.client)
            append(' ')
            append(entry.method)
            append(' ')
            append(entry.path)
            if (entry.statusCode > 0) {
                append(" → ")
                append(entry.statusCode)
            }
            append(" · ")
            append(entry.summary)
            entry.detail?.let {
                append(" — ")
                append(it)
            }
        }

        for (project in targetProjects(entry.projectId)) {
            val logger = try {
                MockkHttpLogger.getInstance(project)
            } catch (e: Exception) {
                continue
            }
            when {
                // An environment can cause DENIED (wrong token, READ_ONLY) and ERROR (a bad
                // request), so neither is an internalError: they are operational, not IDE bugs.
                entry.outcome == Outcome.ERROR -> logger.error(line)
                entry.outcome == Outcome.DENIED -> logger.warn(line)
                entry.mutating -> logger.info(line)
                else -> logger.debug(line)
            }
        }
    }

    /** The project named by the entry, or every open project when the call was not project-scoped. */
    private fun targetProjects(projectId: String?): List<Project> {
        val open = try {
            ProjectManager.getInstance().openProjects
        } catch (e: Exception) {
            return emptyList()
        }
        val usable = open.filter { !it.isDisposed && !it.isDefault }
        if (projectId == null) return usable
        val match = usable.firstOrNull { it.locationHash == projectId }
        return if (match != null) listOf(match) else usable
    }
}
