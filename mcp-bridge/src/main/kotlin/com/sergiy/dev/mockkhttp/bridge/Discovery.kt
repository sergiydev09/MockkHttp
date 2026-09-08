package com.sergiy.dev.mockkhttp.bridge

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Finds the IDE to talk to, without any of it being written down in a committed file.
 *
 * Every running MockkHttp plugin writes `~/.mockkhttp/instances/<instanceId>.json` (mode 0600)
 * containing its loopback base URL, its per-start bearer token and the projects it has open. This
 * class globs that directory, drops the files whose process is gone, and picks the project whose
 * `basePath` is the LONGEST prefix of the working directory — so `claude` started anywhere inside a
 * repo lands on that repo's IDE, and a second IDE open next door is never touched.
 *
 * The file is re-read on every bridge launch (and again after a connection failure), which is what
 * makes an IDE restart — new ephemeral port, freshly rotated token — invisible to the user: the
 * committed `.mcp.json` contains no port, no token and no project id to go stale.
 */
object Discovery {

    /** Set by `.mcp.json` to `${CLAUDE_PROJECT_DIR}`; the process cwd is the fallback. */
    private const val ENV_PROJECT_DIR = "MOCKKHTTP_PROJECT_DIR"

    /** Pin a project explicitly: projectId, project name, or `instanceId:projectId`. */
    private const val ENV_PROJECT = "MOCKKHTTP_PROJECT"

    /** Bypass discovery entirely (CI, devcontainer, SSH port-forward). Both are required together. */
    private const val ENV_BASE_URL = "MOCKKHTTP_BASE_URL"
    private const val ENV_TOKEN = "MOCKKHTTP_TOKEN"

    /** Test/relocation hook for the `~/.mockkhttp` root. */
    private const val ENV_HOME = "MOCKKHTTP_HOME"

    /** One project of one IDE instance, as the instance file describes it. */
    data class ProjectRef(
        val projectId: String,
        val name: String?,
        val basePath: String?,
        val sessionRunning: Boolean?,
        val mode: String?
    )

    /** One running IDE, as its instance file describes it. */
    data class Instance(
        val instanceId: String?,
        val pid: Long?,
        val baseUrl: String,
        val token: String,
        val scheme: String,
        val ide: String?,
        val pluginVersion: String?,
        val agentControl: String?,
        val projects: List<ProjectRef>,
        val file: Path?
    )

    /** Where a call should go, and why it goes there. */
    data class Target(
        val baseUrl: String,
        val token: String,
        val scheme: String,
        val instanceId: String?,
        val ide: String?,
        val pluginVersion: String?,
        val agentControl: String?,
        val project: ProjectRef?,
        /** cwd-prefix | env-pin | explicit-arg | base-url-override | sole-open-project */
        val matchedBy: String,
        val cwd: String,
        val liveInstances: Int,
        /** Why [project] is null, phrased as something the caller can act on. */
        val projectProblem: String?
    )

    sealed class Resolution {
        data class Found(val target: Target, val instances: List<Instance>) : Resolution()
        data class Missing(val message: String) : Resolution()
    }

    @Volatile
    private var cached: Resolution? = null

