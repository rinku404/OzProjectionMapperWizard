package com.msc.ozprojectionmapperwizard.controller

import com.msc.ozprojectionmapperwizard.core.model.Cue
import com.msc.ozprojectionmapperwizard.core.model.MediaItem
import com.msc.ozprojectionmapperwizard.core.model.MediaKind
import com.msc.ozprojectionmapperwizard.core.model.Project
import com.msc.ozprojectionmapperwizard.core.model.ProjectorMapping
import com.msc.ozprojectionmapperwizard.core.render.Compositor
import com.msc.ozprojectionmapperwizard.core.render.Frame
import com.msc.ozprojectionmapperwizard.core.render.Timeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders a project for one projector into an H.264 video with FFmpeg: each source is decoded to
 * raw frames, corner-pinned onto its surface with [Compositor] and the result is encoded at the
 * projector's native size, so the player just loops a plain video.
 */
class ShowRender(private val ffmpeg: String, private val ffprobe: String) {

    /** Reads a media file's size, length and kind. [id] is the file's sha256. */
    fun probe(file: File, id: String = sha256(file)): MediaItem {
        val result = run(
            listOf(
                ffprobe, "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=width,height:stream_tags=rotate:stream_side_data=rotation:format=duration,format_name",
                "-of", "json", file.absolutePath,
            ),
        )
        val json = runCatching { Json.parseToJsonElement(result).jsonObject }.getOrNull()
        val stream = (json?.get("streams") as? JsonArray)?.firstOrNull()?.jsonObject
            ?: throw RenderException("${file.name} doesn't look like a video, GIF or image.")
        var width = stream["width"]?.jsonPrimitive?.intOrNull ?: 0
        var height = stream["height"]?.jsonPrimitive?.intOrNull ?: 0
        if (width <= 0 || height <= 0) throw RenderException("${file.name} has no picture.")
        // Phone videos are often stored sideways with a rotation flag; FFmpeg turns them upright.
        if (abs(rotation(stream)) % 180 == 90) width = height.also { height = width }
        val format = json["format"]?.jsonObject
        val formatName = format?.get("format_name")?.jsonPrimitive?.contentOrNull.orEmpty()
        val duration = format?.get("duration")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        val still = formatName == "image2" || formatName.endsWith("_pipe") || duration == null || duration <= 0.0
        return MediaItem(
            id = id,
            fileName = file.name,
            kind = if (still) MediaKind.IMAGE else MediaKind.VIDEO,
            width = width,
            height = height,
            durationSeconds = if (still) null else duration,
        )
    }

