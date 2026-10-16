package com.msc.ozprojectionmapperwizard.controller

import com.msc.ozprojectionmapperwizard.core.model.MediaItem
import com.msc.ozprojectionmapperwizard.core.model.Project
import com.msc.ozprojectionmapperwizard.core.model.Trigger

/**
 * Triggered animations: a video or GIF that plays once on one surface when fired, over the rest
 * of the mapping, before the looping show carries on. Each is rendered as its own short show.
 */
object Triggers {
    /** Length of a trigger made from a still image, which has no length of its own. */
    const val IMAGE_SECONDS = 3.0
    const val MAX_SECONDS = 60.0

    fun forFace(project: Project, faceId: String): List<Trigger> = project.triggers.filter { it.faceId == faceId }

    fun mediaOf(project: Project, trigger: Trigger): MediaItem? = project.media.firstOrNull { it.id == trigger.mediaId }

    /** Adds a trigger playing [media] on [faceId], named after the file. Returns the project and the trigger's id. */
    fun add(project: Project, faceId: String, media: MediaItem): Pair<Project, String> {
        val n = (1..Int.MAX_VALUE).first { i -> project.triggers.none { it.id == "trigger-$i" } }
        val trigger = Trigger("trigger-$n", media.fileName.substringBeforeLast('.').ifBlank { media.fileName }, faceId, media.id)
        val library = if (project.media.any { it.id == media.id }) project.media else project.media + media
        return project.copy(triggers = project.triggers + trigger, media = library) to trigger.id
    }

    /** Removes the trigger, and its file from the project when nothing else uses it. */
    fun remove(project: Project, triggerId: String): Project {
        val trigger = project.triggers.firstOrNull { it.id == triggerId } ?: return project
        val left = project.copy(triggers = project.triggers - trigger)
        val stillUsed = left.triggers.any { it.mediaId == trigger.mediaId } || left.scene.cues.any { it.mediaId == trigger.mediaId }
        return if (stillUsed) left else left.copy(media = left.media.filterNot { it.id == trigger.mediaId })
    }

    fun withShow(project: Project, triggerId: String, showId: String?): Project =
        project.copy(triggers = project.triggers.map { if (it.id == triggerId) it.copy(showId = showId) else it })

    fun lengthOf(media: MediaItem): Double =
        (media.durationSeconds ?: IMAGE_SECONDS).coerceIn(Content.MIN_SECONDS, MAX_SECONDS)

    /** The mapping as it looks while [trigger] plays: its file on its face, the rest as usual, for the file's length. */
    fun renderProject(project: Project, trigger: Trigger): Project {
        val media = mediaOf(project, trigger) ?: throw RenderException("The file for \"${trigger.name}\" is missing. Remove the trigger and add it again.")
        return Content.setDuration(Content.setMedia(project, trigger.faceId, media), lengthOf(media))
    }

    /** File name the trigger's render is sent as, so it never replaces the main show. */
    fun fileName(trigger: Trigger) = "Trigger ${trigger.name}.mp4"
}
