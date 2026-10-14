package com.msc.ozprojectionmapperwizard.controller

import com.msc.ozprojectionmapperwizard.core.geometry.Vec2
import com.msc.ozprojectionmapperwizard.core.model.MediaKind
import com.msc.ozprojectionmapperwizard.core.model.Output
import com.msc.ozprojectionmapperwizard.core.model.Project
import com.msc.ozprojectionmapperwizard.core.model.ProjectorMapping
import com.msc.ozprojectionmapperwizard.core.model.Warp
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs the real FFmpeg; skipped on machines without it. */
class ShowRenderTest {
    private val ffmpeg = which("ffmpeg")
    private val ffprobe = which("ffprobe")
    private val dir = Files.createTempDirectory("oz-render").toFile()

    @Test
    fun probesVideosGifsAndImages() {
        if (ffmpeg == null || ffprobe == null) return
        val render = ShowRender(ffmpeg, ffprobe)
        val video = make("clip.mp4", "testsrc=size=320x240:rate=30:duration=2")
        val gif = make("anim.gif", "testsrc=size=160x120:rate=10:duration=1")
        val image = make("still.png", "color=red:size=64x48", "-frames:v", "1")

        render.probe(video).let {
            assertEquals(MediaKind.VIDEO, it.kind)
            assertEquals(320, it.width)
            assertEquals(2.0, it.durationSeconds!!, 0.1)
        }
        assertEquals(MediaKind.VIDEO, render.probe(gif).kind)
        render.probe(image).let {
            assertEquals(MediaKind.IMAGE, it.kind)
            assertEquals(48, it.height)
            assertEquals(null, it.durationSeconds)
        }
    }

    @Test
    fun rendersEachSurfaceWarpedAtTheProjectorsSize() = runBlocking {
        if (ffmpeg == null || ffprobe == null) return@runBlocking
        val render = ShowRender(ffmpeg, ffprobe)
        val red = make("red.png", "color=red:size=64x48", "-frames:v", "1")
        val gif = make("blue.gif", "color=blue:size=80x60:rate=10:duration=0.5")

        var project = Project(name = "Test", projectors = listOf(ProjectorMapping("p", "Projector", Output(320, 180, 10))))
        project = Mapping.addSurface(project, "p").first
        project = Mapping.addSurface(project, "p").first
        // Left and right halves, the right one a slanted quad.
        project = project.copy(
            projectors = project.projectors.map { p ->
                p.copy(
                    surfaces = listOf(
                        p.surfaces[0].copy(warp = Warp.Quad(listOf(Vec2(10.0, 10.0), Vec2(150.0, 10.0), Vec2(150.0, 170.0), Vec2(10.0, 170.0)))),
                        p.surfaces[1].copy(warp = Warp.Quad(listOf(Vec2(180.0, 20.0), Vec2(310.0, 5.0), Vec2(300.0, 175.0), Vec2(170.0, 160.0)))),
                    ),
                )
            },
        )
        val redItem = render.probe(red)
        val gifItem = render.probe(gif)
        project = Content.setDuration(project, 2.0)
        project = Content.setMedia(project, "face-1", redItem)
        project = Content.setMedia(project, "face-2", gifItem)

        val out = File(dir, "show.mp4")
        var frames = 0
        render.render(project, project.projectors.single(), mapOf(redItem.id to red, gifItem.id to gif), out) { done, total ->
            frames = done
            assertEquals(20, total)
        }
        assertEquals(20, frames)

        val info = render.probe(out)
        assertEquals(320, info.width)
        assertEquals(180, info.height)
        assertEquals(2.0, info.durationSeconds!!, 0.15)

        // A frame from the end (after the GIF has looped): red on the left, blue on the right, black between.
        val frame = decodeFrame(out, 1.9, 320, 180)
        fun px(x: Int, y: Int) = frame[(y * 320 + x)]
        assertTrue(isNear(px(80, 90), 0xFF0000), "left surface should be red, was ${hex(px(80, 90))}")
        assertTrue(isNear(px(240, 90), 0x0000FF), "right surface should be blue, was ${hex(px(240, 90))}")
        assertTrue(isNear(px(162, 90), 0x000000), "the gap should be black, was ${hex(px(162, 90))}")
        assertTrue(isNear(px(315, 175), 0x000000), "outside the slanted corner should be black")
    }