    /**
     * Renders what [mapping] shows of [project] into [out] (an mp4). [files] maps media ids to the
     * files on this computer. [onFrame] gets the number of frames done and the total.
     */
    suspend fun render(
        project: Project,
        mapping: ProjectorMapping,
        files: Map<String, File>,
        out: File,
        onFrame: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val timeline = Timeline(project, mapping)
        val output = mapping.output
        val mediaById = project.media.associateBy { it.id }
        for (cue in timeline.cues) {
            val media = mediaById[cue.mediaId] ?: throw RenderException("A surface uses media that isn't in the project.")
            val file = files[cue.mediaId]
            if (file == null || !file.isFile) throw RenderException("${media.fileName} isn't on this computer any more. Choose it again.")
        }

        val sources = HashMap<Cue, Source>()
        val log = File.createTempFile("oz-encode", ".log")
        val encoder = ProcessBuilder(
            ffmpeg, "-v", "error", "-y",
            "-f", "rawvideo", "-pix_fmt", "bgra", "-s", "${output.width}x${output.height}", "-r", "${output.fps}", "-i", "-",
            "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
            "-g", "${output.fps * 2}", "-movflags", "+faststart", out.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(log).start()
        try {
            val frame = Frame(output.width, output.height)
            val bytes = ByteBuffer.allocate(output.width * output.height * 4).order(ByteOrder.LITTLE_ENDIAN)
            val stdin = encoder.outputStream.buffered(1 shl 20)
            for (i in 0 until timeline.frameCount) {
                coroutineContext.ensureActive()
                val t = timeline.timeOf(i)
                frame.fill(OPAQUE_BLACK)
                for (layer in timeline.layersAt(t)) {
                    val source = sources.getOrPut(layer.cue) { Source.open(this@ShowRender, mediaById.getValue(layer.cue.mediaId), files.getValue(layer.cue.mediaId), layer.cue, output.width, output.height, output.fps) }
                    val image = source.frameAt(t)
                    Compositor.draw(frame, image, layer.warp, layer.cue.sourceCrop, layer.opacity, layer.surface.featherPx)
                }
                // Cues that have ended free their decoder.
                sources.entries.removeAll { (cue, source) -> (t >= cue.startSeconds + cue.durationSeconds).also { if (it) source.close() } }
                writeFrame(stdin, frame, bytes)
                onFrame(i + 1, timeline.frameCount)
            }
            stdin.close()
            if (encoder.waitFor() != 0) throw RenderException("FFmpeg couldn't write the video. ${log.readText().trim().take(400)}")
        } catch (e: Throwable) {
            encoder.destroyForcibly()
            out.delete()
            throw if (e is java.io.IOException && !encoder.isAlive) RenderException("FFmpeg stopped. ${log.readText().trim().take(400)}") else e
        } finally {
            sources.values.forEach { it.close() }
            log.delete()
        }
    }

    /** Decodes one cue's media at the show's frame rate, scaled to fit the output. */
    private abstract class Source {
        abstract fun frameAt(t: Double): Frame
        open fun close() {}

        companion object {
            fun open(render: ShowRender, media: MediaItem, file: File, cue: Cue, maxWidth: Int, maxHeight: Int, fps: Int): Source {
                val (w, h) = fit(media, maxWidth, maxHeight)
                return if (media.kind == MediaKind.IMAGE) {
                    Still(render.decodeOne(file, w, h))
                } else {
                    Moving(render, file, cue, w, h, fps)
                }
            }
        }
    }

    private class Still(private val frame: Frame) : Source() {
        override fun frameAt(t: Double) = frame
    }

    /** Streams frames from FFmpeg, one per output frame; a video that ends without looping holds its last frame. */
    private class Moving(render: ShowRender, private val file: File, cue: Cue, private val w: Int, private val h: Int, fps: Int) : Source() {
        private val log = File.createTempFile("oz-decode", ".log")
        private val process = ProcessBuilder(
            buildList {
                add(render.ffmpeg); add("-v"); add("error")
                if (cue.loop) { add("-stream_loop"); add("-1") }
                add("-i"); add(file.absolutePath)
                add("-an"); add("-vf"); add("fps=$fps,scale=$w:$h:flags=area,format=bgra")
                add("-f"); add("rawvideo"); add("-")
            },
        ).redirectError(log).start()
        private val input = DataInputStream(process.inputStream.buffered(1 shl 20))
        private val bytes = ByteArray(w * h * 4)
        private var current: Frame? = null
        private var ended = false
        private var lastT = Double.NaN

        override fun frameAt(t: Double): Frame {
            // Two surfaces showing the same face ask for the same moment; decode it once.
            if (!ended && t != lastT) {
                lastT = t
                try {
                    input.readFully(bytes)
                    val pixels = IntArray(w * h)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels)
                    current = Frame(w, h, pixels)
                } catch (_: EOFException) {
                    ended = true
                }
            }
            return current ?: throw RenderException("FFmpeg couldn't read ${file.name}. ${log.readText().trim().take(400)}")
        }

        override fun close() {
            process.destroy()
            runCatching { input.close() }
            log.delete()
        }
    }

