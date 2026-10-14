package com.msc.ozprojectionmapperwizard.core.model

/** Returns human-readable problems; an empty list means the project can be rendered. */
fun Project.validate(): List<String> = buildList {
    val faceIds = scene.faces.map { it.id }.toSet()
    val mediaIds = media.map { it.id }.toSet()

    duplicates(scene.faces.map { it.id }).forEach { add("Duplicate face id '$it'") }
    duplicates(projectors.map { it.id }).forEach { add("Duplicate projector id '$it'") }
    if (scene.durationSeconds <= 0) add("Show duration must be positive")

    scene.cues.forEachIndexed { i, cue ->
        if (cue.faceId !in faceIds) add("Cue $i uses unknown face '${cue.faceId}'")
        if (cue.mediaId !in mediaIds) add("Cue $i uses unknown media '${cue.mediaId}'")
        if (cue.startSeconds < 0 || cue.durationSeconds <= 0) add("Cue $i has an invalid time range")
        if (cue.fadeInSeconds + cue.fadeOutSeconds > cue.durationSeconds) add("Cue $i fades are longer than the cue")
    }

    projectors.forEach { p ->
        if (p.output.width <= 0 || p.output.height <= 0 || p.output.fps <= 0) add("Projector '${p.id}' has an invalid output")
        duplicates(p.surfaces.map { it.id }).forEach { add("Projector '${p.id}' has duplicate surface id '$it'") }
        p.surfaces.forEach { s ->
            val where = "Surface '${s.id}' on projector '${p.id}'"
            if (s.faceId !in faceIds) add("$where uses unknown face '${s.faceId}'")
            when (val w = s.warp) {
                is Warp.Quad -> if (w.corners.size != 4) add("$where needs 4 corners, has ${w.corners.size}")
                is Warp.Polygon -> if (w.corners.size < 3 || w.corners.size != w.source.size) {
                    add("$where needs 3 or more corners, matching its shape")
                }
                is Warp.Mesh -> if (w.cols < 2 || w.rows < 2 || w.points.size != w.cols * w.rows) {
                    add("$where has a mesh with the wrong number of points")
                }
            }
            if (s.masks.any { it.points.size < 3 }) add("$where has a mask with fewer than 3 points")
        }
        if (p.masks.any { it.points.size < 3 }) add("Projector '${p.id}' has a mask with fewer than 3 points")
    }
}

private fun duplicates(ids: List<String>) = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
