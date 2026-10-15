package com.msc.ozprojectionmapperwizard.controller

import com.msc.ozprojectionmapperwizard.core.geometry.Vec2
import com.msc.ozprojectionmapperwizard.core.model.MediaItem
import com.msc.ozprojectionmapperwizard.core.model.MediaKind
import com.msc.ozprojectionmapperwizard.core.model.validate
import com.msc.ozprojectionmapperwizard.core.protocol.DeviceInfo
import com.msc.ozprojectionmapperwizard.core.protocol.NowPlaying
import com.msc.ozprojectionmapperwizard.core.protocol.PlayReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MappingTest {
    private val info = DeviceInfo(
        deviceId = "hy300", name = "HY300", model = "HY300", platform = "android", osVersion = "12",
        displayWidth = 1280, displayHeight = 720, refreshRate = 60.0, freeBytes = 0, shows = emptyList(),
        nowPlaying = NowPlaying(null, PlayReason.NO_SHOW),
    )

    @Test
    fun newProjectGetsTheProjectorAtNativeResolution() {
        val project = Mapping.forPlayer(null, info)
        val mapping = Mapping.projector(project, "hy300")
        assertEquals(1280, mapping.output.width)
        assertEquals(project, Mapping.forPlayer(project, info))
    }

    @Test
    fun addedSurfacesAreValidAndDistinct() {
        var project = Mapping.forPlayer(null, info)
        repeat(3) { project = Mapping.addSurface(project, "hy300").first }
        assertEquals(emptyList(), project.validate())
        assertEquals(listOf("Surface 1", "Surface 2", "Surface 3"), project.scene.faces.map { it.name })
        val corners = Mapping.projector(project, "hy300").surfaces.map { Mapping.corners(it)[0] }
        assertEquals(3, corners.toSet().size)
    }

    @Test
    fun aSurfaceFromAnImageHasItsShapeAndShowsIt() {
        val project = Mapping.addSurface(Mapping.forPlayer(null, info), "hy300").first
        val tall = MediaItem("tall", "Window.png", MediaKind.IMAGE, 300, 600)
        val (added, id) = Mapping.addSurfaceFor(project, "hy300", tall)
        assertEquals(emptyList(), added.validate())
        val surface = Mapping.projector(added, "hy300").surfaces.first { it.id == id }
        val c = Mapping.corners(surface)
        assertEquals(0.5, (c[1].x - c[0].x) / (c[3].y - c[0].y), 1e-9)
        assertTrue(c.all { it.x in 0.0..1280.0 && it.y in 0.0..720.0 })
        val face = added.scene.faces.first { it.id == surface.faceId }
        assertEquals("Window", face.name)
        assertEquals(0.5, face.aspect, 1e-9)
        assertEquals(tall, Content.mediaFor(added, face.id))
    }

    @Test
    fun cornersStayInsideTheImageAndNeverFold() {
        val (project, id) = Mapping.addSurface(Mapping.forPlayer(null, info), "hy300")
        val moved = Mapping.moveCorner(project, "hy300", id, 0, Vec2(-50.0, 5000.0))
        // Dragging TL below BL would fold the quad, so that move is refused...
        assertEquals(project, moved)
        // ...but a move off the edge is clamped.
        val clamped = Mapping.moveCorner(project, "hy300", id, 0, Vec2(-50.0, -50.0))
        assertEquals(Vec2(0.0, 0.0), Mapping.corners(Mapping.projector(clamped, "hy300").surfaces.single())[0])
    }

    @Test
    fun movingASurfaceStopsAtTheEdge() {
        val (project, id) = Mapping.addSurface(Mapping.forPlayer(null, info), "hy300")
        val moved = Mapping.moveSurface(project, "hy300", id, Vec2(-10_000.0, 0.0))
        val corners = Mapping.corners(Mapping.projector(moved, "hy300").surfaces.single())
        assertEquals(0.0, corners.minOf { it.x })
    }

    @Test
    fun hitTesting() {
        val (project, id) = Mapping.addSurface(Mapping.forPlayer(null, info), "hy300")
        val mapping = Mapping.projector(project, "hy300")
        val tl = Mapping.corners(mapping.surfaces.single())[0]
        assertEquals(id to 0, Mapping.cornerAt(mapping, tl + Vec2(5.0, 5.0), radius = 20.0))
        assertNull(Mapping.cornerAt(mapping, Vec2(0.0, 0.0), radius = 20.0))
        assertEquals(id, Mapping.surfaceAt(mapping, Vec2(640.0, 400.0)))
        assertNull(Mapping.surfaceAt(mapping, Vec2(5.0, 5.0)))
    }

    @Test
    fun removingASurfaceRemovesItsFace() {
        val (project, id) = Mapping.addSurface(Mapping.forPlayer(null, info), "hy300")
        val removed = Mapping.removeSurface(project, "hy300", id)
        assertTrue(removed.scene.faces.isEmpty())
        assertTrue(Mapping.projector(removed, "hy300").surfaces.isEmpty())
    }

    @Test
    fun aProjectorWithANewResolutionKeepsItsMappingInPlace() {
        val (project, _) = Mapping.addSurface(Mapping.forPlayer(null, info), "hy300")
        val bigger = Mapping.forPlayer(project, info.copy(displayWidth = 1920, displayHeight = 1080))
        val before = Mapping.corners(Mapping.projector(project, "hy300").surfaces.single())[0]
        val after = Mapping.corners(Mapping.projector(bigger, "hy300").surfaces.single())[0]
        assertEquals(Vec2(before.x * 1.5, before.y * 1.5), after)
    }
}
