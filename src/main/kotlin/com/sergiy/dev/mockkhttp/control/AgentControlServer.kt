package com.sergiy.dev.mockkhttp.control

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.sergiy.dev.mockkhttp.control.dto.AGENT_CONTROL_OFF
import com.sun.net.httpserver.HttpServer
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import com.sergiy.dev.mockkhttp.agent.AgentAuditLog
import com.sergiy.dev.mockkhttp.agent.FirstConnectionNotifier
import com.sergiy.dev.mockkhttp.agent.InstanceRegistry
import com.sergiy.dev.mockkhttp.store.AgentSettingsStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a successful bind produced. This is exactly what `InstanceRegistry` writes into
 * `~/.mockkhttp/instances/<instanceId>.json`, and the only place the bridge learns any of it.
 */
data class ControlBinding(
    val instanceId: String,
    val port: Int,
    /** Includes the `/v1` prefix, e.g. `http://127.0.0.1:53411/v1`. */
    val baseUrl: String,
    val token: String,
    val agentControl: String
)

/** One served control-plane call, for the audit log and the status-bar widget. */
data class ControlCall(
    val clientName: String,
    val method: String,
    val path: String,
    val status: Int,
    val mutating: Boolean,
    val durationMs: Long,
    val at: Long = System.currentTimeMillis()
)

/**
 * Notified after every served call, on the worker thread that served it.
 *
 * Implementations must not block and must not touch Swing directly — hop to the EDT if they need to
 * paint. Registered with [AgentControlServer.addCallListener], which unsubscribes on disposal.
 */
fun interface ControlCallListener {
    fun onControlCall(call: ControlCall)
}

/**
 * The loopback HTTP control plane: the socket, its thread pool, the token, and the lifecycle.
 *
 * Application-level because there is exactly one of it per IDE process, like the interceptor server
 * — several open projects share one port and are addressed by `project_id` in the path, so opening a
 * second project can never cause a port conflict.
 *
 * ## Binding
 *
 * `127.0.0.1`, always, on an **ephemeral** port (bind 0, read the real one back). The ephemeral port
 * is not laziness: a fixed port would have to be walked when a second IDE is open, and a walked port
 * cannot be baked into a committed `.mcp.json` — which is why the bridge discovers everything from
 * the instance file instead (plan §8).
 *
 * The address is `InetAddress.getByName("127.0.0.1")` and **not** `getLoopbackAddress()`: on a JVM
 * started with `-Djava.net.preferIPv6Addresses=true` the latter is `::1`, and everything downstream
 * — the published `baseUrl`, the bridge's `HttpClient`, the loopback checks in [ControlAuth] — is
 * written in terms of the IPv4 literal. One line of drift there and the bridge cannot connect at all.
 *
 * ## Threads
 *
 * Its own pool, never `AppExecutorUtil`'s shared one: a `flows/await` call parks a worker for up to
 * 25 s by design, and two of those on the shared application pool would starve unrelated IDE work.
 *
 * ## Never in the constructor
 *
 * An IntelliJ service constructor must not do I/O, so nothing binds until [start] is called from the
 * startup activity (or from the Settings panel when the user switches agent control back on).
 */
@Service(Service.Level.APP)
class AgentControlServer : Disposable {

    private val logger = Logger.getInstance(AgentControlServer::class.java)

    /** Token, browser lockout, rate limit, long-poll permits. Owned here, used by [ControlRouter]. */
    internal val auth: ControlAuth = ControlAuth().apply {
        // Seed from disk. ControlAuth defaults to FULL in memory, so without this a user who had
        // switched agent control OFF would find it back ON after every IDE restart — a setting
        // that silently un-sets itself is worse than no setting.
        setAgentControl(AgentSettingsStore.getInstance().getAgentControl())
    }

    /**
     * Identifies this IDE process in `~/.mockkhttp/instances/`.
     *
     * Pure computation (a pid and six random hex characters), so it is safe to do in a constructor,
     * and stable for the life of the process, so the instance file keeps one name across a rebind.
     */
    // Taken from InstanceRegistry, never generated here. Both classes used to draw their own,
    // so the id the bridge read out of the FILE never matched the one the control plane reported
    // in /v1/meta and /v1/status — and `MOCKKHTTP_PROJECT=instanceId:projectId` could never resolve.
    val instanceId: String
        get() = InstanceRegistry.getInstance().instanceId

