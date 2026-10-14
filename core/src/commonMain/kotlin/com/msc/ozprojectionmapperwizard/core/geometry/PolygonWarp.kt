package com.msc.ozprojectionmapperwizard.core.geometry

import com.msc.ozprojectionmapperwizard.core.model.Warp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Maps a polygon of any number of corners in face space ([source], 0..1 of the media image) onto
 * the same polygon dragged into output pixels ([corners]).
 *
 * A flat surface seen by a projector is a perspective transform of its face, so the warp is the
 * homography that best fits all the corners (exact for 4), plus a small per-triangle correction
 * that makes every corner land exactly where it was dragged. With 4 corners it is the plain
 * corner pin, so quads and polygons render the same way.
 */
class PolygonWarp(val source: List<Vec2>, val corners: List<Vec2>) {
    init {
        require(source.size >= 3 && source.size == corners.size) { "Need the same 3 or more corners in both polygons" }
    }

    /** Corner indices of triangles covering the polygon, wound like [source]. */
    val triangles: List<IntArray> = triangulate(source)

    private val forwardFit = fit(source, corners)
    private val inverseFit = fit(corners, source)
    private val forwardResidual = corners.indices.map { corners[it] - forwardFit.map(source[it]) }
    private val inverseResidual = corners.indices.map { source[it] - inverseFit.map(corners[it]) }

    /** Where a face point lands in the output. Points outside the polygon extrapolate from the nearest triangle. */
    fun forward(p: Vec2): Vec2 = forwardFit.map(p) + correction(p, source, forwardResidual)

    /** The face point drawn at output pixel [p], or null when [p] is outside the polygon. */
    fun inverse(p: Vec2): Vec2? {
        val (t, b) = containing(p, corners) ?: return null
        return inverseFit.map(p) + blend(t, b, inverseResidual)
    }

    /** True when no triangle is folded over or squashed flat, so the warp is one-to-one. */
    fun isValid(): Boolean = triangles.all { t ->
        val s = signedArea(source[t[0]], source[t[1]], source[t[2]])
        val c = signedArea(corners[t[0]], corners[t[1]], corners[t[2]])
        s != 0.0 && c != 0.0 && (s > 0) == (c > 0) && abs(c) > MIN_AREA
    }

    /**
     * Grid lines across the face's bounding box, mapped to the output as polylines with
     * [steps] segments each (a polygon's lines can bend at triangle edges).
     */
    fun gridLines(divisions: Int, steps: Int = 24): List<List<Vec2>> {
        val minX = source.minOf { it.x }; val maxX = source.maxOf { it.x }
        val minY = source.minOf { it.y }; val maxY = source.maxOf { it.y }
        val lines = ArrayList<List<Vec2>>()
        for (i in 1 until divisions) {
            val x = minX + (maxX - minX) * i / divisions
            val y = minY + (maxY - minY) * i / divisions
            lines += (0..steps).map { s -> forward(Vec2(x, minY + (maxY - minY) * s / steps)) }
            lines += (0..steps).map { s -> forward(Vec2(minX + (maxX - minX) * s / steps, y)) }
        }
        return lines
    }

    private fun correction(p: Vec2, polygon: List<Vec2>, residual: List<Vec2>): Vec2 {
        val (t, b) = containing(p, polygon) ?: nearest(p, polygon)
        return blend(t, b, residual)
    }

    private fun blend(t: IntArray, b: DoubleArray, residual: List<Vec2>) =
        residual[t[0]] * b[0] + residual[t[1]] * b[1] + residual[t[2]] * b[2]

    private fun containing(p: Vec2, polygon: List<Vec2>): Pair<IntArray, DoubleArray>? {
        for (t in triangles) {
            val b = barycentric(p, polygon[t[0]], polygon[t[1]], polygon[t[2]]) ?: continue
            if (b.all { it >= -INSIDE_TOLERANCE }) return t to b
        }
        return null
    }

