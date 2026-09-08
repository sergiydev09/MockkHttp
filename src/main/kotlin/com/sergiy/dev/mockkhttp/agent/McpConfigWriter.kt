package com.sergiy.dev.mockkhttp.agent

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference

/**
 * Writes the `mcpServers.mockkhttp` entry into `<project>/.mcp.json` — and nothing else.
 *
 * ## Three rules, all of them about not surprising the user
 *
 * 1. **Explicit action only.** This never runs on startup. `.mcp.json` is a file the user's team
 *    commits and reviews; a plugin that edits it because an IDE was opened is a plugin people
 *    uninstall. It is reached from one button in Settings.
 * 2. **Merge, never replace.** Only `mcpServers.mockkhttp` is touched. Every other server, and
 *    every other top-level key, survives verbatim. A file that is not valid JSON is *refused*, not
 *    overwritten — a half-typed config is still the user's work.
 * 3. **Never touch `.gitignore`.** Whether `.mcp.json` is committed is a team decision (plan §8
 *    documents both paths for mixed-OS teams), and it is not this plugin's to make.
 *
 * ## Why it goes through the editor
 *
 * If the file is open, writing its bytes behind the editor's back leaves a stale in-memory
 * Document that overwrites the change the moment the user types. So the merge is applied to the
 * `Document` when one exists, inside a `WriteCommandAction` — which also makes the whole edit a
 * single undo step the user can Ctrl-Z.
 *
 * The refresh-and-read happens *outside* the write action on purpose: a synchronous VFS refresh
 * under the write lock is a platform error.
 *
 * What lands in the file carries no port, no token and no project id (plan §8) — all three are
 * discovered at bridge launch from `~/.mockkhttp/instances/`, which is what makes this file safe
 * to commit and stable across IDE restarts and token rotation.
 */
@Service(Service.Level.APP)
class McpConfigWriter {

    private val log = Logger.getInstance(McpConfigWriter::class.java)

    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()

    companion object {
        const val CONFIG_FILE_NAME: String = ".mcp.json"

        /** The key under `mcpServers`. Stable: renaming it would orphan every committed config. */
        const val SERVER_KEY: String = "mockkhttp"

        /**
         * The launcher path as it must appear *in the file* — with the placeholder intact.
         * Claude Code expands `${HOME}` / `${USERPROFILE}` itself, which is what lets one committed
         * file work for every teammate.
         */
        const val COMMAND_POSIX: String = "\${HOME}/.mockkhttp/bin/mockkhttp-mcp"
        const val COMMAND_WINDOWS: String = "\${USERPROFILE}\\.mockkhttp\\bin\\mockkhttp-mcp.cmd"

        /** Tells the bridge which directory to resolve against, for a `claude` started elsewhere. */
        const val ENV_PROJECT_DIR: String = "MOCKKHTTP_PROJECT_DIR"
        /**
         * Note the `:-` fallback. Claude Code expands `${VAR}` in a `.mcp.json` env value and
         * REJECTS the whole server entry when the variable is unset and offers no default —
         * CLAUDE_PROJECT_DIR is documented for hook commands and is not guaranteed to be exported
         * for an MCP server launch. With the fallback, expansion always succeeds; an empty value is
         * discarded by the bridge's Discovery.env() and it falls back to the process working
         * directory, which Claude Code already sets to the project root. Dropping the `:-` turns
         * "the agent works" into "the MCP server silently never loads".
         */
        const val ENV_PROJECT_DIR_VALUE: String = "\${CLAUDE_PROJECT_DIR:-}"

        fun getInstance(): McpConfigWriter =
            ApplicationManager.getApplication().getService(McpConfigWriter::class.java)
    }

    sealed interface WriteResult {
        /** The file now contains the entry. [created] distinguishes a new file from a merge. */
        data class Written(val path: String, val created: Boolean) : WriteResult

        /** The entry was already exactly right; nothing was touched. */
        data class Unchanged(val path: String) : WriteResult

        /** [reason] is shown to the user; [hint] is the next thing to try, when there is one. */
        data class Failed(val reason: String, val hint: String? = null) : WriteResult
    }