    /** Guards the one-time audit subscription; see [startAuditing]. */
    private val auditingStarted = AtomicBoolean(false)

    @Volatile
    private var httpServer: HttpServer? = null

    @Volatile
    private var executor: ThreadPoolExecutor? = null

    @Volatile
    private var boundPort: Int = -1

    /** Why the last bind failed, or null when the listener is up. Shown in the Agent tab. */
    @Volatile
    private var lastBindError: String? = null

    /** Terminal. Once the IDE has disposed this service it must never bind again. */
    @Volatile
    private var disposed = false

    /** Backs `include_secrets`. Off until Settings grows the toggle; see plan §7.6. */
    @Volatile
    private var revealSecrets = false

    /**
     * Set before teardown so in-flight long-polls stop waiting.
     *
     * Read by [ControlRouter] on every request: while it is true a long-poll gets a budget of 0 ms
     * and returns at once instead of parking a worker for 25 s across `dispose()` — a worker that
     * outlives disposal pins the plugin classloader, which is how a plugin update leaves a dead port
     * behind (the same failure `GlobalOkHttpInterceptorServer` documents).
     */
    private val shuttingDown = AtomicBoolean(false)

    private val callListeners = CopyOnWriteArrayList<ControlCallListener>()

    private val threadCounter = AtomicInteger(0)

