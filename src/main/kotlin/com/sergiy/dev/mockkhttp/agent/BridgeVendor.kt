package com.sergiy.dev.mockkhttp.agent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Puts the stdio MCP bridge somewhere a terminal can run it, and pins it to a JVM that is
 * guaranteed to exist.
 *
 * ## Why the jar leaves the plugin at all
 *
 * The bridge is launched by `claude`, not by the IDE, so it cannot live inside the plugin's
 * classloader. It is copied to `~/.mockkhttp/bin/` and started by a generated launcher whose
 * `java` is **this IDE's own JBR** (`System.getProperty("java.home")`). That is the whole reason
 * the documented setup has zero prerequisites: every Android Studio ships a JBR ≥ 21, so there is
 * no Node, no npm, no `JAVA_HOME` to get wrong, and no registry to compromise — the only thing the
 * happy path ever executes is a jar this plugin shipped and whose SHA-256 it publishes in the
 * instance file (plan §7.10).
 *
 * ## Idempotence
 *
 * [vendor] runs on every IDE start. The jar is rewritten only when its SHA-256 differs, so a
 * plugin update replaces it and a restart does not. The launcher is regenerated whenever its
 * content would change, which is how a bridge survives the IDE moving to a new JBR.
 *
 * The copy is atomic (temp + `fsync` + rename), so replacing the jar under a bridge that is
 * running right now is safe: on POSIX the running JVM keeps the old inode open.
 *
 * Does file I/O — call it off the EDT.
 */
@Service(Service.Level.APP)
class BridgeVendor {

    private val log = Logger.getInstance(BridgeVendor::class.java)
    private val vendorLock = Any()

    @Volatile
    private var lastResult: VendorResult? = null

    @Volatile
    private var lastError: String? = null

    companion object {
        /** Produced by the `:mcp-bridge` subproject and copied into plugin resources at build time. */
        const val BRIDGE_RESOURCE: String = "/bridge/mockkhttp-mcp.jar"

        const val JAR_NAME: String = "mockkhttp-mcp.jar"

        /** The name `.mcp.json` points at. Extensionless on POSIX, `.cmd` on Windows. */
        const val LAUNCHER_NAME_POSIX: String = "mockkhttp-mcp"
        const val LAUNCHER_NAME_WINDOWS: String = "mockkhttp-mcp.cmd"

        fun getInstance(): BridgeVendor =
            ApplicationManager.getApplication().getService(BridgeVendor::class.java)
    }

    /** What ended up on disk. [updated] is true only when bytes actually changed. */
    data class VendorResult(
        val jarPath: Path,
        val launcherPath: Path,
        val sha256: String,
        val updated: Boolean
    )

    /** `~/.mockkhttp/bin` — locked down to the owner, same as the instance directory. */
    fun binDirectory(): Path = AgentFiles.home().resolve("bin")

    fun jarPath(): Path = binDirectory().resolve(JAR_NAME)

    fun launcherPath(): Path =
        binDirectory().resolve(if (SystemInfo.isWindows) LAUNCHER_NAME_WINDOWS else LAUNCHER_NAME_POSIX)

    /** The last successful vendoring, or null when the bridge is not on disk. */
    fun lastResult(): VendorResult? = lastResult

    /** Why the last [vendor] produced nothing, or null. User-facing; shown in Settings. */
    fun lastError(): String? = lastError

    /**
     * Copy the bridge out of plugin resources and (re)generate its launcher.
     *
     * Returns null and records [lastError] when the bridge cannot be vendored — most importantly
     * when the jar is **not bundled yet**, which is the normal state until the `:mcp-bridge`
     * subproject lands. That is a missing feature, not a crash: the plugin, the control plane and
     * every existing capture workflow must keep working, so this never throws.
     */
    fun vendor(): VendorResult? {
        synchronized(vendorLock) {
            val bytes = readBundledJar() ?: return null

            val dir = binDirectory()
            val dirResult = AgentFiles.ensureDirectory(dir)
            if (dirResult.refused) {
                return fail(dirResult.detail)
            }
            if (dirResult.degraded) {
                log.warn("🤖 ⚠️ ${dirResult.detail}")
            }

            val sha = sha256Hex(bytes)
            val jar = jarPath()

            val updated = if (sha == sha256OfFile(jar)) {
                false
            } else {
                val failure = AgentFiles.writeAtomically(jar, bytes, AgentFiles.PathKind.FILE)
                if (failure != null) {
                    return fail("Could not write $jar: $failure")
                }
                log.info("🤖 ✅ Bridge vendored to $jar (sha256 ${sha.take(12)}…)")
                true
            }

            val launcher = launcherPath()
            val script = launcherScript(jar)
            if (script != readTextOrNull(launcher)) {
                // PathKind.EXECUTABLE == rwx------: the owner must be able to run it, nobody else
                // may even read it, since it names the jar the agent channel trusts.
                val failure = AgentFiles.writeAtomically(
                    launcher,
                    script.toByteArray(StandardCharsets.UTF_8),
                    AgentFiles.PathKind.EXECUTABLE
                )
                if (failure != null) {
                    return fail("Could not write $launcher: $failure")
                }
                log.info("🤖 ✅ Bridge launcher written to $launcher")
            }

            val result = VendorResult(jarPath = jar, launcherPath = launcher, sha256 = sha, updated = updated)
            lastResult = result
            lastError = null

            // The bridge verifies the jar it was told to run against this hash, so discovery has
            // to learn about it before any client can launch.
            try {
                InstanceRegistry.getInstance().setBridge(jar, sha)
            } catch (e: Exception) {
                log.debug("🤖 Could not publish the bridge hash to the instance file", e)
            }
            return result
        }
    }