    /**
     * Merge the MockkHttp entry into `<project>/.mcp.json`.
     *
     * Callable from the EDT (a Settings button) or from a background thread; the write itself is
     * always marshalled onto the EDT inside a `WriteCommandAction`. Never throws.
     */
    fun write(project: Project): WriteResult {
        if (project.isDisposed) {
            return WriteResult.Failed("The project is closed.")
        }
        val basePath = project.basePath
            ?: return WriteResult.Failed(
                "This project has no base directory, so there is nowhere to put $CONFIG_FILE_NAME.",
                "Open the app's repository as the project root, then try again."
            )

        val baseDir = Paths.get(basePath)
        val configPath = baseDir.resolve(CONFIG_FILE_NAME)

        // Outside any lock: refreshAndFindFileByNioFile does a synchronous VFS refresh, which the
        // platform forbids under a read or write action.
        val existingFile = try {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(configPath)
        } catch (e: Exception) {
            log.warn("🤖 ⚠️ Could not refresh $configPath", e)
            null
        }

        val existingText = readExisting(existingFile, configPath)
        if (existingText is ReadOutcome.Failure) {
            return WriteResult.Failed(existingText.reason)
        }
        val currentText = (existingText as ReadOutcome.Text).text

        val merged = merge(currentText)
        if (merged is MergeOutcome.Invalid) {
            return WriteResult.Failed(merged.reason, merged.hint)
        }
        val newText = (merged as MergeOutcome.Text).json

        if (currentText != null && currentText == newText) {
            return WriteResult.Unchanged(configPath.toString())
        }

        val created = currentText == null
        val result = applyOnEdt(project) { applyMerge(baseDir, configPath, newText, created) }

        if (result is WriteResult.Written) {
            val summary = if (result.created) "created $CONFIG_FILE_NAME" else "updated $CONFIG_FILE_NAME"
            log.info("🤖 ✅ MCP config $summary at ${result.path}")
            try {
                MockkHttpLogger.getInstance(project)
                    .info("🤖 AGENT ✅ $summary — Claude Code will find this IDE from the project root")
            } catch (e: Exception) {
                // Logs tab unavailable during shutdown; the IDE log already has it.
            }
        }
        return result
    }

    /** The exact JSON block this writer inserts, for a "copy to clipboard" affordance. */
    fun serverEntryJson(): String = gson.toJson(serverEntry())

    /** The whole file as it would be written into an empty project, for the docs and Help tab. */
    fun previewConfigJson(): String {
        val root = JsonObject()
        val servers = JsonObject()
        servers.add(SERVER_KEY, serverEntry())
        root.add("mcpServers", servers)
        return gson.toJson(root) + "\n"
    }