    companion object {
        /**
         * The bind address, spelled as an IPv4 literal on purpose. See the class KDoc — a JVM with
         * `preferIPv6Addresses` turns `getLoopbackAddress()` into `::1`.
         */
        const val BIND_HOST: String = "127.0.0.1"

        /** Listen backlog, per plan §7.1. */
        private const val BACKLOG = 64

        /**
         * Worker ceiling.
         *
         * Sized by long-polls, not by CPUs: `flows/await` holds a worker for up to
         * [ControlRouter.LONG_POLL_BUDGET_MS], and [ControlAuth.MAX_LONG_POLLS_PER_PROJECT] caps
         * those at two per project. Sixteen leaves plenty of headroom for `status`, `flows` and
         * `mocks` while a couple of waiters are parked.
         */
        private const val MAX_WORKERS = 16

        /** Idle workers exit after this, so the pool costs nothing between agent sessions. */
        private const val WORKER_KEEPALIVE_SECONDS = 60L

        /** How long teardown waits for workers to finish before interrupting them. */
        private const val DRAIN_WAIT_MS = 1_500L

        /** How long teardown waits after interrupting. Bounded: disposal must never hang the IDE. */
        private const val INTERRUPT_WAIT_MS = 1_000L

        fun getInstance(): AgentControlServer =
            ApplicationManager.getApplication().getService(AgentControlServer::class.java)

        private fun randomSuffix(): String {
            val bytes = ByteArray(3)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    /**
     * Bind the control plane, or return the binding it already has.
     *
     * Idempotent and safe to call from a startup activity, from Settings, and from a retry button.
     *
     * @return the live binding, or null when agent control is off, the service is disposed, or the
     *         bind failed — [getBindError] then says which.
     */
    @Synchronized
    fun start(): ControlBinding? {
        if (disposed) return null
        if (auth.getAgentControl() == AGENT_CONTROL_OFF) {
            lastBindError = "Agent control is off (MockkHttp → Settings → AI Agent Access)."
            return null
        }
        binding()?.let { return it }

        startAuditing()
        logger.info("🔧 Starting agent control plane on $BIND_HOST (ephemeral port)")
        shuttingDown.set(false)
        return try {
            val pool = newWorkerPool()
            val server = HttpServer.create(InetSocketAddress(InetAddress.getByName(BIND_HOST), 0), BACKLOG)
            server.createContext("/", ControlRouter(this))
            server.executor = pool
            server.start()

            httpServer = server
            executor = pool
            boundPort = server.address.port
            lastBindError = null
            publishEnvironment()

            val bound = requireNotNull(binding())

            // Publish it. Without this the server binds, works, and is invisible: the instance file
            // is the ONLY channel through which the bridge learns the port and the token.
            val published = InstanceRegistry.getInstance().setControlEndpoint(
                InstanceRegistry.ControlEndpoint(
                    baseUrl = bound.baseUrl,
                    token = bound.token,
                    agentControl = bound.agentControl
                )
            )
            if (!published) {
                logger.warn(
                    "⚠️ Control plane is listening but its instance file could not be written — " +
                            "an agent will not be able to discover it. Check ~/.mockkhttp permissions."
                )
            }

            logger.info("✅ Agent control plane listening on ${bound.baseUrl} (instance ${bound.instanceId})")
            bound
        } catch (e: Exception) {
            lastBindError = describeBindFailure(e)
            // Operational, not a plugin bug: a firewall or a sandbox can cause it, so no fatal
            // dialog. The Agent tab shows getBindError() and the feature simply stays unavailable.
            logger.warn("❌ Agent control plane failed to bind: $lastBindError", e)
            teardown()
            publishEnvironment()
            null
        }
    }

    /**
     * Close the socket and release the workers. Safe to call when nothing is bound.
     *
     * Order matters: flag → drain → interrupt → `stop(0)` → shut the pool. A parked long-poll only
     * unwinds on an interrupt (`ControlApi.awaitFlow` handles one and returns a normal
     * not-satisfied answer), and `HttpServer.stop` waits for its handlers, so interrupting first is
     * what keeps teardown bounded instead of 25 s long.
     */
    @Synchronized
    fun stop() {
        val server = httpServer ?: run {
            teardown()
            return
        }
        logger.info("🛑 Stopping agent control plane on port $boundPort")
        shuttingDown.set(true)

        // Retract the advertisement FIRST. A bridge that reads the file between the socket closing
        // and the file being cleared would dial a dead port and report a confusing connection error
        // instead of the accurate "no live MockkHttp instance".
        //
        // Guarded: on IDE shutdown the application container can already be gone, and asking it for
        // another service then throws. Losing the file costs nothing — InstanceRegistry.dispose()
        // deletes it anyway — while letting the throw escape would skip the socket close below and
        // leak the port for the rest of the process.
        try {
            InstanceRegistry.getInstance().clearControlEndpoint()
        } catch (e: Exception) {
            logger.debug("Could not clear the instance file during shutdown", e)
        }

        val pool = executor
        if (pool != null) {
            // Refuse new work but let what is running finish — most calls take microseconds. The
            // pool is drained BEFORE the listener closes so an in-flight long-poll still gets its
            // answer written; the cost is a sub-second window in which a newly arriving request is
            // rejected by the pool and dropped by the JDK server. During teardown a dropped new
            // request is cheaper than a swallowed answer to one already in flight.
            pool.shutdown()
            if (!pool.awaitTermination(DRAIN_WAIT_MS, TimeUnit.MILLISECONDS)) {
                // Whatever is left is a parked long-poll. Interrupt it: the façade unwinds cleanly.
                pool.shutdownNow()
                pool.awaitTermination(INTERRUPT_WAIT_MS, TimeUnit.MILLISECONDS)
            }
        }
        try {
            server.stop(0)
        } catch (e: Exception) {
            logger.warn("⚠️ Agent control plane did not stop cleanly: ${e.message}", e)
        }
        teardown()
        publishEnvironment()
    }

    /**
     * Disposed by the platform on IDE shutdown **and on plugin unload** (update / disable).
     *
     * Without this the workers survive the unload — they are daemon threads, so nothing reaps them —
     * pinning the old classloader and keeping the port bound, and the reloaded plugin then binds a
     * second one while the bridge still talks to the corpse.
     */
    override fun dispose() {
        logger.info("🛑 Disposing agent control plane (IDE shutdown or plugin unload)")
        // Set BEFORE stop(): start() is public and reachable from a background thread, so without a
        // terminal flag a start racing with teardown would resurrect the listener on the very
        // classloader we are trying to release.
        disposed = true
        shuttingDown.set(true)
        stop()
        callListeners.clear()
    }

    private fun teardown() {
        httpServer = null
        executor = null
        boundPort = -1
    }

    private fun newWorkerPool(): ThreadPoolExecutor {
        val factory = ThreadFactory { runnable ->
            Thread(runnable, "MockkHttp-Control-${threadCounter.incrementAndGet()}").apply {
                // Daemon: a worker must never be the reason the IDE cannot exit.
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }
        // Core == max with allowCoreThreadTimeOut, deliberately, rather than the more obvious
        // core 2 / max 16 over a SynchronousQueue. With an unbounded queue a ThreadPoolExecutor only
        // grows past the CORE size when the queue is full — which never happens — so "core 2" there
        // would cap real concurrency at two and one parked long-poll would halve the control plane.
        // A SynchronousQueue fixes that but rejects the 17th task, and a rejected task is an
        // HttpExchange nobody can answer: the caller hangs until its own timeout with a leaked
        // socket. This shape gives the same behaviour the plan asks for — threads created on demand
        // up to 16, idle ones reaped after 60 s so the resting pool is empty — and queues the
        // overflow instead of dropping it.
        return ThreadPoolExecutor(
            MAX_WORKERS,
            MAX_WORKERS,
            WORKER_KEEPALIVE_SECONDS,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
            factory
        ).apply { allowCoreThreadTimeOut(true) }
    }

    private fun describeBindFailure(e: Exception): String = when (e) {
        is BindException ->
            "Could not bind a loopback port: ${e.message ?: "address unavailable"}. " +
                    "Something is restricting sockets on 127.0.0.1 (a sandbox or a security product)."

        is SecurityException ->
            "The JVM security policy refused a loopback listener: ${e.message ?: "permission denied"}."

        else -> "${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
    }

    // ========================================================================
    // State
    // ========================================================================

    fun isBound(): Boolean = httpServer != null && boundPort > 0

    /** Human-readable reason the control plane is not up, or null when it is. */
    fun getBindError(): String? = lastBindError

    fun getPort(): Int? = boundPort.takeIf { it > 0 }

    fun getBaseUrl(): String? = getPort()?.let { "http://$BIND_HOST:$it/${ControlRouter.API_PREFIX}" }

    /** Everything the instance file needs, or null when nothing is bound. */
    fun binding(): ControlBinding? {
        val port = getPort() ?: return null
        val url = getBaseUrl() ?: return null
        return ControlBinding(
            instanceId = instanceId,
            port = port,
            baseUrl = url,
            token = auth.currentToken(),
            agentControl = auth.getAgentControl()
        )
    }

    /** True from the moment teardown begins. [ControlRouter] uses it to zero the long-poll budget. */
    fun isShuttingDown(): Boolean = shuttingDown.get() || disposed

    // ========================================================================
    // Token and mode
    // ========================================================================

    fun currentToken(): String = auth.currentToken()

    /**
     * Revoke: regenerate the token, instantly breaking every connected bridge.
     *
     * The caller **must** republish the instance file afterwards (`InstanceRegistry`), or the next
     * bridge launch reads a token this IDE no longer accepts and reports a 401 it cannot explain.
     */
    fun rotateToken(): String {
        val fresh = auth.rotateToken()
        logger.info("🤖 Agent control token rotated — every connected client must re-read the instance file")
        return fresh
    }

    fun getAgentControl(): String = auth.getAgentControl()

    /**
     * Switch between `full`, `read_only` and `off` (plan D4).
     *
     * `off` closes the socket outright rather than only refusing requests: the point of the switch
     * is that there is nothing left to talk to. Turning it back on binds a **new** ephemeral port
     * and keeps the same token, so a caller that cached the old port must re-read the instance file.
     */
    @Synchronized
    fun setAgentControl(value: String) {
        val previous = auth.getAgentControl()
        auth.setAgentControl(value)
        val effective = auth.getAgentControl()
        if (effective == previous) return

        logger.info("🤖 Agent control changed: $previous → $effective")
        // Recorded here, not at the callers: the Settings radio buttons, the balloon's "Revoke
        // Access" and any future entry point all pass through this method, and the Agent tab
        // must read as one story whichever of them the user pressed.
        try {
            AgentAuditLog.getInstance().note("control/$effective", "Agent control switched from $previous to $effective")
        } catch (t: Throwable) {
            logger.debug("Could not record the control change in the audit log", t)
        }
        AgentSettingsStore.getInstance().setAgentControl(effective)
        // Republish: InstanceRegistry's copy of agentControl is only written by setControlEndpoint,
        // so a Full → Read-only switch on an already-bound plane would leave the instance file
        // advertising "full" until the next rebind, and the bridge reads that field.
        binding()?.let { bound ->
            try {
                InstanceRegistry.getInstance().setControlEndpoint(
                    InstanceRegistry.ControlEndpoint(
                        baseUrl = bound.baseUrl,
                        token = bound.token,
                        agentControl = bound.agentControl
                    )
                )
            } catch (e: Exception) {
                logger.warn("⚠️ Could not republish the instance file after a mode change", e)
            }
        }
        if (effective == AGENT_CONTROL_OFF) {
            stop()
        } else if (!isBound()) {
            start()
        } else {
            publishEnvironment()
        }
    }

    fun isRevealSecretsAllowed(): Boolean = revealSecrets

    /**
     * Allow `include_secrets:true` to return real header values (plan §7.6).
     *
     * Default off, and the façade answers `403 REVEAL_DISABLED` while it is: captured
     * `Authorization` headers are live credentials, and an agent asks for them by accident far more
     * often than on purpose.
     */
    fun setRevealSecrets(allowed: Boolean) {
        if (revealSecrets == allowed) return
        revealSecrets = allowed
        logger.info(if (allowed) "🤖 Agent secret reveal ENABLED" else "🤖 Agent secret reveal disabled")
        publishEnvironment()
    }

    /**
     * Push the facts the façade cannot discover on its own into [ControlApi].
     *
     * Called after every bind, teardown and settings change. `ControlApi` never reads a setting or a
     * socket itself — that one-way flow is what keeps it a pure, testable façade.
     */
    private fun publishEnvironment() {
        // Guarded because this is also called from stop(), and stop() is called from dispose():
        // during IDE shutdown the application container can already be gone by the time our service
        // is disposed, and asking it for another service then throws. Failing to publish at that
        // point is harmless — nothing will read it again — while throwing out of dispose() aborts
        // the rest of the teardown and leaks the listener socket the whole method exists to close.
        val application = ApplicationManager.getApplication() ?: return
        if (application.isDisposed) return
        try {
            application.getService(ControlApi::class.java)?.environment = ControlEnvironment(
                instanceId = instanceId,
                controlPort = getPort(),
                agentControl = auth.getAgentControl(),
                revealSecrets = revealSecrets
            )
        } catch (t: Throwable) {
            logger.info("⚠️ Could not publish the control environment (the IDE is shutting down): ${t.message}")
        }
    }

    // ========================================================================
    // Audit hook
    // ========================================================================

    /**
     * Observe every served call — the audit log, the status-bar widget, the first-connection balloon.
     *
     * A hook rather than a direct call into `AgentAuditLog` so that neither this file nor the router
     * has to know that class exists; [parent] scopes the subscription, so nothing leaks a listener
     * when a tool window is rebuilt.
     */
    fun addCallListener(parent: Disposable, listener: ControlCallListener) {
        callListeners.add(listener)
        Disposer.register(parent) { callListeners.remove(listener) }
    }

    /**
     * Feed every served call into the audit log.
     *
     * Registered once, against this service's own lifetime, so the trail is complete from the first
     * request — not only while the Agent tab happens to be open. The tab is a *reader* of the log;
     * having it do the recording would both miss calls made before it was opened and double-record
     * once this wiring existed.
     */
    private fun startAuditing() {
        if (!auditingStarted.compareAndSet(false, true)) return

        // Announce the first connection with a Revoke action. Installed here, alongside the audit
        // subscription, because a listener registered later would miss the one connection actually
        // worth announcing — the first.
        FirstConnectionNotifier.getInstance().install(this)
        addCallListener(this) { call ->
            val outcome = when {
                call.status in 200..399 -> AgentAuditLog.Outcome.OK
                call.status == 401 || call.status == 403 || call.status == 429 -> AgentAuditLog.Outcome.DENIED
                else -> AgentAuditLog.Outcome.ERROR
            }
            AgentAuditLog.getInstance().record(
                client = call.clientName,
                method = call.method,
                path = call.path,
                outcome = outcome,
                statusCode = call.status,
                summary = "${call.method} ${call.path} → ${call.status} (${call.durationMs} ms)",
                mutating = call.mutating
            )
        }
    }

    /** Called by [ControlRouter] on the worker thread, after the response has been written. */
    internal fun notifyCall(call: ControlCall) {
        if (callListeners.isEmpty()) return
        for (listener in callListeners) {
            try {
                listener.onControlCall(call)
            } catch (t: Throwable) {
                // An audit listener must never be able to break the request it is describing.
                logger.warn("⚠️ Agent control call listener failed for ${call.method} ${call.path}", t)
            }
        }
    }

    /** Uppercased mode name, for log lines and the Agent tab. */
    fun describeState(): String = when {
        disposed -> "DISPOSED"
        auth.getAgentControl() == AGENT_CONTROL_OFF -> "OFF"
        isBound() -> "${auth.getAgentControl().uppercase(Locale.ROOT)} on port $boundPort"
        else -> "NOT BOUND${lastBindError?.let { " — $it" } ?: ""}"
    }
}
