package com.msc.ozprojectionmapperwizard.core.model

import com.msc.ozprojectionmapperwizard.core.geometry.Vec2
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A mapping project: one [scene] (what to show on each face of the object) and one
 * [ProjectorMapping] per projector (how that projector sees each face).
 *
 * Version 1 of the editor works with a single projector, but the format already holds a list so
 * several projectors can light the same object later without a migration.
 */
@Serializable
data class Project(
    val version: Int = CURRENT_VERSION,
    val name: String,
    val media: List<MediaItem> = emptyList(),
    val scene: Scene = Scene(),
    val projectors: List<ProjectorMapping> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** A media file, identified by the sha256 of its bytes so it is never uploaded twice. */
@Serializable
data class MediaItem(
    val id: String,
    val fileName: String,
    val kind: MediaKind,
    val width: Int,
    val height: Int,
    /** Null for still images. */
    val durationSeconds: Double? = null,
)

@Serializable
enum class MediaKind {
    @SerialName("image") IMAGE,
    @SerialName("video") VIDEO,
}

/** The object being mapped, independent of any projector. */
@Serializable
data class Scene(
    val faces: List<Face> = emptyList(),
    val cues: List<Cue> = emptyList(),
    /** Length of one loop of the show. */
    val durationSeconds: Double = 30.0,
)

/** One flat area of the object (a face of a box, a window, a panel). [aspect] is width / height. */
@Serializable
data class Face(
    val id: String,
    val name: String,
    val aspect: Double = 16.0 / 9.0,
)

/** Plays [mediaId] on [faceId] from [startSeconds] for [durationSeconds]. */
@Serializable
data class Cue(
    val faceId: String,
    val mediaId: String,
    val startSeconds: Double,
    val durationSeconds: Double,
    val fadeInSeconds: Double = 0.0,
    val fadeOutSeconds: Double = 0.0,
    /** Loop the media when it is shorter than the cue. */
    val loop: Boolean = true,
    /** Part of the media shown, normalised 0..1, so one video can span several faces. */
    val sourceCrop: Rect = Rect.FULL,
)

@Serializable
data class Rect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    companion object {
        val FULL = Rect(0.0, 0.0, 1.0, 1.0)
    }
}

/** How one projector lights the scene. */
@Serializable
data class ProjectorMapping(
    val id: String,
    val name: String,
    val output: Output,
    val surfaces: List<Surface> = emptyList(),
    /** Masks over the whole output, in output pixels (for example to black out a wall). */
    val masks: List<Mask> = emptyList(),
)

/** The projector's native display mode. Shows are rendered at exactly this size. */
@Serializable
data class Output(val width: Int, val height: Int, val fps: Int = 30) {
    companion object {
        val HD_720 = Output(1280, 720)
    }
}

/** A face as seen by one projector. */
@Serializable
data class Surface(
    val id: String,
    val faceId: String,
    val warp: Warp,
    val opacity: Double = 1.0,
    /** Soft edge width in output pixels. */
    val featherPx: Double = 0.0,
    /** Masks in face space (0..1). */
    val masks: List<Mask> = emptyList(),
    /** Soft ramps where this projector overlaps another on the same face. */
    val edgeBlend: EdgeBlend? = null,
    /** Opacity over time, for cross-fading a face between projectors. Empty means [opacity] throughout. */
    val opacityKeys: List<Keyframe> = emptyList(),
)

/** How a face is placed in the output image. */
@Serializable
sealed interface Warp {
    /** Corner pin: TL, TR, BR, BL in output pixels, perspective-correct. */
    @Serializable
    @SerialName("quad")
    data class Quad(val corners: List<Vec2>) : Warp

    /**
     * A flat surface with any number of corners (3 or more), as exported from Blender: [source] is
     * the outline in the media image (0..1), [corners] the same corners in output pixels.
     */
    @Serializable
    @SerialName("polygon")
    data class Polygon(val corners: List<Vec2>, val source: List<Vec2>) : Warp

    /** Grid of control points in output pixels, row-major, for curved or uneven surfaces. */
    @Serializable
    @SerialName("mesh")
    data class Mesh(val cols: Int, val rows: Int, val points: List<Vec2>) : Warp
}

@Serializable
data class Mask(
    val mode: MaskMode = MaskMode.SUBTRACT,
    val points: List<Vec2>,
    val featherPx: Double = 0.0,
)

@Serializable
enum class MaskMode {
    /** Only what is inside the mask is shown. */
    @SerialName("add") ADD,
    /** What is inside the mask is hidden. */
    @SerialName("subtract") SUBTRACT,
}

/** Gradient widths per edge as a fraction of the face (0..1), plus the projector's gamma. */
@Serializable
data class EdgeBlend(
    val left: Double = 0.0,
    val top: Double = 0.0,
    val right: Double = 0.0,
    val bottom: Double = 0.0,
    val gamma: Double = 2.2,
)

@Serializable
data class Keyframe(val timeSeconds: Double, val value: Double)
