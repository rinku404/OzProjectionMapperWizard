package com.msc.ozprojectionmapperwizard

import com.msc.ozprojectionmapperwizard.controller.LoadedExport
import com.msc.ozprojectionmapperwizard.controller.PickedVideo
import com.msc.ozprojectionmapperwizard.core.model.MediaItem
import com.msc.ozprojectionmapperwizard.core.model.Project
import com.msc.ozprojectionmapperwizard.core.model.ProjectorMapping

/** What a long step is doing, with how far along it is when that is known. */
data class RenderStep(val text: String, val fraction: Float? = null)

/** Turns media on this device into a mapped show video. Only the PC app has one for now. */
interface ShowRenderer {
    /** Lets the person choose a video, GIF or image and reads its size and length; null if they cancelled. */
    suspend fun importMedia(onStep: (RenderStep) -> Unit): MediaItem?

    /**
     * Lets the person choose an `oz-surfaces.json` written by the Blender add-on and reads each
     * face's render next to it; null if they cancelled.
     */
    suspend fun importBlender(onStep: (RenderStep) -> Unit): LoadedExport?

    /** Whether the file chosen for [mediaId] is still on this device. */
    fun hasMedia(mediaId: String): Boolean

    /** Renders what [mapping] shows into one video, ready to send. */
    suspend fun render(project: Project, mapping: ProjectorMapping, onStep: (RenderStep) -> Unit): PickedVideo
}
