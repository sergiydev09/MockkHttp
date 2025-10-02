package com.sergiy.dev.mockkhttp.proxy

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Controller for managing mitmproxy process lifecycle.
 * Handles starting, stopping, and monitoring mitmproxy with custom Python addons.
 */
@Service(Service.Level.PROJECT)
class MitmproxyController(project: Project) {

    private val logger = MockkHttpLogger.getInstance(project)

    private var mitmproxyProcess: Process? = null
    private var outputReaderThread: Thread? = null
    private var errorReaderThread: Thread? = null

    @Volatile
    private var isRunning = false

    companion object {
        fun getInstance(project: Project): MitmproxyController {
            return project.getService(MitmproxyController::class.java)
        }

        // Default ports
        const val DEFAULT_PROXY_PORT = 8080
        const val DEFAULT_CONTROL_PORT = 9999
        const val DEFAULT_PLUGIN_SERVER_PORT = 8765

        // Mitmproxy executable names
        private val MITM_EXECUTABLES = listOf("mitmdump", "mitmproxy", "mitmweb")
    }

    /**
     * Mitmproxy operation mode.
     */
    enum class Mode {
        RECORDING,
        DEBUG,
        MOCKK
    }

    /**
     * Configuration for starting mitmproxy.
     */
    data class MitmproxyConfig(
        val mode: Mode,
        val proxyPort: Int = DEFAULT_PROXY_PORT,
        val controlPort: Int = DEFAULT_CONTROL_PORT,
        val pluginServerPort: Int = DEFAULT_PLUGIN_SERVER_PORT,
        val addonScriptPath: String,
        val listenHost: String = "0.0.0.0"
    )

    /**
     * Start mitmproxy with specified configuration.
     */
    fun start(config: MitmproxyConfig): Boolean {
        logger.info("🚀 Starting mitmproxy in ${config.mode} mode...")

        if (isRunning) {
            logger.warn("⚠️ Mitmproxy is already running")
            return false
        }

        // Check if ports are available and cleanup old processes if needed
        if (!ensurePortsAvailable(config.proxyPort, config.controlPort)) {
            logger.error("❌ Required ports are not available")
            return false
        }

        // Find mitmproxy executable
        val mitmproxyPath = findMitmproxyExecutable()
        if (mitmproxyPath == null) {
            logger.error("❌ mitmproxy not found in PATH. Please install mitmproxy: https://mitmproxy.org")
            return false
        }

        logger.debug("Found mitmproxy at: $mitmproxyPath")

        // Verify addon script exists
        val addonScript = File(config.addonScriptPath)
        if (!addonScript.exists()) {
            logger.error("❌ Addon script not found: ${config.addonScriptPath}")
            return false
        }

        logger.debug("Addon script found: ${addonScript.absolutePath}")

        // Build command
        val command = buildMitmproxyCommand(mitmproxyPath, config)
        logger.debug("Command: ${command.joinToString(" ")}")

        try {
            // Start process
            val processBuilder = ProcessBuilder(command)
            processBuilder.redirectErrorStream(false)

            mitmproxyProcess = processBuilder.start()
            isRunning = true

            logger.info("✅ Mitmproxy process started (PID: ${mitmproxyProcess?.pid()})")

            // Start output readers
            startOutputReaders()

            // Wait a bit to check if process started successfully
            Thread.sleep(2000)

            if (!mitmproxyProcess!!.isAlive) {
                logger.error("❌ Mitmproxy process died immediately after start")
                isRunning = false
                return false
            }

            logger.info("✅ Mitmproxy is running successfully")
            logger.info("   Proxy listening on: ${config.listenHost}:${config.proxyPort}")
            logger.info("   Control API on: localhost:${config.controlPort}")
            logger.info("   Mode: ${config.mode}")

            return true

        } catch (e: Exception) {
            logger.error("❌ Failed to start mitmproxy", e)
            isRunning = false
            mitmproxyProcess = null
            return false
        }
    }

