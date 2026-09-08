package com.sergiy.dev.mockkhttp.control.handlers

import com.sergiy.dev.mockkhttp.control.ControlApi
import com.sergiy.dev.mockkhttp.control.ControlRequest
import com.sergiy.dev.mockkhttp.control.ControlResponse
import com.sergiy.dev.mockkhttp.control.dto.ApiResult
import com.sergiy.dev.mockkhttp.control.dto.ErrorCode
import com.sergiy.dev.mockkhttp.control.dto.SetModeRequest
import com.sergiy.dev.mockkhttp.control.dto.SetPackageFilterRequest
import com.sergiy.dev.mockkhttp.control.dto.StartSessionRequest
import com.sergiy.dev.mockkhttp.control.dto.StatusRequest

/**
 * `/v1/projects/{pid}/status`, `/v1/projects/{pid}/session…` and the device listing.
 *
 * | Route | Verb | Meaning |
 * |---|---|---|
 * | `…/status` | `GET` | orientation: project, interceptor, session, flows, mocks, warnings |
 * | `…/session` | `GET` | the session alone (running, mode, device, package filter) |
 * | `…/session/start` | `POST` | begin capturing; every field optional |
 * | `…/session/stop` | `POST` | stop capturing |
 * | `…/session/restart` | `POST` | stop, then start — the only way to change device |
 * | `…/session/mode` | `POST` / `PUT` | `RECORDING` \| `DEBUG` \| `MOCKK` \| `MOCKK_DEBUG` |
 * | `…/session/app` | `POST` | set (or `clear`) the package filter |
 * | `…/session/devices` | `GET` | connected devices and the apps known to be instrumented |
 *
 * ## Why start/stop live here now
 *
 * They used to answer `501 NOT_IMPLEMENTED` naming a milestone, because the session was owned by
 * the Swing Inspector and a stub here would have reported success while a human still had to press
 * Start. `CaptureSessionService` now owns it, so the honest answer changed: these routes do the
 * thing. Nothing else about the shape of the surface changed — a failure is still typed, and its
 * hint still names the exact next call.
 *
 * ## `/v1/devices` and `/v1/projects/{pid}/devices`
 *
 * [handle] answers `devices` (and `app`) as a RESOURCE as well as a session sub-route, so the two
 * spellings behave identically the moment `ControlRouter` dispatches them here. That file's
 * future-resource table still answers them today; this handler is ready either way, and
 * `…/session/devices` works right now regardless.
 */
class SessionHandler(private val api: ControlApi) {

    fun handle(request: ControlRequest): ControlResponse = when (request.resource) {
        RESOURCE_STATUS -> status(request)
        RESOURCE_SESSION -> session(request)
        // Reachable only once the router stops answering these from its future-resource table.
        RESOURCE_DEVICES, RESOURCE_DEVICE -> devices(request)
        RESOURCE_APP -> app(request)
        else -> HandlerSupport.unknownSubRoute(request, KNOWN_ROUTES)
    }

    // ------------------------------------------------------------------------
    // status
    // ------------------------------------------------------------------------

    /**
     * `matched_by` and `cwd` are the bridge telling us **how** it chose this project; they are
     * echoed back in `resolution` so a model that is talking to the wrong IDE can see why.
     */
    private fun status(request: ControlRequest): ControlResponse {
        if (request.tail.isNotEmpty()) return HandlerSupport.unknownSubRoute(request, KNOWN_ROUTES)
        if (request.method != "GET") return HandlerSupport.wrongMethod(request, "GET")

        val q = QueryReader(request)
        val statusRequest = StatusRequest(matchedBy = q.str("matched_by"), cwd = q.str("cwd"))
        q.failure?.let { return it }
        return ControlResponse.of(api.status(request.projectId, statusRequest))
    }

    // ------------------------------------------------------------------------
    // session
    // ------------------------------------------------------------------------

