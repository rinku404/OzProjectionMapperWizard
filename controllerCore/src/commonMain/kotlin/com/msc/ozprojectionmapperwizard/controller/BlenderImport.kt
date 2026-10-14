package com.msc.ozprojectionmapperwizard.controller

import com.msc.ozprojectionmapperwizard.core.geometry.Vec2
import com.msc.ozprojectionmapperwizard.core.model.Face
import com.msc.ozprojectionmapperwizard.core.model.MediaItem
import com.msc.ozprojectionmapperwizard.core.model.Project
import com.msc.ozprojectionmapperwizard.core.model.Surface
import com.msc.ozprojectionmapperwizard.core.model.Warp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.min

/**
 * `oz-surfaces.json`, written by the Blender add-on next to one rendered video (or image) per face.
 * Each surface's [ExportedSurface.points] are its corners in that render, 0..1 from the top left.
 */
@Serializable
data class SurfacesExport(
    val format: String,
    val version: Int,
    val fps: Double = 30.0,
    val durationSeconds: Double,
    val surfaces: List<ExportedSurface>,
)

@Serializable
data class ExportedSurface(
    val name: String,
    /** File name of the render, in the same folder as the json. */
    val media: String,
    /** Size of the render in pixels, so the shape keeps its proportions when first placed. */
    val width: Int,
    val height: Int,
    val points: List<List<Double>>,
)

/** An export read from disk, with each surface's render probed. */
class LoadedExport(val export: SurfacesExport, val media: Map<String, MediaItem>)

object BlenderImport {
    const val FORMAT = "oz-surfaces"
    const val FILE_NAME = "oz-surfaces.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): SurfacesExport {
        val export = try {
            json.decodeFromString<SurfacesExport>(text)
        } catch (e: Exception) {
            throw RenderException("That isn't an export from the Oz Blender add-on (${e.message?.take(120)}).")
        }
        if (export.format != FORMAT) throw RenderException("That isn't an export from the Oz Blender add-on.")
        if (export.surfaces.isEmpty()) throw RenderException("The export has no surfaces. Select some faces in Blender before exporting.")
        export.surfaces.forEach { s ->
            if (s.points.size < 3 || s.points.any { it.size != 2 }) throw RenderException("Surface ${s.name} needs 3 or more corners.")
        }
        return export
    }

    /**
     * Adds every exported face to [projectorId]'s mapping with its render playing for the whole
     * show. A face imported before (same name and corner count) keeps where its corners were
     * dragged, so re-exporting a changed animation doesn't undo the alignment.
     */
    fun apply(project: Project, projectorId: String, loaded: LoadedExport): Project {
        var result = Content.setDuration(project, loaded.export.durationSeconds)
        loaded.export.surfaces.forEachIndexed { index, exported ->
            val media = loaded.media[exported.name] ?: throw RenderException("The render of ${exported.name} is missing.")
            val source = exported.points.map { Vec2(it[0].coerceIn(0.0, 1.0), it[1].coerceIn(0.0, 1.0)) }
            val mapping = Mapping.projector(result, projectorId)
            val existing = mapping.surfaces.firstOrNull { s ->
                val face = result.scene.faces.firstOrNull { it.id == s.faceId }
                face?.name == exported.name && (s.warp as? Warp.Polygon)?.corners?.size == source.size
            }
            val faceId: String
            if (existing != null) {
                faceId = existing.faceId
                val warp = (existing.warp as Warp.Polygon).copy(source = source)
                result = result.copy(projectors = result.projectors.map { p ->
                    if (p.id != projectorId) p else p.copy(surfaces = p.surfaces.map { if (it.id == existing.id) it.copy(warp = warp) else it })
                })
            } else {
                val n = (1..Int.MAX_VALUE).first { i -> result.scene.faces.none { it.id == "face-$i" } }
                faceId = "face-$n"
                val aspect = exported.width.toDouble() / exported.height.coerceAtLeast(1)
                val surface = Surface(
                    id = "surface-$n",
                    faceId = faceId,
                    warp = Warp.Polygon(corners = place(source, aspect, mapping.output.width, mapping.output.height, index), source = source),
                )
                result = result.copy(
                    scene = result.scene.copy(faces = result.scene.faces + Face(faceId, exported.name, aspect)),
                    projectors = result.projectors.map { p -> if (p.id == projectorId) p.copy(surfaces = p.surfaces + surface) else p },
                )
            }
            result = Content.setMedia(result, faceId, media)
        }
        return result
    }

    /** A starting position: the shape at its proportions in a box up to 40% of the image, stepped so faces don't stack. */
    private fun place(source: List<Vec2>, aspect: Double, width: Int, height: Int, index: Int): List<Vec2> {
        val maxW = width * 0.4
        val maxH = height * 0.4
        val scale = min(maxW / aspect, maxH)
        val boxW = scale * aspect
        val boxH = scale
        val shift = (index % 6) * 0.08
        val left = width * (0.1 + shift)
        val top = height * (0.1 + shift * 0.5)
        return source.map { Vec2(left + it.x * boxW, top + it.y * boxH) }
    }
}