    /** Remove the vendored jar and launcher. Used when agent control is switched off for good. */
    fun unvendor() {
        synchronized(vendorLock) {
            for (path in listOf(jarPath(), launcherPath())) {
                try {
                    Files.deleteIfExists(path)
                } catch (e: Exception) {
                    log.warn("🤖 ⚠️ Could not delete $path", e)
                }
            }
            lastResult = null
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun readBundledJar(): ByteArray? {
        val stream = javaClass.getResourceAsStream(BRIDGE_RESOURCE)
        if (stream == null) {
            // Expected until :mcp-bridge is wired into the build. Say so precisely — a vague
            // "bridge unavailable" would send someone hunting through ~/.mockkhttp for a file
            // that was never built.
            return fail(
                "The MCP bridge jar is not bundled in this build ($BRIDGE_RESOURCE). Agent control " +
                        "still works over HTTP, but there is nothing for Claude Code to launch yet."
            )
        }
        return try {
            stream.use { it.readBytes() }
        } catch (e: Exception) {
            fail("Could not read $BRIDGE_RESOURCE (${e.javaClass.simpleName}: ${e.message})")
        }
    }

    /**
     * Records [reason] and returns null, so callers can `return fail(...)` in one line whatever
     * they are returning. Logged only on a change: [vendor] runs on every start, and a build
     * without the bridge jar would otherwise repeat the same warning forever.
     */
    private fun <T : Any> fail(reason: String): T? {
        if (lastError != reason) {
            log.warn("🤖 ⚠️ $reason")
        }
        lastError = reason
        return null
    }

    /**
     * The launcher, pinned to the JBR running this IDE.
     *
     * `java.home` is the only JVM we can prove exists and is new enough — the user's `PATH` may
     * have no `java` at all, which is normal on a machine that only ever installed Android Studio.
     */
    private fun launcherScript(jar: Path): String {
        val javaBin = Path.of(System.getProperty("java.home"), "bin", if (SystemInfo.isWindows) "java.exe" else "java")
        return if (SystemInfo.isWindows) {
            buildString {
                append("@echo off\r\n")
                append("rem Generated by MockkHttp. Regenerated on every IDE start - do not edit.\r\n")
                append("rem Pinned to the JBR shipped with this IDE, so no JAVA_HOME is required.\r\n")
                append("\"").append(javaBin).append("\" -jar \"").append(jar).append("\" %*\r\n")
            }
        } else {
            buildString {
                append("#!/bin/sh\n")
                append("# Generated by MockkHttp. Regenerated on every IDE start - do not edit.\n")
                append("# Pinned to the JBR shipped with this IDE, so no JAVA_HOME is required.\n")
                append("exec \"").append(javaBin).append("\" -jar \"").append(jar).append("\" \"${'$'}@\"\n")
            }
        }
    }

    private fun readTextOrNull(path: Path): String? =
        try {
            if (Files.isRegularFile(path)) Files.readString(path, StandardCharsets.UTF_8) else null
        } catch (e: Exception) {
            null
        }

    private fun sha256OfFile(path: Path): String? =
        try {
            if (Files.isRegularFile(path)) sha256Hex(Files.readAllBytes(path)) else null
        } catch (e: Exception) {
            // Unreadable means "not what we want on disk" — the copy below replaces it.
            null
        }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            out.append("%02x".format(b))
        }
        return out.toString()
    }
}
