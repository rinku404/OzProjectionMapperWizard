package com.msc.ozprojectionmapperwizard.core.geometry

/**
 * Maps face coordinates (u, v in 0..1) to output pixels through a grid of control points.
 *
 * [points] is row-major, [rows] x [cols], first row at v = 0. Each cell is interpolated bilinearly,
 * so a finer grid follows curved surfaces more closely.
 */
class MeshWarp(val cols: Int, val rows: Int, val points: List<Vec2>) {
    init {
        require(cols >= 2 && rows >= 2) { "A mesh needs at least 2 x 2 points" }
        require(points.size == cols * rows) { "Expected ${cols * rows} points, got ${points.size}" }
    }

    fun point(col: Int, row: Int): Vec2 = points[row * cols + col]

    fun map(uv: Vec2): Vec2 {
        val gx = (uv.x.coerceIn(0.0, 1.0) * (cols - 1))
        val gy = (uv.y.coerceIn(0.0, 1.0) * (rows - 1))
        val c = gx.toInt().coerceAtMost(cols - 2)
        val r = gy.toInt().coerceAtMost(rows - 2)
        val tx = gx - c
        val ty = gy - r
        val top = lerp(point(c, r), point(c + 1, r), tx)
        val bottom = lerp(point(c, r + 1), point(c + 1, r + 1), tx)
        return lerp(top, bottom, ty)
    }

    companion object {
        /** Subdivides a corner-pinned quad into a mesh, keeping its perspective. */
        fun fromQuad(corners: List<Vec2>, cols: Int, rows: Int): MeshWarp {
            val h = Homography.unitSquareTo(corners)
            val pts = buildList {
                for (r in 0 until rows) for (c in 0 until cols) {
                    add(h.map(Vec2(c / (cols - 1.0), r / (rows - 1.0))))
                }
            }
            return MeshWarp(cols, rows, pts)
        }
    }
}
