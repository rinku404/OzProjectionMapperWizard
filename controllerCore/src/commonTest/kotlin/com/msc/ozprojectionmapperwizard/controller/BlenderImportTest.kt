package com.msc.ozprojectionmapperwizard.controller

import com.msc.ozprojectionmapperwizard.core.geometry.Vec2
import com.msc.ozprojectionmapperwizard.core.model.MediaItem
import com.msc.ozprojectionmapperwizard.core.model.MediaKind
import com.msc.ozprojectionmapperwizard.core.model.Output
import com.msc.ozprojectionmapperwizard.core.model.Project
import com.msc.ozprojectionmapperwizard.core.model.ProjectorMapping
import com.msc.ozprojectionmapperwizard.core.model.Warp
import com.msc.ozprojectionmapperwizard.core.model.validate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BlenderImportTest {
    private val json = """
        {"format": "oz-surfaces", "version": 1, "fps": 30, "durationSeconds": 8.0, "extra": true,
         "surfaces": [
           {"name": "Roof", "media": "Roof.mp4", "width": 1000, "height": 500,
            "points": [[0.0, 1.0], [0.5, 0.0], [1.0, 1.0]]},
           {"name": "Front", "media": "Front.mp4", "width": 800, "height": 800,
            "points": [[0.0, 0.0], [1.0, 0.0], [1.0, 0.6], [0.5, 1.0], [0.0, 0.6]]}
         ]}
    """.trimIndent()

    private fun loaded(export: SurfacesExport) = LoadedExport(
        export,
        export.surfaces.associate { s -> s.name to MediaItem("sha-" + s.name, s.media, MediaKind.VIDEO, s.width, s.height, 8.0) },
    )

    private fun project() = Project(name = "T", projectors = listOf(ProjectorMapping("p", "P", Output.HD_720)))

    @Test
    fun importsEachFaceAsAPolygonWithItsRender() {
        val p = BlenderImport.apply(project(), "p", loaded(BlenderImport.parse(json)))
        val surfaces = Mapping.projector(p, "p").surfaces
        assertEquals(listOf(3, 5), surfaces.map { (it.warp as Warp.Polygon).corners.size })
        assertEquals(listOf("Roof", "Front"), p.scene.faces.map { it.name })
        assertEquals("Roof.mp4", Content.mediaFor(p, surfaces[0].faceId)?.fileName)
        assertEquals(8.0, p.scene.durationSeconds)
        assertTrue(p.validate().isEmpty(), p.validate().toString())
        // Placed at the render's proportions: the roof triangle is twice as wide as it is tall.
        val roof = (surfaces[0].warp as Warp.Polygon).corners
        assertEquals(2.0, (roof.maxOf { it.x } - roof.minOf { it.x }) / (roof.maxOf { it.y } - roof.minOf { it.y }), 1e-6)
    }

    @Test
    fun reimportingKeepsTheAlignment() {
        var p = BlenderImport.apply(project(), "p", loaded(BlenderImport.parse(json)))
        val roof = Mapping.projector(p, "p").surfaces[0]
        p = Mapping.moveCorner(p, "p", roof.id, 1, Vec2(500.0, 50.0))
        val moved = Mapping.corners(Mapping.projector(p, "p").surfaces[0])
        p = BlenderImport.apply(p, "p", loaded(BlenderImport.parse(json)))
        assertEquals(2, Mapping.projector(p, "p").surfaces.size)
        assertEquals(moved, Mapping.corners(Mapping.projector(p, "p").surfaces[0]))
    }

    @Test
    fun polygonCornersMoveButDontFold() {
        var p = BlenderImport.apply(project(), "p", loaded(BlenderImport.parse(json)))
        val roof = Mapping.projector(p, "p").surfaces[0]
        val before = Mapping.corners(roof)
        // Dragging the apex below the base would flip the triangle.
        p = Mapping.moveCorner(p, "p", roof.id, 1, Vec2(before[1].x, 700.0))
        assertEquals(before, Mapping.corners(Mapping.projector(p, "p").surfaces[0]))
        val front = Mapping.projector(p, "p").surfaces[1]
        val centre = Mapping.corners(front).reduce { a, b -> a + b } * (1.0 / 5)
        assertEquals(front.id, Mapping.surfaceAt(Mapping.projector(p, "p"), centre))
    }

    @Test
    fun rejectsOtherFiles() {
        assertFailsWith<RenderException> { BlenderImport.parse("{}") }
        assertFailsWith<RenderException> { BlenderImport.parse(json.replace("oz-surfaces", "other")) }
    }
}
