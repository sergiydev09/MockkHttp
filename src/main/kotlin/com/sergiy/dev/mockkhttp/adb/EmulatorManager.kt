package com.sergiy.dev.mockkhttp.adb

import com.android.ddmlib.AdbInitOptions
import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manager for detecting and managing Android emulators via ADB.
 * Handles ADB bridge initialization and emulator discovery.
 */
@Service(Service.Level.PROJECT)
class EmulatorManager(project: Project) {

    private val logger = MockkHttpLogger.getInstance(project)
    private var adbBridge: AndroidDebugBridge? = null
    private var isInitialized = false
    private val deviceChangeListeners = mutableListOf<() -> Unit>()
    private var deviceListener: AndroidDebugBridge.IDeviceChangeListener? = null

    companion object {
        fun getInstance(project: Project): EmulatorManager {
            return project.getService(EmulatorManager::class.java)
        }

        private const val ADB_INIT_TIMEOUT_MS = 10000L
    }
    
    /**
     * Initialize ADB bridge.
     * Must be called before any ADB operations.
     */
    fun initialize(): Boolean {
        logger.info("🔧 Initializing ADB bridge...")
        
        if (isInitialized && adbBridge != null) {
            logger.debug("ADB bridge already initialized")
            return true
        }
        
        try {
            // Find ADB executable
            val adbPath = findAdbPath()
            if (adbPath == null) {
                logger.error("ADB executable not found. Please install Android SDK Platform Tools.")
                return false
            }
            
            logger.debug("ADB found at: $adbPath")

            // Initialize ADB with modern AdbInitOptions
            val adbInitOptions = AdbInitOptions.builder()
                .setClientSupportEnabled(false)
                .useJdwpProxyService(false)
                .build()

            AndroidDebugBridge.init(adbInitOptions)
            logger.debug("ADB initialized successfully with AdbInitOptions")

            // Create bridge with timeout to prevent hanging
            adbBridge = AndroidDebugBridge.createBridge(
                adbPath,
                false,
                ADB_INIT_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
            
            if (adbBridge == null) {
                logger.error("Failed to create ADB bridge")
                return false
            }
            
            // Wait for bridge to connect
            logger.debug("Waiting for ADB bridge to connect...")
            val startTime = System.currentTimeMillis()
            while (!adbBridge!!.isConnected && 
                   System.currentTimeMillis() - startTime < ADB_INIT_TIMEOUT_MS) {
                Thread.sleep(100)
            }
            
            if (!adbBridge!!.isConnected) {
                logger.error("ADB bridge connection timeout")
                return false
            }
            
            // Wait for initial device list
            logger.debug("Waiting for device list...")
            var waited = 0L
            while (!adbBridge!!.hasInitialDeviceList() && waited < ADB_INIT_TIMEOUT_MS) {
                Thread.sleep(100)
                waited += 100
            }

            // Register device change listener
            deviceListener = object : AndroidDebugBridge.IDeviceChangeListener {
                override fun deviceConnected(device: IDevice?) {
                    logger.debug("Device connected: ${device?.serialNumber}")
                    notifyDeviceChange()
                }

                override fun deviceDisconnected(device: IDevice?) {
                    logger.debug("Device disconnected: ${device?.serialNumber}")
                    notifyDeviceChange()
                }

                override fun deviceChanged(device: IDevice?, changeMask: Int) {
                    // Only notify on significant changes
                    if (changeMask and IDevice.CHANGE_STATE != 0) {
                        logger.debug("Device state changed: ${device?.serialNumber}")
                        notifyDeviceChange()
                    }
                }
            }
            AndroidDebugBridge.addDeviceChangeListener(deviceListener)
            logger.debug("Device change listener registered")

            isInitialized = true
            logger.info("✅ ADB bridge initialized successfully")
            return true

        } catch (e: Exception) {
            logger.error("Failed to initialize ADB bridge", e)
            isInitialized = false
            return false
        }
    }
    
    /**
     * Find ADB executable path.
     * Checks common locations for Android SDK.
     * Made public so other components can use it.
     */
    fun findAdbPath(): String? {
        logger.debug("Searching for ADB executable...")
        
        // Try ANDROID_HOME environment variable
        val androidHome = System.getenv("ANDROID_HOME")
        if (androidHome != null) {
            val adbPath = File(androidHome, "platform-tools/adb")
            if (adbPath.exists()) {
                logger.debug("ADB found via ANDROID_HOME: ${adbPath.absolutePath}")
                return adbPath.absolutePath
            }
        }
        
        // Try ANDROID_SDK_ROOT environment variable
        val androidSdkRoot = System.getenv("ANDROID_SDK_ROOT")
        if (androidSdkRoot != null) {
            val adbPath = File(androidSdkRoot, "platform-tools/adb")
            if (adbPath.exists()) {
                logger.debug("ADB found via ANDROID_SDK_ROOT: ${adbPath.absolutePath}")
                return adbPath.absolutePath
            }
        }
        
        // Try common macOS location
        val macOsPath = File(System.getProperty("user.home"), 
                              "Library/Android/sdk/platform-tools/adb")
        if (macOsPath.exists()) {
            logger.debug("ADB found at macOS default location: ${macOsPath.absolutePath}")
            return macOsPath.absolutePath
        }
        
        // Try PATH
        val pathEnv = System.getenv("PATH") ?: ""
        for (dir in pathEnv.split(":")) {
            val adbPath = File(dir, "adb")
            if (adbPath.exists() && adbPath.canExecute()) {
                logger.debug("ADB found in PATH: ${adbPath.absolutePath}")
                return adbPath.absolutePath
            }
        }
        
        logger.warn("ADB not found in any common location")
        return null
    }
    
    /**
     * Get list of connected emulators.
     * Returns only emulators, not physical devices.
     */
    fun getConnectedEmulators(): List<EmulatorInfo> {
        logger.info("🔍 Detecting connected emulators...")
        
        if (!isInitialized) {
            logger.warn("ADB not initialized, attempting to initialize...")
            if (!initialize()) {
                logger.error("Cannot detect emulators: ADB initialization failed")
                return emptyList()
            }
        }
        
        try {
            val devices = adbBridge?.devices ?: emptyArray()
            logger.debug("Found ${devices.size} device(s) connected")
            
            val emulators = devices
                .filter { it.isEmulator }
                .map { device ->
                    logger.debug("Processing emulator: ${device.serialNumber}")
                    convertToEmulatorInfo(device)
                }
            
            logger.info("✅ Found ${emulators.size} emulator(s)")
            emulators.forEach { emulator ->
                logger.debug("  - ${emulator.fullDescription}")
            }
            
            return emulators
            
        } catch (e: Exception) {
            logger.error("Failed to get connected emulators", e)
            return emptyList()
        }
    }
    
    /**
     * Get specific emulator by serial number.
     */
    fun getEmulator(serialNumber: String): EmulatorInfo? {
        logger.debug("Looking for emulator: $serialNumber")
        
        return getConnectedEmulators()
            .find { it.serialNumber == serialNumber }
            ?.also { logger.debug("Emulator found: ${it.fullDescription}") }
            ?: run {
                logger.warn("Emulator not found: $serialNumber")
                null
            }
    }
    
    /**
     * Get IDevice by serial number.
     * Returns the underlying IDevice for advanced operations.
     */
    fun getDevice(serialNumber: String): IDevice? {
        logger.debug("Getting device: $serialNumber")

        if (!isInitialized) {
            logger.warn("ADB not initialized, attempting to initialize...")
            if (!initialize()) {
                logger.error("Cannot get device: ADB initialization failed")
                return null
            }
        }

        return adbBridge?.devices?.find { it.serialNumber == serialNumber }
    }

    /**
     * Check if emulator has root access.
     */
    fun hasRootAccess(serialNumber: String): Boolean {
        return getRootType(serialNumber) != RootType.NONE
    }

    /**
     * Root access type for an emulator.
     */
    enum class RootType {
        NONE,           // No root access
        DIRECT_SHELL,   // ADB shell is already root (no su needed)
        VIA_SU          // Root via su command
    }

    /**
     * Detect the type of root access available.
     */
    fun getRootType(serialNumber: String): RootType {
        logger.debug("Checking root access for: $serialNumber")

        try {
            val device = getDevice(serialNumber)

            if (device == null) {
                logger.warn("Device not found: $serialNumber")
                return RootType.NONE
            }

            // First check if ADB shell is already running as root (common in emulators)
            val whoamiReceiver = CollectingOutputReceiver()
            device.executeShellCommand("whoami", whoamiReceiver, 5, TimeUnit.SECONDS)
            val whoamiOutput = whoamiReceiver.output.trim()

            if (whoamiOutput == "root") {
                logger.info("✅ Root access confirmed for $serialNumber (ADB shell is root)")
                return RootType.DIRECT_SHELL
            }

            // If not, try su command
            val receiver = CollectingOutputReceiver()
            device.executeShellCommand("su -c id", receiver, 5, TimeUnit.SECONDS)
            val output = receiver.output

            val hasRoot = output.contains("uid=0(root)")

            if (hasRoot) {
                logger.info("✅ Root access confirmed for $serialNumber (via su)")
                return RootType.VIA_SU
            } else {
                logger.warn("❌ No root access for $serialNumber")
                logger.debug("Root check output: $output")
                return RootType.NONE
            }

        } catch (e: Exception) {
            logger.error("Failed to check root access for $serialNumber", e)
            return RootType.NONE
        }
    }
    
    /**
     * Convert IDevice to EmulatorInfo.
     */
    private fun convertToEmulatorInfo(device: IDevice): EmulatorInfo {
        val apiLevel = try {
            device.getProperty(IDevice.PROP_BUILD_API_LEVEL)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            logger.warn("Failed to get API level for ${device.serialNumber}", e)
            0
        }

        // Use getProperty() instead of deprecated getAvdName()
        val avdName = device.getProperty("ro.kernel.qemu.avd_name") ?: device.serialNumber
        val architecture = device.getProperty(IDevice.PROP_DEVICE_CPU_ABI)
        val manufacturer = device.getProperty(IDevice.PROP_DEVICE_MANUFACTURER)
        val model = device.getProperty(IDevice.PROP_DEVICE_MODEL)
        
        return EmulatorInfo(
            serialNumber = device.serialNumber,
            avdName = avdName,
            apiLevel = apiLevel,
            isOnline = device.isOnline,
            architecture = architecture,
            manufacturer = manufacturer,
            model = model
        )
    }
    
    /**
     * Add listener for device changes (connect/disconnect).
     */
    fun addDeviceChangeListener(listener: () -> Unit) {
        deviceChangeListeners.add(listener)
    }

    /**
     * Notify all listeners of device changes.
     */
    private fun notifyDeviceChange() {
        // Notify listeners in a safe manner
        deviceChangeListeners.toList().forEach { listener ->
            try {
                listener()
            } catch (e: Exception) {
                logger.error("Error in device change listener", e)
            }
        }
    }

    /**
     * Simple output receiver for shell commands.
     * Made package-private for use in other managers.
     */
    class CollectingOutputReceiver : com.android.ddmlib.IShellOutputReceiver {
        private val outputBuilder = StringBuilder()
        
        val output: String
            get() = outputBuilder.toString()
        
        override fun addOutput(data: ByteArray, offset: Int, length: Int) {
            outputBuilder.append(String(data, offset, length))
        }
        
        override fun flush() {
            // No-op
        }
        
        override fun isCancelled(): Boolean = false
    }
}
