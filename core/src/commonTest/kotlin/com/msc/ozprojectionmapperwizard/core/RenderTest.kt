package com.msc.ozprojectionmapperwizard.core

import com.msc.ozprojectionmapperwizard.core.geometry.Vec2
import com.msc.ozprojectionmapperwizard.core.model.Cue
import com.msc.ozprojectionmapperwizard.core.model.Face
import com.msc.ozprojectionmapperwizard.core.model.Output
import com.msc.ozprojectionmapperwizard.core.model.Project
import com.msc.ozprojectionmapperwizard.core.model.ProjectorMapping
import com.msc.ozprojectionmapperwizard.core.model.Rect
import com.msc.ozprojectionmapperwizard.core.model.Scene
import com.msc.ozprojectionmapperwizard.core.model.Surface
import com.msc.ozprojectionmapperwizard.core.model.Warp
import com.msc.ozprojectionmapperwizard.core.render.Compositor
import com.msc.ozprojectionmapperwizard.core.render.Frame
import com.msc.ozprojectionmapperwizard.core.render.Timeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RenderTest {
    private val black = 0xFF000000.toInt()
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun solid(w: Int, h: Int, argb: Int) = Frame(w, h).also { it.fill(argb) }

    private fun rectCorners(l: Double, t: Double, r: Double, b: Double) = listOf(Vec2(l, t), Vec2(r, t), Vec2(r, b), Vec2(l, b))

    @Test
    fun anAxisAlignedQuadCoversExactlyItsPixels() {
        val out = solid(100, 50, black)
        Compositor.drawQuad(out, solid(4, 4, red), rectCorners(10.0, 10.0, 30.0, 20.0))
        assertEquals(red, out[10, 10])
        assertEquals(red, out[29, 19])
        assertEquals(black, out[30, 15])
        assertEquals(black, out[5, 5])
        assertEquals(200, out.pixels.count { it == red })
    }

    @Test
    fun theSourceIsMappedCornerToCornerInPerspective() {
        // Left half red, right half blue, pinned onto a trapezoid.
        val src = Frame(2, 1, intArrayOf(red, blue))
        val out = solid(200, 100, black)
        val corners = listOf(Vec2(40.0, 10.0), Vec2(160.0, 10.0), Vec2(190.0, 90.0), Vec2(10.0, 90.0))
        Compositor.drawQuad(out, src, corners)
        assertEquals(red, out[45, 12])
        assertEquals(blue, out[155, 12])
        assertEquals(red, out[15, 88])
        assertEquals(blue, out[185, 88])
        assertEquals(black, out[5, 5])
    }

    @Test
    fun cropOpacityAndFeather() {
        val src = Frame(2, 1, intArrayOf(red, blue))
        val out = solid(40, 40, black)
        Compositor.drawQuad(out, src, rectCorners(0.0, 0.0, 40.0, 40.0), crop = Rect(0.5, 0.0, 1.0, 1.0), opacity = 0.5)
        val c = out[20, 20]
        assertEquals(0, (c shr 16) and 0xFF)
        assertTrue((c and 0xFF) in 126..128)

        val feathered = solid(100, 100, black)
        Compositor.drawQuad(feathered, solid(1, 1, red), rectCorners(0.0, 0.0, 100.0, 100.0), featherPx = 20.0)
        assertTrue(((feathered[1, 50] shr 16) and 0xFF) < 30)
        assertEquals(red, feathered[50, 50])
    }

    @Test
    fun timelineFadesAndOrdersLayers() {
        val faces = listOf(Face("a", "A"), Face("b", "B"))
        val project = Project(
            name = "p",
            scene = Scene(
                faces = faces,
                cues = listOf(
                    Cue("a", "m1", startSeconds = 0.0, durationSeconds = 10.0, fadeInSeconds = 2.0),
                    Cue("b", "m2", startSeconds = 5.0, durationSeconds = 5.0),
                ),
                durationSeconds = 10.0,
            ),
        )
        val mapping = ProjectorMapping(
            "p1", "P", Output.HD_720,
            surfaces = listOf(
                Surface("s1", "a", Warp.Quad(rectCorners(0.0, 0.0, 10.0, 10.0))),
                Surface("s2", "b", Warp.Quad(rectCorners(0.0, 0.0, 10.0, 10.0))),
            ),
        )
        val timeline = Timeline(project, mapping)
        assertEquals(300, timeline.frameCount)
        assertEquals(listOf("s1"), timeline.layersAt(1.0).map { it.surface.id })
        assertEquals(0.5, timeline.layersAt(1.0).single().opacity, 1e-9)
        assertEquals(listOf("s1", "s2"), timeline.layersAt(6.0).map { it.surface.id })
        assertEquals(2, timeline.cues.size)
    }
}
