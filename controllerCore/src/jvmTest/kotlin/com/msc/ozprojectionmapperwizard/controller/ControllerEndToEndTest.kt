package com.msc.ozprojectionmapperwizard.controller

import com.msc.ozprojectionmapperwizard.core.protocol.ClockSync
import com.msc.ozprojectionmapperwizard.core.protocol.PlayReason
import com.msc.ozprojectionmapperwizard.player.Pairing
import com.msc.ozprojectionmapperwizard.player.PlatformInfo
import com.msc.ozprojectionmapperwizard.player.PlaybackDirector
import com.msc.ozprojectionmapperwizard.player.PlayerClock
import com.msc.ozprojectionmapperwizard.player.PlayerServer
import com.msc.ozprojectionmapperwizard.player.PlayerStorage
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Runs the controller against a real player server on localhost. */
class ControllerEndToEndTest {
    private val storage = PlayerStorage(Files.createTempDirectory("player").toFile())
    private val pairing = Pairing(storage)
    private val clock = PlayerClock(storage)
    private val director = PlaybackDirector(storage, clock)
    private val server = PlayerServer(storage, pairing, clock, director, FakeProjector)
    private val port = ServerSocket(0).use { it.localPort }
    private val address = PlayerAddress("127.0.0.1", port)
    private val store = MemoryStore()

    init {
        server.start(port)
    }

    @AfterTest
    fun stop() = server.stop()

    @Test
    fun pairsSendsAVideoAndItPlays() = runBlocking {
        val known = KnownPlayers(store)
        val player = known.pair(address, pairing.pin, "test pc")
        assertEquals("fake-hy300", player.deviceId)

        val bytes = Random(1).nextBytes(SendVideo.CHUNK_BYTES * 2 + 1234)
        val progress = mutableListOf<SendProgress>()
        val info = SendVideo.send(player.client(), video("loop.mp4", bytes), ClockSync(1_700_000_000_000, "UTC")) { progress += it }

        val id = SendVideo.showId(video("loop.mp4", bytes))
        assertEquals(id, info.nowPlaying.showId)
        assertEquals(PlayReason.SCHEDULE, info.nowPlaying.reason)
        assertEquals(listOf("loop"), info.shows.map { it.name })
        assertEquals(1280, info.shows.single().width)
        assertEquals(id, storage.schedule().default)
        assertEquals(bytes.size.toLong(), progress.last().sentBytes)
        assertEquals(4, progress.size)

        // Black, then resume brings the video back.
        assertEquals(PlayReason.BLACK, player.client().black().nowPlaying.reason)
        assertEquals(id, player.client().resume().nowPlaying.showId)
    }

    @Test
    fun resumesAPartialUpload() = runBlocking {
        val player = KnownPlayers(store).pair(address, pairing.pin, "test pc")
        val bytes = Random(2).nextBytes(SendVideo.CHUNK_BYTES + 10)
        val video = video("a.mp4", bytes)
        player.client().putChunk(video.sha256, 0, bytes.copyOf(1000))

        val progress = mutableListOf<SendProgress>()
        SendVideo.upload(player.client(), video) { progress += it }
        assertEquals(1000, progress.first().sentBytes)
        assertTrue(player.client().uploadStatus(video.sha256).complete)
    }

    @Test
    fun aDamagedFileIsRejected() = runBlocking {
        val player = KnownPlayers(store).pair(address, pairing.pin, "test pc")
        val bytes = Random(3).nextBytes(5000)
        val wrong = PickedVideo("x.mp4", bytes.size.toLong(), sha256(bytes.copyOf(10)), ChunkSource { o, m -> slice(bytes, o, m) })
        val error = assertFailsWith<PlayerException> { SendVideo.upload(player.client(), wrong) }
        assertEquals(422, error.status)
    }

    @Test
    fun wrongPinAndMissingTokenGiveClearErrors() = runBlocking {
        val wrongPin = assertFailsWith<PlayerException> { KnownPlayers(store).pair(address, "000000x", "pc") }
        assertEquals(403, wrongPin.status)
        val noToken = assertFailsWith<PlayerException> { PlayerClient(address).black() }
        assertEquals(401, noToken.status)
        assertTrue("Pair" in noToken.message!!)
    }

    @Test
    fun pairedPlayersSurviveARestart() = runBlocking {
        val player = KnownPlayers(store).pair(address, pairing.pin, "pc")
        val reloaded = KnownPlayers(store)
        assertEquals(listOf(player), reloaded.players.value)
        reloaded.moved(player.deviceId, PlayerAddress("10.0.0.9"))
        assertEquals("10.0.0.9", KnownPlayers(store).byId(player.deviceId)!!.host)
        reloaded.forget(player.deviceId)
        assertEquals(emptyList(), KnownPlayers(store).players.value)
    }

    @Test
    fun parsesTypedAddresses() {
        assertEquals(PlayerAddress("192.168.1.20"), PlayerAddress.parse(" 192.168.1.20 "))
        assertEquals(PlayerAddress("192.168.1.20", 9000), PlayerAddress.parse("http://192.168.1.20:9000/"))
        assertEquals(null, PlayerAddress.parse("host:abc"))
        assertEquals(null, PlayerAddress.parse(""))
    }

    private fun video(name: String, bytes: ByteArray) =
        PickedVideo(name, bytes.size.toLong(), sha256(bytes), ChunkSource { offset, max -> slice(bytes, offset, max) })

    private fun slice(bytes: ByteArray, offset: Long, max: Int): ByteArray =
        bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + max))

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class MemoryStore : KeyValueStore {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private object FakeProjector : PlatformInfo {
        override val deviceId = "fake-hy300"
        override val name = "Fake HY300"
        override val model = "HY300"
        override val platform = "android"
        override val osVersion = "12"
        override val displayWidth = 1280
        override val displayHeight = 720
        override val refreshRate = 60.0
    }
}
