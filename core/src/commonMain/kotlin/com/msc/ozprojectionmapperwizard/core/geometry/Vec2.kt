package com.msc.ozprojectionmapperwizard.core.geometry

import kotlinx.serialization.Serializable

/** A 2D point or vector. Output-space points are in projector pixels, face-space points in 0..1. */
@Serializable
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)

    companion object {
        val ZERO = Vec2(0.0, 0.0)
    }
}

/** Linear interpolation between [a] and [b]. */
fun lerp(a: Vec2, b: Vec2, t: Double): Vec2 = Vec2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

/** Even-odd point-in-polygon test, used for masks. */
fun Vec2.isInside(polygon: List<Vec2>): Boolean {
    var inside = false
    var j = polygon.lastIndex
    for (i in polygon.indices) {
        val a = polygon[i]
        val b = polygon[j]
        if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) {
            inside = !inside
        }
        j = i
    }
    return inside
}
