package com.sergiy.dev.mockkhttp.adb

/**
 * Data class representing an Android emulator.
 * Contains all relevant information for identifying and working with emulators.
 */
data class EmulatorInfo(
    val serialNumber: String,
    val avdName: String?,
    val apiLevel: Int,
    val isOnline: Boolean,
    val architecture: String?,
    val manufacturer: String?,
    val model: String?
) {
    /**
     * Display name for UI
     */
    val displayName: String
        get() = avdName ?: serialNumber
    
    /**
     * Full description for logging
     */
    val fullDescription: String
        get() = buildString {
            append("Emulator(")
            append("serial=$serialNumber")
            avdName?.let { append(", avd=$it") }
            append(", api=$apiLevel")
            append(", online=$isOnline")
            architecture?.let { append(", arch=$it") }
            append(")")
        }
    
    override fun toString(): String = displayName
}