    /** A PNG of [media] at most [maxSize] pixels on its longer side; videos show a frame a little way in. */
    fun thumbnail(file: File, media: MediaItem, maxSize: Int = 320): ByteArray {
        val (w, h) = fit(media, maxSize, maxSize)
        val seek = media.durationSeconds?.let { min(1.0, it / 3) } ?: 0.0
        val process = ProcessBuilder(
            ffmpeg, "-v", "error", "-ss", "%.3f".format(java.util.Locale.ROOT, seek), "-i", file.absolutePath, "-frames:v", "1",
            "-vf", "scale=$w:$h:flags=area", "-f", "image2pipe", "-c:v", "png", "-",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val png = process.inputStream.use(InputStream::readAllBytes)
        process.waitFor()
        if (png.isEmpty()) throw RenderException("FFmpeg couldn't make a preview of ${file.name}.")
        return png
    }

    /**
     * One frame of the show at [atSeconds] as a PNG at the projector's size: how the mapping looks
     * with its current media, for previewing a choice before rendering the whole show.
     */
    fun preview(project: Project, mapping: ProjectorMapping, files: Map<String, File>, atSeconds: Double = 1.0): ByteArray {
        val timeline = Timeline(project, mapping)
        val output = mapping.output
        val t = atSeconds.coerceIn(0.0, project.scene.durationSeconds)
        val frame = Frame(output.width, output.height).apply { fill(OPAQUE_BLACK) }
        val mediaById = project.media.associateBy { it.id }
        val decoded = HashMap<Pair<String, Double>, Frame>()
        for (layer in timeline.layersAt(t)) {
            val media = mediaById[layer.cue.mediaId] ?: continue
            val file = files[media.id]?.takeIf { it.isFile } ?: continue
            val local = (t - layer.cue.startSeconds).coerceAtLeast(0.0)
            val duration = media.durationSeconds ?: 0.0
            val at = when {
                duration <= 0.0 -> 0.0
                layer.cue.loop -> local % duration
                else -> min(local, duration - 0.05).coerceAtLeast(0.0)
            }
            val (w, h) = fit(media, output.width, output.height)
            val image = decoded.getOrPut(media.id to at) { decodeOne(file, w, h, at) }
            Compositor.draw(frame, image, layer.warp, layer.cue.sourceCrop, layer.opacity, layer.surface.featherPx)
        }
        val image = java.awt.image.BufferedImage(output.width, output.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, output.width, output.height, frame.pixels, 0, output.width)
        return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun decodeOne(file: File, w: Int, h: Int, atSeconds: Double = 0.0): Frame {
        val seek = if (atSeconds > 0) listOf("-ss", "%.3f".format(java.util.Locale.ROOT, atSeconds)) else emptyList()
        val process = ProcessBuilder(
            listOf(ffmpeg, "-v", "error") + seek + listOf(
                "-i", file.absolutePath, "-frames:v", "1",
                "-vf", "scale=$w:$h:flags=area,format=bgra", "-f", "rawvideo", "-",
            ),
        ).start()
        val stderr = StringBuilder()
        val drain = Thread { stderr.append(process.errorStream.bufferedReader().readText()) }.apply { start() }
        val bytes = process.inputStream.use { it.readNBytes(w * h * 4) }
        process.waitFor()
        drain.join()
        if (bytes.size != w * h * 4) throw RenderException("FFmpeg couldn't read ${file.name}. ${stderr.trim().take(400)}")
        val pixels = IntArray(w * h)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels)
        return Frame(w, h, pixels)
    }

    private fun run(command: List<String>): String {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: java.io.IOException) {
            throw RenderException("Couldn't start ${File(command.first()).name}: ${e.message}")
        }
        val text = process.inputStream.use(InputStream::readAllBytes).decodeToString()
        if (process.waitFor() != 0) throw RenderException(text.trim().take(400))
        return text
    }

    private fun rotation(stream: JsonObject): Int {
        val tag = (stream["tags"] as? JsonObject)?.get("rotate")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val side = (stream["side_data_list"] as? JsonArray)?.firstNotNullOfOrNull {
            (it as? JsonObject)?.get("rotation")?.jsonPrimitive?.intOrNull
        }
        return tag ?: side ?: 0
    }

    companion object {
        private const val OPAQUE_BLACK = 0xFF000000.toInt()

        /** Fits [media] inside the box: a bigger source only costs time, as each pixel is sampled once. Even sizes for FFmpeg. */
        private fun fit(media: MediaItem, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
            val scale = min(1.0, min(maxWidth.toDouble() / media.width, maxHeight.toDouble() / media.height))
            val w = (media.width * scale).roundToInt().coerceAtLeast(2) / 2 * 2
            val h = (media.height * scale).roundToInt().coerceAtLeast(2) / 2 * 2
            return w to h
        }

        private fun writeFrame(out: OutputStream, frame: Frame, buffer: ByteBuffer) {
            buffer.clear()
            buffer.asIntBuffer().put(frame.pixels)
            out.write(buffer.array())
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(1 shl 20).use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** A file on this computer as something [SendVideo] can upload. */
        fun pickedVideo(file: File, name: String = file.name): PickedVideo =
            PickedVideo(name, file.length(), sha256(file)) { offset, max ->
                withContext(Dispatchers.IO) {
                    java.io.RandomAccessFile(file, "r").use { raf ->
                        val length = minOf(max.toLong(), raf.length() - offset).coerceAtLeast(0).toInt()
                        ByteArray(length).also { raf.seek(offset); raf.readFully(it) }
                    }
                }
            }
    }
}
