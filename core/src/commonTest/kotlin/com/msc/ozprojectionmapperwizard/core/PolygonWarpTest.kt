package com.msc.ozprojectionmapperwizard.core

import com.msc.ozprojectionmapperwizard.core.geometry.Homography
import com.msc.ozprojectionmapperwizard.core.geometry.PolygonWarp
import com.msc.ozprojectionmapperwizard.core.geometry.Vec2
import com.msc.ozprojectionmapperwizard.core.model.Warp
import com.msc.ozprojectionmapperwizard.core.render.Compositor
import com.msc.ozprojectionmapperwizard.core.render.Frame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolygonWarpTest {
    private fun near(a: Vec2, b: Vec2, tolerance: Double = 1e-6) =
        assertTrue(hypot(a.x - b.x, a.y - b.y) < tolerance, "expected $b, got $a")

    private fun hexagon(cx: Double, cy: Double, r: Double) =
        (0 until 6).map { i -> Vec2(cx + r * cos(i * PI / 3), cy + r * sin(i * PI / 3)) }

    @Test
    fun everyCornerLandsWhereItWasDragged() {
        val source = hexagon(0.5, 0.5, 0.5)
        val corners = listOf(Vec2(400.0, 300.0), Vec2(330.0, 420.0), Vec2(180.0, 430.0), Vec2(100.0, 290.0), Vec2(190.0, 160.0), Vec2(320.0, 170.0))
        val warp = PolygonWarp(source, corners)
        source.indices.forEach { i ->
            near(warp.forward(source[i]), corners[i])
            near(warp.inverse(corners[i])!!, source[i])
        }
        assertTrue(warp.isValid())
        assertEquals(4, warp.triangles.size)
    }

    @Test
    fun aPerspectiveViewOfAPolygonIsReproducedExactly() {
        // A planar hexagon seen in perspective is a homography of its face; the fit recovers it.
        val h = Homography.fromQuads(Homography.UNIT_SQUARE, listOf(Vec2(100.0, 80.0), Vec2(600.0, 40.0), Vec2(650.0, 600.0), Vec2(60.0, 500.0)))
        val source = hexagon(0.5, 0.5, 0.45)
        val warp = PolygonWarp(source, source.map(h::map))
        listOf(Vec2(0.5, 0.5), Vec2(0.3, 0.6), Vec2(0.7, 0.35)).forEach { p ->
            near(warp.forward(p), h.map(p), 1e-6)
            near(warp.inverse(h.map(p))!!, p, 1e-6)
        }
    }

    @Test
    fun fourCornersIsTheUsualCornerPin() {
        val corners = listOf(Vec2(40.0, 10.0), Vec2(160.0, 10.0), Vec2(190.0, 90.0), Vec2(10.0, 90.0))
        val warp = PolygonWarp(Homography.UNIT_SQUARE, corners)
        val h = Homography.unitSquareTo(corners)
        near(warp.forward(Vec2(0.3, 0.7)), h.map(Vec2(0.3, 0.7)))
    }

    @Test
    fun concaveShapesAreTriangulatedAndOutsideIsEmpty() {
        // An L shape.
        val source = listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(1.0, 0.5), Vec2(0.5, 0.5), Vec2(0.5, 1.0), Vec2(0.0, 1.0))
        val warp = PolygonWarp(source, source.map { it * 100.0 })
        assertEquals(4, warp.triangles.size)
        assertNull(warp.inverse(Vec2(75.0, 75.0)))
        near(warp.inverse(Vec2(25.0, 75.0))!!, Vec2(0.25, 0.75))
    }

    @Test
    fun foldingACornerAcrossIsInvalid() {
        val source = listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(0.5, 1.0))
        assertTrue(PolygonWarp(source, listOf(Vec2(0.0, 0.0), Vec2(100.0, 0.0), Vec2(50.0, 100.0))).isValid())
        assertFalse(PolygonWarp(source, listOf(Vec2(0.0, 0.0), Vec2(100.0, 0.0), Vec2(50.0, -100.0))).isValid())
    }

    @Test
    fun compositorFillsATriangleAndNothingElse() {
        val out = Frame(100, 100).apply { fill(0xFF000000.toInt()) }
        val red = Frame(2, 2, IntArray(4) { 0xFFFF0000.toInt() })
        val warp = Warp.Polygon(
            corners = listOf(Vec2(10.0, 10.0), Vec2(90.0, 10.0), Vec2(10.0, 90.0)),
            source = listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(0.0, 1.0)),
        )
        Compositor.draw(out, red, warp)
        assertEquals(0xFFFF0000.toInt(), out[20, 20])
        assertEquals(0xFF000000.toInt(), out[80, 80])
        assertEquals(0xFF000000.toInt(), out[5, 5])
        assertNotNull(out)
    }
}
