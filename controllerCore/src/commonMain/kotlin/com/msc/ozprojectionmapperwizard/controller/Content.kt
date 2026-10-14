package com.msc.ozprojectionmapperwizard.controller

import com.msc.ozprojectionmapperwizard.core.model.Cue
import com.msc.ozprojectionmapperwizard.core.model.MediaItem
import com.msc.ozprojectionmapperwizard.core.model.MediaKind
import com.msc.ozprojectionmapperwizard.core.model.Project
import kotlin.math.ceil

/** A render that couldn't be made, with a message for the person. */
class RenderException(message: String) : Exception(message)

/**
 * What plays on each surface. For now every face shows one video, GIF or image for the whole
 * show, looping, so the editor only needs "choose" and "clear" per surface plus the show length.
 */
object Content {
    const val MIN_SECONDS = 1.0
    const val MAX_SECONDS = 600.0

    fun mediaFor(project: Project, faceId: String): MediaItem? {
        val cue = project.scene.cues.firstOrNull { it.faceId == faceId } ?: return null
        return project.media.firstOrNull { it.id == cue.mediaId }
    }

    /** Shows [media] on [faceId] for the whole show, or clears the face when [media] is null. */
    fun setMedia(project: Project, faceId: String, media: MediaItem?): Project {
        val others = project.scene.cues.filterNot { it.faceId == faceId }
        val cues = if (media == null) others else others + Cue(faceId, media.id, 0.0, project.scene.durationSeconds)
        val library = if (media == null || project.media.any { it.id == media.id }) project.media else project.media + media
        return project.copy(scene = project.scene.copy(cues = cues), media = library).pruneMedia()
    }

    /** Sets the length of one loop of the show; whole-show cues stretch with it. */
    fun setDuration(project: Project, seconds: Double): Project {
        val length = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
        val old = project.scene.durationSeconds
        val cues = project.scene.cues.map { cue ->
            if (cue.startSeconds == 0.0 && cue.durationSeconds >= old) cue.copy(durationSeconds = length) else cue
        }
        return project.copy(scene = project.scene.copy(durationSeconds = length, cues = cues))
    }

    /**
     * A show length that lets the longest video play once to its end: its length rounded up to a
     * whole second, or 10 seconds when only images are used.
     */
    fun suggestedDuration(project: Project): Double {
        val longest = project.scene.cues
            .mapNotNull { cue -> project.media.firstOrNull { it.id == cue.mediaId } }
            .filter { it.kind == MediaKind.VIDEO }
            .mapNotNull { it.durationSeconds }
            .maxOrNull()
            ?: return 10.0
        return ceil(longest).coerceIn(MIN_SECONDS, MAX_SECONDS)
    }

    /** Media that some face still uses, so the project doesn't keep files nobody plays. */
    private fun Project.pruneMedia(): Project {
        val used = scene.cues.map { it.mediaId }.toSet()
        return copy(media = media.filter { it.id in used })
    }
}
