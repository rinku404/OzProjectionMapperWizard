package com.msc.ozprojectionmapperwizard.controller

import com.msc.ozprojectionmapperwizard.core.model.ShowManifest
import com.msc.ozprojectionmapperwizard.core.protocol.ClockSync
import com.msc.ozprojectionmapperwizard.core.protocol.DeviceInfo

/** Reads part of a file the person picked, wherever the platform keeps it. */
fun interface ChunkSource {
    /** Up to [max] bytes starting at [offset]; empty at the end. */
    suspend fun read(offset: Long, max: Int): ByteArray
}

/** A video file chosen on this device, already hashed so the player can verify it. */
class PickedVideo(
    val name: String,
    val size: Long,
    val sha256: String,
    val source: ChunkSource,
)

/** Where a send is: [sentBytes] of [totalBytes] so far. */
data class SendProgress(val sentBytes: Long, val totalBytes: Long) {
    val fraction: Float get() = if (totalBytes <= 0) 1f else (sentBytes.toDouble() / totalBytes).toFloat()
}

object SendVideo {
    const val CHUNK_BYTES = 4 * 1024 * 1024

    /** The show id for a picked video: the same file always gets the same id, so resending replaces it. */
    fun showId(video: PickedVideo): String = "video-" + video.sha256.take(12)

    fun manifestFor(video: PickedVideo, player: DeviceInfo, durationSeconds: Double = 0.0) = ShowManifest(
        id = showId(video),
        name = video.name.substringBeforeLast('.').ifBlank { video.name },
        fileName = video.name,
        sha256 = video.sha256,
        // 0 when unknown (a video sent as it is); the player loops whatever length the file is.
        durationSeconds = durationSeconds,
        width = player.displayWidth,
        height = player.displayHeight,
        fps = 30,
        projectorId = player.deviceId,
    )

    /**
     * Uploads [video] (resuming a previous partial upload), installs it as a show, syncs the
     * player's clock and makes it the default show, so it loops now and after a reboot.
     */
    suspend fun send(
        client: PlayerClient,
        video: PickedVideo,
        clock: ClockSync,
        durationSeconds: Double = 0.0,
        onProgress: (SendProgress) -> Unit = {},
    ): DeviceInfo {
        upload(client, video, onProgress)
        val info = client.info()
        val manifest = manifestFor(video, info, durationSeconds)
        client.installShow(manifest)
        client.syncClock(clock)
        val schedule = client.schedule()
        client.saveSchedule(schedule.copy(default = manifest.id))
        // Clears a "black" or "play" override so the schedule (and this video) takes over.
        return client.resume()
    }

    /** Name of the show a mapped render is installed as; each new render replaces the last one. */
    const val MAPPED_SHOW_FILE = "Mapped show.mp4"

    /** Sends a render of the mapping and removes the previous render from the projector to save space. */
    suspend fun sendRender(
        client: PlayerClient,
        video: PickedVideo,
        durationSeconds: Double,
        clock: ClockSync,
        onProgress: (SendProgress) -> Unit = {},
    ): DeviceInfo {
        var info = send(client, video, clock, durationSeconds, onProgress)
        val current = showId(video)
        info.shows.filter { it.fileName == MAPPED_SHOW_FILE && it.id != current }.forEach { old ->
            info = runCatching { client.removeShow(old.id) }.getOrDefault(info)
        }
        return info
    }

    suspend fun upload(client: PlayerClient, video: PickedVideo, onProgress: (SendProgress) -> Unit = {}) {
        val status = client.uploadStatus(video.sha256)
        if (status.complete) {
            onProgress(SendProgress(video.size, video.size))
            return
        }
        var offset = status.receivedBytes.coerceAtMost(video.size)
        onProgress(SendProgress(offset, video.size))
        var stalls = 0
        while (offset < video.size) {
            val bytes = video.source.read(offset, CHUNK_BYTES)
            check(bytes.isNotEmpty()) { "The video file ended early at $offset of ${video.size} bytes" }
            val reply = client.putChunk(video.sha256, offset, bytes)
            // On a 409 the player says how much it really has; carry on from there.
            if (reply.receivedBytes <= offset) {
                if (++stalls > 3) throw PlayerException(409, "The projector keeps refusing the upload at byte $offset")
            } else {
                stalls = 0
            }
            offset = reply.receivedBytes
            onProgress(SendProgress(offset, video.size))
        }
        client.complete(video.sha256)
    }
}
