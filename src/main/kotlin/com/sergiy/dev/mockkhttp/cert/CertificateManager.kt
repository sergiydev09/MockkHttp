package com.sergiy.dev.mockkhttp.cert

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import java.io.File

/**
 * Manager for handling SSL/TLS certificates for MITM interception.
 * Generates and installs mitmproxy certificates on Android emulators.
 */
@Service(Service.Level.PROJECT)
class CertificateManager(project: Project) {

    private val logger = MockkHttpLogger.getInstance(project)
    
    companion object {
        fun getInstance(project: Project): CertificateManager {
            return project.getService(CertificateManager::class.java)
        }
        
        private const val MITMPROXY_DIR = ".mitmproxy"
        private const val CERT_FILE = "mitmproxy-ca-cert.pem"
    }
    
    /**
     * Check if mitmproxy is installed on the system.
     */
    fun isMitmproxyInstalled(): Boolean {
        logger.info("🔍 Checking if mitmproxy is installed...")

        val path = getMitmproxyPath()
        if (path != null) {
            logger.info("✅ mitmproxy found at: $path")
            return true
        }

        logger.warn("❌ mitmproxy not found")
        return false
    }

    /**
     * Get the path to mitmproxy executable.
     * Searches common Homebrew locations and PATH.
     */
    fun getMitmproxyPath(): String? {
        logger.debug("Getting mitmproxy path...")

        // Common Homebrew locations for mitmproxy
        val commonPaths = listOf(
            "/opt/homebrew/bin/mitmproxy",  // Apple Silicon Homebrew
            "/usr/local/bin/mitmproxy",      // Intel Homebrew
            "/opt/homebrew/bin/mitmdump",
            "/usr/local/bin/mitmdump"
        )

        // Check common paths first
        for (path in commonPaths) {
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                logger.debug("Found mitmproxy at: $path")
                return path
            }
        }

        // Try using 'which' with full PATH
        try {
            val env = System.getenv().toMutableMap()
            // Add common Homebrew paths to PATH
            val currentPath = env["PATH"] ?: ""
            env["PATH"] = "/opt/homebrew/bin:/usr/local/bin:$currentPath"

            val process = ProcessBuilder("which", "mitmproxy")
                .redirectErrorStream(true)
                .apply { environment().putAll(env) }
                .start()

            val exitCode = process.waitFor()
            if (exitCode == 0) {
                val path = process.inputStream.bufferedReader().readText().trim()
                if (path.isNotEmpty()) {
                    logger.debug("Found mitmproxy via which: $path")
                    return path
                }
            }
        } catch (e: Exception) {
            logger.debug("Failed to find mitmproxy via which: ${e.message}")
        }