    private fun session(request: ControlRequest): ControlResponse {
        val tail = request.tail

        return when {
            tail.isEmpty() -> when (request.method) {
                "GET" -> ControlResponse.of(api.getSession(request.projectId))
                // A bare POST is ambiguous — start? change the mode? — and guessing which would
                // start a capture nobody asked for.
                else -> HandlerSupport.wrongMethod(
                    request,
                    "GET (start with POST …/session/start, change the mode with POST …/session/mode)"
                )
            }

            tail.size == 1 && tail[0] == MODE -> when (request.method) {
                "POST", "PUT", "PATCH" -> setMode(request)
                "GET" -> ControlResponse.of(api.getSession(request.projectId))
                else -> HandlerSupport.wrongMethod(request, "POST")
            }

            tail.size == 1 && tail[0] == START -> when (request.method) {
                "POST", "PUT" -> start(request, restart = false)
                else -> HandlerSupport.wrongMethod(request, "POST")
            }

            tail.size == 1 && tail[0] == RESTART -> when (request.method) {
                "POST", "PUT" -> start(request, restart = true)
                else -> HandlerSupport.wrongMethod(request, "POST")
            }

            tail.size == 1 && tail[0] == STOP -> when (request.method) {
                "POST", "PUT", "DELETE" -> ControlResponse.of(api.stopSession(request.projectId))
                else -> HandlerSupport.wrongMethod(request, "POST")
            }

            tail.size == 1 && tail[0] in APP_ROUTES -> app(request)

            tail.size == 1 && tail[0] in DEVICE_ROUTES -> devices(request)

            // Clearing flows exists — it just lives on the flows resource, which owns the cursor.
            tail.size == 1 && tail[0] == FLOWS -> ControlResponse.fail(
                ErrorCode.UNSUPPORTED_ACTION,
                "Flows are not cleared through the session resource.",
                "Call DELETE /v1/projects/${request.projectId}/flows."
            )

            else -> HandlerSupport.unknownSubRoute(request, KNOWN_ROUTES)
        }
    }

    /**
     * `POST …/session/start` and `POST …/session/restart`.
     *
     * Every field may arrive in the JSON body or the query string, so a caller with only a URL bar
     * can still start a session. The body wins where both are present.
     */
    private fun start(request: ControlRequest, restart: Boolean): ControlResponse {
        val body = when (val parsed = request.jsonBody(StartSessionRequest::class.java)) {
            is ApiResult.Err -> return ControlResponse.error(parsed.error)
            is ApiResult.Ok -> parsed.value
        }
        val q = QueryReader(request)
        // `device` is accepted alongside `serial` because it is what a human types first, and a
        // 400 on a synonym is a wasted round trip for a model that guessed the near-miss.
        val serialParam = q.str("serial") ?: q.str("device") ?: q.str("device_serial")
        val packageParam = q.str("package_name") ?: q.str("package")
        val modeParam = q.str("mode")
        val confirmParam = q.bool("confirm_pause_all")
        q.failure?.let { return it }

        val merged = StartSessionRequest(
            serial = body.serial ?: serialParam,
            packageName = body.packageName ?: packageParam,
            mode = body.mode ?: modeParam,
            confirmPauseAll = body.confirmPauseAll ?: confirmParam
        )
        return ControlResponse.of(
            if (restart) api.restartSession(request.projectId, merged)
            else api.startSession(request.projectId, merged)
        )
    }

    /**
     * `POST …/session/mode`.
     *
     * `mode` may arrive in the JSON body or the query string. Validating the value, the
     * `confirm_pause_all` gate on DEBUG / MOCKK_DEBUG and the refusal on a stopped session all
     * belong to [ControlApi]: it is the only thing that knows what the interceptor is doing.
     */
    private fun setMode(request: ControlRequest): ControlResponse {
        val body = when (val parsed = request.jsonBody(SetModeRequest::class.java)) {
            is ApiResult.Err -> return ControlResponse.error(parsed.error)
            is ApiResult.Ok -> parsed.value
        }
        val q = QueryReader(request)
        val modeParam = q.str("mode")
        val confirmParam = q.bool("confirm_pause_all")
        q.failure?.let { return it }

        val merged = body.copy(
            mode = body.mode ?: modeParam,
            confirmPauseAll = body.confirmPauseAll ?: confirmParam
        )
        return ControlResponse.of(api.setMode(request.projectId, merged))
    }

