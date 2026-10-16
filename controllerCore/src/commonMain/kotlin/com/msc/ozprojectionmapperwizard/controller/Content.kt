package com.msc.ozprojectionmapperwizard.controller

import com.msc.ozprojectionmapperwizard.core.model.Cue
import com.msc.ozprojectionmapperwizard.core.model.MediaItem
import com.msc.ozprojectionmapperwizard.core.model.MediaKind
import com.msc.ozprojectionmapperwizard.core.model.Project
import kotlin.math.ceil
import kotlin.random.Random

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

    /** How [fill] hands a group's files out to the surfaces. */
    enum class FillOrder { IN_ORDER, SHUFFLED }

    /**
     * Puts one of [files] on each of [faceIds]: in order, starting over when there are more
     * surfaces than files, or shuffled so a file only repeats once every one has been used.
     */
    fun fill(project: Project, faceIds: List<String>, files: List<MediaItem>, order: FillOrder, random: Random = Random.Default): Project {
        if (files.isEmpty()) return project
        val picks = when (order) {
            FillOrder.IN_ORDER -> faceIds.indices.map { files[it % files.size] }
            FillOrder.SHUFFLED -> generateSequence { files.shuffled(random) }.flatten().take(faceIds.size).toList()
        }
        return faceIds.zip(picks).fold(project) { p, (faceId, media) -> setMedia(p, faceId, media) }
    }

    /** Shows [media] on every one of [faceIds]. */
    fun setMediaOnAll(project: Project, faceIds: List<String>, media: MediaItem): Project =
        faceIds.fold(project) { p, faceId -> setMedia(p, faceId, media) }

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

    /** Media that some face or trigger still uses, so the project doesn't keep files nobody plays. */
    private fun Project.pruneMedia(): Project {
        val used = scene.cues.map { it.mediaId }.toSet() + triggers.map { it.mediaId }
        return copy(media = media.filter { it.id in used })
    }
}
