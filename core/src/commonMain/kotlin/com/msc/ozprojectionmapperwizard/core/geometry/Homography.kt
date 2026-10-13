package com.msc.ozprojectionmapperwizard.core.geometry

import kotlin.math.abs

/**
 * A 3x3 projective transform, row-major, used for perspective-correct corner pinning.
 *
 * Renderers upload [m] as a matrix uniform; the CPU [map] is for hit testing, tests and previews.
 */
class Homography(val m: DoubleArray) {
    init {
        require(m.size == 9) { "Homography needs 9 values, got ${m.size}" }
    }

    fun map(p: Vec2): Vec2 {
        val w = m[6] * p.x + m[7] * p.y + m[8]
        return Vec2(
            (m[0] * p.x + m[1] * p.y + m[2]) / w,
            (m[3] * p.x + m[4] * p.y + m[5]) / w,
        )
    }

    fun inverse(): Homography {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val coA = e * i - f * h
        val coB = -(d * i - f * g)
        val coC = d * h - e * g
        val det = a * coA + b * coB + c * coC
        require(abs(det) > EPSILON) { "Homography is not invertible" }
        val inv = doubleArrayOf(
            coA, -(b * i - c * h), b * f - c * e,
            coB, a * i - c * g, -(a * f - c * d),
            coC, -(a * h - b * g), a * e - b * d,
        )
        return Homography(DoubleArray(9) { inv[it] / det })
    }

    companion object {
        private const val EPSILON = 1e-12

        val IDENTITY = Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        /** Maps the four [src] points onto the four [dst] points (same order). */
        fun fromQuads(src: List<Vec2>, dst: List<Vec2>): Homography {
            require(src.size == 4 && dst.size == 4) { "Both quads need exactly 4 points" }
            // Solve A * h = b for the 8 unknowns, with h[8] fixed to 1.
            val a = Array(8) { DoubleArray(9) }
            for (k in 0 until 4) {
                val (x, y) = src[k]
                val (u, v) = dst[k]
                a[2 * k] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * k + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            val h = solve(a)
            return Homography(doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0))
        }

        /** Maps the unit square (0,0)-(1,0)-(1,1)-(0,1) onto [corners] in TL, TR, BR, BL order. */
        fun unitSquareTo(corners: List<Vec2>): Homography = fromQuads(UNIT_SQUARE, corners)

        val UNIT_SQUARE = listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(1.0, 1.0), Vec2(0.0, 1.0))

        /** Gaussian elimination with partial pivoting on an augmented n x (n+1) matrix. */
        private fun solve(a: Array<DoubleArray>): DoubleArray {
            val n = a.size
            for (col in 0 until n) {
                val pivot = (col until n).maxBy { abs(a[it][col]) }
                require(abs(a[pivot][col]) > EPSILON) { "Degenerate quad: three or more corners are collinear" }
                val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
                for (row in col + 1 until n) {
                    val factor = a[row][col] / a[col][col]
                    for (k in col..n) a[row][k] -= factor * a[col][k]
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
