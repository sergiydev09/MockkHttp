package com.sergiy.dev.mockkhttp.proxy

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.BufferedReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Global application-level server that listens for connections from MockkHttpInterceptor in Android apps.
 * This singleton server handles connections from ALL projects and routes flows to the correct project.
 *
 * This solves the problem of multiple projects trying to bind to the same port.
 */
@Service(Service.Level.APP)
class GlobalOkHttpInterceptorServer {

    private val gson = Gson()

    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    // Coroutines infrastructure for high-throughput request handling
    private var serverScope: CoroutineScope? = null
    private var requestChannel: Channel<Socket>? = null

    // Worker configuration
    private val numWorkers = 50  // 50 coroutines can handle 1000s of requests with minimal memory

    // Registry of projects listening for flows (LinkedHashMap maintains insertion order)
    private val registeredProjects = java.util.Collections.synchronizedMap(
        LinkedHashMap<String, ProjectRegistration>()
    )
    private val registrationListeners = CopyOnWriteArrayList<RegistrationListener>()

    // Track most recently active project (for smarter routing)
    @Volatile
    private var lastActiveProjectId: String? = null

    companion object {
        const val SERVER_PORT = 9876

        fun getInstance(): GlobalOkHttpInterceptorServer {
            return ApplicationManager.getApplication().getService(GlobalOkHttpInterceptorServer::class.java)
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
        if (isRunning) {
            return true
        }


        return try {
            serverSocket = ServerSocket(SERVER_PORT)
            isRunning = true

            // Create coroutine scope with SupervisorJob (failures don't cascade)
            serverScope = CoroutineScope(
                SupervisorJob() + Dispatchers.IO + CoroutineName("MockkHttp-GlobalServer")
            )

            // Create channel with capacity limit (backpressure)
            // If channel is full, accept() will suspend, naturally slowing down incoming connections
            requestChannel = Channel<Socket>(capacity = 500)

            // Start acceptor coroutine (accepts new connections)
            serverScope?.launch {
                runAcceptor()
            }

            // Start worker coroutines (process requests from channel)
            repeat(numWorkers) { workerId ->
                serverScope?.launch {
                    processRequests(workerId)
                }
            }

            true

        } catch (e: Exception) {
            isRunning = false
            serverScope?.cancel()
            serverScope = null
            requestChannel?.close()
            requestChannel = null
            false
        }
    }

    /**
     * Stop the global server.
     * Only stops if no projects are registered.
     */
    @Synchronized
    fun stopIfNoProjects() {
        if (registeredProjects.isEmpty() && isRunning) {
            stop()
        }
    }

    /**
     * Force stop the global server regardless of registered projects.
     */
    @Synchronized
    private fun stop() {
        if (!isRunning) {
            return
        }

        isRunning = false

        try {
            // Cancel all coroutines gracefully
            serverScope?.cancel()

            // Close channel (will cause workers to exit)
            requestChannel?.close()

            // Close server socket (will cause acceptor to exit)
            serverSocket?.close()

            // Wait a bit for graceful shutdown
            runBlocking {
                delay(1000)
            }
        } catch (e: Exception) {
        }

        serverScope = null
        requestChannel = null
        serverSocket = null
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


        // Check if project already registered
        if (registeredProjects.containsKey(projectId)) {
            unregisterProject(project)
        }

        val registration = ProjectRegistration(
            projectId = projectId,
            projectName = projectName,
            project = project,
            mode = mode,
            flowHandler = flowHandler,
            packageNameFilter = packageNameFilter
        )

        registeredProjects[projectId] = registration

        // Mark as last active project
        lastActiveProjectId = projectId

        if (packageNameFilter != null) {
        } else {
        }

        // Ensure server is running
        if (!ensureStarted()) {
            registeredProjects.remove(projectId)
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
        val registration = registeredProjects.remove(projectId)

        if (registration != null) {

            // Notify listeners
            registrationListeners.forEach { it.onProjectUnregistered(projectId) }

            // Stop server if no projects left
            stopIfNoProjects()
        } else {
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
            registeredProjects[projectId] = updated
        } else {
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
            registeredProjects[projectId] = updated
            if (packageNameFilter != null) {
            } else {
            }
        } else {
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
     * Acceptor coroutine: accepts incoming connections and puts them in the channel.
     * Runs on Dispatchers.IO (blocking I/O is OK here).
     */
    private suspend fun runAcceptor() {
        try {
            while (isRunning) {
                try {
                    // Accept connection (blocking call, but we're on Dispatchers.IO)
                    val clientSocket = withContext(Dispatchers.IO) {
                        serverSocket?.accept()
                    } ?: break


                    // Send socket to channel (suspends if channel is full = backpressure)
                    requestChannel?.send(clientSocket)

                } catch (e: SocketException) {
                    if (isRunning) {
                    }
                    // Socket closed, exit loop
                    break
                } catch (e: Exception) {
                    if (isRunning) {
                    }
                }
            }
        } finally {
        }
    }

    /**
     * Worker coroutine: processes sockets from the channel.
     * Each worker runs independently and can handle requests concurrently.
     */
    private suspend fun processRequests(workerId: Int) {
        try {
            // Keep consuming from channel until it's closed
            for (clientSocket in requestChannel!!) {
                try {
                    handleClient(clientSocket)
                } catch (e: Exception) {
                }
            }
        } finally {
        }
    }

    /**
     * Handle a single client connection.
     */
    private fun handleClient(socket: Socket) {
        socket.use { clientSocket ->
            try {
                val reader = BufferedReader(clientSocket.getInputStream().reader())
                val writer = PrintWriter(clientSocket.getOutputStream(), true)

                // Read flow data (one line JSON)
                val json = reader.readLine()

                if (json == null) {
                    return
                }

                // Handle PING
                if (json == "PING") {
                    writer.println("PONG")
                    return
                }

                // Parse flow data
                val flowData = try {
                    gson.fromJson(json, AndroidFlowData::class.java)
                } catch (e: Exception) {
                    writer.println(gson.toJson(ModifiedResponseData.original()))
                    return
                }


                // Route flow to appropriate project(s)
                val response = routeFlow(flowData)

                val responseJson = gson.toJson(response)
                writer.println(responseJson)

            } catch (e: Exception) {
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
                return ModifiedResponseData.original()
            }

            // Only use fallback if NO projects have filters (all are catch-all)
            val fallbackProject = lastActiveProjectId?.let { registeredProjects[it] }
                ?: registeredProjects.values.lastOrNull()

            if (fallbackProject != null) {
                return fallbackProject.flowHandler.handleFlow(flowData)
            } else {
                return ModifiedResponseData.original()
            }
        }

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
                if (matchingProjects.size > 1) {
                }
                return target
            }
        }

        // 3. If only one project registered AND it has NO filter (catch-all), use it
        if (registeredProjects.size == 1) {
            val project = registeredProjects.values.first()
            if (project.packageNameFilter == null) {
                return project
            } else {
                // Project has filter but flow doesn't match - DON'T use it
                return null
            }
        }

        // 4. Look for catch-all projects (no filter) if multiple projects
        val catchAllProjects = registeredProjects.values.filter { it.packageNameFilter == null }
        if (catchAllProjects.isNotEmpty()) {
            val target = catchAllProjects.first()
            if (catchAllProjects.size > 1) {
            }
            return target
        }

        // 5. No match found
        return null
    }
}