    // ------------------------------------------------------------------------
    // app (the package filter) and devices
    // ------------------------------------------------------------------------

    /**
     * `POST …/session/app`.
     *
     * A `GET` reads the filter out of the session rather than 405-ing: a caller that wants to know
     * which app is being captured should not have to know that the answer lives on another route.
     */
    private fun app(request: ControlRequest): ControlResponse {
        if (request.method == "GET") return ControlResponse.of(api.getSession(request.projectId))
        if (request.method !in MUTATING_METHODS) {
            return HandlerSupport.wrongMethod(request, "POST (or GET to read the current filter)")
        }

        val body = when (val parsed = request.jsonBody(SetPackageFilterRequest::class.java)) {
            is ApiResult.Err -> return ControlResponse.error(parsed.error)
            is ApiResult.Ok -> parsed.value
        }
        val q = QueryReader(request)
        val packageParam = q.str("package_name") ?: q.str("package")
        val clearParam = q.bool("clear")
        q.failure?.let { return it }

        val merged = body.copy(
            packageName = body.packageName ?: packageParam,
            clear = body.clear ?: clearParam
        )
        return ControlResponse.of(api.setPackageFilter(request.projectId, merged))
    }

    /**
     * `GET …/session/devices` (and `GET …/devices` once the router routes it here).
     *
     * `scan=true` is the caller opting into a full device scan; it costs minutes on a physical
     * device, so it is never implied. A `POST` here is refused with the route that actually
     * chooses a device, because "set the device" without starting is not a state worth having.
     */
    private fun devices(request: ControlRequest): ControlResponse {
        if (request.method != "GET") {
            return ControlResponse.fail(
                ErrorCode.UNSUPPORTED_ACTION,
                "${request.method} is not allowed on ${request.path}: the device list is read-only.",
                "Choose a device by starting a session on it: POST " +
                        "/v1/projects/${request.projectId ?: "{project_id}"}/session/start {\"serial\":\"…\"}."
            )
        }
        val q = QueryReader(request)
        val scan = q.bool("scan") ?: q.bool("deep_scan")
        q.failure?.let { return it }
        return ControlResponse.of(api.listDevices(request.projectId, scan == true))
    }

    private companion object {
        const val RESOURCE_STATUS = "status"
        const val RESOURCE_SESSION = "session"
        const val RESOURCE_DEVICES = "devices"
        const val RESOURCE_DEVICE = "device"
        const val RESOURCE_APP = "app"

        const val MODE = "mode"
        const val START = "start"
        const val STOP = "stop"
        const val RESTART = "restart"
        const val FLOWS = "flows"

        /** Spellings of the package-filter sub-route. All the same call. */
        val APP_ROUTES = setOf("app", "set_app", "package", "package_filter")

        /** Spellings of the device sub-route. All the same call. */
        val DEVICE_ROUTES = setOf("device", "devices")

        val MUTATING_METHODS = setOf("POST", "PUT", "PATCH")

        const val KNOWN_ROUTES =
            "GET /v1/projects/{pid}/status, GET /v1/projects/{pid}/session, " +
                    "POST /v1/projects/{pid}/session/start, POST /v1/projects/{pid}/session/stop, " +
                    "POST /v1/projects/{pid}/session/restart, POST /v1/projects/{pid}/session/mode, " +
                    "POST /v1/projects/{pid}/session/app, GET /v1/projects/{pid}/session/devices"
    }
}
