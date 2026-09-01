package com.sergiy.dev.mockkhttp.proxy

import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Global application-level server that listens for connections from MockkHttpInterceptor in Android apps.
 * This singleton server handles connections from ALL projects and routes flows to the correct project.
 *
 * This solves the problem of multiple projects trying to bind to the same port.
 *
 * Implements [Disposable] because the platform disposes application-level services when the IDE
 * closes AND when the plugin is unloaded (update / disable). Without a teardown the accept thread
 * and every connection thread survive the unload — they are daemon threads, so nothing reaps them —
 * pinning the old plugin classloader and keeping port 9876 bound. The reloaded plugin then fails to
 * bind and capture is silently dead until the user restarts the IDE.
 */
@Service(Service.Level.APP)
class GlobalOkHttpInterceptorServer : Disposable {

    private val logger = Logger.getInstance(GlobalOkHttpInterceptorServer::class.java)
    private val gson = Gson()

    // All four are read from the accept thread and the connection threads while the lifecycle
    // methods write them under the instance monitor, so every one of them must be volatile.
    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var isRunning = false

    @Volatile
    private var serverThread: Thread? = null

    @Volatile
    private var connectionPool: ThreadPoolExecutor? = null

    /** Why the last bind attempt failed, or null if the listener is up. Surfaced by [getBindError]. */
    @Volatile
    private var lastBindError: String? = null

    /** Terminal: once disposed the server must never bind again. See [dispose]. */
    @Volatile
    private var disposed = false

    /**
     * Bind the listener to loopback only instead of to every interface.
     *
     * Kept OFF by default, and that is deliberate. A physical iPhone has no `adb reverse`
     * equivalent: it reaches the plugin over the LAN at the Mac's real IP, which is exactly what
     * `MockkHttp.init(host: '192.168.x.x')` configures (flutter-package/lib/src/mockk_http_core.dart).
     * Loopback-only binding would kill that setup with nothing but a connection timeout to show for
     * it. Everything else is unaffected: emulators, iOS Simulators and physical Android devices all
     * arrive through `adb reverse tcp:9876`, which terminates on the HOST's loopback.
     *
     * Until it is flipped, [BIND_LOOPBACK_PROPERTY] lets a user who never touches a physical iPhone
     * harden the bind today. The value is read at bind time, so toggling it only takes effect the
     * next time the server starts.
     *
     * TODO(settings): back this with a persisted flag — add `var bindLoopbackOnly: Boolean = false`
     * to SettingsStore.State plus an `isBindLoopbackOnly()` getter, and have the settings UI assign
     * it here. Deliberately not done in this file: SettingsStore is a PROJECT service and this
     * server is application-level, so the wire-up belongs to whoever owns both.
     */
    @Volatile
    var bindLoopbackOnly: Boolean = System.getProperty(BIND_LOOPBACK_PROPERTY).toBoolean()

    /**
     * Registry of projects listening for flows, insertion-ordered.
     *
     * Held as an IMMUTABLE snapshot that is swapped wholesale under
     * [registrationWriteLock]. Readers — [routeFlow], [findTargetProject],
     * [findTargetProjectForMockCheck] — run on socket threads and iterate
     * `.values` freely.
     *
     * This used to be a `Collections.synchronizedMap(LinkedHashMap)`, whose
     * iterators are only safe while holding the map's own monitor. No read site
     * held it, so opening or closing a project while a flow was in flight could
     * throw ConcurrentModificationException on the socket thread and drop the
     * request. A snapshot has no iterator to invalidate.
     */
    @Volatile
    private var registeredProjects: Map<String, ProjectRegistration> = emptyMap()

    /** Guards the read-copy-publish cycle in [updateRegistrations]. Never held while calling out. */
    private val registrationWriteLock = Any()
    private val registrationListeners = CopyOnWriteArrayList<RegistrationListener>()

    // Track most recently active project (for smarter routing)
    @Volatile
    private var lastActiveProjectId: String? = null

    // Packages that have announced themselves via PING (Flutter and native apps)
    private val knownMockkHttpPackages = java.util.Collections.synchronizedSet(
        LinkedHashSet<String>()
    )

