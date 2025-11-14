package com.sergiy.dev.mockkhttp.adb

/**
 * Data class representing an installed Android application.
 */
data class AppInfo(
    val packageName: String,
    val appName: String?,
    val versionName: String?,
    val versionCode: Int?,
    val isSystemApp: Boolean,
    val uid: Int? = null,  // Android UID for network filtering
    val hasMockkHttp: Boolean = false  // Whether app has MockkHttp interceptor
) {
    /**
     * Display name for UI
     */
    val displayName: String
        get() {
            val baseName = appName ?: packageName
            return if (hasMockkHttp) {
                "🎭 $baseName"  // Mask emoji indicates MockkHttp is active
            } else {
                baseName
            }
        }
    
    /**
     * Full description for logging
     */
    val fullDescription: String
        get() = buildString {
            append("App(")
            append("package=$packageName")
            appName?.let { append(", name=$it") }
            versionName?.let { append(", version=$it") }
            append(", system=$isSystemApp")
            append(")")
        }
    
    override fun toString(): String = displayName
}