        logger.warn("mitmproxy not found in common locations or PATH")
        return null
    }
    
    /**
     * Generate mitmproxy certificate if it doesn't exist.
     * This will run mitmproxy briefly to generate the certificates.
     */
    fun generateCertificateIfNeeded(): Boolean {
        logger.info("🔐 Checking mitmproxy certificate...")
        
        val certFile = getCertificateFile()
        if (certFile != null && certFile.exists()) {
            logger.info("✅ Certificate already exists at: ${certFile.absolutePath}")
            return true
        }
        
        logger.info("📝 Generating new mitmproxy certificate...")
        
        try {
            // Run mitmdump briefly to generate certificates
            val process = ProcessBuilder("mitmdump", "--version")
                .redirectErrorStream(true)
                .start()
            
            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().readText()
            
            if (exitCode != 0) {
                logger.error("Failed to run mitmdump: $output")
                return false
            }
            
            logger.debug("mitmdump output: $output")
            
            // Wait a bit for certificate generation
            Thread.sleep(1000)
            
            // Check if certificate was created
            val newCertFile = getCertificateFile()
            if (newCertFile != null && newCertFile.exists()) {
                logger.info("✅ Certificate generated successfully at: ${newCertFile.absolutePath}")
                return true
            }
            
            logger.error("❌ Certificate file not created")
            return false
            
        } catch (e: Exception) {
            logger.error("Failed to generate certificate", e)
            return false
        }
    }
    
    /**
     * Get the mitmproxy certificate file.
     */
    fun getCertificateFile(): File? {
        val homeDir = System.getProperty("user.home")
        val mitmproxyDir = File(homeDir, MITMPROXY_DIR)
        
        if (!mitmproxyDir.exists()) {
            logger.debug("mitmproxy directory does not exist: ${mitmproxyDir.absolutePath}")
            return null
        }
        
        val certFile = File(mitmproxyDir, CERT_FILE)
        logger.debug("Certificate file path: ${certFile.absolutePath}, exists: ${certFile.exists()}")
        
        return certFile
    }
    
    /**
     * Calculate the Android certificate hash (old subject hash).
     * Android uses OpenSSL's old subject hash algorithm.
     */
    fun calculateCertificateHash(certFile: File): String? {
        logger.info("🔢 Calculating certificate hash...")
        
        try {
            // Use openssl to get the hash (old format for Android)
            val process = ProcessBuilder(
                "openssl", "x509",
                "-inform", "PEM",
                "-subject_hash_old",
                "-in", certFile.absolutePath
            )
                .redirectErrorStream(true)
                .start()
            
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val error = process.inputStream.bufferedReader().readText()
                logger.error("Failed to calculate hash: $error")
                return null
            }
            
            val hash = process.inputStream.bufferedReader().readLine()?.trim()
            
            if (hash != null) {
                logger.info("✅ Certificate hash: $hash")
                return hash
            }
            
            logger.error("❌ Failed to read certificate hash")
            return null
            
        } catch (e: Exception) {
            logger.error("Failed to calculate certificate hash", e)
            return null
        }
    }

    /**
     * Result of certificate installation attempt.
     */
    enum class CertInstallResult {
        /** Certificate installed automatically (system or user store) */
        INSTALLED_AUTOMATICALLY,
        /** Certificate copied to device, requires manual installation */
        REQUIRES_MANUAL_INSTALL,
        /** Installation failed completely */
        FAILED
    }

    /**
     * Install mitmproxy certificate on Android device automatically.
     * Like Proxyman, tries system installation first (works on Google APIs emulators),
     * then falls back to user certificate installation.
     *
     * @param serialNumber Device serial number
     * @return CertInstallResult indicating the installation result
     */
    fun installUserCertificate(serialNumber: String): CertInstallResult {
        logger.info("📲 Checking mitmproxy certificate on $serialNumber...")

        // Generate certificate if needed
        if (!generateCertificateIfNeeded()) {
            logger.error("❌ Cannot install certificate: generation failed")
            return CertInstallResult.FAILED
        }

        val certFile = getCertificateFile()
        if (certFile == null || !certFile.exists()) {
            logger.error("❌ Certificate file not found")
            return CertInstallResult.FAILED
        }

        // Get ADB path
        val adbPath = getAdbPath()
        if (adbPath == null) {
            logger.error("❌ ADB not found")
            return CertInstallResult.FAILED
        }

        // Check if certificate is already installed
        if (isCertificateInstalled(serialNumber, certFile, adbPath)) {
            logger.info("✅ Certificate already installed, skipping installation")
            return CertInstallResult.INSTALLED_AUTOMATICALLY
        }

        logger.info("📲 Installing mitmproxy certificate...")

        // Try Method 1: System certificate installation
        // Works on Google APIs emulators that allow remount
        if (installAsSystemCertificate(serialNumber, certFile, adbPath)) {
            logger.info("✅ Certificate installed as system certificate")
            return CertInstallResult.INSTALLED_AUTOMATICALLY
        }

        // Try Method 2: Automatic user certificate installation
        // Copies to /data/misc/user/0/cacerts-added/ (requires special permissions)
        logger.info("⚠️  System installation failed, trying automatic user certificate installation...")
        if (installAsUserCertificateAutomatic(serialNumber, certFile, adbPath)) {
            logger.info("✅ Certificate installed automatically in user store")
            return CertInstallResult.INSTALLED_AUTOMATICALLY
        }

        // Try Method 3: Manual user certificate installation (fallback)
        logger.info("⚠️  Automatic installation failed, certificate requires manual installation...")
        if (installAsUserCertificateManual(serialNumber, certFile, adbPath)) {
            return CertInstallResult.REQUIRES_MANUAL_INSTALL
        }

        return CertInstallResult.FAILED
    }

    /**
     * Check if the mitmproxy certificate is already installed on the device.
     *
     * Strategy:
     * 1. Check system CA store (works on Google APIs emulators with root)
     * 2. If not found, return false (will trigger installation attempt)
     *
     * Note: We only check if the file exists in known locations.
     * The real test is when the app makes HTTPS requests through mitmproxy.
     */
    private fun isCertificateInstalled(serialNumber: String, certFile: File, adbPath: String): Boolean {
        try {
            logger.debug("Checking if certificate is already installed...")

            // Calculate certificate hash
            val hash = calculateCertificateHash(certFile)
            if (hash == null) {
                logger.debug("Cannot verify certificate: hash calculation failed")
                return false
            }

            // Check system CA store: /system/etc/security/cacerts/{hash}.0
            // This works on Google APIs emulators with root
            val systemCertPath = "/system/etc/security/cacerts/$hash.0"
            logger.debug("Checking system CA store: $systemCertPath")

            val systemCheckProcess = ProcessBuilder(
                adbPath, "-s", serialNumber,
                "shell", "ls", systemCertPath
            )
                .redirectErrorStream(true)
                .start()

            val systemOutput = systemCheckProcess.inputStream.bufferedReader().readText()
            val systemExitCode = systemCheckProcess.waitFor()

            if (systemExitCode == 0 && !systemOutput.contains("No such file")) {
                logger.info("✅ Certificate found in system CA store: $systemCertPath")
                return true
            }

            logger.debug("Certificate not in system store, will need to verify with HTTPS test")
            return false

        } catch (e: Exception) {
            logger.debug("Error checking certificate installation: ${e.message}")
            return false
        }
    }

    /**
     * Install certificate as system certificate (like Proxyman does).
     * Works on Google APIs emulators that allow /system remounting.
     */
    private fun installAsSystemCertificate(serialNumber: String, certFile: File, adbPath: String): Boolean {
        try {
            logger.debug("Attempting system certificate installation (Proxyman method)...")

            // Calculate certificate hash for Android system format
            val hash = calculateCertificateHash(certFile)
            if (hash == null) {
                logger.debug("Failed to calculate certificate hash")
                return false
            }

            // Step 1: Try to remount /system as writable
            logger.debug("Remounting /system as writable...")
            val remountProcess = ProcessBuilder(adbPath, "-s", serialNumber, "remount")
                .redirectErrorStream(true)
                .start()

            val remountOutput = remountProcess.inputStream.bufferedReader().readText()
            remountProcess.waitFor()

            logger.debug("Remount output: $remountOutput")

            // Check if remount succeeded (if it contains "remount succeeded" or similar)
            if (!remountOutput.contains("succeeded", ignoreCase = true) &&
                !remountOutput.contains("remount of", ignoreCase = true)) {
                logger.debug("Remount may have failed, trying alternative method...")

                // Try alternative: adb root + remount
                val rootProcess = ProcessBuilder(adbPath, "-s", serialNumber, "root")
                    .redirectErrorStream(true)
                    .start()
                rootProcess.waitFor()

                Thread.sleep(1000) // Wait for adb to restart in root mode

                val remount2 = ProcessBuilder(adbPath, "-s", serialNumber, "remount")
                    .redirectErrorStream(true)
                    .start()
                remount2.waitFor()
            }

            // Step 2: Push certificate to /system/etc/security/cacerts/
            val systemCertPath = "/system/etc/security/cacerts/$hash.0"
            logger.debug("Pushing certificate to: $systemCertPath")

            val pushSystemProcess = ProcessBuilder(
                adbPath, "-s", serialNumber,
                "push", certFile.absolutePath, systemCertPath
            )
                .redirectErrorStream(true)
                .start()

            val pushSystemOutput = pushSystemProcess.inputStream.bufferedReader().readText()
            val pushSystemExit = pushSystemProcess.waitFor()

            logger.debug("Push to system output: $pushSystemOutput")

            if (pushSystemExit != 0) {
                logger.debug("Failed to push to /system, emulator may not support remount")
                return false
            }

            // Step 3: Set correct permissions (644 = rw-r--r--)
            logger.debug("Setting certificate permissions...")
            val chmodProcess = ProcessBuilder(
                adbPath, "-s", serialNumber,
                "shell", "chmod", "644", systemCertPath
            )
                .redirectErrorStream(true)
                .start()

            chmodProcess.waitFor()

            // Step 4: Verify installation
            val verifyProcess = ProcessBuilder(
                adbPath, "-s", serialNumber,
                "shell", "ls", "-la", systemCertPath
            )
                .redirectErrorStream(true)
                .start()

            val verifyOutput = verifyProcess.inputStream.bufferedReader().readText()
            val verifyExit = verifyProcess.waitFor()

            if (verifyExit == 0 && verifyOutput.contains(hash)) {
                logger.info("✅ Certificate installed successfully at: $systemCertPath")
                logger.info("   Certificate will be trusted by all apps (including non-debuggable)")
                return true
            }

            return false

        } catch (e: Exception) {
            logger.debug("System certificate installation failed: ${e.message}")
            return false
        }
    }

    /**
     * Install certificate automatically in user CA store.
     * Copies to /data/misc/user/0/cacerts-added/ with correct permissions.
     * Works on emulators with proper permissions.
     */
    private fun installAsUserCertificateAutomatic(serialNumber: String, certFile: File, adbPath: String): Boolean {
        try {
            logger.debug("Attempting automatic user certificate installation...")

            // Calculate certificate hash
            val hash = calculateCertificateHash(certFile)
            if (hash == null) {
                logger.debug("Failed to calculate certificate hash")
                return false
            }

            val userCertPath = "/data/misc/user/0/cacerts-added/$hash.0"

            // Create directory if it doesn't exist
            logger.debug("Creating user cert directory...")
            val mkdirProcess = ProcessBuilder(
                adbPath, "-s", serialNumber,
                "shell", "mkdir", "-p", "/data/misc/user/0/cacerts-added"
            )
                .redirectErrorStream(true)
                .start()
            mkdirProcess.waitFor()

            // Push certificate
            logger.debug("Pushing certificate to: $userCertPath")
            val pushProcess = ProcessBuilder(
                adbPath, "-s", serialNumber,
                "push", certFile.absolutePath, userCertPath
            )
                .redirectErrorStream(true)
                .start()

            val pushOutput = pushProcess.inputStream.bufferedReader().readText()
            val pushExit = pushProcess.waitFor()

            if (pushExit != 0) {
                logger.debug("Failed to push to user cert store: $pushOutput")
                return false
            }

            // Set permissions (644 = rw-r--r--)
            logger.debug("Setting certificate permissions...")
            val chmodProcess = ProcessBuilder(
                adbPath, "-s", serialNumber,
                "shell", "chmod", "644", userCertPath
            )
                .redirectErrorStream(true)
                .start()
            chmodProcess.waitFor()

            // Try to change ownership to system:system (may fail without root, but that's okay)
            val chownProcess = ProcessBuilder(
                adbPath, "-s", serialNumber,
                "shell", "chown", "system:system", userCertPath
            )
                .redirectErrorStream(true)
                .start()
            chownProcess.waitFor()

            // Verify installation
            val verifyProcess = ProcessBuilder(
                adbPath, "-s", serialNumber,
                "shell", "ls", "-la", userCertPath
            )
                .redirectErrorStream(true)
                .start()

            val verifyOutput = verifyProcess.inputStream.bufferedReader().readText()
            val verifyExit = verifyProcess.waitFor()

            if (verifyExit == 0 && verifyOutput.contains(hash)) {
                logger.info("✅ Certificate installed successfully in user store: $userCertPath")
                logger.info("   Certificate will be trusted by apps in debug mode")
                return true
            }

            return false

        } catch (e: Exception) {
            logger.debug("Automatic user certificate installation failed: ${e.message}")
            return false
        }
    }

    /**
     * Install certificate manually (requires user interaction).
     * Copies to Download folder and opens certificate installer.
     */
    private fun installAsUserCertificateManual(serialNumber: String, certFile: File, adbPath: String): Boolean {
        try {
            // Push certificate to Download folder (visible in Files app)
            val devicePath = "/sdcard/Download/mitmproxy-ca-cert.pem"
            logger.debug("Pushing certificate to device: $devicePath")

            val pushProcess = ProcessBuilder(
                adbPath, "-s", serialNumber,
                "push", certFile.absolutePath, devicePath
            )
                .redirectErrorStream(true)
                .start()

            val pushOutput = pushProcess.inputStream.bufferedReader().readText()
            val pushExitCode = pushProcess.waitFor()

            if (pushExitCode != 0) {
                logger.error("❌ Failed to push certificate: $pushOutput")
                return false
            }

            logger.info("✅ Certificate copied to Download folder")

            // Try to open certificate installer directly with the file
            logger.info("📱 Opening certificate installer...")

            // Try Method 1: Open installer with file path (works on some versions)
            val installerProcess = ProcessBuilder(
                adbPath, "-s", serialNumber,
                "shell", "am", "start",
                "-a", "android.intent.action.VIEW",
                "-t", "application/x-x509-ca-cert",
                "-d", "file:///sdcard/Download/mitmproxy-ca-cert.pem"
            )
                .redirectErrorStream(true)
                .start()

            val installerOutput = installerProcess.inputStream.bufferedReader().readText()
            installerProcess.waitFor()

            // If that failed, try opening credential installation intent
            if (installerOutput.contains("Error", ignoreCase = true) || installerOutput.contains("No activity", ignoreCase = true)) {
                logger.debug("Direct file open failed, trying credential installer...")
                val credentialProcess = ProcessBuilder(
                    adbPath, "-s", serialNumber,
                    "shell", "am", "start",
                    "-a", "android.credentials.INSTALL"
                )
                    .redirectErrorStream(true)
                    .start()
                credentialProcess.waitFor()
            }

            logger.info("✅ Certificate installer opened")
            logger.info("   👉 Please complete installation on device:")
            logger.info("   1. If prompted, tap 'Install anyway'")
            logger.info("   2. Select the certificate file from Download folder if needed")
            logger.info("   3. Name it 'mitmproxy' and tap OK")
            logger.info("")
            logger.info("   If the installer didn't open, go to:")
            logger.info("   Settings → Security → Encryption & credentials → Install a certificate")
            logger.info("")
            logger.info("   Note: This only needs to be done once per emulator")

            return true

        } catch (e: Exception) {
            logger.error("❌ Failed to install user certificate", e)
            return false
        }
    }

    /**
     * Get ADB executable path.
     */
    private fun getAdbPath(): String? {
        val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        if (androidHome != null) {
            val adb = File(androidHome, "platform-tools/adb")
            if (adb.exists()) {
                return adb.absolutePath
            }
        }

        // Try default macOS location
        val defaultPath = File(System.getProperty("user.home"), "Library/Android/sdk/platform-tools/adb")
        if (defaultPath.exists()) {
            return defaultPath.absolutePath
        }

        return null
    }
}
