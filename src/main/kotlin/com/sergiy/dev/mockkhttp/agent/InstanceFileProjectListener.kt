package com.sergiy.dev.mockkhttp.agent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener

/**
 * Keeps `projects[]` in the instance file honest, so the bridge never resolves a working directory
 * to a project this IDE has already closed.
 *
 * ## Why only `projectClosed`
 *
 * [ProjectManagerListener.projectOpened] is `@Deprecated` on this interface (verified against the
 * 2024.3 platform bytecode: `projectOpened` and `canCloseProject` carry the `Deprecated` attribute,
 * `projectClosed`, `projectClosing` and `projectClosingBeforeSave` do not). Implementing it would
 * be a Marketplace-verifier failure, and a release blocker for this plugin. The platform's
 * replacement for "run something when a project opens" is a `postStartupActivity`
 * ([com.sergiy.dev.mockkhttp.startup.MockkHttpStartupActivity]), which is where the open side of
 * this refresh lives — so this class only has to cover the close side.
 *
 * `projectClosed` fires *after* the project has left `ProjectManager.getOpenProjects()` and
 * *before* it is disposed, which is exactly the window
 * [InstanceRegistry.refresh] needs: it re-reads the open list and the departing project is already
 * gone from it. The refresh is scheduled rather than run inline because this callback is on the
 * EDT and writing the file must not be.
 *
 * Registered as an `<applicationListeners>` entry in `plugin.xml` — see the wiring note in the
 * agent milestone's build order.
 */
class InstanceFileProjectListener : ProjectManagerListener {

    private val log = Logger.getInstance(InstanceFileProjectListener::class.java)

    override fun projectClosed(project: Project) {
        try {
            InstanceRegistry.getInstance().scheduleRefresh("project closed: ${project.name}")
        } catch (e: Exception) {
            // Closing the last project during IDE shutdown can race the service container away.
            // A stale entry costs the bridge one failed resolve; taking the close path down with
            // us would cost the user their project.
            log.debug("🤖 Could not refresh the instance file after closing ${project.name}", e)
        }
    }
}
