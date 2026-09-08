package com.sergiy.dev.mockkhttp.agent

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.sergiy.dev.mockkhttp.control.dto.AGENT_CONTROL_FULL
import com.sergiy.dev.mockkhttp.control.dto.AGENT_CONTROL_OFF
import com.sergiy.dev.mockkhttp.control.dto.API_VERSION
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import com.sergiy.dev.mockkhttp.proxy.GlobalOkHttpInterceptorServer
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.EnumSet

// ============================================================================
// Shared filesystem plumbing for everything the agent channel writes to ~/.mockkhttp
// ============================================================================

/**
 * The one place that decides *where* the agent channel writes outside the IDE, and *who may read
 * it*. Shared by [InstanceRegistry] (`instances/`) and [BridgeVendor] (`bin/`), because a bridge
 * jar the whole machine can rewrite is exactly as bad as a token the whole machine can read.
 *
 * The hardening contract is plan §7.4 and is deliberately asymmetric between platforms:
 *
 * - **POSIX** — `rwx------` on directories, `rw-------` on files. If that fails we **refuse**:
 *   on a POSIX box a failure here means the mode did not take, and a 256-bit bearer token sitting
 *   in a world-readable file is worse than no agent channel at all.
 * - **Windows / anything else** — an owner-only ACL. If *that* fails (roaming profiles, network
 *   homes and mapped drives all manage it) we do **not** brick the feature: we degrade, and the
 *   caller is expected to surface a loud, persistent warning instead. Bricking agent control on a
 *   corporate Windows profile buys no security — the file is same-uid readable either way, and a
 *   same-uid attacker is explicitly out of the threat model.
 */
internal object AgentFiles {

    /** How locked down a path actually ended up. */
    enum class Hardening { POSIX, ACL, DEGRADED, FAILED }

    /** [detail] is user-facing: it goes straight into the Agent tab's warning. */
    data class HardenResult(val hardening: Hardening, val detail: String) {
        /** True when the path must NOT be used to hold a secret. */
        val refused: Boolean get() = hardening == Hardening.FAILED

        /** True when the path is in use but could not be locked down. Warn loudly, keep going. */
        val degraded: Boolean get() = hardening == Hardening.DEGRADED
    }

    private val posixSupported: Boolean =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    /**
     * JVM property that relocates the root of everything this plugin writes for agents. The test
     * task sets it, so an IDE started by the test suite never publishes its endpoint and bearer
     * token into the developer's real `~/.mockkhttp` — where the agents they use look, and where
     * a file whose process has died would sit until the real IDE happened to prune it (audit
     * round 11, AR).
     */
    const val HOME_PROPERTY = "mockkhttp.home"

    /** `~/.mockkhttp` (or [HOME_PROPERTY]) — the root of every path this plugin writes for the agent channel. */
    fun home(): Path {
        val override = System.getProperty(HOME_PROPERTY)?.takeIf { it.isNotBlank() }
        return if (override != null) Paths.get(override) else Paths.get(System.getProperty("user.home"), ".mockkhttp")
    }

    /**
     * What [harden] is locking down. It decides the POSIX mode (a directory needs `x` to be
     * traversed, a launcher needs `x` to be run) and whether ACL inheritance flags are legal —
     * `FILE_INHERIT`/`DIRECTORY_INHERIT` are only valid on a directory and are rejected on a file.
     */
    enum class PathKind(internal val posixMode: String) {
        DIRECTORY("rwx------"),
        FILE("rw-------"),
        EXECUTABLE("rwx------")
    }

