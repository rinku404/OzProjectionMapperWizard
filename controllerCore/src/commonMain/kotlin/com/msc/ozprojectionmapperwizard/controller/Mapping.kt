package com.msc.ozprojectionmapperwizard.controller

import com.msc.ozprojectionmapperwizard.core.geometry.corners
import com.msc.ozprojectionmapperwizard.core.geometry.isInside
import com.msc.ozprojectionmapperwizard.core.geometry.polygonWarp
import com.msc.ozprojectionmapperwizard.core.geometry.withCorners
import com.msc.ozprojectionmapperwizard.core.geometry.Vec2
import com.msc.ozprojectionmapperwizard.core.model.Face
import com.msc.ozprojectionmapperwizard.core.model.Output
import com.msc.ozprojectionmapperwizard.core.model.Project
import com.msc.ozprojectionmapperwizard.core.model.ProjectorMapping
import com.msc.ozprojectionmapperwizard.core.model.Surface
import com.msc.ozprojectionmapperwizard.core.model.Warp
import com.msc.ozprojectionmapperwizard.core.protocol.DeviceInfo
import com.msc.ozprojectionmapperwizard.core.protocol.LiveState
import com.msc.ozprojectionmapperwizard.core.protocol.TestPattern
import kotlin.math.hypot

/**
 * Editing operations on one projector's mapping. Every call returns a new [Project]; the editor
 * keeps undo-free immutable snapshots that can go straight to the player.
 */
object Mapping {
    const val MAX_SURFACES = 8

    /** A project for [player], adding its projector mapping (at its native resolution) if missing. */
    fun forPlayer(project: Project?, player: DeviceInfo): Project {
        val base = project ?: Project(name = player.name)
        val output = Output(player.displayWidth, player.displayHeight)
        val existing = base.projectors.firstOrNull { it.id == player.deviceId }
        return when {
            existing == null -> base.copy(projectors = base.projectors + ProjectorMapping(player.deviceId, player.name, output))
            existing.output.width != output.width || existing.output.height != output.height ->
                base.updateProjector(player.deviceId) { rescale(it, output) }
            else -> base
        }
    }

    fun projector(project: Project, projectorId: String): ProjectorMapping = project.projectors.first { it.id == projectorId }

    /** Adds a face and a centred rectangle for it, offset a little from the last one so they don't stack. */
    fun addSurface(project: Project, projectorId: String): Pair<Project, String> {
        val mapping = projector(project, projectorId)
        require(mapping.surfaces.size < MAX_SURFACES) { "Up to $MAX_SURFACES surfaces" }
        val n = (1..Int.MAX_VALUE).first { i -> project.scene.faces.none { it.id == "face-$i" } }
        val face = Face(id = "face-$n", name = "Surface $n")
        val w = mapping.output.width.toDouble()
        val h = mapping.output.height.toDouble()
        val shift = (mapping.surfaces.size % 4) * 0.05
        val l = w * (0.25 + shift)
        val t = h * (0.25 + shift)
        val r = l + w * 0.4
        val b = t + h * 0.4
        val surface = Surface(
            id = "surface-$n",
            faceId = face.id,
            warp = Warp.Quad(listOf(Vec2(l, t), Vec2(r, t), Vec2(r, b), Vec2(l, b))),
        )
        val updated = project
            .copy(scene = project.scene.copy(faces = project.scene.faces + face))
            .updateProjector(projectorId) { it.copy(surfaces = it.surfaces + surface) }
        return updated to surface.id
    }

    /** Removes the surface, and its face if no other projector uses it. */
    fun removeSurface(project: Project, projectorId: String, surfaceId: String): Project {
        val faceId = projector(project, projectorId).surfaces.firstOrNull { it.id == surfaceId }?.faceId ?: return project
        val withoutSurface = project.updateProjector(projectorId) { p -> p.copy(surfaces = p.surfaces.filterNot { it.id == surfaceId }) }
        val stillUsed = withoutSurface.projectors.any { p -> p.surfaces.any { it.faceId == faceId } }
        if (stillUsed) return withoutSurface
        return withoutSurface.copy(
            scene = withoutSurface.scene.copy(
                faces = withoutSurface.scene.faces.filterNot { it.id == faceId },
                cues = withoutSurface.scene.cues.filterNot { it.faceId == faceId },
            ),
        )
    }

