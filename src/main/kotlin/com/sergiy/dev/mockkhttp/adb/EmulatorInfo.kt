package com.sergiy.dev.mockkhttp.adb

/**
 * Platform of a device shown in the Inspector device selector.
 */
enum class DevicePlatform {
    ANDROID,        // Android emulator or physical device (via ADB)
    IOS_SIMULATOR,  // iOS Simulator (via xcrun simctl)
    IOS_DEVICE      // Physical iOS device (via xcrun devicectl, best effort)
}

/**
 * Data class representing a device (Android emulator/physical, iOS Simulator/physical).
 * Contains all relevant information for identifying and working with devices.
 */
data class EmulatorInfo(
    val serialNumber: String,      // ADB serial (Android) or UDID (iOS)
    val avdName: String?,          // AVD name (Android emulator) or device name (iOS)
    val apiLevel: Int,             // Android API level, or iOS major version
    val isOnline: Boolean,
    val architecture: String?,
    val manufacturer: String?,
    val model: String?,
    val isEmulator: Boolean = true,
    val platform: DevicePlatform = DevicePlatform.ANDROID,
    val osVersion: String? = null  // Full OS version string (e.g. "18.3" for iOS)
) {
    /**
     * Display name for UI
     */
    val displayName: String
        get() = if (isEmulator) {
            avdName ?: serialNumber
        } else {
            val desc = listOfNotNull(manufacturer, model).joinToString(" ").trim()
            if (desc.isNotEmpty()) desc else serialNumber
        }

    /**
     * Version label for UI (e.g. "API 34" or "iOS 18.3")
     */
    val versionLabel: String
        get() = when (platform) {
            DevicePlatform.ANDROID -> "API $apiLevel"
            DevicePlatform.IOS_SIMULATOR, DevicePlatform.IOS_DEVICE -> "iOS ${osVersion ?: apiLevel}"
        }

    /**
     * Full description for logging
     */
    val fullDescription: String
        get() = buildString {
            append(
                when (platform) {
                    DevicePlatform.ANDROID -> if (isEmulator) "Emulator(" else "Device("
                    DevicePlatform.IOS_SIMULATOR -> "iOSSimulator("
                    DevicePlatform.IOS_DEVICE -> "iOSDevice("
                }
            )
            append("serial=$serialNumber")
            if (isEmulator) {
                avdName?.let { append(", name=$it") }
            } else {
                manufacturer?.let { append(", manufacturer=$it") }
                model?.let { append(", model=$it") }
            }
            append(", ${versionLabel.lowercase()}")
            append(", online=$isOnline")
            architecture?.let { append(", arch=$it") }
            append(")")
        }

    override fun toString(): String = displayName
}
