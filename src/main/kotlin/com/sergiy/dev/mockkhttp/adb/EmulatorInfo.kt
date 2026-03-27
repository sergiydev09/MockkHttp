package com.sergiy.dev.mockkhttp.adb

/**
 * Data class representing an Android device (emulator or physical).
 * Contains all relevant information for identifying and working with devices.
 */
data class EmulatorInfo(
    val serialNumber: String,
    val avdName: String?,
    val apiLevel: Int,
    val isOnline: Boolean,
    val architecture: String?,
    val manufacturer: String?,
    val model: String?,
    val isEmulator: Boolean = true
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
     * Full description for logging
     */
    val fullDescription: String
        get() = buildString {
            append(if (isEmulator) "Emulator(" else "Device(")
            append("serial=$serialNumber")
            if (isEmulator) {
                avdName?.let { append(", avd=$it") }
            } else {
                manufacturer?.let { append(", manufacturer=$it") }
                model?.let { append(", model=$it") }
            }
            append(", api=$apiLevel")
            append(", online=$isOnline")
            architecture?.let { append(", arch=$it") }
            append(")")
        }

    override fun toString(): String = displayName
}
