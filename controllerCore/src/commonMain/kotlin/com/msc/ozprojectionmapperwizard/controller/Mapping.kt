package com.msc.ozprojectionmapperwizard.controller

import com.msc.ozprojectionmapperwizard.core.geometry.corners
import com.msc.ozprojectionmapperwizard.core.geometry.isInside
import com.msc.ozprojectionmapperwizard.core.geometry.polygonWarp
import com.msc.ozprojectionmapperwizard.core.geometry.withCorners
import com.msc.ozprojectionmapperwizard.core.geometry.Vec2
import com.msc.ozprojectionmapperwizard.core.model.CornerRef
import com.msc.ozprojectionmapperwizard.core.model.Face
import com.msc.ozprojectionmapperwizard.core.model.MediaItem
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

    /**
     * Adds a face and a centred rectangle for it, offset a little from the last one so they don't
     * stack. With [aspect] (width over height) the rectangle has that shape, as large as fits in
     * the usual box.
     */
    fun addSurface(project: Project, projectorId: String, name: String? = null, aspect: Double? = null): Pair<Project, String> {
        val mapping = projector(project, projectorId)
        require(mapping.surfaces.size < MAX_SURFACES) { "Up to $MAX_SURFACES surfaces" }
        val n = (1..Int.MAX_VALUE).first { i -> project.scene.faces.none { it.id == "face-$i" } }
        val face = Face(id = "face-$n", name = name?.takeIf { it.isNotBlank() } ?: "Surface $n", aspect = aspect ?: 16.0 / 9.0)
        val w = mapping.output.width.toDouble()
        val h = mapping.output.height.toDouble()
        val shift = (mapping.surfaces.size % 4) * 0.05
        var boxW = w * 0.4
        var boxH = h * 0.4
        if (aspect != null && aspect > 0) {
            if (boxW / boxH > aspect) boxW = boxH * aspect else boxH = boxW / aspect
        }
        val l = w * (0.25 + shift) + (w * 0.4 - boxW) / 2
        val t = h * (0.25 + shift) + (h * 0.4 - boxH) / 2
        val r = l + boxW
        val b = t + boxH
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

    /**
     * Adds a surface shaped like [media] (same width to height), named after the file and already
     * showing it, ready to be dragged into place.
     */
    fun addSurfaceFor(project: Project, projectorId: String, media: MediaItem): Pair<Project, String> {
        val aspect = if (media.width > 0 && media.height > 0) media.width.toDouble() / media.height else null
        val (added, surfaceId) = addSurface(project, projectorId, media.fileName.substringBeforeLast('.'), aspect)
        val faceId = projector(added, projectorId).surfaces.first { it.id == surfaceId }.faceId
        return Content.setMedia(added, faceId, media) to surfaceId
    }

    /** Removes the surface, its ties, and its face if no other projector uses it. */
    fun removeSurface(project: Project, projectorId: String, surfaceId: String): Project {
        val faceId = projector(project, projectorId).surfaces.firstOrNull { it.id == surfaceId }?.faceId ?: return project
        val withoutSurface = project.updateProjector(projectorId) { p ->
            p.copy(
                surfaces = p.surfaces.filterNot { it.id == surfaceId },
                ties = p.ties.map { tie -> tie.filterNot { it.surfaceId == surfaceId } }.filter { it.size > 1 },
            )
        }
        val stillUsed = withoutSurface.projectors.any { p -> p.surfaces.any { it.faceId == faceId } }
        if (stillUsed) return withoutSurface
        return withoutSurface.copy(
            scene = withoutSurface.scene.copy(
                faces = withoutSurface.scene.faces.filterNot { it.id == faceId },
                cues = withoutSurface.scene.cues.filterNot { it.faceId == faceId },
            ),
            triggers = withoutSurface.triggers.filterNot { it.faceId == faceId },
        )
    }

    fun renameFace(project: Project, faceId: String, name: String): Project =
        project.copy(scene = project.scene.copy(faces = project.scene.faces.map { if (it.id == faceId) it.copy(name = name) else it }))

    fun corners(surface: Surface): List<Vec2> = surface.warp.corners

    /**
     * Moves one corner, and every corner tied to it, kept inside the projector's image. Refuses
     * moves that would fold any surface. Within [snap] pixels of another surface's corner it lands
     * exactly on it, ready to be tied.
     */
    fun moveCorner(project: Project, projectorId: String, surfaceId: String, corner: Int, to: Vec2, snap: Double = 0.0): Project {
        val mapping = projector(project, projectorId)
        val group = tiedTo(mapping, CornerRef(surfaceId, corner))
        var target = clamp(to, mapping.output)
        if (snap > 0) {
            val tiedSurfaces = group.map { it.surfaceId }.toSet()
            mapping.surfaces.filter { it.id !in tiedSurfaces }
                .flatMap { corners(it) }
                .minByOrNull { distance(it, target) }
                ?.takeIf { distance(it, target) <= snap }
                ?.let { target = it }
        }
        val moved = setCorners(mapping, group.associateWith { target }) ?: return project
        return project.updateProjector(projectorId) { moved }
    }

    /** Moves the whole surface by [delta], stopping at the image edges. Corners tied to it follow. */
    fun moveSurface(project: Project, projectorId: String, surfaceId: String, delta: Vec2): Project {
        val mapping = projector(project, projectorId)
        val surface = mapping.surfaces.firstOrNull { it.id == surfaceId } ?: return project
        val c = corners(surface)
        val output = mapping.output
        val dx = delta.x.coerceIn(-c.minOf { it.x }, output.width - c.maxOf { it.x })
        val dy = delta.y.coerceIn(-c.minOf { it.y }, output.height - c.maxOf { it.y })
        val targets = c.indices.flatMap { i ->
            val to = Vec2(c[i].x + dx, c[i].y + dy)
            tiedTo(mapping, CornerRef(surfaceId, i)).map { it to to }
        }.toMap()
        val moved = setCorners(mapping, targets) ?: return project
        return project.updateProjector(projectorId) { moved }
    }

    /** [ref] and every corner tied to it. */
    fun tiedTo(mapping: ProjectorMapping, ref: CornerRef): Set<CornerRef> =
        mapping.ties.firstOrNull { ref in it }?.toSet() ?: setOf(ref)

    fun isTied(mapping: ProjectorMapping, ref: CornerRef): Boolean = mapping.ties.any { ref in it }

    /**
     * Ties [ref] to the nearest corner of another surface within [maxDistance] pixels, moving it
     * (and anything already tied to it) onto that corner. Unchanged when there is none or the move
     * would fold a surface.
     */
    fun tieToNearest(project: Project, projectorId: String, ref: CornerRef, maxDistance: Double): Project {
        val mapping = projector(project, projectorId)
        val group = tiedTo(mapping, ref)
        val at = cornerOf(mapping, ref) ?: return project
        val groupSurfaces = group.map { it.surfaceId }.toSet()
        val nearest = mapping.surfaces.filter { it.id !in groupSurfaces }
            .flatMap { s -> corners(s).mapIndexed { i, p -> CornerRef(s.id, i) to p } }
            .filter { (other, _) -> tiedTo(mapping, other).none { it.surfaceId in groupSurfaces } }
            .minByOrNull { (_, p) -> distance(p, at) }
            ?.takeIf { (_, p) -> distance(p, at) <= maxDistance }
            ?: return project
        val (other, target) = nearest
        val otherGroup = tiedTo(mapping, other)
        val moved = setCorners(mapping, group.associateWith { target }) ?: return project
        val merged = group + otherGroup
        return project.updateProjector(projectorId) {
            moved.copy(ties = moved.ties.filterNot { tie -> tie.any { it in merged } } + listOf(merged.toList()))
        }
    }

    /** Ties together corners of different surfaces that sit on the same spot, as after snapping. */
    fun tieTouching(project: Project, projectorId: String, ref: CornerRef): Project {
        val mapping = projector(project, projectorId)
        val at = cornerOf(mapping, ref) ?: return project
        val touching = mapping.surfaces.filter { it.id != ref.surfaceId }.flatMap { s ->
            corners(s).mapIndexedNotNull { i, p -> CornerRef(s.id, i).takeIf { distance(p, at) < 0.5 } }
        }
        if (touching.isEmpty()) return project
        var result = project
        touching.forEach { result = tieToNearest(result, projectorId, ref, 0.5) }
        return result
    }

    /** Frees [ref] from its tie; the other corners stay tied to each other. */
    fun untie(project: Project, projectorId: String, ref: CornerRef): Project = project.updateProjector(projectorId) { p ->
        p.copy(ties = p.ties.map { tie -> tie - ref }.filter { it.size > 1 })
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

    private fun cornerOf(mapping: ProjectorMapping, ref: CornerRef): Vec2? =
        mapping.surfaces.firstOrNull { it.id == ref.surfaceId }?.let { corners(it).getOrNull(ref.corner) }

    /** Puts each corner at its target, or null when that would fold a surface. */
    private fun setCorners(mapping: ProjectorMapping, targets: Map<CornerRef, Vec2>): ProjectorMapping? {
        val surfaces = mapping.surfaces.map { surface ->
            val mine = targets.filterKeys { it.surfaceId == surface.id }
            if (mine.isEmpty()) return@map surface
            val moved = corners(surface).toMutableList()
            mine.forEach { (ref, to) -> if (ref.corner in moved.indices) moved[ref.corner] = clamp(to, mapping.output) }
            val warp = surface.warp.withCorners(moved)
            val ok = if (warp is Warp.Quad) isConvex(moved) else warp.polygonWarp()?.isValid() == true
            if (!ok) return null
            surface.copy(warp = warp)
        }
        return mapping.copy(surfaces = surfaces)
    }

    private fun clamp(p: Vec2, output: Output) =
        Vec2(p.x.coerceIn(0.0, output.width.toDouble()), p.y.coerceIn(0.0, output.height.toDouble()))

    private fun distance(a: Vec2, b: Vec2) = hypot(a.x - b.x, a.y - b.y)

    private fun Project.updateProjector(id: String, change: (ProjectorMapping) -> ProjectorMapping) =
        copy(projectors = projectors.map { if (it.id == id) change(it) else it })
}