    companion object {
        const val SERVER_PORT = 9876

        /**
         * How long the app waits for our reply before giving up, in ms.
         *
         * Mirrors `MockkHttpInterceptor.READ_TIMEOUT_MS` (android-library) and
         * `_readTimeoutMs` (flutter-package/lib/src/mockk_http_client.dart).
         * Change all three together or the plugin will keep writing into sockets
         * the app has already abandoned.
         */
        const val CLIENT_READ_TIMEOUT_MS = 60_000L

        /**
         * The budget a Debug-mode decision actually has.
         *
         * The plugin used to wait 5 minutes on the intercept dialog while the app
         * gave up after 60 s: for four minutes it believed the user was still
         * deciding, and the edited response was then written to a closed socket
         * where PrintWriter swallows the IOException — the edit vanished with no
         * error anywhere. Staying under [CLIENT_READ_TIMEOUT_MS] keeps the reply
         * meaningful, and the margin covers serialisation plus the trip back.
         */
        const val DEBUG_DECISION_TIMEOUT_MS = 55_000L

        /**
         * How many connections are handled concurrently before new ones are refused.
         *
         * This pool cannot be sized by CPU count: a DEBUG-mode connection is parked for as long as
         * the user takes to decide, up to [DEBUG_DECISION_TIMEOUT_MS], so the real question is "how
         * many requests can an app fire while one intercept dialog is open". 64 is generous enough
         * that a chatty app behind a stuck dialog never starves — and it still bounds what a runaway
         * client can do, which the previous one-thread-per-socket approach did not.
         */
        private const val MAX_CONNECTION_THREADS = 64

        /** Idle connection threads exit after this, so the pool costs nothing between sessions. */
        private const val CONNECTION_THREAD_KEEPALIVE_SECONDS = 60L

        /** Listen backlog. Same value the no-arg ServerSocket constructor uses. */
        private const val ACCEPT_BACKLOG = 50

        /**
         * How long to wait for a client to send its request line once connected.
         *
         * Generous — a 5 MB body arriving from a physical device through `adb reverse` is the
         * worst case — but finite, so a dead peer cannot hold a connection slot forever. This
         * bounds only the READ: a DEBUG connection legitimately stays open far longer than this
         * while the decision is pending, and writing is unaffected.
         */
        private const val REQUEST_READ_TIMEOUT_MS = 15_000

        /** Teardown waits. Bounded so disposal can never hang plugin unload or IDE shutdown. */
        private const val ACCEPT_THREAD_JOIN_MS = 2_000L
        private const val POOL_TERMINATION_WAIT_MS = 2_000L

        /** Opt-in loopback-only bind until a real setting exists. See [bindLoopbackOnly]. */
        private const val BIND_LOOPBACK_PROPERTY = "mockkhttp.bind.loopbackOnly"

        fun getInstance(): GlobalOkHttpInterceptorServer {
            return ApplicationManager.getApplication().getService(GlobalOkHttpInterceptorServer::class.java)
        }
    }

    /**
     * Returns the set of package names that have announced themselves via PING.
     * Used by AppManager to detect Flutter (and other) apps that can't be found via APK grep.
     */
    fun getKnownMockkHttpPackages(): Set<String> = knownMockkHttpPackages.toSet()

    /**
     * Check if a specific package has announced itself via PING.
     */
    fun isKnownMockkHttpPackage(packageName: String): Boolean = packageName in knownMockkHttpPackages

    /** Record a package that has proven it speaks the MockkHttp protocol. */
    private fun rememberPackage(packageName: String?) {
        if (packageName.isNullOrBlank()) return
        if (knownMockkHttpPackages.add(packageName)) {
            logger.info("📡 Registered MockkHttp app: $packageName")
        }
    }

