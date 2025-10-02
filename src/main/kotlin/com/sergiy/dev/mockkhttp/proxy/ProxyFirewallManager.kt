package com.sergiy.dev.mockkhttp.proxy

import com.android.ddmlib.IDevice
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.sergiy.dev.mockkhttp.adb.EmulatorManager
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import java.util.concurrent.TimeUnit

/**
 * Manager for configuring iptables firewall rules on Android emulators.
 * Enables transparent proxy redirection for specific apps by UID.
 */
@Service(Service.Level.PROJECT)
class ProxyFirewallManager(project: Project) {

    private val logger = MockkHttpLogger.getInstance(project)
    private val emulatorManager = EmulatorManager.getInstance(project)

    companion object {
        fun getInstance(project: Project): ProxyFirewallManager {
            return project.getService(ProxyFirewallManager::class.java)
        }

        private const val SHELL_TIMEOUT_SECONDS = 10L
        private const val PROXY_HOST = "10.0.2.2"  // Host machine from emulator perspective
        private const val PROXY_PORT = 8080
    }

    /**
     * Configure iptables to redirect ONLY the specified app's traffic to mitmproxy.
     * Uses NAT (DNAT) to transparently redirect HTTP/HTTPS traffic.
     */
    fun setupAppFirewall(serialNumber: String, appUid: Int): Boolean {
        logger.info("🔥 Setting up firewall for app UID $appUid on $serialNumber...")

        try {
            val device = emulatorManager.getDevice(serialNumber)
            if (device == null) {
                logger.error("❌ Device not found: $serialNumber")
                return false
            }

            // Check root access first
            if (!emulatorManager.hasRootAccess(serialNumber)) {
                logger.error("❌ Root access required for firewall configuration")
                return false
            }

            // Clear any existing rules first (in case of previous failed attempt)
            clearAppFirewall(serialNumber, appUid)

            // Redirect HTTP traffic (port 80) to proxy
            val httpRule = "iptables -t nat -A OUTPUT -m owner --uid-owner $appUid -p tcp --dport 80 -j DNAT --to $PROXY_HOST:$PROXY_PORT"
            if (!executeRootCommand(device, httpRule, "HTTP redirect")) {
                return false
            }

            // Redirect HTTPS traffic (port 443) to proxy
            val httpsRule = "iptables -t nat -A OUTPUT -m owner --uid-owner $appUid -p tcp --dport 443 -j DNAT --to $PROXY_HOST:$PROXY_PORT"
            if (!executeRootCommand(device, httpsRule, "HTTPS redirect")) {
                // Rollback HTTP rule if HTTPS fails
                clearAppFirewall(serialNumber, appUid)
                return false
            }

            logger.info("✅ Firewall configured successfully")
            logger.info("   Only UID $appUid traffic will be redirected to proxy")
            logger.info("   All other apps will use internet normally")

            return true

        } catch (e: Exception) {
            logger.error("❌ Failed to setup firewall", e)
            return false
        }
    }

    /**
     * Remove iptables rules for the specified app.
     * Should be called when stopping recording/debug mode.
     */
    fun clearAppFirewall(serialNumber: String, appUid: Int): Boolean {
        logger.info("🧹 Clearing firewall rules for UID $appUid on $serialNumber...")

        try {
            val device = emulatorManager.getDevice(serialNumber)
            if (device == null) {
                logger.warn("⚠️ Device not found: $serialNumber (cannot clear rules)")
                return false
            }

            // Try to delete HTTP rule (don't fail if it doesn't exist)
            val httpRule = "iptables -t nat -D OUTPUT -m owner --uid-owner $appUid -p tcp --dport 80 -j DNAT --to $PROXY_HOST:$PROXY_PORT"
            executeRootCommand(device, httpRule, "Remove HTTP redirect", ignoreErrors = true)

            // Try to delete HTTPS rule (don't fail if it doesn't exist)
            val httpsRule = "iptables -t nat -D OUTPUT -m owner --uid-owner $appUid -p tcp --dport 443 -j DNAT --to $PROXY_HOST:$PROXY_PORT"
            executeRootCommand(device, httpsRule, "Remove HTTPS redirect", ignoreErrors = true)

            logger.info("✅ Firewall rules cleared")
            return true

        } catch (e: Exception) {
            logger.error("❌ Failed to clear firewall rules", e)
            return false
        }
    }

    /**
     * Execute a root command and check for errors.
     * Automatically detects if su is needed or if shell is already root.
     */
    private fun executeRootCommand(
        device: IDevice,
        command: String,
        description: String,
        ignoreErrors: Boolean = false
    ): Boolean {
        try {
            val receiver = EmulatorManager.CollectingOutputReceiver()

            // Detect root type
            val rootType = emulatorManager.getRootType(device.serialNumber)

            val actualCommand = when (rootType) {
                EmulatorManager.RootType.DIRECT_SHELL -> {
                    // Shell is already root, execute directly
                    logger.debug("Executing (direct): $command")
                    command
                }
                EmulatorManager.RootType.VIA_SU -> {
                    // Need to use su
                    val wrappedCommand = "su -c '$command'"
                    logger.debug("Executing (via su): $wrappedCommand")
                    wrappedCommand
                }
                EmulatorManager.RootType.NONE -> {
                    logger.error("❌ No root access available")
                    return false
                }
            }

            device.executeShellCommand(actualCommand, receiver, SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            val output = receiver.output

            // Check for common error patterns
            if (!ignoreErrors && output.isNotEmpty()) {
                if (output.contains("iptables: No chain/target/match", ignoreCase = true) ||
                    output.contains("iptables: Bad rule", ignoreCase = true) ||
                    output.contains("iptables: Permission denied", ignoreCase = true) ||
                    output.contains("su: not found", ignoreCase = true)
                ) {
                    logger.error("❌ $description failed: $output")
                    return false
                }
            }

            if (output.isNotEmpty() && !ignoreErrors) {
                logger.debug("$description output: $output")
            }

            logger.debug("✅ $description completed")
            return true

        } catch (e: Exception) {
            if (!ignoreErrors) {
                logger.error("❌ $description failed", e)
            }
            return false
        }
    }
}