    private fun nearest(p: Vec2, polygon: List<Vec2>): Pair<IntArray, DoubleArray> {
        val t = triangles.minBy { t ->
            val c = (polygon[t[0]] + polygon[t[1]] + polygon[t[2]]) * (1.0 / 3)
            hypot(c.x - p.x, c.y - p.y)
        }
        return t to (barycentric(p, polygon[t[0]], polygon[t[1]], polygon[t[2]]) ?: doubleArrayOf(1.0 / 3, 1.0 / 3, 1.0 / 3))
    }

    companion object {
        private const val INSIDE_TOLERANCE = 1e-9
        private const val MIN_AREA = 1e-9

        fun signedArea(a: Vec2, b: Vec2, c: Vec2) = ((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)) / 2

        fun barycentric(p: Vec2, a: Vec2, b: Vec2, c: Vec2): DoubleArray? {
            val det = (b.y - c.y) * (a.x - c.x) + (c.x - b.x) * (a.y - c.y)
            if (det == 0.0) return null
            val l1 = ((b.y - c.y) * (p.x - c.x) + (c.x - b.x) * (p.y - c.y)) / det
            val l2 = ((c.y - a.y) * (p.x - c.x) + (a.x - c.x) * (p.y - c.y)) / det
            return doubleArrayOf(l1, l2, 1 - l1 - l2)
        }

        /** Ear clipping: splits a simple polygon (convex or not, either winding) into triangles. */
        fun triangulate(polygon: List<Vec2>): List<IntArray> {
            val area = polygon.indices.sumOf { i -> val a = polygon[i]; val b = polygon[(i + 1) % polygon.size]; a.x * b.y - b.x * a.y }
            val ccw = area > 0
            val left = polygon.indices.toMutableList()
            val result = ArrayList<IntArray>()
            var guard = 0
            while (left.size > 3 && guard++ < polygon.size * polygon.size) {
                var clipped = false
                for (i in left.indices) {
                    val ia = left[(i + left.size - 1) % left.size]; val ib = left[i]; val ic = left[(i + 1) % left.size]
                    val a = polygon[ia]; val b = polygon[ib]; val c = polygon[ic]
                    val turn = signedArea(a, b, c)
                    if (turn == 0.0 || (turn > 0) != ccw) continue
                    val blocked = left.any { j ->
                        j != ia && j != ib && j != ic &&
                            (barycentric(polygon[j], a, b, c)?.all { it > 0 } ?: false)
                    }
                    if (blocked) continue
                    result += intArrayOf(ia, ib, ic)
                    left.removeAt(i)
                    clipped = true
                    break
                }
                // Not a simple polygon (or numerically degenerate): fall back to a fan.
                if (!clipped) break
            }
            if (left.size > 3) {
                for (k in 1 until left.size - 1) result += intArrayOf(left[0], left[k], left[k + 1])
            } else {
                result += intArrayOf(left[0], left[1], left[2])
            }
            return result
        }

        /**
         * The homography that best maps [from] onto [to]: exact for 4 points, least squares for more,
         * affine for 3. Points are normalised first so the solve stays well conditioned.
         */
        fun fit(from: List<Vec2>, to: List<Vec2>): Homography {
            if (from.size == 4) runCatching { return Homography.fromQuads(from, to) }
            val nf = normaliser(from).first
            val (nt, ntBack) = normaliser(to)
            val a = from.map(nf::map)
            val b = to.map(nt::map)
            val h = if (from.size == 3) affine(a, b) else leastSquares(a, b) ?: affine(a.take(3), b.take(3))
            // h maps normalised points; undo the normalisation on the output side.
            val full = multiply(ntBack, multiply(h, nf))
            return if (full.m[8] != 0.0) scaled(full) else full
        }

        private fun affine(a: List<Vec2>, b: List<Vec2>): Homography {
            // Solve for the 6 affine terms from 3 points (or the first 3 of a degenerate set).
            val m = Array(6) { DoubleArray(7) }
            for (k in 0 until 3) {
                m[2 * k] = doubleArrayOf(a[k].x, a[k].y, 1.0, 0.0, 0.0, 0.0, b[k].x)
                m[2 * k + 1] = doubleArrayOf(0.0, 0.0, 0.0, a[k].x, a[k].y, 1.0, b[k].y)
            }
            val h = solve(m) ?: doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
            return Homography(doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], 0.0, 0.0, 1.0))
        }

        private fun leastSquares(a: List<Vec2>, b: List<Vec2>): Homography? {
            // Normal equations of the DLT with h[8] = 1.
            val ata = Array(8) { DoubleArray(9) }
            fun add(row: DoubleArray, rhs: Double) {
                for (i in 0 until 8) {
                    for (j in 0 until 8) ata[i][j] += row[i] * row[j]
                    ata[i][8] += row[i] * rhs
                }
            }
            for (k in a.indices) {
                val (x, y) = a[k]
                val (u, v) = b[k]
                add(doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y), u)
                add(doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y), v)
            }
            val h = solve(ata) ?: return null
            return Homography(doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0))
        }

        /** Moves the points' centre to the origin and scales their mean distance to √2. */
        private fun normaliser(points: List<Vec2>): Pair<Homography, Homography> {
            val cx = points.sumOf { it.x } / points.size
            val cy = points.sumOf { it.y } / points.size
            val mean = points.sumOf { hypot(it.x - cx, it.y - cy) } / points.size
            val s = if (mean > 0) sqrt(2.0) / mean else 1.0
            val forward = Homography(doubleArrayOf(s, 0.0, -s * cx, 0.0, s, -s * cy, 0.0, 0.0, 1.0))
            val back = Homography(doubleArrayOf(1 / s, 0.0, cx, 0.0, 1 / s, cy, 0.0, 0.0, 1.0))
            return forward to back
        }

        private fun multiply(a: Homography, b: Homography): Homography {
            val r = DoubleArray(9)
            for (i in 0 until 3) for (j in 0 until 3) {
                r[i * 3 + j] = (0 until 3).sumOf { k -> a.m[i * 3 + k] * b.m[k * 3 + j] }
            }
            return Homography(r)
        }

        private fun scaled(h: Homography) = Homography(DoubleArray(9) { h.m[it] / h.m[8] })

        private fun solve(a: Array<DoubleArray>): DoubleArray? {
            val n = a.size
            for (col in 0 until n) {
                val pivot = (col until n).maxBy { abs(a[it][col]) }
                if (abs(a[pivot][col]) < 1e-12) return null
                val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
                for (row in col + 1 until n) {
                    val f = a[row][col] / a[col][col]
                    for (k in col..n) a[row][k] -= f * a[col][k]
                }
            }
            val x = DoubleArray(n)
            for (row in n - 1 downTo 0) {
                var sum = a[row][n]
                for (k in row + 1 until n) sum -= a[row][k] * x[k]
                x[row] = sum / a[row][row]
            }
            return x
        }
    }
}

/** The corners a person drags for this warp (none for a mesh). */
val Warp.corners: List<Vec2>
    get() = when (this) {
        is Warp.Quad -> corners
        is Warp.Polygon -> corners
        is Warp.Mesh -> emptyList()
    }

/** The same warp with its corners moved. */
fun Warp.withCorners(corners: List<Vec2>) = when (this) {
    is Warp.Quad -> copy(corners = corners)
    is Warp.Polygon -> copy(corners = corners)
    is Warp.Mesh -> this
}

/** How to draw this warp, or null for a mesh or a warp whose corners don't make a polygon. */
fun Warp.polygonWarp(): PolygonWarp? = when (this) {
    is Warp.Quad ->
        if (corners.size == 4) PolygonWarp(Homography.UNIT_SQUARE, corners) else null
    is Warp.Polygon ->
        if (corners.size >= 3 && corners.size == source.size) PolygonWarp(source, corners) else null
    is Warp.Mesh -> null
}