    /**
     * Represents a project that is registered to receive flows.
     */
    data class ProjectRegistration(
        val projectId: String,
        val projectName: String,
        val project: Project,
        val mode: InterceptMode,
        val flowHandler: FlowHandler,
        val packageNameFilter: String? = null // Optional: only route flows from specific package
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ProjectRegistration) return false
            return projectId == other.projectId
        }

        override fun hashCode(): Int = projectId.hashCode()
    }

    /**
     * Intercept mode for a project.
     */
    enum class InterceptMode {
        RECORDING,    // Just capture, don't pause
        DEBUG,        // Pause and show dialog
        MOCKK,        // Auto-apply mock rules
        MOCKK_DEBUG   // Apply mock THEN pause for editing
    }

    /**
     * Handler interface for receiving flows in a project.
     */
    fun interface FlowHandler {
        /**
         * Handle an intercepted flow.
         * Returns the modified response data (or null for original).
         */
        fun handleFlow(flowData: AndroidFlowData): ModifiedResponseData
    }

    /**
     * Listener for registration events.
     */
    interface RegistrationListener {
        fun onProjectRegistered(registration: ProjectRegistration)
        fun onProjectUnregistered(projectId: String)
    }

    /**
     * Start the global server if not already running.
     * This is automatically called when a project registers.
     */
    @Synchronized
    fun ensureStarted(): Boolean {
        if (disposed) {
            logger.warn("⚠️ Global interceptor server is disposed; refusing to start")
            return false
        }

        if (isBound()) {
            logger.info("Global interceptor server already running on port $SERVER_PORT")
            return true
        }

        // Not bound, but something may still be left standing. runServer()'s finally clears
        // isRunning while leaving the ServerSocket open and the pool alive, so gating this
        // recovery on isRunning alone would skip it and then fail the rebind with
        // "Address already in use" — against our own orphaned socket.
        if (isRunning || serverSocket != null || serverThread != null || connectionPool != null) {
            logger.warn("⚠️ Global server left partially built (listener gone), tearing down before rebinding...")
            stop()
        }

        // null means "every local address", which is what ServerSocket has always done here.
        // 127.0.0.1 explicitly, NOT InetAddress.getLoopbackAddress(): on a JVM started with
        // -Djava.net.preferIPv6Addresses=true that returns ::1, and `adb reverse tcp:9876` dials
        // the host's IPv4 loopback — so the hardened bind would silently kill every Android device.
        val bindAddress = if (bindLoopbackOnly) InetAddress.getByName("127.0.0.1") else null
        val scope = if (bindLoopbackOnly) "loopback only" else "all interfaces"
        logger.info("🚀 Starting Global OkHttp Interceptor Server on port $SERVER_PORT ($scope)")

        return try {
            serverSocket = ServerSocket(SERVER_PORT, ACCEPT_BACKLOG, bindAddress)
            connectionPool = createConnectionPool()
            isRunning = true
            lastBindError = null

            // start = false so the field is assigned before the loop can reach runServer()'s
            // finally, whose `serverThread === currentThread()` identity guard would otherwise
            // read null and refuse to clear the state of a loop that had already died.
            serverThread = thread(start = false, isDaemon = true, name = "MockkHttp-Global-Server") {
                runServer()
            }.also { it.start() }

            logger.info("✅ Global interceptor server listening on port $SERVER_PORT ($scope)")
            true

        } catch (e: Exception) {
            // Remember WHY. "Address already in use" is by far the most common failure — a second
            // IDE already owns 9876 — and without this the UI could only show an empty flow list.
            lastBindError = describeBindFailure(e)
            logger.error("❌ Failed to start global interceptor server: $lastBindError", e)
            isRunning = false
            // Do not leave a half-built server behind: the next ensureStarted() must start clean.
            try {
                serverSocket?.close()
            } catch (_: Exception) {
                // Already unusable; nothing to report.
            }
            serverSocket = null
            connectionPool?.shutdownNow()
            connectionPool = null
            false
        }
    }

    /**
     * True when the listener is actually bound and accepting.
     *
     * Distinct from "a project is registered": a project can be registered against a server that
     * never managed to bind, which is precisely the state [getBindError] explains.
     */
    fun isBound(): Boolean {
        val socket = serverSocket
        return isRunning && socket != null && socket.isBound && !socket.isClosed
    }

    /**
     * Human-readable reason the listener is not up, or null when it is (or was never started).
     * Meant to be shown to the user: "another IDE owns port 9876" beats a dead capture session.
     */
    fun getBindError(): String? = lastBindError

    /** Turn a bind exception into something a user can act on. */
    private fun describeBindFailure(e: Exception): String = when (e) {
        is BindException ->
            "Port $SERVER_PORT is already in use — another IDE (or another process) owns it. " +
                    "Close the other IDE's MockkHttp session, or stop the process holding the port."

        else -> "${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
    }

    /**
     * Stop the global server.
     * Only stops if no projects are registered.
     */
    @Synchronized
    fun stopIfNoProjects() {
        // Also fires when the listener already died on its own: its socket and thread pool are
        // still there to release, and nobody is left who could use them.
        if (registeredProjects.isEmpty() && (isRunning || serverSocket != null || connectionPool != null)) {
            logger.info("🛑 No projects registered, stopping global server...")
            stop()
        }
    }

    /**
     * Force stop the global server regardless of registered projects.
     */
    @Synchronized
    private fun stop() {
        if (!isRunning && serverSocket == null && serverThread == null && connectionPool == null) {
            logger.debug("Global server not running, nothing to stop")
            return
        }

        logger.info("🛑 Stopping global interceptor server...")
        isRunning = false

        // Close the socket FIRST: that is what unblocks accept(), an interrupt alone does not.
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            logger.warn("⚠️ Error closing the server socket", e)
        }

        // Bounded join: teardown runs on the EDT during unregister, and on the unload thread during
        // dispose(). Neither may hang, so a stuck thread gets a warning, not an indefinite wait.
        val acceptThread = serverThread
        if (acceptThread != null && acceptThread !== Thread.currentThread()) {
            try {
                acceptThread.interrupt()
                acceptThread.join(ACCEPT_THREAD_JOIN_MS)
                if (acceptThread.isAlive) {
                    logger.warn("⚠️ Accept thread still alive after ${ACCEPT_THREAD_JOIN_MS}ms")
                }
            } catch (e: InterruptedException) {
                // Never swallow an interrupt of the CALLING thread: restore it and move on.
                Thread.currentThread().interrupt()
                logger.warn("⚠️ Interrupted while waiting for the accept thread", e)
            }
        }

        val pool = connectionPool
        if (pool != null) {
            // shutdownNow(), not shutdown(): a DEBUG connection is parked on the intercept dialog's
            // latch for up to DEBUG_DECISION_TIMEOUT_MS, and waiting that out would freeze the IDE.
            // The interrupt breaks the latch wait; handleClient() catches it and answers the app
            // with the original response, so nothing is left hanging on the device either.
            // (The queue is a SynchronousQueue, so shutdownNow() has no pending tasks to drain.)
            pool.shutdownNow()
            try {
                if (!pool.awaitTermination(POOL_TERMINATION_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    logger.warn("⚠️ ${pool.activeCount} connection thread(s) still finishing after ${POOL_TERMINATION_WAIT_MS}ms")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.warn("⚠️ Interrupted while waiting for connection threads", e)
            }
        }

        serverSocket = null
        serverThread = null
        connectionPool = null
        // A deliberate stop is not a failure: leaving the previous message behind would have the
        // UI report a stale "Address already in use" for a server the user simply switched off.
        lastBindError = null
        logger.info("✅ Global interceptor server stopped")
    }

    /**
     * Full teardown on IDE shutdown and on plugin unload.
     *
     * Deliberately NOT `@Synchronized`: [stop] already takes the instance monitor and releases it
     * before the registry is touched, which keeps the documented one-way lock order (see
     * [updateRegistrations]) — no thread ever holds the instance monitor and
     * [registrationWriteLock] at the same time.
     */
    override fun dispose() {
        logger.info("🛑 Disposing global interceptor server (IDE shutdown or plugin unload)")
        // Set BEFORE stop(): ensureStarted() is public and reachable from background threads
        // (InspectorPanel calls it directly), so without a terminal flag a start racing with
        // teardown would resurrect the listener on the classloader we are trying to release.
        synchronized(this) { disposed = true }
        stop()

        // Registrations hold Project instances and flow handlers, i.e. the whole plugin object
        // graph. Clearing them after the threads are down is what actually lets the old classloader
        // go on a plugin update.
        updateRegistrations { it.clear() }
        registrationListeners.clear()
        knownMockkHttpPackages.clear()
        lastActiveProjectId = null
    }

    /**
     * Apply [mutate] to a copy of the registry and publish it atomically.
     *
     * Returns whatever [mutate] returns, so callers can recover a removed entry.
     * The lock is released before this returns: no caller ever holds it while
     * invoking a flow handler or [ensureStarted], so the lock order stays
     * one-way and cannot deadlock against the `@Synchronized` lifecycle methods.
     */
    private fun <T> updateRegistrations(
        mutate: (LinkedHashMap<String, ProjectRegistration>) -> T
    ): T = synchronized(registrationWriteLock) {
        val copy = LinkedHashMap(registeredProjects)
        val result = mutate(copy)
        registeredProjects = copy
        result
    }

    /**
     * Register a project to receive flows.
     * Returns true if registration successful.
     */
    fun registerProject(
        project: Project,
        mode: InterceptMode,
        flowHandler: FlowHandler,
        packageNameFilter: String? = null
    ): Boolean {
        val projectId = project.locationHash
        val projectName = project.name

        logger.info("📝 Registering project: $projectName (ID: $projectId, Mode: $mode)")

        // Replace an existing registration IN PLACE. Routing through
        // unregisterProject() here — as this used to — calls stopIfNoProjects(),
        // which tears the global server down whenever this is the only open
        // project, just so ensureStarted() can rebuild it a few lines below.
        // Apps that connect in that window get "connection refused", and the
        // re-bind can itself fail while the old socket lingers.
        if (registeredProjects.containsKey(projectId)) {
            logger.warn("⚠️ Project $projectName already registered, replacing registration...")
        }

        val registration = ProjectRegistration(
            projectId = projectId,
            projectName = projectName,
            project = project,
            mode = mode,
            flowHandler = flowHandler,
            packageNameFilter = packageNameFilter
        )

        updateRegistrations {
            // remove-then-put so a replaced project moves back to the end:
            // the no-filter fallback in routeFlow() reads the last entry as
            // "most recently registered".
            it.remove(projectId)
            it[projectId] = registration
        }

        // Mark as last active project
        lastActiveProjectId = projectId

        logger.info("✅ Project registered: $projectName (Total active projects: ${registeredProjects.size})")
        logger.info("   Mode: $mode")
        logger.info("   Project ID: $projectId")
        if (packageNameFilter != null) {
            logger.info("   Package filter: $packageNameFilter")
        } else {
            logger.info("   Package filter: none (will receive all flows)")
        }

        // Ensure server is running. Registering against a server that is not listening would give
        // the caller a capture session that can never receive a flow, so this failure is fatal.
        if (!ensureStarted()) {
            updateRegistrations { it.remove(projectId) }
            logger.error("❌ Failed to start global server, unregistering project: ${getBindError() ?: "unknown reason"}")
            return false
        }

        // Notify listeners
        registrationListeners.forEach { it.onProjectRegistered(registration) }

        return true
    }

    /**
     * Unregister a project.
     */
    fun unregisterProject(project: Project) {
        val projectId = project.locationHash
        val registration = updateRegistrations { it.remove(projectId) }

        if (registration != null) {
            logger.info("📝 Unregistered project: ${registration.projectName} (Remaining: ${registeredProjects.size})")

            // Notify listeners
            registrationListeners.forEach { it.onProjectUnregistered(projectId) }

            // Stop server if no projects left
            stopIfNoProjects()
        } else {
            logger.debug("Project not registered: ${project.name}")
        }
    }

    /**
     * Update the mode of a registered project.
     */
    fun updateProjectMode(project: Project, newMode: InterceptMode) {
        val projectId = project.locationHash
        val registration = registeredProjects[projectId]

        if (registration != null) {
            val updated = registration.copy(mode = newMode)
            updateRegistrations { it[projectId] = updated }
            logger.info("🔄 Updated project mode: ${project.name} -> $newMode")
        } else {
            logger.warn("⚠️ Cannot update mode for unregistered project: ${project.name}")
        }
    }

    /**
     * Update package name filter for a registered project.
     */
    fun updateProjectPackageFilter(project: Project, packageNameFilter: String?) {
        val projectId = project.locationHash
        val registration = registeredProjects[projectId]

        if (registration != null) {
            val updated = registration.copy(packageNameFilter = packageNameFilter)
            updateRegistrations { it[projectId] = updated }
            if (packageNameFilter != null) {
                logger.info("🔄 Updated package filter: ${project.name} -> $packageNameFilter")
            } else {
                logger.info("🔄 Removed package filter: ${project.name} (will receive ALL flows)")
            }
        } else {
            logger.warn("⚠️ Cannot update package filter for unregistered project: ${project.name}")
        }
    }

    /**
     * Get all registered projects.
     */
    fun getRegisteredProjects(): List<ProjectRegistration> {
        return registeredProjects.values.toList()
    }

    /**
     * Add a registration listener.
     */
    fun addRegistrationListener(listener: RegistrationListener) {
        registrationListeners.add(listener)
    }

    /**
     * One accepted connection, kept as a named type so the pool's rejection handler can recover the
     * socket and still answer the app instead of dropping it on the floor.
     */
    private inner class ClientConnectionTask(val socket: Socket) : Runnable {
        override fun run() {
            handleClient(socket)
        }
    }

    /**
     * Bounded pool that owns every connection thread.
     *
     * A [SynchronousQueue] is the point: with no queue capacity a connection is either handed to a
     * thread immediately or rejected, so a burst can never pile up invisibly behind
     * [MAX_CONNECTION_THREADS] blocked DEBUG dialogs. Rejection answers the app with the original
     * response, which is far better than a connection that sits in a queue until the app's 60s read
     * timeout expires.
     */
    private fun createConnectionPool(): ThreadPoolExecutor {
        val threadCounter = AtomicInteger(1)
        val threadFactory = ThreadFactory { runnable ->
            Thread(runnable, "MockkHttp-Connection-${threadCounter.getAndIncrement()}").apply {
                isDaemon = true
            }
        }
        val rejectionHandler = RejectedExecutionHandler { task, executor ->
            val socket = (task as? ClientConnectionTask)?.socket
            if (executor.isShutdown) {
                logger.debug("Connection refused: the server is shutting down")
            } else {
                logger.warn(
                    "⚠️ All $MAX_CONNECTION_THREADS connection slots are busy — this connection gets " +
                            "the original response. Are intercept dialogs left open?"
                )
            }
            if (socket != null) {
                answerOriginalAndClose(socket)
            }
        }

        return ThreadPoolExecutor(
            0,
            MAX_CONNECTION_THREADS,
            CONNECTION_THREAD_KEEPALIVE_SECONDS,
            TimeUnit.SECONDS,
            SynchronousQueue<Runnable>(),
            threadFactory,
            rejectionHandler
        )
    }

    /**
     * Answer "keep the original response" and hang up.
     *
     * Used whenever a connection cannot be served normally. A CHECK_MOCK client reads this as `{}`,
     * whose `hasMock` defaults to false — i.e. "no mock, do the real call" — so the same failsafe is
     * correct for both message types.
     */
    private fun answerOriginalAndClose(socket: Socket) {
        try {
            socket.use {
                PrintWriter(OutputStreamWriter(it.getOutputStream(), Charsets.UTF_8), true)
                    .println(gson.toJson(ModifiedResponseData.original()))
            }
        } catch (_: Exception) {
            // The app is already gone; it falls back to the original response on its own.
        }
    }

    /**
     * Main server loop.
     */
    private fun runServer() {
        logger.info("🔄 Global server loop started")
        try {
            while (isRunning) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    logger.debug("📱 Client connected: ${clientSocket.inetAddress.hostAddress}")

                    val pool = connectionPool
                    if (pool == null) {
                        // Only reachable if stop() raced us. Answer anyway: an unanswered socket
                        // costs the app its full read timeout.
                        answerOriginalAndClose(clientSocket)
                        continue
                    }
                    // Rejection is handled by the pool's handler, so this never throws.
                    pool.execute(ClientConnectionTask(clientSocket))
                } catch (e: SocketException) {
                    if (isRunning) {
                        logger.error("Socket error", e)
                    }
                    // Socket closed, exit loop
                    break
                } catch (e: Exception) {
                    if (isRunning) {
                        logger.error("Error accepting client connection", e)
                    }
                }
            }
        } finally {
            // Reaching here with isRunning still set means accept() died on its own. Record it so
            // isBound() stops claiming a listener that no longer exists and the next ensureStarted()
            // rebinds instead of returning a false success. The identity check matters: a slow dying
            // thread must never clear the flag of the server that has already replaced it.
            if (isRunning && serverThread === Thread.currentThread()) {
                isRunning = false
                lastBindError = "The listener on port $SERVER_PORT stopped unexpectedly. Restart capture to rebind."
                logger.warn("⚠️ $lastBindError")
            }
            logger.debug("Global server loop ended")
        }
    }

    /**
     * Handle a single client connection.
     */
    private fun handleClient(socket: Socket) {
        socket.use { clientSocket ->
            // Tracks whether this connection has been answered, so the failsafe in
            // the catch below cannot append a second line after a normal reply.
            var answered = false
            try {
                // Bound the REQUEST read. No timeout was ever set on accepted sockets, and
                // readLine() on a blocking socket is not interruptible — so a peer that connected
                // and then vanished (a device unplugged mid-request, a killed app) parked its
                // thread forever. Harmless while threads were unbounded; with a fixed pool it
                // leaks slots until capture stops working entirely. The client always writes its
                // line immediately after connecting, so this only ever fires on a dead peer.
                clientSocket.soTimeout = REQUEST_READ_TIMEOUT_MS
                // Pin UTF-8 explicitly on BOTH ends. Both clients encode UTF-8
                // unconditionally (Kotlin's String.toByteArray() default, Dart's
                // utf8.encode), while these two used the JVM's platform charset —
                // which is UTF-8 on modern macOS/Linux but not guaranteed, and is
                // windows-1252 on a default Windows JVM before JEP 400. Any
                // non-ASCII byte in a body or header was silently mangled there.
                val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream(), Charsets.UTF_8))
                val writer = PrintWriter(OutputStreamWriter(clientSocket.getOutputStream(), Charsets.UTF_8), true)

                // Read flow data (one line JSON)
                val json = reader.readLine()

                if (json == null) {
                    logger.debug("Client disconnected without sending data")
                    return
                }

                // Handle PING (supports "PING" and "PING:<packageName>" for app detection)
                if (json == "PING" || json.startsWith("PING:")) {
                    val packageName = if (json.startsWith("PING:")) json.substringAfter("PING:").trim() else null
                    if (!packageName.isNullOrBlank()) {
                        rememberPackage(packageName)
                    } else {
                        logger.debug("📡 PING received, sent PONG")
                    }
                    writer.println("PONG")
                    answered = true
                    return
                }

                // Detect message type by checking for "type" field
                val messageType = try {
                    val jsonObject = com.google.gson.JsonParser.parseString(json).asJsonObject
                    jsonObject.get("type")?.asString
                } catch (e: Exception) {
                    null
                }

                when (messageType) {
                    "CHECK_MOCK" -> {
                        // Handle mock check request
                        val mockCheckRequest = try {
                            gson.fromJson(json, com.sergiy.dev.mockkhttp.model.MockCheckRequest::class.java)
                        } catch (e: Exception) {
                            logger.error("Failed to parse mock check request", e)
                            writer.println(gson.toJson(com.sergiy.dev.mockkhttp.model.MockCheckResponse.noMockUnknown()))
                            answered = true
                            return
                        }

                        rememberPackage(mockCheckRequest.packageName)

                        logger.debug("🔍 CHECK_MOCK: ${mockCheckRequest.request.method} ${mockCheckRequest.request.url}")

                        // Find matching mock rule
                        val mockResponse = findMockForRequest(mockCheckRequest)
                        val responseJson = gson.toJson(mockResponse)
                        writer.println(responseJson)
                        answered = true

                        if (mockResponse.hasMock) {
                            logger.info("⚡ Mock found! Responding with mock: ${mockResponse.mockRuleName}")
                        } else {
                            logger.debug("📝 No mock found, app will make real network call")
                        }
                    }

                    else -> {
                        // Handle normal flow data (FLOW type or no type field)
                        val flowData = try {
                            gson.fromJson(json, AndroidFlowData::class.java)
                        } catch (e: Exception) {
                            logger.error("Failed to parse flow data", e)
                            writer.println(gson.toJson(ModifiedResponseData.original()))
                            answered = true
                            return
                        }

                        // Any message carrying a package name registers the app.
                        // Relying on PING:<pkg> alone meant native Android apps —
                        // which sent a bare PING — never appeared in the known set.
                        rememberPackage(flowData.packageName)

                        logger.info("🔴 INTERCEPTED: ${flowData.request.method} ${flowData.request.url}")
                        logger.info("   📦 Package: ${flowData.packageName ?: "unknown"}")
                        logger.info("   🎯 Project hint: ${flowData.projectId ?: "none"}")

                        // Route flow to appropriate project(s)
                        val response = routeFlow(flowData)

                        val responseJson = gson.toJson(response)
                        writer.println(responseJson)
                        answered = true

                        // PrintWriter swallows IOException, so a reply written to a
                        // socket the app already abandoned looks exactly like success.
                        // checkError() is the only way to notice, and silently losing
                        // an edited response is the worst possible failure mode here.
                        if (writer.checkError()) {
                            logger.warn(
                                "⚠️ Could not deliver the response for ${flowData.request.method} " +
                                        "${flowData.request.url}: the app had already closed the connection. " +
                                        "It waits ${CLIENT_READ_TIMEOUT_MS / 1000}s and then uses the original response."
                            )
                        } else {
                            logger.debug("✅ Response sent back to app")
                        }
                    }
                }

            } catch (e: Exception) {
                // A thrown handler used to return without writing anything, leaving
                // the app blocked on its socket read for the full 60s before it
                // gave up. Always answer: "keep the original response" is the
                // correct failsafe and unblocks the app immediately.
                logger.error("Error handling client", e)
                if (answered) return@use
                try {
                    PrintWriter(OutputStreamWriter(clientSocket.getOutputStream(), Charsets.UTF_8), true)
                        .println(gson.toJson(ModifiedResponseData.original()))
                } catch (_: Exception) {
                    // The socket is already gone — nothing left to rescue.
                }
            }
        }
    }

    /**
     * Route a flow to the appropriate project.
     * Returns the modified response (or original if no modifications).
     */
    private fun routeFlow(flowData: AndroidFlowData): ModifiedResponseData {
        // If no projects registered, return original
        if (registeredProjects.isEmpty()) {
            logger.warn("⚠️ No projects registered, flow will not be captured")
            return ModifiedResponseData.original()
        }

        // Try to find the target project
        val targetProject = findTargetProject(flowData)

        if (targetProject == null) {
            // NO FALLBACK if projects have explicit filters
            // This prevents flows from wrong apps going to projects with filters
            val projectsWithFilters = registeredProjects.values.filter { it.packageNameFilter != null }

            if (projectsWithFilters.isNotEmpty()) {
                // Projects have filters, so flow MUST match one - DON'T fallback
                logger.warn("⚠️ Flow from '${flowData.packageName}' doesn't match any project filter")
                logger.warn("   Projects with filters: ${projectsWithFilters.map { "${it.projectName} (${it.packageNameFilter})" }}")
                logger.warn("   Flow will NOT be captured (strict filtering)")
                return ModifiedResponseData.original()
            }

            // Only use fallback if NO projects have filters (all are catch-all)
            val fallbackProject = lastActiveProjectId?.let { registeredProjects[it] }
                ?: registeredProjects.values.lastOrNull()

            if (fallbackProject != null) {
                logger.info("⚠️ No filters configured, routing to LAST ACTIVE: ${fallbackProject.projectName}")
                return fallbackProject.flowHandler.handleFlow(flowData)
            } else {
                logger.warn("⚠️ No projects available, flow will not be captured")
                return ModifiedResponseData.original()
            }
        }

        logger.info("✅ Routing flow to project: ${targetProject.projectName}")
        return targetProject.flowHandler.handleFlow(flowData)
    }

    /**
     * Find the target project for a flow based on project ID, package name, etc.
     */
    private fun findTargetProject(flowData: AndroidFlowData): ProjectRegistration? {
        // 1. If flow has explicit project ID, use that
        if (flowData.projectId != null) {
            val project = registeredProjects[flowData.projectId]
            if (project != null) {
                logger.info("🎯 Matched by project ID: ${project.projectName}")
                return project
            }
        }

        // 2. STRICT package name filtering - only match projects with explicit filter
        if (flowData.packageName != null) {
            // Find projects with explicit package filter that matches
            val matchingProjects = registeredProjects.values.filter {
                it.packageNameFilter != null && it.packageNameFilter == flowData.packageName
            }

            if (matchingProjects.isNotEmpty()) {
                val target = matchingProjects.first()
                logger.info("🎯 Matched by package filter: ${target.projectName} (filter: ${target.packageNameFilter})")
                if (matchingProjects.size > 1) {
                    logger.warn("⚠️ Multiple projects match package ${flowData.packageName}, using first")
                }
                return target
            }
        }

        // 3. If only one project registered AND it has NO filter (catch-all), use it
        if (registeredProjects.size == 1) {
            val project = registeredProjects.values.first()
            if (project.packageNameFilter == null) {
                logger.info("🎯 Using sole registered project (no filter): ${project.projectName}")
                return project
            } else {
                // Project has filter but flow doesn't match - DON'T use it
                logger.info("⚠️ Flow package '${flowData.packageName}' doesn't match project filter '${project.packageNameFilter}'")
                return null
            }
        }

        // 4. Look for catch-all projects (no filter) if multiple projects
        val catchAllProjects = registeredProjects.values.filter { it.packageNameFilter == null }
        if (catchAllProjects.isNotEmpty()) {
            val target = catchAllProjects.first()
            logger.info("🎯 Using catch-all project (no filter): ${target.projectName}")
            if (catchAllProjects.size > 1) {
                logger.warn("⚠️ Multiple catch-all projects, using first")
            }
            return target
        }

        // 5. No match found
        logger.info("⚠️ Could not find matching project for package '${flowData.packageName}'")
        logger.info("   Available projects with filters: ${registeredProjects.values.map { "${it.projectName} (${it.packageNameFilter ?: "no filter"})" }}")
        return null
    }

    /**
     * Find mock for a CHECK_MOCK request.
     * Returns MockCheckResponse with mock data if available.
     */
    private fun findMockForRequest(mockCheckRequest: com.sergiy.dev.mockkhttp.model.MockCheckRequest): com.sergiy.dev.mockkhttp.model.MockCheckResponse {
        // Find the target project (same logic as findTargetProject, but using MockCheckRequest)
        val targetProject = findTargetProjectForMockCheck(mockCheckRequest)

        if (targetProject == null) {
            logger.debug("No target project found for mock check")
            return com.sergiy.dev.mockkhttp.model.MockCheckResponse.noMockUnknown()
        }

        val currentMode = when (targetProject.mode) {
            InterceptMode.RECORDING -> "RECORDING"
            InterceptMode.DEBUG -> "DEBUG"
            InterceptMode.MOCKK -> "MOCKK"
            InterceptMode.MOCKK_DEBUG -> "MOCKK_DEBUG"
        }

        // Only do mock lookup if in MOCKK or MOCKK_DEBUG mode
        if (targetProject.mode != InterceptMode.MOCKK && targetProject.mode != InterceptMode.MOCKK_DEBUG) {
            logger.debug("Project ${targetProject.projectName} is in ${targetProject.mode} mode - no mock lookup needed")
            return com.sergiy.dev.mockkhttp.model.MockCheckResponse.noMock(currentMode)
        }

        // Get MockkRulesStore from the project
        val mockkRulesStore = try {
            com.sergiy.dev.mockkhttp.store.MockkRulesStore.getInstance(targetProject.project)
        } catch (e: Exception) {
            logger.error("Failed to get MockkRulesStore", e)
            return com.sergiy.dev.mockkhttp.model.MockCheckResponse.noMock(currentMode)
        }

        // Parse URL to extract host, path, query params
        val (host, path, queryParams) = try {
            val url = java.net.URI.create(mockCheckRequest.request.url).toURL()
            val host = url.host
            val path = url.path.ifEmpty { "/" }
            val queryParams = url.query?.split("&")?.associate { param ->
                val (key, value) = param.split("=", limit = 2).let {
                    it[0] to (it.getOrNull(1) ?: "")
                }
                key to value
            } ?: emptyMap()
            Triple(host, path, queryParams)
        } catch (e: Exception) {
            logger.error("Failed to parse URL for mock check: ${mockCheckRequest.request.url}", e)
            return com.sergiy.dev.mockkhttp.model.MockCheckResponse.noMock(currentMode)
        }

        // Find matching mock rule
        val matchingRule = mockkRulesStore.findMatchingRuleObject(
            method = mockCheckRequest.request.method,
            host = host,
            path = path,
            queryParams = queryParams
        )

        if (matchingRule != null) {
            logger.debug("Found matching mock rule: ${matchingRule.name}")
            return com.sergiy.dev.mockkhttp.model.MockCheckResponse(
                hasMock = true,
                mode = currentMode,
                statusCode = matchingRule.statusCode,
                headers = matchingRule.headers,
                body = matchingRule.content,
                mockRuleName = matchingRule.name
            )
        }

        logger.debug("No matching mock rule found")
        return com.sergiy.dev.mockkhttp.model.MockCheckResponse.noMock(currentMode)
    }

    /**
     * Find target project for mock check request (similar to findTargetProject but for MockCheckRequest).
     */
    private fun findTargetProjectForMockCheck(mockCheckRequest: com.sergiy.dev.mockkhttp.model.MockCheckRequest): ProjectRegistration? {
        // 1. If request has explicit project ID, use that
        if (mockCheckRequest.projectId != null) {
            val project = registeredProjects[mockCheckRequest.projectId]
            if (project != null) {
                logger.debug("Matched by project ID: ${project.projectName}")
                return project
            }
        }

        // 2. STRICT package name filtering
        if (mockCheckRequest.packageName != null) {
            val matchingProjects = registeredProjects.values.filter {
                it.packageNameFilter != null && it.packageNameFilter == mockCheckRequest.packageName
            }

            if (matchingProjects.isNotEmpty()) {
                return matchingProjects.first()
            }
        }

        // 3. If only one project registered AND it has NO filter (catch-all), use it
        if (registeredProjects.size == 1) {
            val project = registeredProjects.values.first()
            if (project.packageNameFilter == null) {
                return project
            }
        }

        // 4. Look for catch-all projects (no filter)
        val catchAllProjects = registeredProjects.values.filter { it.packageNameFilter == null }
        if (catchAllProjects.isNotEmpty()) {
            return catchAllProjects.first()
        }

        return null
    }
}