    /** Restrict [path] to its owner. Never throws; the outcome is in the result. */
    fun harden(path: Path, kind: PathKind): HardenResult {
        if (posixSupported) {
            return try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(kind.posixMode))
                HardenResult(Hardening.POSIX, "owner-only (POSIX)")
            } catch (e: Exception) {
                HardenResult(
                    Hardening.FAILED,
                    "Could not restrict $path to your user (${e.javaClass.simpleName}: ${e.message}). " +
                            "MockkHttp will not write an agent token to a file it cannot protect."
                )
            }
        }

        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
            ?: return HardenResult(
                Hardening.DEGRADED,
                "$path is on a filesystem that supports neither POSIX permissions nor ACLs, so the " +
                        "MockkHttp agent token is readable by anyone who can read your user profile."
            )

        return try {
            val entry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(view.owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
                .setFlags(
                    if (kind == PathKind.DIRECTORY) {
                        EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                    } else {
                        EnumSet.noneOf(AclEntryFlag::class.java)
                    }
                )
                .build()
            view.acl = listOf(entry)
            HardenResult(Hardening.ACL, "owner-only (ACL)")
        } catch (e: Exception) {
            HardenResult(
                Hardening.DEGRADED,
                "Could not apply an owner-only ACL to $path (${e.javaClass.simpleName}: ${e.message}). " +
                        "The MockkHttp agent token is readable by anyone who can read your user profile."
            )
        }
    }

    /** Create [dir] if needed and harden it. Never throws. */
    fun ensureDirectory(dir: Path): HardenResult {
        return try {
            Files.createDirectories(dir)
            harden(dir, PathKind.DIRECTORY)
        } catch (e: Exception) {
            HardenResult(
                Hardening.FAILED,
                "Could not create $dir (${e.javaClass.simpleName}: ${e.message})."
            )
        }
    }

    /**
     * Write [bytes] to [target] so that a reader never sees a half-written file: a private temp
     * file in the same directory, `fsync`, then an atomic rename. The permissions are applied to
     * the temp file, so the published name is never briefly world-readable.
     *
     * @return null on success, or the reason it failed.
     */
    fun writeAtomically(target: Path, bytes: ByteArray, kind: PathKind = PathKind.FILE): String? {
        val dir = target.parent ?: return "$target has no parent directory"
        var tmp: Path? = null
        return try {
            val temp = Files.createTempFile(dir, ".${target.fileName}-", ".tmp")
            tmp = temp
            val permissions = harden(temp, kind)
            if (permissions.refused) return permissions.detail

            FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
                channel.write(ByteBuffer.wrap(bytes))
                // fsync before the rename: an atomic rename of a file whose contents are still in
                // the page cache still publishes an empty file after a hard reset.
                channel.force(true)
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            tmp = null
            null
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            val leftover = tmp
            if (leftover != null) {
                try {
                    Files.deleteIfExists(leftover)
                } catch (ignored: Exception) {
                    // A stray temp file is not worth a second failure path.
                }
            }
        }
    }

    /** True when a process with [pid] is still running. Used to prune instance files. */
    fun isPidAlive(pid: Long): Boolean =
        try {
            val handle = ProcessHandle.of(pid)
            handle.isPresent && handle.get().isAlive
        } catch (e: Exception) {
            // A pid outside the platform's range throws rather than returning an empty Optional.
            false
        }
}

// ============================================================================
// The instance file
// ============================================================================

/**
 * Publishes `~/.mockkhttp/instances/<instanceId>.json` — the **only** thing that connects a
 * `claude` process in a terminal to this IDE (plan §8).
 *
 * ## Why a file and not a well-known port
 *
 * The committed `.mcp.json` contains no port, no token and no project id, so it survives IDE
 * restarts, port walks and token rotation, and is safe to share with a team. The bridge instead
 * globs this directory at launch, drops files whose pid is dead, and longest-prefix-matches its
 * working directory against every entry in [InstanceFile.projects]. Everything volatile lives
 * here, written by the process that owns it.
 *
 * ## Contract with the rest of the plugin
 *
 * This service **pulls** nothing from the control plane. `AgentControlServer` pushes the endpoint
 * in with [setControlEndpoint] after it binds and again after every token rotation, and calls
 * [clearControlEndpoint] when agent control is switched off. That keeps the two independently
 * testable and means a change to the auth layer cannot silently break discovery.
 *
 * Every write is atomic (temp + `fsync` + `ATOMIC_MOVE`, see [AgentFiles.writeAtomically]) because
 * a bridge launching at the same instant must read either the previous file or the new one, never
 * a truncated one. Nothing here throws: discovery failing must never take the IDE down with it.
 */