    fun renameFace(project: Project, faceId: String, name: String): Project =
        project.copy(scene = project.scene.copy(faces = project.scene.faces.map { if (it.id == faceId) it.copy(name = name) else it }))

    fun corners(surface: Surface): List<Vec2> = surface.warp.corners

    /** Moves one corner, kept inside the projector's image. Refuses moves that would fold the surface. */
    fun moveCorner(project: Project, projectorId: String, surfaceId: String, corner: Int, to: Vec2): Project =
        project.updateSurface(projectorId, surfaceId) { surface, output ->
            val moved = corners(surface).toMutableList().also { it[corner] = clamp(to, output) }
            val warp = surface.warp.withCorners(moved)
            val ok = if (warp is Warp.Quad) isConvex(moved) else warp.polygonWarp()?.isValid() == true
            if (ok) surface.copy(warp = warp) else surface
        }

    /** Moves the whole surface by [delta], stopping at the image edges. */
    fun moveSurface(project: Project, projectorId: String, surfaceId: String, delta: Vec2): Project =
        project.updateSurface(projectorId, surfaceId) { surface, output ->
            val c = corners(surface)
            val dx = delta.x.coerceIn(-c.minOf { it.x }, output.width - c.maxOf { it.x })
            val dy = delta.y.coerceIn(-c.minOf { it.y }, output.height - c.maxOf { it.y })
            surface.copy(warp = surface.warp.withCorners(c.map { Vec2(it.x + dx, it.y + dy) }))
        }

    /** The corner of any surface within [radius] of [point], preferring [preferredSurface]. */
    fun cornerAt(mapping: ProjectorMapping, point: Vec2, radius: Double, preferredSurface: String? = null): Pair<String, Int>? {
        val ordered = mapping.surfaces.sortedByDescending { it.id == preferredSurface }
        for (surface in ordered) {
            val corners = corners(surface).ifEmpty { continue }
            val best = corners.indices.minBy { distance(corners[it], point) }
            if (distance(corners[best], point) <= radius) return surface.id to best
        }
        return null
    }

    /** The top-most surface containing [point]. */
    fun surfaceAt(mapping: ProjectorMapping, point: Vec2): String? =
        mapping.surfaces.lastOrNull { surface -> corners(surface).let { it.size >= 3 && point.isInside(it) } }?.id

    fun liveState(
        project: Project,
        projectorId: String,
        pattern: TestPattern,
        selectedSurface: String?,
        selectedCorner: Int?,
    ) = LiveState(
        mapping = projector(project, projectorId),
        faceNames = project.scene.faces.associate { it.id to it.name },
        pattern = pattern,
        selectedSurface = selectedSurface,
        selectedCorner = selectedCorner,
    )

    /** True when the quad's corners turn the same way all round, so the homography doesn't fold. */
    fun isConvex(corners: List<Vec2>): Boolean {
        var sign = 0
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            val c = corners[(i + 2) % corners.size]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross == 0.0) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    private fun rescale(mapping: ProjectorMapping, output: Output): ProjectorMapping {
        val sx = output.width.toDouble() / mapping.output.width
        val sy = output.height.toDouble() / mapping.output.height
        return mapping.copy(
            output = output,
            surfaces = mapping.surfaces.map { s ->
                s.copy(warp = s.warp.withCorners(s.warp.corners.map { Vec2(it.x * sx, it.y * sy) }))
            },
        )
    }

    private fun clamp(p: Vec2, output: Output) =
        Vec2(p.x.coerceIn(0.0, output.width.toDouble()), p.y.coerceIn(0.0, output.height.toDouble()))

    private fun distance(a: Vec2, b: Vec2) = hypot(a.x - b.x, a.y - b.y)

    private fun Project.updateProjector(id: String, change: (ProjectorMapping) -> ProjectorMapping) =
        copy(projectors = projectors.map { if (it.id == id) change(it) else it })

    private fun Project.updateSurface(projectorId: String, surfaceId: String, change: (Surface, Output) -> Surface) =
        updateProjector(projectorId) { p -> p.copy(surfaces = p.surfaces.map { if (it.id == surfaceId) change(it, p.output) else it }) }
}
