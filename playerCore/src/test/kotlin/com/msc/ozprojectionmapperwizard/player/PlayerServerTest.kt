package com.msc.ozprojectionmapperwizard.player

import com.msc.ozprojectionmapperwizard.core.ProjectJson
import com.msc.ozprojectionmapperwizard.core.protocol.DeviceInfo
import com.msc.ozprojectionmapperwizard.core.protocol.PairResponse
import com.msc.ozprojectionmapperwizard.core.protocol.PlayReason
import com.msc.ozprojectionmapperwizard.core.protocol.UploadStatus
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerServerTest {
    private val storage = tempStorage()
    private val pairing = Pairing(storage)
    private val clock = PlayerClock(storage)
    private val director = PlaybackDirector(storage, clock)
    private val server = PlayerServer(storage, pairing, clock, director, TestPlatform)

    private fun serverTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { with(server) { module() } }
        block()
    }

    private suspend fun ApplicationTestBuilder.pair(): String {
        val response = client.post("/pair") {
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"${pairing.pin}","controllerName":"test"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return ProjectJson.json.decodeFromString<PairResponse>(response.bodyAsText()).token
    }

    private inline fun <reified T> decode(text: String): T = ProjectJson.json.decodeFromString(text)

    @Test
    fun infoIsPublicButControlNeedsPairing() = serverTest {
        assertEquals(HttpStatusCode.OK, client.get("/info").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/black").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/black") { header("Authorization", "Bearer nope") }.status)
    }

    @Test
    fun wrongPinIsRefused() = serverTest {
        val response = client.post("/pair") {
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"x","controllerName":"test"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun uploadInstallAndPlay() = serverTest {
        val token = pair()
        val video = ByteArray(150_000) { (it % 97).toByte() }
        val sha = sha256(video)
        suspend fun upload(offset: Int, end: Int): HttpResponse = client.put("/files/$sha?offset=$offset") {
            header("Authorization", "Bearer $token")
            setBody(video.copyOfRange(offset, end))
        }

        assertEquals(HttpStatusCode.OK, upload(0, 60_000).status)
        // A resend from the wrong place tells the controller where to resume.
        val conflict = upload(0, 10)
        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertEquals(60_000, decode<UploadStatus>(conflict.bodyAsText()).receivedBytes)
        assertEquals(HttpStatusCode.OK, upload(60_000, video.size).status)
        val done = client.post("/files/$sha/complete") { header("Authorization", "Bearer $token") }
        assertEquals(true, decode<UploadStatus>(done.bodyAsText()).complete)

        val installed = client.put("/shows/ambient") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(ProjectJson.encodeManifest(manifest("ambient", sha)))
        }
        assertEquals(HttpStatusCode.OK, installed.status)
        val info = decode<DeviceInfo>(installed.bodyAsText())
        assertEquals("ambient", info.nowPlaying.showId)
        assertEquals(PlayReason.SCHEDULE, info.nowPlaying.reason)
        assertEquals(video.size.toLong(), storage.file(sha).length())
    }

    @Test
    fun badRequestsGetA400() = serverTest {
        val token = pair()
        val response = client.get("/files/not-a-hash") { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
