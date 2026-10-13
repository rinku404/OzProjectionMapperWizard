package com.msc.ozprojectionmapperwizard.core.model

/** Opacity of a surface at [timeSeconds], interpolating linearly between its keyframes. */
fun Surface.opacityAt(timeSeconds: Double): Double {
    if (opacityKeys.isEmpty()) return opacity
    val keys = opacityKeys.sortedBy { it.timeSeconds }
    if (timeSeconds <= keys.first().timeSeconds) return keys.first().value
    if (timeSeconds >= keys.last().timeSeconds) return keys.last().value
    val next = keys.indexOfFirst { it.timeSeconds > timeSeconds }
    val a = keys[next - 1]
    val b = keys[next]
    val t = (timeSeconds - a.timeSeconds) / (b.timeSeconds - a.timeSeconds)
    return a.value + (b.value - a.value) * t
}

/** Fade multiplier (0..1) of a cue at [timeSeconds], or 0 when the cue is not playing. */
fun Cue.fadeAt(timeSeconds: Double): Double {
    val local = timeSeconds - startSeconds
    if (local < 0 || local >= durationSeconds) return 0.0
    val fadeIn = if (fadeInSeconds > 0) (local / fadeInSeconds).coerceAtMost(1.0) else 1.0
    val fadeOut = if (fadeOutSeconds > 0) ((durationSeconds - local) / fadeOutSeconds).coerceAtMost(1.0) else 1.0
    return minOf(fadeIn, fadeOut)
}
