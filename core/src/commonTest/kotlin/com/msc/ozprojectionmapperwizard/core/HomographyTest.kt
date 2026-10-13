package com.msc.ozprojectionmapperwizard.core

import com.msc.ozprojectionmapperwizard.core.geometry.Homography
import com.msc.ozprojectionmapperwizard.core.geometry.MeshWarp
import com.msc.ozprojectionmapperwizard.core.geometry.Vec2
import com.msc.ozprojectionmapperwizard.core.geometry.isInside
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomographyTest {
    private val corners = listOf(Vec2(212.0, 140.0), Vec2(880.0, 96.0), Vec2(902.0, 1010.0), Vec2(190.0, 980.0))

    @Test
    fun mapsUnitSquareCornersOntoQuad() {
        val h = Homography.unitSquareTo(corners)
        Homography.UNIT_SQUARE.zip(corners).forEach { (src, dst) -> assertClose(dst, h.map(src)) }
    }

    @Test
    fun inverseRoundTrips() {
        val h = Homography.unitSquareTo(corners)
        val p = Vec2(0.3, 0.7)
        assertClose(p, h.inverse().map(h.map(p)))
    }

    @Test
    fun centreFollowsPerspectiveNotAverage() {
        // In a trapezoid the projected centre is where the diagonals cross, not the corner average.
        val trapezoid = listOf(Vec2(0.0, 0.0), Vec2(100.0, 0.0), Vec2(75.0, 100.0), Vec2(25.0, 100.0))
        val centre = Homography.unitSquareTo(trapezoid).map(Vec2(0.5, 0.5))
        assertClose(Vec2(50.0, 200.0 / 3.0), centre)
    }

    @Test
    fun rejectsCollinearCorners() {
        val flat = listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(2.0, 0.0), Vec2(3.0, 0.0))
        assertFailsWith<IllegalArgumentException> { Homography.unitSquareTo(flat) }
    }

    @Test
    fun meshFromQuadMatchesHomography() {
        val h = Homography.unitSquareTo(corners)
        val mesh = MeshWarp.fromQuad(corners, cols = 5, rows = 5)
        // Grid points are exact; points between them are close for a fine enough grid.
        assertClose(h.map(Vec2(0.25, 0.75)), mesh.map(Vec2(0.25, 0.75)))
        assertClose(h.map(Vec2(0.4, 0.6)), mesh.map(Vec2(0.4, 0.6)), tolerance = 3.0)
    }

    @Test
    fun pointInPolygon() {
        val square = listOf(Vec2(0.0, 0.0), Vec2(10.0, 0.0), Vec2(10.0, 10.0), Vec2(0.0, 10.0))
        assertTrue(Vec2(5.0, 5.0).isInside(square))
        assertFalse(Vec2(15.0, 5.0).isInside(square))
    }

    private fun assertClose(expected: Vec2, actual: Vec2, tolerance: Double = 1e-6) {
        assertTrue(
            abs(expected.x - actual.x) < tolerance && abs(expected.y - actual.y) < tolerance,
            "Expected $expected but was $actual",
        )
    }
}
