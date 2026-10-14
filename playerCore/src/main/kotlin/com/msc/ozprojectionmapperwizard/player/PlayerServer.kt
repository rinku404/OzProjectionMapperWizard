package com.msc.ozprojectionmapperwizard.player

import com.msc.ozprojectionmapperwizard.core.ProjectJson
import com.msc.ozprojectionmapperwizard.core.model.Project
import com.msc.ozprojectionmapperwizard.core.model.ShowManifest
import com.msc.ozprojectionmapperwizard.core.protocol.ApiError
import com.msc.ozprojectionmapperwizard.core.protocol.ClockSync
import com.msc.ozprojectionmapperwizard.core.protocol.DeviceInfo
import com.msc.ozprojectionmapperwizard.core.protocol.LiveState
import com.msc.ozprojectionmapperwizard.core.protocol.PairRequest
import com.msc.ozprojectionmapperwizard.core.protocol.PairResponse
import com.msc.ozprojectionmapperwizard.core.protocol.PlayerProtocol
import com.msc.ozprojectionmapperwizard.core.protocol.UpdateRequest
import com.msc.ozprojectionmapperwizard.core.protocol.UploadStatus
import com.msc.ozprojectionmapperwizard.core.update.Releases
import com.msc.ozprojectionmapperwizard.core.schedule.Schedule
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Platform facts the player cannot learn from the JVM alone. */
interface PlatformInfo {
    val deviceId: String
    val name: String
    val model: String
    val platform: String
    val osVersion: String
    val displayWidth: Int
    val displayHeight: Int
    val refreshRate: Double
}

