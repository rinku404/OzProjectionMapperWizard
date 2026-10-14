package com.msc.ozprojectionmapperwizard.core.protocol

import com.msc.ozprojectionmapperwizard.core.model.ShowManifest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The HTTP API a player (projector) exposes on the local network, shared by the player and the controllers.
 *
 * Unauthenticated: `GET /info`, `POST /pair`. Everything else needs `Authorization: Bearer <token>`.
 * - `GET  /files/{sha256}` -> [UploadStatus]
 * - `PUT  /files/{sha256}?offset=N` raw bytes appended at N (resumable upload)
 * - `POST /files/{sha256}/complete` verifies the hash and keeps the file
 * - `PUT  /shows/{id}` [com.msc.ozprojectionmapperwizard.core.model.ShowManifest], `DELETE /shows/{id}`
 * - `GET|PUT /schedule` [com.msc.ozprojectionmapperwizard.core.schedule.Schedule]
 * - `POST /time` [ClockSync]
 * - `POST /play/{showId}`, `POST /black`, `POST /resume` (back to the schedule)
 */
object PlayerProtocol {
    const val VERSION = 1
    const val DEFAULT_PORT = 8737
    /** mDNS / Android NSD service type the player advertises. */
    const val SERVICE_TYPE = "_ozmap._tcp"
}

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val name: String,
    val model: String,
    val platform: String,
    val osVersion: String,
    val protocolVersion: Int = PlayerProtocol.VERSION,
    val displayWidth: Int,
    val displayHeight: Int,
    val refreshRate: Double,
    val freeBytes: Long,
    val shows: List<ShowManifest>,
    val nowPlaying: NowPlaying,
)

@Serializable
data class NowPlaying(val showId: String?, val reason: PlayReason)

@Serializable
enum class PlayReason {
    /** Chosen by the schedule (a rule or the default show). */
    @SerialName("schedule") SCHEDULE,
    /** A controller asked for this show with /play. */
    @SerialName("override") OVERRIDE,
    /** Inside the schedule's off hours: black. */
    @SerialName("off_hours") OFF_HOURS,
    /** A controller asked for black with /black. */
    @SerialName("black") BLACK,
    /** Nothing installed or scheduled yet: the player shows its setup screen. */
    @SerialName("no_show") NO_SHOW,
}

@Serializable
data class PairRequest(val pin: String, val controllerName: String)

@Serializable
data class PairResponse(val token: String, val deviceId: String)

@Serializable
data class UploadStatus(val sha256: String, val receivedBytes: Long, val complete: Boolean)

/** The controller's wall clock; players without internet keep their own time from this. */
@Serializable
data class ClockSync(val epochMillis: Long, val timeZone: String)

@Serializable
data class ApiError(val message: String)