    /**
     * Stop mitmproxy process.
     */
    fun stop(): Boolean {
        logger.info("🛑 Stopping mitmproxy...")

        if (!isRunning || mitmproxyProcess == null) {
            logger.warn("⚠️ Mitmproxy is not running")
            return false
        }

        try {
            // Try graceful shutdown first
            mitmproxyProcess?.destroy()

            // Wait for termination
            val terminated = mitmproxyProcess?.waitFor(5, TimeUnit.SECONDS) ?: false

            if (!terminated) {
                logger.warn("⚠️ Mitmproxy did not terminate gracefully, forcing...")
                mitmproxyProcess?.destroyForcibly()
                mitmproxyProcess?.waitFor(5, TimeUnit.SECONDS)
            }

            // Stop reader threads
            stopOutputReaders()

            isRunning = false
            mitmproxyProcess = null

            logger.info("✅ Mitmproxy stopped successfully")
            return true

        } catch (e: Exception) {
            logger.error("❌ Failed to stop mitmproxy", e)
            return false
        }
    }

    /**
     * Find mitmproxy executable in common locations and PATH.
     */
    private fun findMitmproxyExecutable(): String? {
        logger.debug("Searching for mitmproxy executable...")

        // Common Homebrew locations
        val commonPaths = listOf(
            "/opt/homebrew/bin",  // Apple Silicon Homebrew
            "/usr/local/bin"       // Intel Homebrew
        )

        // Try common locations first
        for (executable in MITM_EXECUTABLES) {
            for (basePath in commonPaths) {
                val execPath = File(basePath, executable)
                if (execPath.exists() && execPath.canExecute()) {
                    logger.debug("Found executable: ${execPath.absolutePath}")
                    return execPath.absolutePath
                }
            }
        }

        // Try PATH with Homebrew paths added
        val pathEnv = System.getenv("PATH") ?: ""
        val enhancedPath = "/opt/homebrew/bin:/usr/local/bin:$pathEnv"
        val paths = enhancedPath.split(File.pathSeparator)

        for (executable in MITM_EXECUTABLES) {
            for (pathDir in paths) {
                val execPath = File(pathDir, executable)
                if (execPath.exists() && execPath.canExecute()) {
                    logger.debug("Found executable: ${execPath.absolutePath}")
                    return execPath.absolutePath
                }
            }
        }

        // Last resort: try 'which' command with enhanced PATH
        for (executable in MITM_EXECUTABLES) {
            try {
                val env = mutableMapOf<String, String>()
                env["PATH"] = enhancedPath

                val process = ProcessBuilder("which", executable)
                    .apply { environment().putAll(env) }
                    .start()

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val path = reader.readLine()?.trim()
                if (!path.isNullOrEmpty() && File(path).exists()) {
                    logger.debug("Found via 'which': $path")
                    return path
                }
            } catch (_: Exception) {
                // Ignore and continue
            }
        }

        logger.warn("Mitmproxy executable not found")
        return null
    }

    /**
     * Build mitmproxy command with all parameters.
     */
    private fun buildMitmproxyCommand(
        executablePath: String,
        config: MitmproxyConfig
    ): List<String> {
        return listOf(
            executablePath,
            "-s", config.addonScriptPath,
            "--set", "intercept_mode=${config.mode.name.lowercase()}",
            "--set", "plugin_port=${config.controlPort}",
            "--set", "plugin_client_port=${config.pluginServerPort}",
            "--listen-host", config.listenHost,
            "--listen-port", config.proxyPort.toString(),
            "--ssl-insecure" // Allow insecure SSL connections
        )
    }

    /**
     * Start threads to read process output and error streams.
     */
    private fun startOutputReaders() {
        val process = mitmproxyProcess ?: return

        // Output reader
        outputReaderThread = Thread {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null && isRunning) {
                        // Show important logs as INFO, others as DEBUG
                        if (line?.contains("🚀") == true || line?.contains("📤") == true ||
                            line?.contains("🔴") == true || line?.contains("⏸️") == true ||
                            line?.contains("▶️") == true || line?.contains("✅") == true ||
                            line?.contains("❌") == true || line?.contains("⚠️") == true ||
                            line?.contains("📋") == true) {
                            logger.info("[mitmproxy] $line")
                        } else {
                            logger.debug("[mitmproxy] $line")
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    logger.error("Error reading mitmproxy output", e)
                }
            }
        }.apply {
            name = "mitmproxy-output-reader"
            isDaemon = true
            start()
        }

