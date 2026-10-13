package com.msc.ozprojectionmapperwizard.core

import com.msc.ozprojectionmapperwizard.core.geometry.Vec2
import com.msc.ozprojectionmapperwizard.core.model.Cue
import com.msc.ozprojectionmapperwizard.core.model.Face
import com.msc.ozprojectionmapperwizard.core.model.Keyframe
import com.msc.ozprojectionmapperwizard.core.model.MediaItem
import com.msc.ozprojectionmapperwizard.core.model.MediaKind
import com.msc.ozprojectionmapperwizard.core.model.Output
import com.msc.ozprojectionmapperwizard.core.model.Project
import com.msc.ozprojectionmapperwizard.core.model.ProjectorMapping
import com.msc.ozprojectionmapperwizard.core.model.Scene
import com.msc.ozprojectionmapperwizard.core.model.Surface
import com.msc.ozprojectionmapperwizard.core.model.Warp
import com.msc.ozprojectionmapperwizard.core.model.fadeAt
import com.msc.ozprojectionmapperwizard.core.model.opacityAt
import com.msc.ozprojectionmapperwizard.core.model.validate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectTest {
    private val project = Project(
        name = "Box",
        media = listOf(MediaItem("sha256:ab12", "waves.mp4", MediaKind.VIDEO, 1920, 1080, 12.0)),
        scene = Scene(
            faces = listOf(Face("front", "Front"), Face("left", "Left")),
            cues = listOf(Cue("front", "sha256:ab12", startSeconds = 0.0, durationSeconds = 30.0, fadeInSeconds = 1.0)),
        ),
        projectors = listOf(
            ProjectorMapping(
                id = "hy300-a",
                name = "HY300 left",
                output = Output.HD_720,
                surfaces = listOf(
                    Surface(
                        id = "s1",
                        faceId = "front",
                        warp = Warp.Quad(listOf(Vec2(100.0, 80.0), Vec2(600.0, 60.0), Vec2(620.0, 650.0), Vec2(90.0, 640.0))),
                    ),
                    Surface(
                        id = "s2",
                        faceId = "left",
                        warp = Warp.Mesh(2, 2, listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(0.0, 1.0), Vec2(1.0, 1.0))),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun jsonRoundTrips() {
        val text = ProjectJson.encodeProject(project)
        assertTrue("\"type\": \"quad\"" in text, text)
        assertEquals(project, ProjectJson.decodeProject(text))
    }

    @Test
    fun ignoresUnknownFieldsFromNewerVersions() {
        val text = ProjectJson.encodeProject(project).replaceFirst("{", "{\n  \"futureField\": 42,")
        assertEquals(project, ProjectJson.decodeProject(text))
    }

    @Test
    fun validProjectHasNoProblems() {
        assertEquals(emptyList(), project.validate())
    }

    @Test
    fun reportsBrokenReferencesAndWarps() {
        val broken = project.copy(
            scene = project.scene.copy(cues = project.scene.cues + Cue("roof", "missing", 0.0, 5.0)),
            projectors = listOf(
                project.projectors[0].copy(
                    surfaces = listOf(Surface("s1", "front", Warp.Quad(listOf(Vec2.ZERO, Vec2.ZERO, Vec2.ZERO)))),
                ),
            ),
        )
        val problems = broken.validate()
        assertEquals(3, problems.size, problems.toString())
    }

    @Test
    fun cueFadesInAndStops() {
        val cue = project.scene.cues[0]
        assertEquals(0.5, cue.fadeAt(0.5))
        assertEquals(1.0, cue.fadeAt(10.0))
        assertEquals(0.0, cue.fadeAt(30.0))
    }

    @Test
    fun opacityKeysCrossFade() {
        val s = project.projectors[0].surfaces[0].copy(opacityKeys = listOf(Keyframe(10.0, 1.0), Keyframe(14.0, 0.0)))
        assertEquals(1.0, s.opacityAt(0.0))
        assertEquals(0.5, s.opacityAt(12.0))
        assertEquals(0.0, s.opacityAt(20.0))
    }
}
