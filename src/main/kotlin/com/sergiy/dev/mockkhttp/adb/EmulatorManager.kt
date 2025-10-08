package com.sergiy.dev.mockkhttp.adb

import com.android.ddmlib.AdbInitOptions
import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manager for detecting and managing Android emulators via ADB.
 * Handles ADB bridge initialization and emulator discovery.
 */
@Service(Service.Level.PROJECT)
class EmulatorManager(project: Project) {

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
        
        if (isInitialized && adbBridge != null) {
            return true
        }
        
        try {
            // Find ADB executable
            val adbPath = findAdbPath()
            if (adbPath == null) {
                return false
            }


            // Initialize ADB with modern AdbInitOptions (only if not already initialized)
            // AndroidDebugBridge.init() can only be called ONCE per JVM process
            // When multiple projects are open, only the first one should call init()
            try {
                val adbInitOptions = AdbInitOptions.builder()
                    .setClientSupportEnabled(false)
                    .useJdwpProxyService(false)
                    .build()

                AndroidDebugBridge.init(adbInitOptions)
            } catch (e: IllegalStateException) {
                if (e.message?.contains("already been called") == true) {
                } else {
                    throw e
                }
            }

            // Create bridge with timeout to prevent hanging
            adbBridge = AndroidDebugBridge.createBridge(
                adbPath,
                false,
                ADB_INIT_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
            
            if (adbBridge == null) {
                return false
            }
            
            // Wait for bridge to connect
            val startTime = System.currentTimeMillis()
            while (!adbBridge!!.isConnected && 
                   System.currentTimeMillis() - startTime < ADB_INIT_TIMEOUT_MS) {
                Thread.sleep(100)
            }
            
            if (!adbBridge!!.isConnected) {
                return false
            }
            
            // Wait for initial device list
            var waited = 0L
            while (!adbBridge!!.hasInitialDeviceList() && waited < ADB_INIT_TIMEOUT_MS) {
                Thread.sleep(100)
                waited += 100
            }

            // Register device change listener
            deviceListener = object : AndroidDebugBridge.IDeviceChangeListener {
                override fun deviceConnected(device: IDevice?) {
                    notifyDeviceChange()
                }

                override fun deviceDisconnected(device: IDevice?) {
                    notifyDeviceChange()
                }

                override fun deviceChanged(device: IDevice?, changeMask: Int) {
                    // Only notify on significant changes
                    if (changeMask and IDevice.CHANGE_STATE != 0) {
                        notifyDeviceChange()
                    }
                }
            }
            AndroidDebugBridge.addDeviceChangeListener(deviceListener)

            isInitialized = true
            return true

        } catch (e: Exception) {
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
        
        // Try ANDROID_HOME environment variable
        val androidHome = System.getenv("ANDROID_HOME")
        if (androidHome != null) {
            val adbPath = File(androidHome, "platform-tools/adb")
            if (adbPath.exists()) {
                return adbPath.absolutePath
            }
        }
        
        // Try ANDROID_SDK_ROOT environment variable
        val androidSdkRoot = System.getenv("ANDROID_SDK_ROOT")
        if (androidSdkRoot != null) {
            val adbPath = File(androidSdkRoot, "platform-tools/adb")
            if (adbPath.exists()) {
                return adbPath.absolutePath
            }
        }
        
        // Try common macOS location
        val macOsPath = File(System.getProperty("user.home"), 
                              "Library/Android/sdk/platform-tools/adb")
        if (macOsPath.exists()) {
            return macOsPath.absolutePath
        }
        
        // Try PATH
        val pathEnv = System.getenv("PATH") ?: ""
        for (dir in pathEnv.split(":")) {
            val adbPath = File(dir, "adb")
            if (adbPath.exists() && adbPath.canExecute()) {
                return adbPath.absolutePath
            }
        }
        
        return null
    }
    
    /**
     * Get list of connected emulators.
     * Returns only emulators, not physical devices.
     */
    fun getConnectedEmulators(): List<EmulatorInfo> {
        
        if (!isInitialized) {
            if (!initialize()) {
                return emptyList()
            }
        }
        
        try {
            val devices = adbBridge?.devices ?: emptyArray()
            
            val emulators = devices
                .filter { it.isEmulator }
                .map { device ->
                    convertToEmulatorInfo(device)
                }
            
            emulators.forEach { emulator ->
            }
            
            return emulators
            
        } catch (e: Exception) {
            return emptyList()
        }
    }
    
    /**
     * Get specific emulator by serial number.
     */
    fun getEmulator(serialNumber: String): EmulatorInfo? {
        
        return getConnectedEmulators()
            .find { it.serialNumber == serialNumber }
            ?: run {
                null
            }
    }
    
    /**
     * Get IDevice by serial number.
     * Returns the underlying IDevice for advanced operations.
     */
    fun getDevice(serialNumber: String): IDevice? {

        if (!isInitialized) {
            if (!initialize()) {
                return null
            }
        }

        return adbBridge?.devices?.find { it.serialNumber == serialNumber }
    }
    
    /**
     * Convert IDevice to EmulatorInfo.
     */
    private fun convertToEmulatorInfo(device: IDevice): EmulatorInfo {
        val apiLevel = try {
            device.getProperty(IDevice.PROP_BUILD_API_LEVEL)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
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
