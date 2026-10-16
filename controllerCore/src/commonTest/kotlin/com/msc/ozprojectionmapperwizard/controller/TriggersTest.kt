package com.msc.ozprojectionmapperwizard.controller

import com.msc.ozprojectionmapperwizard.core.model.MediaItem
import com.msc.ozprojectionmapperwizard.core.model.MediaKind
import com.msc.ozprojectionmapperwizard.core.model.Output
import com.msc.ozprojectionmapperwizard.core.model.Project
import com.msc.ozprojectionmapperwizard.core.model.ProjectorMapping
import com.msc.ozprojectionmapperwizard.core.model.validate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TriggersTest {
    private val loop = MediaItem("loop", "grass.png", MediaKind.IMAGE, 16, 16)
    private val boom = MediaItem("boom", "tnt_explode.gif", MediaKind.VIDEO, 512, 512, durationSeconds = 4.2)

    private fun project(): Project {
        var p = Project(name = "T", projectors = listOf(ProjectorMapping("p", "P", Output(1280, 720))))
        p = Mapping.addSurface(p, "p").first
        p = Mapping.addSurface(p, "p").first
        p = Content.setDuration(Content.setMedia(p, "face-1", loop), 10.0)
        return Content.setMedia(p, "face-2", loop)
    }

    @Test
    fun aTriggerRendersItsFileOnItsFaceForItsLength() {
        val (p, id) = Triggers.add(project(), "face-1", boom)
        assertEquals(emptyList(), p.validate())
        assertEquals("tnt_explode", p.triggers.single().name)
        // Changing what loops on the face keeps the trigger's file.
        assertTrue(Content.setMedia(p, "face-1", null).media.any { it.id == "boom" })

        val render = Triggers.renderProject(p, p.triggers.single())
        assertEquals(4.2, render.scene.durationSeconds)
        assertEquals(boom, Content.mediaFor(render, "face-1"))
        assertEquals(loop, Content.mediaFor(render, "face-2"))
        assertTrue(render.scene.cues.all { it.durationSeconds == 4.2 })

        val shown = Triggers.withShow(p, id, "trigger-abc")
        assertEquals("trigger-abc", shown.triggers.single().showId)
        val removed = Triggers.remove(shown, id)
        assertTrue(removed.triggers.isEmpty())
        assertTrue(removed.media.none { it.id == "boom" })
    }

    @Test
    fun removingTheSurfaceRemovesItsTriggers() {
        val (p, _) = Triggers.add(project(), "face-2", boom)
        val surface = Mapping.projector(p, "p").surfaces.first { it.faceId == "face-2" }
        val removed = Mapping.removeSurface(p, "p", surface.id)
        assertTrue(removed.triggers.isEmpty())
        assertEquals(emptyList(), removed.validate())
    }
}