@Service(Service.Level.APP)
class InstanceRegistry : Disposable {

    private val log = Logger.getInstance(InstanceRegistry::class.java)

    // serializeNulls: the bridge distinguishes "not bound by anyone" (heldByPid: null) from a
    // field this plugin version does not know about, and it can only do that if we emit the null.
    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .disableHtmlEscaping()
        .create()

    /** Serialises writers; every publish and every prune goes through it. */
    private val writeLock = Any()

    private val pid: Long = ProcessHandle.current().pid()

    /** Stable for the life of the process; the file name and the bridge's `instanceId:` prefix. */
    val instanceId: String = "ij-$pid-${randomSuffix()}"

    private val startedAt: String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

    @Volatile
    private var control: ControlEndpoint? = null

    @Volatile
    private var bridge: BridgeRef? = null

    @Volatile
    private var sessionInfoProvider: ProjectSessionInfoProvider? = ProjectSessionInfoProvider { project ->
        // The truthful source: the session service knows the device and whether a session is
        // really running, which the interceptor registration alone cannot tell. Wired here, at
        // construction, so it cannot be forgotten by a caller — it was, for a whole milestone.
        val state = com.sergiy.dev.mockkhttp.session.CaptureSessionService.getInstance(project).state()
        // The device is reported only while a session runs, exactly as the control plane's
        // session DTO does: the one the UI has selected survives a stop, and two sources
        // answering "which device" differently is worse than one of them saying nothing.
        val device = state.device?.takeIf { state.running }
        ProjectSessionInfo(
            running = state.running,
            mode = state.mode?.name,
            packageFilter = state.packageFilter,
            deviceSerial = device?.serialNumber,
            devicePlatform = device?.platform?.name
        )
    }

    /** Non-null when the file is published but could not be locked down. Shown in the Agent tab. */
    @Volatile
    private var permissionWarning: String? = null

    /** Non-null when publishing was REFUSED outright; agent control is then unreachable. */
    @Volatile
    private var publishRefusal: String? = null

    @Volatile
    private var published: Boolean = false

    @Volatile
    private var disposed: Boolean = false

    init {
        // A dead IDE's file is otherwise pruned only when some live IDE next rewrites its own,
        // which can be hours later. Sweeping once at startup keeps the directory honest from the
        // first second of this process, endpoint or not (audit round 11, AR).
        scheduleSweep()
    }

    /** Prune instance files whose process is gone, without publishing anything of our own. */
    fun sweepStale(): Int {
        if (disposed) return 0
        val dir = instancesDirectory()
        if (!Files.isDirectory(dir)) return 0
        synchronized(writeLock) {
            val before = countInstanceFiles(dir)
            pruneAndReadPeers(dir)
            return (before - countInstanceFiles(dir)).coerceAtLeast(0)
        }
    }

    private fun scheduleSweep() {
        try {
            AppExecutorUtil.getAppExecutorService().execute {
                try {
                    val pruned = sweepStale()
                    if (pruned > 0) log.info("🤖 🔍 Pruned $pruned stale instance file(s) at startup")
                } catch (e: Exception) {
                    log.debug("Startup sweep of instance files failed", e)
                }
            }
        } catch (e: Exception) {
            log.debug("Could not schedule the startup sweep of instance files", e)
        }
    }

    private fun countInstanceFiles(dir: Path): Int = try {
        Files.newDirectoryStream(dir, "*.json").use { it.count() }
    } catch (e: Exception) {
        0
    }