    /** Resolved once per process, then re-resolved after [invalidate] — never on a timer. */
    fun current(): Resolution {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: resolve().also { resolution ->
                // Only a WORKING resolution is remembered. "No instance" and "which project?" are
                // both states the user fixes while the agent is running — by opening the IDE, or by
                // opening the project — and a cached failure would keep answering with a stale no.
                if (resolution is Resolution.Found && resolution.target.projectProblem == null) {
                    cached = resolution
                }
            }
        }
    }

    /**
     * Forget the resolution. Called when the control plane stops answering or rejects the token:
     * the IDE most likely restarted, and the new port and token are already on disk.
     */
    fun invalidate() {
        cached = null
    }

    /** Working directory of the agent, which is what the whole project match hangs off. */
    fun workingDirectory(): String =
        env(ENV_PROJECT_DIR) ?: System.getProperty("user.dir") ?: "."

    /** Value of `MOCKKHTTP_PROJECT`, for the paths that resolve the project over the wire instead. */
    fun projectPin(): String? = env(ENV_PROJECT)

    /**
     * Re-point at whichever live instance owns [wanted] (a projectId, a name or a basePath).
     * Returns null when no instance claims it — the caller then sends the string as-is and lets the
     * control plane's own resolver answer, which knows about projects opened since we read the file.
     */
    fun forProject(wanted: String): Target? {
        val found = current() as? Resolution.Found ?: return null

        // Collect EVERY claimant, never just the first. Instance files are enumerated with
        // Files.newDirectoryStream, whose order is unspecified, and two IDE windows on two git
        // worktrees of the same repo both report the directory name — so returning the first match
        // meant `project:"MyApp"` bound to whichever file the OS happened to list first, and the
        // write landed in the wrong window with no way for anyone to notice.
        val claimants = found.instances.mapNotNull { instance ->
            instance.projects.firstOrNull { matchesPin(instance, it, wanted) }?.let { instance to it }
        }

        if (claimants.size > 1) {
            val detail = claimants.joinToString("; ") { (instance, project) ->
                "${instance.instanceId}:${project.projectId} (${project.basePath})"
            }
            throw BridgeException(
                "\"$wanted\" matches ${claimants.size} open projects: $detail. " +
                        "Pass the unambiguous instanceId:projectId form instead — guessing between " +
                        "them would send this call to the wrong IDE window."
            )
        }

        val (instance, project) = claimants.firstOrNull() ?: return null
        return target(instance, project, "explicit-arg", found.target.cwd, found.instances.size, null)
    }

    // ── resolution ────────────────────────────────────────────────────────────────────────────────

    private fun resolve(): Resolution {
        val cwd = workingDirectory()

        // 1. Explicit endpoint wins over everything: it exists precisely for the cases where no
        //    instance file can be read (container, remote dev, another user's machine).
        val baseUrl = env(ENV_BASE_URL)
        val token = env(ENV_TOKEN)
        if (baseUrl != null && token != null) {
            return Resolution.Found(
                Target(
                    baseUrl = normalizeBaseUrl(baseUrl),
                    token = token,
                    scheme = "Bearer",
                    instanceId = null,
                    ide = null,
                    pluginVersion = null,
                    agentControl = null,
                    project = null,
                    matchedBy = "base-url-override",
                    cwd = cwd,
                    liveInstances = 0,
                    // Resolved lazily over the wire by RestClient: the override says nothing about
                    // which projects that IDE has open.
                    projectProblem = null
                ),
                emptyList()
            )
        }
        if (baseUrl != null || token != null) {
            return Resolution.Missing(
                "MOCKKHTTP_BASE_URL and MOCKKHTTP_TOKEN must be set together; only " +
                    (if (baseUrl != null) ENV_BASE_URL else ENV_TOKEN) + " is set. " +
                    "Unset both to use automatic discovery, or set the missing one."
            )
        }

        val instancesDir = home().resolve("instances")
        val scan = scan(instancesDir)
        if (scan.live.isEmpty()) return Resolution.Missing(missingMessage(cwd, instancesDir, scan))

        val pin = env(ENV_PROJECT)
        if (pin != null) {
            for (instance in scan.live) {
                val project = instance.projects.firstOrNull { matchesPin(instance, it, pin) }
                if (project != null) {
                    return Resolution.Found(
                        target(instance, project, "env-pin", cwd, scan.live.size, null),
                        scan.live
                    )
                }
            }
            return Resolution.Missing(
                "MOCKKHTTP_PROJECT is set to \"$pin\" but no open project matches it. Open projects: " +
                    describeProjects(scan.live) + ". Use the project name or its base path, or unset " +
                    "MOCKKHTTP_PROJECT to resolve from the working directory."
            )
        }

        // 2. Longest-prefix match of the working directory against every open project's basePath.
        val here = normalize(cwd)
        var bestInstance: Instance? = null
        var bestProject: ProjectRef? = null
        var bestDepth = -1
        if (here != null) {
            for (instance in scan.live) {
                for (project in instance.projects) {
                    val base = normalize(project.basePath) ?: continue
                    if (!here.startsWith(base)) continue
                    // Deeper base path wins, so a nested module project beats its parent repo.
                    if (base.nameCount > bestDepth) {
                        bestDepth = base.nameCount
                        bestInstance = instance
                        bestProject = project
                    }
                }
            }
        }
        if (bestInstance != null && bestProject != null) {
            return Resolution.Found(
                target(bestInstance, bestProject, "cwd-prefix", cwd, scan.live.size, null),
                scan.live
            )
        }

        // 3. Nothing matched. One IDE with one project open is unambiguous enough to use anyway —
        //    running `claude` from a sibling directory is a common and harmless mistake — but say so
        //    in matched_by, which mockkhttp_status echoes back for the model to check.
        val onlyInstance = scan.live.singleOrNull()
        val onlyProject = onlyInstance?.projects?.singleOrNull()
        if (onlyInstance != null && onlyProject != null) {
            return Resolution.Found(
                target(onlyInstance, onlyProject, "sole-open-project", cwd, scan.live.size, null),
                scan.live
            )
        }

        val problem = "The working directory $cwd is not inside any open project. Open projects: " +
            describeProjects(scan.live) + ". Pass project:\"<name>\" on the tool call, cd into the " +
            "project root, or add MOCKKHTTP_PROJECT to the env block of .mcp.json."
        val fallback = scan.live.first()
        return Resolution.Found(
            target(fallback, null, "unresolved", cwd, scan.live.size, problem),
            scan.live
        )
    }

    private fun target(
        instance: Instance,
        project: ProjectRef?,
        matchedBy: String,
        cwd: String,
        liveInstances: Int,
        problem: String?
    ) = Target(
        baseUrl = instance.baseUrl,
        token = instance.token,
        scheme = instance.scheme,
        instanceId = instance.instanceId,
        ide = instance.ide,
        pluginVersion = instance.pluginVersion,
        agentControl = instance.agentControl,
        project = project,
        matchedBy = matchedBy,
        cwd = cwd,
        liveInstances = liveInstances,
        projectProblem = problem
    )

    private fun matchesPin(instance: Instance, project: ProjectRef, wanted: String): Boolean {
        if (project.projectId.equals(wanted, ignoreCase = true)) return true
        if (project.name != null && project.name.equals(wanted, ignoreCase = true)) return true
        if (project.basePath != null && project.basePath.equals(wanted, ignoreCase = true)) return true
        val qualified = "${instance.instanceId}:${project.projectId}"
        return qualified.equals(wanted, ignoreCase = true)
    }

    // ── instance files ────────────────────────────────────────────────────────────────────────────

    private class Scan(
        val live: List<Instance>,
        val deadPids: List<Long>,
        val unreadable: Int,
        val dirExists: Boolean
    )

    private fun scan(dir: Path): Scan {
        if (!Files.isDirectory(dir)) return Scan(emptyList(), emptyList(), 0, false)
        val live = ArrayList<Instance>()
        val dead = ArrayList<Long>()
        var unreadable = 0
        try {
            Files.newDirectoryStream(dir, "*.json").use { stream ->
                for (file in stream) {
                    val instance = try {
                        read(file)
                    } catch (_: Throwable) {
                        null
                    }
                    if (instance == null) {
                        unreadable++
                        continue
                    }
                    // A file left behind by a crashed IDE points at a port nobody is listening on;
                    // worse, that port may have been recycled by something else entirely.
                    if (isAlive(instance.pid)) live.add(instance) else instance.pid?.let { dead.add(it) }
                }
            }
        } catch (_: Throwable) {
            // An unreadable directory is reported as "no live instance", never as a crash.
        }
        return Scan(live, dead, unreadable, true)
    }

    private fun read(file: Path): Instance? {
        val text = Files.readString(file, StandardCharsets.UTF_8)
        val root = JsonParser.parseString(text) as? JsonObject ?: return null
        val control = root.optObject("control") ?: return null
        val baseUrl = control.optString("baseUrl")?.takeIf { it.isNotBlank() } ?: return null
        val token = control.optString("token")?.takeIf { it.isNotBlank() } ?: return null
        val ide = root.optObject("ide")

        val projects = ArrayList<ProjectRef>()
        root.optArray("projects")?.forEach { element ->
            val project = element as? JsonObject ?: return@forEach
            val id = project.optString("projectId")?.takeIf { it.isNotBlank() } ?: return@forEach
            projects.add(
                ProjectRef(
                    projectId = id,
                    name = project.optString("name"),
                    basePath = project.optString("basePath"),
                    sessionRunning = project.optBoolean("sessionRunning"),
                    mode = project.optString("mode")
                )
            )
        }

        return Instance(
            instanceId = root.optString("instanceId"),
            pid = root.optLong("pid"),
            baseUrl = normalizeBaseUrl(baseUrl),
            token = token,
            scheme = control.optString("scheme")?.takeIf { it.isNotBlank() } ?: "Bearer",
            ide = listOfNotNull(ide?.optString("name"), ide?.optString("build"))
                .joinToString(" ")
                .takeIf { it.isNotBlank() },
            pluginVersion = ide?.optString("pluginVersion"),
            agentControl = root.optString("agentControl"),
            projects = projects,
            file = file
        )
    }

    private fun isAlive(pid: Long?): Boolean {
        // No pid recorded: trust the file rather than discard a usable instance.
        if (pid == null || pid <= 0) return true
        return try {
            ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        } catch (_: Throwable) {
            true
        }
    }

    private fun missingMessage(cwd: String, dir: Path, scan: Scan): String {
        val detail = when {
            !scan.dirExists ->
                "$dir does not exist, so no MockkHttp plugin with agent control has ever started on " +
                    "this machine. Update MockkHttp in Settings -> Plugins (1.8.0 or newer) and restart the IDE."
            scan.deadPids.isNotEmpty() ->
                "$dir holds ${scan.deadPids.size} instance file(s), all written by processes that have " +
                    "exited (pid ${scan.deadPids.joinToString(", ")}). Reopen the IDE — the bridge re-reads " +
                    "the file on every launch, so the new port and token need no configuration change."
            scan.unreadable > 0 ->
                "$dir holds ${scan.unreadable} file(s) this bridge could not parse. They may be from a " +
                    "newer plugin than this bridge jar; update MockkHttp and restart the IDE."
            else ->
                "$dir is empty. Agent control may be switched off in MockkHttp -> Settings -> Agent Control."
        }
        return "No live MockkHttp instance found for $cwd; open the project in Android Studio, or set " +
            "MOCKKHTTP_BASE_URL and MOCKKHTTP_TOKEN.\n$detail"
    }

    private fun describeProjects(instances: List<Instance>): String {
        val all = instances.flatMap { it.projects }
        if (all.isEmpty()) return "(none — the IDE is running but has no project open)"
        return all.joinToString(", ") { "${it.name ?: it.projectId} at ${it.basePath ?: "unknown path"}" }
    }

    // ── paths & env ───────────────────────────────────────────────────────────────────────────────

    private fun home(): Path {
        env(ENV_HOME)?.let { custom ->
            normalize(custom)?.let { return it }
        }
        val userHome = System.getProperty("user.home") ?: "."
        return Path.of(userHome, ".mockkhttp")
    }

    /**
     * Absolute, symlink-resolved where possible. Both sides of the prefix test go through this, so a
     * `/var` vs `/private/var` mismatch on macOS cannot silently break the match.
     */
    internal fun normalize(raw: String?): Path? {
        if (raw.isNullOrBlank()) return null
        return try {
            val path = Path.of(raw).toAbsolutePath().normalize()
            try {
                path.toRealPath()
            } catch (_: Throwable) {
                path
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        // Tolerate someone pasting the origin without the version prefix.
        return if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
    }

    /**
     * Every environment read in this file goes through here.
     *
     * It is a `var` for exactly one reason: the resolution rules below — longest-prefix cwd
     * matching, the two env overrides, a stale pid — are the part of the bridge that fails
     * SILENTLY (it answers about the wrong IDE), and a JVM cannot change its own environment, so
     * there is no other way to test them. Production never reassigns it.
     */
    internal var environment: (String) -> String? = { name -> System.getenv(name) }

    private fun env(name: String): String? = environment(name)?.trim()?.takeIf { it.isNotEmpty() }
}
