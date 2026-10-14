package com.msc.ozprojectionmapperwizard

import com.msc.ozprojectionmapperwizard.controller.KeyValueStore
import com.msc.ozprojectionmapperwizard.controller.PickedVideo
import com.msc.ozprojectionmapperwizard.controller.RenderException
import com.msc.ozprojectionmapperwizard.controller.SendVideo
import com.msc.ozprojectionmapperwizard.controller.ShowRender
import com.msc.ozprojectionmapperwizard.core.model.MediaItem
import com.msc.ozprojectionmapperwizard.core.model.Project
import com.msc.ozprojectionmapperwizard.core.model.ProjectorMapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Renders on the PC with FFmpeg. Uses FFmpeg from PATH when installed, otherwise downloads a copy
 * into the app's folder the first time it is needed. Chosen files stay where they are; the store
 * remembers where each one is by its hash.
 */
class DesktopRenderer(private val store: KeyValueStore, private val home: File) : ShowRenderer {
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    private val exe = if (windows) ".exe" else ""
    private var tools: ShowRender? = null

    override suspend fun importMedia(onStep: (RenderStep) -> Unit): MediaItem? {
        val file = withContext(Dispatchers.Main) {
            val dialog = FileDialog(null as Frame?, "Choose a video, GIF or image", FileDialog.LOAD)
            dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in MEDIA_EXTENSIONS }
            dialog.isVisible = true
            dialog.files.firstOrNull()
        } ?: return null
        val render = tools(onStep)
        return withContext(Dispatchers.IO) {
            onStep(RenderStep("Reading ${file.name}…"))
            val item = render.probe(file)
            store.put(KEY_PREFIX + item.id, file.absolutePath)
            item
        }
    }

    override fun hasMedia(mediaId: String): Boolean = fileOf(mediaId)?.isFile == true

    override suspend fun render(project: Project, mapping: ProjectorMapping, onStep: (RenderStep) -> Unit): PickedVideo {
        val render = tools(onStep)
        val files = project.media.mapNotNull { item -> fileOf(item.id)?.let { item.id to it } }.toMap()
        val out = File(home, "renders/${SendVideo.MAPPED_SHOW_FILE}").apply { parentFile.mkdirs() }
        render.render(project, mapping, files, out) { done, total ->
            onStep(RenderStep("Rendering frame $done of $total…", done.toFloat() / total))
        }
        onStep(RenderStep("Checking the video…"))
        return withContext(Dispatchers.IO) { ShowRender.pickedVideo(out) }
    }

    private fun fileOf(mediaId: String) = store.get(KEY_PREFIX + mediaId)?.let(::File)

    private suspend fun tools(onStep: (RenderStep) -> Unit): ShowRender {
        tools?.let { return it }
        val found = withContext(Dispatchers.IO) { findTools() } ?: run {
            if (!windows) throw RenderException("Install FFmpeg (for example from your package manager) and try again.")
            withContext(Dispatchers.IO) { downloadTools(onStep) }
            findTools() ?: throw RenderException("FFmpeg downloaded but couldn't be found.")
        }
        return ShowRender(found.first, found.second).also { tools = it }
    }

    private fun findTools(): Pair<String, String>? {
        val dirs = listOf(File(home, "ffmpeg")) + System.getenv("PATH").orEmpty().split(File.pathSeparator).map(::File)
        return dirs.firstNotNullOfOrNull { dir ->
            val ffmpeg = File(dir, "ffmpeg$exe")
            val ffprobe = File(dir, "ffprobe$exe")
            if (ffmpeg.canExecute() && ffprobe.canExecute()) ffmpeg.absolutePath to ffprobe.absolutePath else null
        }
    }

    /** Fetches the Windows build of FFmpeg and keeps just ffmpeg.exe and ffprobe.exe. */
    private suspend fun downloadTools(onStep: (RenderStep) -> Unit) {
        onStep(RenderStep("Downloading FFmpeg (about 200 MB, only this once)…", 0f))
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val response = client.send(HttpRequest.newBuilder(URI(FFMPEG_ZIP)).build(), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) throw RenderException("Couldn't download FFmpeg (HTTP ${response.statusCode()}).")
        val total = response.headers().firstValueAsLong("content-length").orElse(-1)
        val target = File(home, "ffmpeg").apply { mkdirs() }
        var read = 0L
        val counting = object : java.io.FilterInputStream(response.body()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { n ->
                if (n > 0) {
                    read += n
                    if (total > 0) onStep(RenderStep("Downloading FFmpeg (about 200 MB, only this once)…", read.toFloat() / total))
                }
            }
        }
        ZipInputStream(counting.buffered(1 shl 20)).use { zip ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = zip.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && entry.name.contains("/bin/") && name in setOf("ffmpeg.exe", "ffprobe.exe")) {
                    val temp = File(target, "$name.part")
                    temp.outputStream().use { zip.copyTo(it) }
                    File(target, name).delete()
                    temp.renameTo(File(target, name))
                }
            }
        }
        if (!File(target, "ffmpeg.exe").isFile || !File(target, "ffprobe.exe").isFile) {
            throw RenderException("The FFmpeg download didn't contain ffmpeg.exe and ffprobe.exe.")
        }
    }

    private companion object {
        const val KEY_PREFIX = "media."
        const val FFMPEG_ZIP = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip"
        val MEDIA_EXTENSIONS = setOf(
            "mp4", "m4v", "mov", "mkv", "webm", "avi", "wmv", "3gp", "ts", "mpg", "mpeg",
            "gif", "png", "jpg", "jpeg", "bmp", "webp", "tif", "tiff",
        )
    }
}