    /**
     * The escape hatch from plan §8, for running `claude` outside the project root or from another
     * MCP client. Uses the real absolute launcher path, since no `${CLAUDE_PROJECT_DIR}` exists on
     * a shell command line.
     */
    fun claudeMcpAddCommand(): String {
        val launcher = BridgeVendor.getInstance().launcherPath()
        return "claude mcp add --transport stdio $SERVER_KEY -- \"$launcher\""
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    private sealed interface ReadOutcome {
        /** [text] is null when the file does not exist yet. */
        data class Text(val text: String?) : ReadOutcome
        data class Failure(val reason: String) : ReadOutcome
    }

    private fun readExisting(file: VirtualFile?, path: Path): ReadOutcome {
        if (file == null) {
            // No VirtualFile: either the file is absent, or it is excluded from the VFS. Fall back
            // to the raw path so an existing file is never silently clobbered.
            return try {
                if (Files.isRegularFile(path)) {
                    ReadOutcome.Text(Files.readString(path, StandardCharsets.UTF_8))
                } else {
                    ReadOutcome.Text(null)
                }
            } catch (e: Exception) {
                ReadOutcome.Failure("Could not read $path (${e.javaClass.simpleName}: ${e.message}).")
            }
        }

        return try {
            // An open editor's Document is the truth; the bytes on disk may be one keystroke old.
            // Application.runReadAction, not ReadAction.compute(ThrowableComputable): the latter is
            // deprecated from 2026.1 on, and this plugin ships with an open untilBuild, so a future
            // IDE would flag it on the Marketplace. Same read lock, same guarantee.
            val fromDocument: String? = ApplicationManager.getApplication().runReadAction<String?> {
                FileDocumentManager.getInstance().getCachedDocument(file)?.text
            }
            ReadOutcome.Text(fromDocument ?: String(file.contentsToByteArray(), StandardCharsets.UTF_8))
        } catch (e: Exception) {
            ReadOutcome.Failure("Could not read ${file.path} (${e.javaClass.simpleName}: ${e.message}).")
        }
    }

    // ------------------------------------------------------------------
    // Merging
    // ------------------------------------------------------------------

    private sealed interface MergeOutcome {
        data class Text(val json: String) : MergeOutcome
        data class Invalid(val reason: String, val hint: String?) : MergeOutcome
    }

    private fun merge(existing: String?): MergeOutcome {
        val root: JsonObject = if (existing.isNullOrBlank()) {
            JsonObject()
        } else {
            val parsed = try {
                JsonParser.parseString(existing)
            } catch (e: Exception) {
                return MergeOutcome.Invalid(
                    "$CONFIG_FILE_NAME is not valid JSON, so MockkHttp will not rewrite it.",
                    "Fix the syntax error and click the button again, or add the entry by hand."
                )
            }
            if (!parsed.isJsonObject) {
                return MergeOutcome.Invalid(
                    "$CONFIG_FILE_NAME does not contain a JSON object at the top level.",
                    "MCP configs must be an object with an \"mcpServers\" key."
                )
            }
            parsed.asJsonObject
        }

        val serversElement = root.get("mcpServers")
        val servers = when {
            serversElement == null || serversElement.isJsonNull -> JsonObject().also { root.add("mcpServers", it) }
            serversElement.isJsonObject -> serversElement.asJsonObject
            else -> return MergeOutcome.Invalid(
                "\"mcpServers\" in $CONFIG_FILE_NAME is not an object, so MockkHttp cannot merge into it.",
                "Make \"mcpServers\" a JSON object, then click the button again."
            )
        }

        // The ONLY mutation. Everything else in the tree is carried through untouched.
        servers.add(SERVER_KEY, serverEntry())

        return MergeOutcome.Text(gson.toJson(root) + "\n")
    }

    private fun serverEntry(): JsonObject {
        val env = JsonObject()
        env.addProperty(ENV_PROJECT_DIR, ENV_PROJECT_DIR_VALUE)

        val entry = JsonObject()
        entry.addProperty("type", "stdio")
        entry.addProperty("command", if (SystemInfo.isWindows) COMMAND_WINDOWS else COMMAND_POSIX)
        entry.add("env", env)
        return entry
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    private fun applyOnEdt(project: Project, block: () -> WriteResult): WriteResult {
        val app = ApplicationManager.getApplication()
        val holder = AtomicReference<WriteResult>()
        val command = Runnable {
            try {
                WriteCommandAction.runWriteCommandAction(project, Runnable { holder.set(block()) })
            } catch (e: Exception) {
                holder.set(WriteResult.Failed("Could not write $CONFIG_FILE_NAME (${e.javaClass.simpleName}: ${e.message})."))
            }
        }

        if (app.isDispatchThread) {
            command.run()
        } else {
            try {
                app.invokeAndWait(command)
            } catch (e: Exception) {
                return WriteResult.Failed("Could not reach the UI thread to write $CONFIG_FILE_NAME.")
            }
        }
        return holder.get() ?: WriteResult.Failed("Writing $CONFIG_FILE_NAME produced no result.")
    }

    /** Runs inside the write action. No VFS refresh here — that is why [write] resolved the file first. */
    private fun applyMerge(
        baseDir: Path,
        configPath: Path,
        newText: String,
        created: Boolean
    ): WriteResult {
        return try {
            val fileSystem = LocalFileSystem.getInstance()
            val file = fileSystem.findFileByNioFile(configPath)
            val documentManager = FileDocumentManager.getInstance()
            val document: Document? = file?.let { documentManager.getCachedDocument(it) }

            when {
                document != null -> {
                    // Through the editor, so an open tab shows the change instead of fighting it.
                    document.setText(newText)
                    documentManager.saveDocument(document)
                }

                file != null -> file.setBinaryContent(newText.toByteArray(StandardCharsets.UTF_8))

                else -> {
                    val parent = fileSystem.findFileByNioFile(baseDir)
                        ?: return WriteResult.Failed(
                            "The project directory $baseDir is not in the IDE's virtual file system.",
                            "Reopen the project from its root directory and try again."
                        )
                    val child = parent.findChild(CONFIG_FILE_NAME)
                        ?: parent.createChildData(this, CONFIG_FILE_NAME)
                    child.setBinaryContent(newText.toByteArray(StandardCharsets.UTF_8))
                }
            }
            // Deliberately absent: any change to .gitignore. Committing .mcp.json is the team's
            // call, and plan §8 documents both choices.
            WriteResult.Written(configPath.toString(), created)
        } catch (e: Exception) {
            log.warn("🤖 ⚠️ Could not write $configPath", e)
            WriteResult.Failed(
                "Could not write $configPath (${e.javaClass.simpleName}: ${e.message}).",
                "Check that the file is writable and not held by another process."
            )
        }
    }
}