        // Error reader
        errorReaderThread = Thread {
            try {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null && isRunning) {
                        logger.warn("[mitmproxy] $line")
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    logger.error("Error reading mitmproxy errors", e)
                }
            }
        }.apply {
            name = "mitmproxy-error-reader"
            isDaemon = true
            start()
        }
    }

    /**
     * Stop output reader threads.
     */
    private fun stopOutputReaders() {
        outputReaderThread?.interrupt()
        errorReaderThread?.interrupt()
        outputReaderThread = null
        errorReaderThread = null
    }

    /**
     * Ensure required ports are available before starting mitmproxy.
     * If ports are occupied by old mitmproxy processes, kill them.
     */
    private fun ensurePortsAvailable(proxyPort: Int, controlPort: Int): Boolean {
        logger.debug("Checking if ports $proxyPort and $controlPort are available...")

        // Check proxy port
        val proxyPortProcess = getProcessUsingPort(proxyPort)
        if (proxyPortProcess != null) {
            logger.warn("⚠️ Port $proxyPort is already in use by process: $proxyPortProcess")

            if (proxyPortProcess.contains("mitm", ignoreCase = true)) {
                logger.info("Attempting to kill old mitmproxy process...")
                if (killProcessUsingPort(proxyPort)) {
                    logger.info("✅ Old mitmproxy process killed")
                    // Wait a bit for port to be released
                    Thread.sleep(500)
                } else {
                    logger.error("❌ Failed to kill old mitmproxy process on port $proxyPort")
                    return false
                }
            } else {
                logger.error("❌ Port $proxyPort is occupied by another application: $proxyPortProcess")
                return false
            }
        }

        // Check control port
        val controlPortProcess = getProcessUsingPort(controlPort)
        if (controlPortProcess != null) {
            logger.warn("⚠️ Port $controlPort is already in use by process: $controlPortProcess")

            // Control port should only be used by mitmproxy addon, safe to kill
            if (killProcessUsingPort(controlPort)) {
                logger.info("✅ Old process on port $controlPort killed")
                Thread.sleep(500)
            } else {
                logger.warn("⚠️ Could not kill process on port $controlPort, continuing anyway...")
            }
        }

        logger.debug("✅ Ports are available")
        return true
    }

    /**
     * Get the name of the process using a specific port.
     */
    private fun getProcessUsingPort(port: Int): String? {
        try {
            val process = ProcessBuilder("lsof", "-i", ":$port", "-sTCP:LISTEN")
                .redirectErrorStream(true)
                .start()

            val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readLines()
            }

            process.waitFor()

            // Output format: COMMAND    PID   USER   FD   TYPE...
            // We want the COMMAND name from the second line
            if (output.size > 1) {
                val parts = output[1].trim().split(Regex("\\s+"))
                if (parts.isNotEmpty()) {
                    return parts[0] // Return command name
                }
            }

            return null
        } catch (e: Exception) {
            logger.debug("Error checking port $port: ${e.message}")
            return null
        }
    }

    /**
     * Kill the process using a specific port.
     */
    private fun killProcessUsingPort(port: Int): Boolean {
        try {
            // Get PID of process using port
            val lsofProcess = ProcessBuilder("lsof", "-ti", ":$port", "-sTCP:LISTEN")
                .redirectErrorStream(true)
                .start()

            val pid = BufferedReader(InputStreamReader(lsofProcess.inputStream)).use { reader ->
                reader.readLine()?.trim()
            }

            lsofProcess.waitFor()

            if (pid.isNullOrEmpty()) {
                logger.debug("No process found on port $port")
                return false
            }

            logger.debug("Killing process with PID: $pid")

            // Kill the process
            val killProcess = ProcessBuilder("kill", pid)
                .redirectErrorStream(true)
                .start()

            val exitCode = killProcess.waitFor()

            if (exitCode == 0) {
                logger.debug("Successfully killed process $pid")
                return true
            } else {
                logger.debug("Failed to kill process $pid (exit code: $exitCode)")
                return false
            }

        } catch (e: Exception) {
            logger.debug("Error killing process on port $port: ${e.message}")
            return false
        }
    }
}