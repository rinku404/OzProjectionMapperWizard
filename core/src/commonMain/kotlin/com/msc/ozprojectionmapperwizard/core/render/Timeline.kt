package com.msc.ozprojectionmapperwizard.core.render

import com.msc.ozprojectionmapperwizard.core.geometry.polygonWarp
import com.msc.ozprojectionmapperwizard.core.model.Cue
import com.msc.ozprojectionmapperwizard.core.model.Project
import com.msc.ozprojectionmapperwizard.core.model.ProjectorMapping
import com.msc.ozprojectionmapperwizard.core.model.Surface
import com.msc.ozprojectionmapperwizard.core.model.Warp
import com.msc.ozprojectionmapperwizard.core.model.fadeAt
import com.msc.ozprojectionmapperwizard.core.model.opacityAt

/** What to draw at one moment: a visible surface, the cue playing on it and how opaque it is. */
data class Layer(val surface: Surface, val warp: Warp, val cue: Cue, val opacity: Double)

/** Turns a project's scene and one projector's mapping into layers, frame by frame. */
class Timeline(project: Project, private val mapping: ProjectorMapping) {
    private val cuesByFace = project.scene.cues.groupBy { it.faceId }
    val fps = mapping.output.fps
    val frameCount = (project.scene.durationSeconds * fps).toInt().coerceAtLeast(1)

    fun timeOf(frame: Int): Double = frame.toDouble() / fps

    /** Cues whose media shows on this projector. */
    val cues: List<Cue> = mapping.surfaces.flatMap { cuesByFace[it.faceId].orEmpty() }

    /** Visible layers at [t], in drawing order (later surfaces on top). */
    fun layersAt(t: Double): List<Layer> = mapping.surfaces.mapNotNull { surface ->
        if (surface.warp.polygonWarp() == null) return@mapNotNull null
        val cue = cuesByFace[surface.faceId]?.firstOrNull { it.fadeAt(t) > 0.0 } ?: return@mapNotNull null
        val opacity = surface.opacityAt(t) * cue.fadeAt(t)
        if (opacity <= 0.0) null else Layer(surface, surface.warp, cue, opacity)
    }
}
