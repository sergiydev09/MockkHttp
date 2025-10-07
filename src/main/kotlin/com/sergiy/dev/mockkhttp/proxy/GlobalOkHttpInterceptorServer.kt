package com.sergiy.dev.mockkhttp.proxy

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Global application-level server that listens for connections from MockkHttpInterceptor in Android apps.
 * This singleton server handles connections from ALL projects and routes flows to the correct project.
 *
 * This solves the problem of multiple projects trying to bind to the same port.
 */
@Service(Service.Level.APP)
class GlobalOkHttpInterceptorServer {

    private val logger = Logger.getInstance(GlobalOkHttpInterceptorServer::class.java)
    private val gson = Gson()

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var serverThread: Thread? = null

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
            logger.info("Global interceptor server already running on port $SERVER_PORT")
            return true
        }

        logger.info("🚀 Starting Global OkHttp Interceptor Server on port $SERVER_PORT")

        return try {
            serverSocket = ServerSocket(SERVER_PORT)
            isRunning = true

            serverThread = thread(isDaemon = true, name = "MockkHttp-Global-Server") {
                runServer()
            }

            logger.info("✅ Global interceptor server listening on port $SERVER_PORT")
            true

        } catch (e: Exception) {
            logger.error("❌ Failed to start global interceptor server", e)
            isRunning = false
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
            logger.info("🛑 No projects registered, stopping global server...")
            stop()
        }
    }

    /**
     * Force stop the global server regardless of registered projects.
     */
    @Synchronized
    private fun stop() {
        if (!isRunning) {
            logger.debug("Global server not running, nothing to stop")
            return
        }

        logger.info("🛑 Stopping global interceptor server...")
        isRunning = false

        try {
            serverSocket?.close()
            serverThread?.interrupt()
            serverThread?.join(2000)
        } catch (e: Exception) {
            logger.error("Error stopping global server", e)
        }

        serverSocket = null
        serverThread = null
        logger.info("✅ Global interceptor server stopped")
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

        // Check if project already registered
        if (registeredProjects.containsKey(projectId)) {
            logger.warn("⚠️ Project $projectName already registered, updating registration...")
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

        logger.info("✅ Project registered: $projectName (Total active projects: ${registeredProjects.size})")
        logger.info("   Mode: $mode")
        logger.info("   Project ID: $projectId")
        if (packageNameFilter != null) {
            logger.info("   Package filter: $packageNameFilter")
        } else {
            logger.info("   Package filter: none (will receive all flows)")
        }

        // Ensure server is running
        if (!ensureStarted()) {
            registeredProjects.remove(projectId)
            logger.error("❌ Failed to start global server, unregistering project")
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
            registeredProjects[projectId] = updated
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
            registeredProjects[projectId] = updated
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
     * Main server loop.
     */
    private fun runServer() {
        logger.info("🔄 Global server loop started")
        try {
            while (isRunning) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    logger.debug("📱 Client connected: ${clientSocket.inetAddress.hostAddress}")

                    // Handle each client in separate thread
                    thread(isDaemon = true) {
                        handleClient(clientSocket)
                    }
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
            logger.debug("Global server loop ended")
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
                    logger.debug("Client disconnected without sending data")
                    return
                }

                // Handle PING
                if (json == "PING") {
                    writer.println("PONG")
                    logger.debug("📡 PING received, sent PONG")
                    return
                }

                // Parse flow data
                val flowData = try {
                    gson.fromJson(json, AndroidFlowData::class.java)
                } catch (e: Exception) {
                    logger.error("Failed to parse flow data", e)
                    writer.println(gson.toJson(ModifiedResponseData.original()))
                    return
                }

                logger.info("🔴 INTERCEPTED: ${flowData.request.method} ${flowData.request.url}")
                logger.info("   📦 Package: ${flowData.packageName ?: "unknown"}")
                logger.info("   🎯 Project hint: ${flowData.projectId ?: "none"}")

                // Route flow to appropriate project(s)
                val response = routeFlow(flowData)

                val responseJson = gson.toJson(response)
                writer.println(responseJson)
                logger.debug("✅ Response sent back to app")

            } catch (e: Exception) {
                logger.error("Error handling client", e)
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
}
