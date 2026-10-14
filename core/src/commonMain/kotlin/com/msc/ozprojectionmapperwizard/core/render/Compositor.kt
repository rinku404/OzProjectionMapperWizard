package com.msc.ozprojectionmapperwizard.core.render

import com.msc.ozprojectionmapperwizard.core.geometry.Homography
import com.msc.ozprojectionmapperwizard.core.geometry.Vec2
import com.msc.ozprojectionmapperwizard.core.model.Rect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** An image as 0xAARRGGBB pixels, row by row. */
class Frame(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {
    init {
        require(pixels.size == width * height) { "Expected ${width * height} pixels, got ${pixels.size}" }
    }

    fun fill(argb: Int) = pixels.fill(argb)

    operator fun get(x: Int, y: Int) = pixels[y * width + x]
}

/**
 * Draws media onto a projector's output: each source image is corner-pinned onto its surface with
 * the same homography the live preview uses, so the render matches what was aligned.
 */
object Compositor {
    /**
     * Draws the [crop] part of [src] (normalised 0..1) onto the quad [corners] (TL, TR, BR, BL in
     * output pixels) of [out], blended over what is there with [opacity]. [featherPx] softens the
     * edges over roughly that many output pixels.
     */
    fun drawQuad(out: Frame, src: Frame, corners: List<Vec2>, crop: Rect = Rect.FULL, opacity: Double = 1.0, featherPx: Double = 0.0) {
        if (opacity <= 0.0) return
        val inverse = runCatching { Homography.unitSquareTo(corners).inverse() }.getOrNull() ?: return
        val m = inverse.m

        val x0 = max(0, floor(corners.minOf { it.x }).toInt())
        val x1 = min(out.width - 1, ceil(corners.maxOf { it.x }).toInt())
        val y0 = max(0, floor(corners.minOf { it.y }).toInt())
        val y1 = min(out.height - 1, ceil(corners.maxOf { it.y }).toInt())
        if (x0 > x1 || y0 > y1) return

        // Feather in face space: how much of the face one output pixel is, along each axis.
        val widthPx = (length(corners[0], corners[1]) + length(corners[3], corners[2])) / 2
        val heightPx = (length(corners[0], corners[3]) + length(corners[1], corners[2])) / 2
        val featherU = if (featherPx > 0 && widthPx > 0) featherPx / widthPx else 0.0
        val featherV = if (featherPx > 0 && heightPx > 0) featherPx / heightPx else 0.0

        val cropW = crop.right - crop.left
        val cropH = crop.bottom - crop.top
        val sw = src.width
        val sh = src.height
        val sp = src.pixels
        val op = out.pixels
        val alpha = opacity.coerceIn(0.0, 1.0)

        for (y in y0..y1) {
            val py = y + 0.5
            // Row start of the projective numerators and denominator; they step linearly in x.
            var nu = m[0] * (x0 + 0.5) + m[1] * py + m[2]
            var nv = m[3] * (x0 + 0.5) + m[4] * py + m[5]
            var w = m[6] * (x0 + 0.5) + m[7] * py + m[8]
            val row = y * out.width
            for (x in x0..x1) {
                val u = nu / w
                val v = nv / w
                nu += m[0]; nv += m[3]; w += m[6]
                if (u < 0.0 || u > 1.0 || v < 0.0 || v > 1.0) continue

                var a = alpha
                if (featherU > 0) a *= min(1.0, min(u, 1 - u) / featherU)
                if (featherV > 0) a *= min(1.0, min(v, 1 - v) / featherV)
                if (a <= 0.0) continue

                // Bilinear sample at pixel centres, clamped to the source edges.
                val sx = ((crop.left + u * cropW) * sw - 0.5).coerceIn(0.0, sw - 1.0)
                val sy = ((crop.top + v * cropH) * sh - 0.5).coerceIn(0.0, sh - 1.0)
                val ix = sx.toInt()
                val iy = sy.toInt()
                val fx = sx - ix
                val fy = sy - iy
                val ix1 = min(ix + 1, sw - 1)
                val iy1 = min(iy + 1, sh - 1)
                val c00 = sp[iy * sw + ix]
                val c10 = sp[iy * sw + ix1]
                val c01 = sp[iy1 * sw + ix]
                val c11 = sp[iy1 * sw + ix1]
                val sa = lerp4(c00 ushr 24, c10 ushr 24, c01 ushr 24, c11 ushr 24, fx, fy)
                val sr = lerp4((c00 shr 16) and 0xFF, (c10 shr 16) and 0xFF, (c01 shr 16) and 0xFF, (c11 shr 16) and 0xFF, fx, fy)
                val sg = lerp4((c00 shr 8) and 0xFF, (c10 shr 8) and 0xFF, (c01 shr 8) and 0xFF, (c11 shr 8) and 0xFF, fx, fy)
                val sb = lerp4(c00 and 0xFF, c10 and 0xFF, c01 and 0xFF, c11 and 0xFF, fx, fy)

                val k = a * sa / 255.0
                if (k <= 0.0) continue
                val d = op[row + x]
                val r = ((d shr 16) and 0xFF) * (1 - k) + sr * k
                val g = ((d shr 8) and 0xFF) * (1 - k) + sg * k
                val b = (d and 0xFF) * (1 - k) + sb * k
                op[row + x] = (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
            }
        }
    }

    private fun lerp4(c00: Int, c10: Int, c01: Int, c11: Int, fx: Double, fy: Double): Double {
        val top = c00 + (c10 - c00) * fx
        val bottom = c01 + (c11 - c01) * fx
        return top + (bottom - top) * fy
    }

    private fun length(a: Vec2, b: Vec2) = hypot(a.x - b.x, a.y - b.y)
}
