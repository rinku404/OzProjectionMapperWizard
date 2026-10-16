package com.msc.ozprojectionmapperwizard

import com.msc.ozprojectionmapperwizard.controller.LoadedExport
import com.msc.ozprojectionmapperwizard.controller.MediaLibrary
import com.msc.ozprojectionmapperwizard.controller.PickedVideo
import com.msc.ozprojectionmapperwizard.core.model.MediaItem
import com.msc.ozprojectionmapperwizard.core.model.Project
import com.msc.ozprojectionmapperwizard.core.model.ProjectorMapping

/** What a long step is doing, with how far along it is when that is known. */
data class RenderStep(val text: String, val fraction: Float? = null)

/** Turns media on this device into a mapped show video. Only the PC app has one for now. */
interface ShowRenderer {
    /** Videos, GIFs and images added on this device, to pick from. */
    val library: MediaLibrary

    /** Lets the person choose any number of videos, GIFs and images and adds them to [library]; returns how many. */
    suspend fun addToLibrary(groupId: String?, onStep: (RenderStep) -> Unit): Int

    /** A small PNG of [media], or null when its file is gone. */
    suspend fun thumbnail(media: MediaItem): ByteArray?

    /** One frame of [mapping] with its media as a PNG, to preview a choice on the surfaces. */
    suspend fun preview(project: Project, mapping: ProjectorMapping): ByteArray

    /**
     * Lets the person choose an `oz-surfaces.json` written by the Blender add-on and reads each
     * face's render next to it; null if they cancelled.
     */
    suspend fun importBlender(onStep: (RenderStep) -> Unit): LoadedExport?

    /** Whether the file chosen for [mediaId] is still on this device. */
    fun hasMedia(mediaId: String): Boolean

    /** Renders what [mapping] shows into one video, ready to send. */
    /** Renders to [fileName], which is also the name the video is sent under. */
    suspend fun render(project: Project, mapping: ProjectorMapping, fileName: String, onStep: (RenderStep) -> Unit): PickedVideo
}