/** The player's HTTP API ([PlayerProtocol]), served on the local network. */
class PlayerServer(
    private val storage: PlayerStorage,
    private val pairing: Pairing,
    private val clock: PlayerClock,
    private val director: PlaybackDirector,
    private val platform: PlatformInfo,
    private val live: LiveSession = LiveSession(),
    private val updater: AppUpdater? = null,
) {
    private var engine: EmbeddedServer<*, *>? = null

    /** Starts listening on all interfaces. Blocking network work: call it off the main thread. */
    @Synchronized
    fun start(port: Int = PlayerProtocol.DEFAULT_PORT) {
        if (engine != null) return
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") { module() }.start(wait = false)
    }

    @Synchronized
    fun stop() {
        engine?.stop(500, 1000)
        engine = null
    }

    fun Application.module() {
        install(ContentNegotiation) { json(ProjectJson.json) }
        install(WebSockets) {
            // Ends live mode soon after a phone walks out of Wi-Fi range without closing the socket.
            pingPeriod = 5.seconds
            timeout = 15.seconds
        }
        install(StatusPages) {
            exception<UploadConflict> { call, e ->
                call.respond(HttpStatusCode.Conflict, UploadStatus(call.parameters["sha256"].orEmpty(), e.receivedBytes, false))
            }
            exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "Bad request")) }
            exception<BadRequestException> { call, e -> call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "Bad request")) }
        }
        routing {
            get("/info") { call.respond(info()) }

            post("/pair") {
                val request = call.receive<PairRequest>()
                val token = pairing.pair(request.pin)
                if (token == null) call.respond(HttpStatusCode.Forbidden, ApiError("Wrong PIN"))
                else call.respond(PairResponse(token, platform.deviceId))
            }

            webSocket("/live") {
                val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
                if (!pairing.isAuthorized(token)) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Pair with this player first"))
                    return@webSocket
                }
                val connection = Any()
                live.begin(connection)
                try {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val state = runCatching { ProjectJson.json.decodeFromString(LiveState.serializer(), frame.readText()) }
                            .getOrNull() ?: continue
                        live.update(connection, state)
                    }
                } finally {
                    live.end(connection)
                }
            }

            authorized {
                get("/project") {
                    val text = storage.readText(PROJECT_FILE)
                    if (text == null) call.respond(HttpStatusCode.NotFound, ApiError("No project saved yet"))
                    else call.respond(ProjectJson.decodeProject(text))
                }
                put("/project") {
                    val project = call.receive<Project>()
                    storage.writeText(PROJECT_FILE, ProjectJson.encodeProject(project))
                    call.respond(HttpStatusCode.NoContent)
                }

                get("/files/{sha256}") {
                    val sha = call.parameters["sha256"]!!
                    val (received, complete) = storage.uploadStatus(sha)
                    call.respond(UploadStatus(sha, received, complete))
                }
                put("/files/{sha256}") {
                    val sha = call.parameters["sha256"]!!
                    val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
                    val received = withContext(Dispatchers.IO) {
                        call.receiveStream().use { storage.appendUpload(sha, offset, it) }
                    }
                    call.respond(UploadStatus(sha, received, false))
                }
                post("/files/{sha256}/complete") {
                    val sha = call.parameters["sha256"]!!
                    val ok = withContext(Dispatchers.IO) { storage.completeUpload(sha) }
                    val (received, complete) = storage.uploadStatus(sha)
                    call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.UnprocessableEntity, UploadStatus(sha, received, complete))
                }

                put("/shows/{id}") {
                    val manifest = call.receive<ShowManifest>()
                    require(manifest.id == call.parameters["id"]) { "Show id in the path and body differ" }
                    storage.installShow(manifest)
                    director.refresh()
                    call.respond(HttpStatusCode.OK, info())
                }
                delete("/shows/{id}") {
                    val removed = storage.removeShow(call.parameters["id"]!!)
                    director.refresh()
                    call.respond(if (removed) HttpStatusCode.OK else HttpStatusCode.NotFound, info())
                }

                get("/schedule") { call.respond(storage.schedule()) }
                put("/schedule") {
                    storage.saveSchedule(call.receive<Schedule>())
                    director.refresh()
                    call.respond(info())
                }

                post("/time") {
                    clock.sync(call.receive<ClockSync>())
                    director.refresh()
                    call.respond(info())
                }

                post("/play/{showId}") {
                    if (director.play(call.parameters["showId"]!!)) call.respond(info())
                    else call.respond(HttpStatusCode.NotFound, ApiError("No such show"))
                }
                post("/black") {
                    director.black()
                    call.respond(info())
                }
                post("/update") {
                    val request = call.receive<UpdateRequest>()
                    require(Releases.isTrustedDownload(request.downloadUrl)) { "Updates only come from this app's GitHub releases" }
                    if (updater == null) {
                        call.respond(HttpStatusCode.NotImplemented, ApiError("This player can't update itself"))
                    } else {
                        updater.install(request.downloadUrl, request.build)
                        call.respond(HttpStatusCode.Accepted, info())
                    }
                }
                post("/resume") {
                    director.resumeSchedule()
                    call.respond(info())
                }
            }
        }
    }

    fun info() = DeviceInfo(
        deviceId = platform.deviceId,
        name = platform.name,
        model = platform.model,
        platform = platform.platform,
        osVersion = platform.osVersion,
        displayWidth = platform.displayWidth,
        displayHeight = platform.displayHeight,
        refreshRate = platform.refreshRate,
        freeBytes = storage.freeBytes(),
        shows = storage.shows(),
        nowPlaying = director.playback.value.nowPlaying,
        live = live.isLive,
        appBuild = updater?.appBuild ?: 0,
        updateStatus = updater?.status,
    )

    private companion object {
        const val PROJECT_FILE = "project.json"
    }

    /** Routes declared inside need a paired controller's bearer token. */
    private fun Route.authorized(build: AuthorizedRoutes.() -> Unit) = AuthorizedRoutes(this).build()

    inner class AuthorizedRoutes(private val route: Route) {
        fun get(path: String, body: suspend RoutingContext.() -> Unit) = route.get(path) { if (checkToken()) body() }
        fun put(path: String, body: suspend RoutingContext.() -> Unit) = route.put(path) { if (checkToken()) body() }
        fun post(path: String, body: suspend RoutingContext.() -> Unit) = route.post(path) { if (checkToken()) body() }
        fun delete(path: String, body: suspend RoutingContext.() -> Unit) = route.delete(path) { if (checkToken()) body() }

        private suspend fun RoutingContext.checkToken(): Boolean {
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
            if (pairing.isAuthorized(token)) return true
            call.respond(HttpStatusCode.Unauthorized, ApiError("Pair with this player first"))
            return false
        }
    }
}
