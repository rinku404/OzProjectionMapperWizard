package com.msc.ozprojectionmapperwizard.controller

import com.msc.ozprojectionmapperwizard.core.ProjectJson
import com.msc.ozprojectionmapperwizard.core.model.ShowManifest
import com.msc.ozprojectionmapperwizard.core.protocol.ApiError
import com.msc.ozprojectionmapperwizard.core.protocol.ClockSync
import com.msc.ozprojectionmapperwizard.core.protocol.DeviceInfo
import com.msc.ozprojectionmapperwizard.core.protocol.PairRequest
import com.msc.ozprojectionmapperwizard.core.protocol.PairResponse
import com.msc.ozprojectionmapperwizard.core.protocol.PlayerProtocol
import com.msc.ozprojectionmapperwizard.core.protocol.UploadStatus
import com.msc.ozprojectionmapperwizard.core.schedule.Schedule
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json

/** Where a player listens on the local network. */
data class PlayerAddress(val host: String, val port: Int = PlayerProtocol.DEFAULT_PORT) {
    val baseUrl: String get() = "http://${if (':' in host) "[$host]" else host}:$port"

    override fun toString() = if (port == PlayerProtocol.DEFAULT_PORT) host else "$host:$port"

    companion object {
        /** Parses what a person types: "192.168.1.20" or "192.168.1.20:8737". */
        fun parse(text: String): PlayerAddress? {
            val trimmed = text.trim().removePrefix("http://").trimEnd('/')
            if (trimmed.isEmpty()) return null
            val colon = trimmed.lastIndexOf(':')
            if (colon > 0 && trimmed.count { it == ':' } == 1) {
                val port = trimmed.substring(colon + 1).toIntOrNull() ?: return null
                return PlayerAddress(trimmed.substring(0, colon), port)
            }
            return PlayerAddress(trimmed)
        }
    }
}

/** A failed call to a player, with the player's own explanation when it gave one. */
class PlayerException(val status: Int, message: String) : Exception(message)

/** Talks to one player's HTTP API ([PlayerProtocol]). [token] is needed for everything but [info] and [pair]. */
class PlayerClient(
    val address: PlayerAddress,
    private val token: String? = null,
    private val http: HttpClient = sharedHttpClient,
) {
    suspend fun info(): DeviceInfo = http.get("${address.baseUrl}/info").ok().body()

    suspend fun pair(pin: String, controllerName: String): PairResponse {
        val response = http.post("${address.baseUrl}/pair") { json(PairRequest(pin.trim(), controllerName)) }
        if (response.status == HttpStatusCode.Forbidden) throw PlayerException(403, "Wrong PIN. Check the number on the projector.")
        return response.ok().body()
    }

    suspend fun uploadStatus(sha256: String): UploadStatus = http.get("${address.baseUrl}/files/$sha256") { auth() }.ok().body()

    /** Appends [bytes] at [offset]. A 409 means the player has a different amount; its count comes back so the caller can resume. */
    suspend fun putChunk(sha256: String, offset: Long, bytes: ByteArray): UploadStatus {
        val response = http.put("${address.baseUrl}/files/$sha256") {
            auth()
            parameter("offset", offset)
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }
        if (response.status == HttpStatusCode.Conflict) return response.body()
        return response.ok().body()
    }

    suspend fun complete(sha256: String): UploadStatus {
        val response = http.post("${address.baseUrl}/files/$sha256/complete") { auth() }
        if (response.status == HttpStatusCode.UnprocessableEntity) {
            throw PlayerException(422, "The video arrived damaged (checksum mismatch). Try sending it again.")
        }
        return response.ok().body()
    }

    suspend fun installShow(manifest: ShowManifest): DeviceInfo =
        http.put("${address.baseUrl}/shows/${manifest.id}") { auth(); json(manifest) }.ok().body()

    suspend fun removeShow(id: String): DeviceInfo = http.delete("${address.baseUrl}/shows/$id") { auth() }.ok().body()

    suspend fun schedule(): Schedule = http.get("${address.baseUrl}/schedule") { auth() }.ok().body()

    suspend fun saveSchedule(schedule: Schedule): DeviceInfo =
        http.put("${address.baseUrl}/schedule") { auth(); json(schedule) }.ok().body()

    suspend fun syncClock(sync: ClockSync): DeviceInfo = http.post("${address.baseUrl}/time") { auth(); json(sync) }.ok().body()

    suspend fun play(showId: String): DeviceInfo = http.post("${address.baseUrl}/play/$showId") { auth() }.ok().body()

    suspend fun black(): DeviceInfo = http.post("${address.baseUrl}/black") { auth() }.ok().body()

    suspend fun resume(): DeviceInfo = http.post("${address.baseUrl}/resume") { auth() }.ok().body()

    private fun HttpRequestBuilder.auth() {
        token?.let { header("Authorization", "Bearer $it") }
    }

    private inline fun <reified T> HttpRequestBuilder.json(value: T) {
        contentType(ContentType.Application.Json)
        setBody(value)
    }

    private suspend fun HttpResponse.ok(): HttpResponse {
        if (status.isSuccess()) return this
        val text = runCatching { bodyAsText() }.getOrDefault("")
        val message = runCatching { ProjectJson.json.decodeFromString(ApiError.serializer(), text).message }.getOrNull()
        throw PlayerException(
            status.value,
            when {
                status == HttpStatusCode.Unauthorized -> "This projector no longer knows this device. Pair it again."
                message != null -> message
                else -> "The projector answered ${status.value} ${status.description}"
            },
        )
    }

    companion object {
        /** One connection pool for the whole app. Long timeout so big chunks survive slow Wi-Fi. */
        val sharedHttpClient: HttpClient by lazy { createHttpClient() }

        fun createHttpClient(): HttpClient = HttpClient(CIO) {
            expectSuccess = false
            engine { requestTimeout = 120_000 }
            install(ContentNegotiation) { json(ProjectJson.json) }
        }
    }
}
