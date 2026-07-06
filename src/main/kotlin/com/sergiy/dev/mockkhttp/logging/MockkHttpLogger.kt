package com.sergiy.dev.mockkhttp.logging

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Centralized logger service for MockkHttp plugin.
 * Captures all logs with timestamps and severity levels for debugging.
 */
@Service(Service.Level.PROJECT)
class MockkHttpLogger() {

    private val platformLogger = Logger.getInstance(MockkHttpLogger::class.java)
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS")

    // Ring buffer of log entries guarded by [logLock]. NOTE: deliberately NOT a
    // CopyOnWriteArrayList — that copies the whole 1000-entry array on EVERY
    // log call, which measurably slows the IDE under heavy traffic.
    private val logLock = Any()
    private val logEntries = ArrayDeque<LogEntry>()

    // Listeners for UI updates
    private val listeners = CopyOnWriteArrayList<LogListener>()
    private val clearListeners = CopyOnWriteArrayList<() -> Unit>()
    
    companion object {
        fun getInstance(project: Project): MockkHttpLogger {
            return project.getService(MockkHttpLogger::class.java)
        }
    }
    
    /**
     * Log entry with timestamp and severity
     */
    data class LogEntry(
        val timestamp: String,
        val level: LogLevel,
        val message: String,
        val throwable: Throwable? = null
    ) {
        override fun toString(): String {
            val builder = StringBuilder()
            builder.append("[$timestamp] ${level.name}: $message")
            throwable?.let {
                builder.append("\n")
                builder.append(it.stackTraceToString())
            }
            return builder.toString()
        }
    }
    
    enum class LogLevel {
        DEBUG, INFO, WARN, ERROR
    }
    
    /**
     * Listener interface for log updates
     */
    fun interface LogListener {
        fun onLogAdded(entry: LogEntry)
    }
    
    /**
     * Add a listener for log updates
     */
    fun addListener(listener: LogListener) {
        listeners.add(listener)
    }

    /**
     * Add a listener invoked (on the EDT) when logs are cleared, so UI mirrors
     * (e.g. the Logs tab text area) can release their copies too.
     */
    fun addClearListener(listener: () -> Unit) {
        clearListeners.add(listener)
    }

    /**
     * Get all log entries
     */
    fun getAllLogs(): List<LogEntry> = synchronized(logLock) { logEntries.toList() }

    /**
     * Clear all logs
     */
    fun clear() {
        synchronized(logLock) { logEntries.clear() }
        // Clear UI mirrors first (queued on the EDT before the entry below)
        ApplicationManager.getApplication().invokeLater {
            clearListeners.forEach {
                try { it() } catch (_: Exception) { /* keep other listeners running */ }
            }
        }
        notifyListeners(LogEntry(
            timestamp = getCurrentTimestamp(),
            level = LogLevel.INFO,
            message = "Logs cleared"
        ))
    }

    /**
     * Get all logs as a formatted string
     */
    fun getLogsAsString(): String {
        return getAllLogs().joinToString("\n") { it.toString() }
    }

    /** Number of retained log entries (for the Cache settings section). */
    fun getLogCount(): Int = synchronized(logLock) { logEntries.size }
    
    // Logging methods
    
    fun debug(message: String) {
        log(LogLevel.DEBUG, message)
        platformLogger.debug(message)
    }
    
    fun info(message: String) {
        log(LogLevel.INFO, message)
        platformLogger.info(message)
    }
    
    fun warn(message: String, throwable: Throwable? = null) {
        log(LogLevel.WARN, message, throwable)
        if (throwable != null) {
            platformLogger.warn(message, throwable)
        } else {
            platformLogger.warn(message)
        }
    }
    
    fun error(message: String, throwable: Throwable? = null) {
        log(LogLevel.ERROR, message, throwable)
        if (throwable != null) {
            platformLogger.error(message, throwable)
        } else {
            platformLogger.error(message)
        }
    }
    
    private fun log(level: LogLevel, message: String, throwable: Throwable? = null) {
        val entry = LogEntry(
            timestamp = getCurrentTimestamp(),
            level = level,
            message = message,
            throwable = throwable
        )
        
        synchronized(logLock) {
            logEntries.addLast(entry)
            // Limit log entries to prevent memory issues (keep last 1000)
            while (logEntries.size > 1000) {
                logEntries.removeFirst()
            }
        }

        notifyListeners(entry)
    }
    
    private fun notifyListeners(entry: LogEntry) {
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it.onLogAdded(entry) }
        }
    }
    
    private fun getCurrentTimestamp(): String {
        return dateFormat.format(Date())
    }
}
