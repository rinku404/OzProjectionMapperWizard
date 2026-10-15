package com.msc.ozprojectionmapperwizard.controller

import com.msc.ozprojectionmapperwizard.core.model.MediaItem
import com.msc.ozprojectionmapperwizard.core.model.MediaKind
import com.msc.ozprojectionmapperwizard.core.model.Output
import com.msc.ozprojectionmapperwizard.core.model.Project
import com.msc.ozprojectionmapperwizard.core.model.ProjectorMapping
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContentTest {
    private val clip = MediaItem("aaa", "clip.mp4", MediaKind.VIDEO, 1920, 1080, 12.4)
    private val logo = MediaItem("bbb", "logo.png", MediaKind.IMAGE, 512, 512)

    private fun project(): Project {
        var p = Project(name = "T", projectors = listOf(ProjectorMapping("p", "P", Output.HD_720)))
        p = Mapping.addSurface(p, "p").first
        return Mapping.addSurface(p, "p").first
    }

    @Test
    fun choosingMediaPlaysItForTheWholeShow() {
        val p = Content.setMedia(project(), "face-1", clip)
        assertEquals(clip, Content.mediaFor(p, "face-1"))
        assertNull(Content.mediaFor(p, "face-2"))
        val cue = p.scene.cues.single()
        assertEquals(0.0, cue.startSeconds)
        assertEquals(p.scene.durationSeconds, cue.durationSeconds)
        assertEquals(listOf(clip), p.media)
    }

    @Test
    fun replacingAndClearingDropsUnusedMedia() {
        var p = Content.setMedia(project(), "face-1", clip)
        p = Content.setMedia(p, "face-2", clip)
        p = Content.setMedia(p, "face-1", logo)
        assertEquals(setOf("aaa", "bbb"), p.media.map { it.id }.toSet())
        assertEquals(2, p.scene.cues.size)
        p = Content.setMedia(p, "face-2", null)
        assertEquals(listOf(logo), p.media)
    }

    private fun threeSurfaces(): Project = Mapping.addSurface(project(), "p").first

    private fun texture(n: Int) = MediaItem("t$n", "block$n.png", MediaKind.IMAGE, 16, 16)

    @Test
    fun fillingInOrderStartsOverWhenTheGroupRunsOut() {
        val faces = listOf("face-1", "face-2", "face-3")
        val p = Content.fill(threeSurfaces(), faces, listOf(texture(1), texture(2)), Content.FillOrder.IN_ORDER)
        assertEquals(listOf("t1", "t2", "t1"), faces.map { Content.mediaFor(p, it)?.id })
        assertEquals(setOf("t1", "t2"), p.media.map { it.id }.toSet())
    }

    @Test
    fun shufflingUsesEveryFileBeforeRepeating() {
        val faces = listOf("face-1", "face-2", "face-3")
        val files = (1..3).map(::texture)
        repeat(20) { seed ->
            val p = Content.fill(threeSurfaces(), faces, files, Content.FillOrder.SHUFFLED, Random(seed))
            assertEquals(setOf("t1", "t2", "t3"), faces.mapNotNull { Content.mediaFor(p, it)?.id }.toSet())
        }
    }

    @Test
    fun fillingWithAnEmptyGroupChangesNothingAndOneFileGoesEverywhere() {
        val faces = listOf("face-1", "face-2", "face-3")
        val start = Content.setMedia(threeSurfaces(), "face-1", clip)
        assertEquals(start, Content.fill(start, faces, emptyList(), Content.FillOrder.SHUFFLED))
        val p = Content.setMediaOnAll(start, faces, logo)
        assertEquals(listOf(logo, logo, logo), faces.map { Content.mediaFor(p, it) })
        assertEquals(listOf(logo), p.media)
    }

    @Test
    fun showLengthStretchesCuesAndFollowsTheLongestVideo() {
        var p = Content.setMedia(project(), "face-1", logo)
        assertEquals(10.0, Content.suggestedDuration(p))
        p = Content.setMedia(p, "face-2", clip)
        assertEquals(13.0, Content.suggestedDuration(p))
        p = Content.setDuration(p, 13.0)
        assertEquals(listOf(13.0, 13.0), p.scene.cues.map { it.durationSeconds })
        assertEquals(Content.MAX_SECONDS, Content.setDuration(p, 99999.0).scene.durationSeconds)
    }
}