    companion object {
        /** Bumped only when the file's shape changes incompatibly for the bridge. */
        const val SCHEMA_VERSION: Int = 1

        private const val PLUGIN_ID = "com.sergiy.dev.MockkHttp"

        fun getInstance(): InstanceRegistry =
            ApplicationManager.getApplication().getService(InstanceRegistry::class.java)

        /** `~/.mockkhttp/instances` — one file per running IDE. */
        fun instancesDirectory(): Path = AgentFiles.home().resolve("instances")

        private fun randomSuffix(): String {
            val bytes = ByteArray(3)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    // ------------------------------------------------------------------
    // What the rest of the plugin pushes in
    // ------------------------------------------------------------------

    /** The bound control plane. Call after `HttpServer` binds and after every token rotation. */
    data class ControlEndpoint(
        val baseUrl: String,
        val token: String,
        val agentControl: String = AGENT_CONTROL_FULL,
        val scheme: String = "Bearer"
    )

    /** The vendored bridge, as reported by [BridgeVendor]. */
    data class BridgeRef(val path: String, val sha256: String)

    /**
     * The per-project session facts the instance file advertises.
     *
     * M1 fills what the interceptor registration already knows (mode, package filter). The device
     * and a truthful `running` flag arrive with `CaptureSessionService` in M4, which is why this
     * is a pluggable provider rather than a direct call: M4 can supply richer data without
     * touching this file.
     */
    data class ProjectSessionInfo(
        val running: Boolean,
        val mode: String?,
        val packageFilter: String?,
        val deviceSerial: String? = null,
        val devicePlatform: String? = null
    )

    fun interface ProjectSessionInfoProvider {
        fun sessionInfoFor(project: Project): ProjectSessionInfo?
    }

    /** Install (or clear, with null) the richer session source. Idempotent. */
    fun setSessionInfoProvider(provider: ProjectSessionInfoProvider?) {
        sessionInfoProvider = provider
    }

    /**
     * Publish [endpoint] and rewrite the file immediately.
     *
     * @return true when the file is on disk and readable only by this user.
     */
    fun setControlEndpoint(endpoint: ControlEndpoint): Boolean {
        control = endpoint
        return refresh("control endpoint published")
    }

    /** Record the vendored bridge so the bridge can verify the jar it was told to run. */
    fun setBridge(jarPath: Path?, sha256: String?) {
        bridge = if (jarPath != null && sha256 != null) BridgeRef(jarPath.toString(), sha256) else null
        scheduleRefresh("bridge vendored")
    }

    /**
     * Stop advertising this IDE: delete the file so every connected bridge loses the endpoint on
     * its next launch. Used by agent control OFF and by Revoke.
     */
    fun clearControlEndpoint() {
        control = null
        synchronized(writeLock) {
            deleteOwnFile()
            published = false
        }
        log.info("🤖 Agent discovery withdrawn: ${instanceFile()} deleted")
    }

    // ------------------------------------------------------------------
    // Publishing
    // ------------------------------------------------------------------

    /** Absolute path of this process's instance file, whether or not it currently exists. */
    fun instanceFile(): Path = instancesDirectory().resolve("$instanceId.json")

    /** True when the file is currently on disk. */
    fun isPublished(): Boolean = published

    /** Loud, persistent warning for the Agent tab, or null when the file is properly locked down. */
    fun permissionWarning(): String? = permissionWarning

    /** Why discovery is unavailable, or null. Non-null means no bridge can find this IDE. */
    fun publishRefusal(): String? = publishRefusal

    /**
     * Rewrite the file off the calling thread. Use this from listeners and UI callbacks —
     * [projectClosed][InstanceFileProjectListener] runs on the EDT and must not touch the disk.
     */
    fun scheduleRefresh(reason: String) {
        if (disposed) return
        try {
            AppExecutorUtil.getAppExecutorService().execute { refresh(reason) }
        } catch (e: Exception) {
            // The pool is gone (shutdown): a stale file is pruned by the next process to start.
            log.debug("Could not schedule instance-file refresh ($reason)", e)
        }
    }

    /**
     * Rewrite the instance file from live state and prune dead peers.
     *
     * Safe to call from any thread and at any frequency; writes are serialised and cheap. Returns
     * false when nothing was published — no control endpoint yet, agent control off, or the
     * directory could not be locked down.
     */
    fun refresh(reason: String): Boolean {
        if (disposed) return false

        val endpoint = control
        if (endpoint == null || endpoint.agentControl == AGENT_CONTROL_OFF) {
            synchronized(writeLock) {
                if (published) {
                    deleteOwnFile()
                    published = false
                }
            }
            return false
        }

        synchronized(writeLock) {
            // Re-checked under the lock: a refresh that passed the check above and then queued
            // behind dispose() must not republish the endpoint dispose() just withdrew — with
            // `disposed` set, nothing would ever retract that file again.
            if (disposed) return false
            val dir = instancesDirectory()
            val dirResult = AgentFiles.ensureDirectory(dir)
            if (dirResult.refused) {
                refusePublishing(dirResult.detail)
                return false
            }

            // Prune first: the pruned peers are also how heldByPid is answered.
            val peers = pruneAndReadPeers(dir)
            val file = buildInstanceFile(peers)
            val bytes = gson.toJson(file).toByteArray(StandardCharsets.UTF_8)

            val failure = AgentFiles.writeAtomically(instanceFile(), bytes)
            if (failure != null) {
                refusePublishing("Could not write ${instanceFile()}: $failure")
                return false
            }

            publishRefusal = null
            val warning = if (dirResult.degraded) dirResult.detail else null
            // Only shout when the state CHANGES. refresh() runs on every project open/close and
            // every mode switch; a warning repeated on each of those is noise the user learns to
            // scroll past, which is the opposite of what a persistent warning is for.
            if (warning != null && warning != permissionWarning) {
                warnEveryProject("🤖 AGENT ⚠️ $warning")
            }
            permissionWarning = warning
            if (!published) {
                log.info("🤖 ✅ Agent discovery published: ${instanceFile()} ($reason)")
            } else {
                log.debug("🤖 Instance file refreshed ($reason)")
            }
            published = true
        }

        return true
    }

    /**
     * A refusal is not an internal error — a locked-down home directory or a full disk causes it —
     * so it is reported, never raised as an IDE exception.
     */
    private fun refusePublishing(detail: String) {
        val firstTime = publishRefusal != detail
        publishRefusal = detail
        if (published) {
            deleteOwnFile()
            published = false
        }
        log.warn("🤖 ❌ Agent discovery refused: $detail")
        // Same rule as the permission warning: report the transition, not every retry.
        if (firstTime) warnEveryProject("🤖 AGENT ❌ Discovery unavailable: $detail")
    }

    private fun deleteOwnFile() {
        try {
            Files.deleteIfExists(instanceFile())
        } catch (e: Exception) {
            log.warn("🤖 ⚠️ Could not delete ${instanceFile()}", e)
        }
    }

    private fun buildInstanceFile(peers: List<PeerProbe>): InstanceFile {
        val endpoint = control
        val bound = try {
            GlobalOkHttpInterceptorServer.getInstance().isBound()
        } catch (e: Exception) {
            // Service lookup can fail while the application is tearing down.
            false
        }

        // We can only see our OWN socket. When we do not hold 9876, the process that does is
        // almost always another IDE running this same plugin — and it says so in its own instance
        // file. That is far cheaper (and far more portable) than shelling out to lsof/netstat, and
        // it answers the only question the bridge actually asks: "who should I talk to instead?".
        val holder = if (bound) null else peers.firstOrNull { it.interceptor?.bound == true }?.pid

        return InstanceFile(
            schema = SCHEMA_VERSION,
            apiVersion = API_VERSION,
            instanceId = instanceId,
            pid = pid,
            startedAt = startedAt,
            ide = IdeInfo(
                name = ideName(),
                build = ideBuild(),
                pluginVersion = pluginVersion()
            ),
            control = ControlInfo(
                baseUrl = endpoint?.baseUrl.orEmpty(),
                token = endpoint?.token.orEmpty(),
                scheme = endpoint?.scheme ?: "Bearer"
            ),
            bridge = bridge?.let { BridgeInfo(path = it.path, sha256 = it.sha256) },
            interceptor = InterceptorInfo(
                port = GlobalOkHttpInterceptorServer.SERVER_PORT,
                bound = bound,
                ownedByThisProcess = bound,
                heldByPid = holder
            ),
            agentControl = endpoint?.agentControl ?: AGENT_CONTROL_FULL,
            projects = collectProjects(bound)
        )
    }

    private fun collectProjects(interceptorBound: Boolean): List<ProjectInfo> {
        val registrations = try {
            GlobalOkHttpInterceptorServer.getInstance().getRegisteredProjects().associateBy { it.projectId }
        } catch (e: Exception) {
            emptyMap()
        }
        val provider = sessionInfoProvider

        val projects = try {
            ProjectManager.getInstance().openProjects
        } catch (e: Exception) {
            // Called during shutdown, when the project manager may already be gone.
            return emptyList()
        }

        return projects.mapNotNull { project ->
            // projectClosed() fires after the project leaves openProjects but before it is
            // disposed, so both guards are needed to keep the bridge from resolving a dead project.
            if (project.isDisposed || project.isDefault) return@mapNotNull null

            val registration = registrations[project.locationHash]
            val session = try {
                provider?.sessionInfoFor(project)
            } catch (e: Exception) {
                null
            }

            ProjectInfo(
                projectId = project.locationHash,
                name = project.name,
                basePath = project.basePath,
                sessionRunning = session?.running ?: (registration != null && interceptorBound),
                mode = session?.mode ?: registration?.mode?.name,
                packageFilter = session?.packageFilter ?: registration?.packageNameFilter,
                device = session?.deviceSerial?.let {
                    DeviceInfo(serial = it, platform = session.devicePlatform)
                }
            )
        }
    }

    // ------------------------------------------------------------------
    // Peers
    // ------------------------------------------------------------------

    /**
     * Delete every instance file whose owning process is gone and return the ones still alive.
     *
     * A crashed IDE leaves its file behind; without this the bridge would happily connect to a
     * closed port and report "MockkHttp is not running" in the least useful way possible.
     */
    private fun pruneAndReadPeers(dir: Path): List<PeerProbe> {
        val alive = ArrayList<PeerProbe>()
        val own = instanceFile()
        try {
            Files.newDirectoryStream(dir, "*.json").use { stream ->
                for (path in stream) {
                    if (path == own) continue

                    val parsed: PeerProbe? = try {
                        Files.newBufferedReader(path, StandardCharsets.UTF_8).use {
                            gson.fromJson(it, PeerProbe::class.java)
                        }
                    } catch (e: Exception) {
                        log.debug("🤖 Skipping unreadable instance file $path", e)
                        null
                    }
                    val probe: PeerProbe = parsed ?: continue

                    // No readable pid means we cannot prove the owner is gone, and deleting
                    // another process's file on a guess is not our call — a file written by a
                    // NEWER plugin version would land here. Leave it; the owner cleans it up.
                    val peerPid = probe.pid ?: continue

                    if (AgentFiles.isPidAlive(peerPid)) {
                        alive.add(probe)
                    } else {
                        try {
                            Files.deleteIfExists(path)
                            log.info("🤖 🔍 Pruned stale instance file $path (pid $peerPid is gone)")
                        } catch (e: Exception) {
                            log.debug("Could not prune $path", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.debug("🤖 Could not scan $dir for stale instance files", e)
        }
        return alive
    }

    // ------------------------------------------------------------------
    // IDE identity
    // ------------------------------------------------------------------

    private fun ideName(): String =
        try {
            ApplicationNamesInfo.getInstance().fullProductName
        } catch (e: Exception) {
            "IntelliJ Platform"
        }

    private fun ideBuild(): String =
        try {
            ApplicationInfo.getInstance().build.asString()
        } catch (e: Exception) {
            "unknown"
        }

    private fun pluginVersion(): String = com.sergiy.dev.mockkhttp.MockkHttpBuild.VERSION

    /**
     * Mirror a security-relevant message into every open project's Logs tab. The IDE log is where
     * this belongs technically, but nobody reads it — and a token nobody can protect is precisely
     * the thing the user must see.
     */
    private fun warnEveryProject(message: String) {
        // Off the caller's thread on purpose: every call site holds [writeLock], and logging fans
        // out to UI listeners. Taking a lock and then calling into Swing is how deadlocks start.
        val task = Runnable {
            val projects = try {
                ProjectManager.getInstance().openProjects
            } catch (e: Exception) {
                return@Runnable
            }
            for (project in projects) {
                if (project.isDisposed || project.isDefault) continue
                try {
                    MockkHttpLogger.getInstance(project).warn(message)
                } catch (e: Exception) {
                    // Project services can be gone mid-shutdown; the IDE log already has this.
                }
            }
        }
        try {
            AppExecutorUtil.getAppExecutorService().execute(task)
        } catch (e: Exception) {
            log.debug("Could not mirror an agent warning to the Logs tab", e)
        }
    }

    override fun dispose() {
        synchronized(writeLock) {
            disposed = true
            // No endpoint survives disposal: even a refresh already past its checks has nothing
            // left to publish.
            control = null
            deleteOwnFile()
            published = false
        }
    }

    // ------------------------------------------------------------------
    // Wire shape — plan §8, "~/.mockkhttp/instances/<instanceId>.json"
    // ------------------------------------------------------------------

    data class InstanceFile(
        @SerializedName("schema") val schema: Int,
        @SerializedName("apiVersion") val apiVersion: String,
        @SerializedName("instanceId") val instanceId: String,
        @SerializedName("pid") val pid: Long,
        @SerializedName("startedAt") val startedAt: String,
        @SerializedName("ide") val ide: IdeInfo,
        @SerializedName("control") val control: ControlInfo,
        @SerializedName("bridge") val bridge: BridgeInfo?,
        @SerializedName("interceptor") val interceptor: InterceptorInfo,
        @SerializedName("agentControl") val agentControl: String,
        @SerializedName("projects") val projects: List<ProjectInfo>
    )

    data class IdeInfo(
        @SerializedName("name") val name: String,
        @SerializedName("build") val build: String,
        @SerializedName("pluginVersion") val pluginVersion: String
    )

    data class ControlInfo(
        @SerializedName("baseUrl") val baseUrl: String,
        @SerializedName("token") val token: String,
        @SerializedName("scheme") val scheme: String
    )

    data class BridgeInfo(
        @SerializedName("path") val path: String,
        @SerializedName("sha256") val sha256: String
    )

    data class InterceptorInfo(
        @SerializedName("port") val port: Int,
        @SerializedName("bound") val bound: Boolean,
        @SerializedName("ownedByThisProcess") val ownedByThisProcess: Boolean,
        @SerializedName("heldByPid") val heldByPid: Long?
    )

    data class ProjectInfo(
        @SerializedName("projectId") val projectId: String,
        @SerializedName("name") val name: String,
        @SerializedName("basePath") val basePath: String?,
        @SerializedName("sessionRunning") val sessionRunning: Boolean,
        @SerializedName("mode") val mode: String?,
        @SerializedName("packageFilter") val packageFilter: String?,
        @SerializedName("device") val device: DeviceInfo?
    )

    data class DeviceInfo(
        @SerializedName("serial") val serial: String,
        @SerializedName("platform") val platform: String?
    )

    /**
     * What we read back out of *another* process's file.
     *
     * Every field is nullable on purpose: Gson builds objects through `Unsafe` and never runs the
     * Kotlin constructor, so a non-null type here would be a lie the moment an older (or newer)
     * plugin version writes a file without the field.
     */
    private data class PeerProbe(
        @SerializedName("pid") val pid: Long? = null,
        @SerializedName("instanceId") val instanceId: String? = null,
        @SerializedName("interceptor") val interceptor: PeerInterceptor? = null
    )

    private data class PeerInterceptor(
        @SerializedName("port") val port: Int? = null,
        @SerializedName("bound") val bound: Boolean? = null
    )
}