    @Test
    fun rendersABlenderExportOntoPolygons() = runBlocking {
        if (ffmpeg == null || ffprobe == null) return@runBlocking
        val render = ShowRender(ffmpeg, ffprobe)
        make("Roof.mp4", "color=red:size=200x100:rate=10:duration=1")
        val export = BlenderImport.parse(
            """{"format": "oz-surfaces", "version": 1, "fps": 10, "durationSeconds": 1.0, "surfaces": [
                 {"name": "Roof", "media": "Roof.mp4", "width": 200, "height": 100, "points": [[0, 1], [0.5, 0], [1, 1]]}]}""",
        )
        val roof = File(dir, "Roof.mp4")
        val item = render.probe(roof)
        var project = Project(name = "T", projectors = listOf(ProjectorMapping("p", "P", Output(320, 180, 10))))
        project = BlenderImport.apply(project, "p", LoadedExport(export, mapOf("Roof" to item)))
        val surface = project.projectors.single().surfaces.single()
        project = Mapping.moveCorner(project, "p", surface.id, 1, Vec2(200.0, 10.0))
        val corners = Mapping.corners(project.projectors.single().surfaces.single())

        val out = File(dir, "roof-show.mp4")
        render.render(project, project.projectors.single(), mapOf(item.id to roof), out)
        val frame = decodeFrame(out, 0.5, 320, 180)
        fun px(p: Vec2) = frame[p.y.toInt() * 320 + p.x.toInt()]
        val centre = (corners[0] + corners[1] + corners[2]) * (1.0 / 3)
        assertTrue(isNear(px(centre), 0xFF0000), "inside the triangle should be red, was ${hex(px(centre))}")
        assertTrue(isNear(px(Vec2(corners[0].x + 2, corners[1].y + 2)), 0x000000), "beside the apex should be black")
    }

    @Test
    fun makesThumbnailsAndAPreviewOfTheMapping() {
        if (ffmpeg == null || ffprobe == null) return
        val render = ShowRender(ffmpeg, ffprobe)
        val clip = make("wide.mp4", "color=green:size=640x360:rate=10:duration=2")
        val item = render.probe(clip)
        val thumb = javax.imageio.ImageIO.read(render.thumbnail(clip, item, maxSize = 160).inputStream())
        assertEquals(160, thumb.width)
        assertEquals(90, thumb.height)

        var project = Project(name = "T", projectors = listOf(ProjectorMapping("p", "P", Output(320, 180, 10))))
        project = Mapping.addSurface(project, "p").first
        project = Content.setMedia(Content.setDuration(project, 2.0), "face-1", item)
        val png = render.preview(project, project.projectors.single(), mapOf(item.id to clip))
        val image = javax.imageio.ImageIO.read(png.inputStream())
        assertEquals(320, image.width)
        val corners = Mapping.corners(project.projectors.single().surfaces.single())
        val centre = corners.reduce { a, b -> a + b } * 0.25
        assertTrue(isNear(image.getRGB(centre.x.toInt(), centre.y.toInt()), 0x008000), "the surface shows the clip")
        assertTrue(isNear(image.getRGB(2, 2), 0x000000), "outside stays black")
    }

    private fun make(name: String, lavfi: String, vararg extra: String): File {
        val file = File(dir, name)
        val p = ProcessBuilder(listOf(ffmpeg!!, "-v", "error", "-y", "-f", "lavfi", "-i", lavfi) + extra + file.absolutePath)
            .redirectErrorStream(true).start()
        val log = p.inputStream.readAllBytes().decodeToString()
        check(p.waitFor() == 0) { log }
        return file
    }

    private fun decodeFrame(file: File, at: Double, w: Int, h: Int): IntArray {
        val p = ProcessBuilder(ffmpeg!!, "-v", "error", "-ss", at.toString(), "-i", file.absolutePath, "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", "-")
            .start()
        val bytes = p.inputStream.readAllBytes()
        p.waitFor()
        assertEquals(w * h * 3, bytes.size)
        return IntArray(w * h) { i ->
            ((bytes[i * 3].toInt() and 0xFF) shl 16) or ((bytes[i * 3 + 1].toInt() and 0xFF) shl 8) or (bytes[i * 3 + 2].toInt() and 0xFF)
        }
    }

    // H.264 and yuv420p shift colours a little.
    private fun isNear(rgb: Int, expected: Int) = (0..2).all { shift ->
        kotlin.math.abs(((rgb shr (shift * 8)) and 0xFF) - ((expected shr (shift * 8)) and 0xFF)) < 40
    }

    private fun hex(rgb: Int) = "%06x".format(rgb and 0xFFFFFF)

    private companion object {
        fun which(name: String): String? =
            System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, name) }.firstOrNull { it.canExecute() }?.absolutePath
    }
}
