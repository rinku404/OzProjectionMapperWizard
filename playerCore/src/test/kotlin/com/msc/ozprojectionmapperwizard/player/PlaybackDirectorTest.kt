package com.msc.ozprojectionmapperwizard.player

import com.msc.ozprojectionmapperwizard.core.protocol.ClockSync
import com.msc.ozprojectionmapperwizard.core.protocol.PlayReason
import com.msc.ozprojectionmapperwizard.core.schedule.Schedule
import com.msc.ozprojectionmapperwizard.core.schedule.ScheduleRule
import com.msc.ozprojectionmapperwizard.core.schedule.TimeWindow
import com.msc.ozprojectionmapperwizard.core.schedule.Weekday
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackDirectorTest {
    private val storage = tempStorage()
    private var deviceNow = 0L
    private val clock = PlayerClock(storage) { deviceNow }
    private val director = PlaybackDirector(storage, clock)

    private fun setTime(iso: String) {
        val t = ZonedDateTime.parse(iso)
        clock.sync(ClockSync(t.toInstant().toEpochMilli(), t.zone.id))
        director.refresh()
    }

    private fun install(id: String) = storage.installShow(manifest(id, storage.addFile(id.toByteArray())))

    @Test
    fun showsSetupScreenWithNothingInstalled() {
        assertEquals(PlayReason.NO_SHOW, director.playback.value.nowPlaying.reason)
    }

    @Test
    fun playsTheOnlyShowWithoutASchedule() {
        install("ambient")
        director.refresh()
        assertEquals("ambient", director.playback.value.nowPlaying.showId)
    }

    @Test
    fun homeShowsTheSetupScreenUntilResumedOrANewShowArrives() {
        install("ambient")
        director.refresh()
        director.home()
        assertEquals(PlayReason.NO_SHOW, director.playback.value.nowPlaying.reason)
        director.refresh()
        assertEquals(PlayReason.NO_SHOW, director.playback.value.nowPlaying.reason)
        director.resumeSchedule()
        assertEquals("ambient", director.playback.value.nowPlaying.showId)

        director.home()
        install("new")
        storage.saveSchedule(Schedule(default = "new"))
        director.showInstalled()
        assertEquals("new", director.playback.value.nowPlaying.showId)
    }

    @Test
    fun aTriggerPlaysOnceThenTheShowCarriesOn() {
        install("ambient")
        storage.installShow(manifest("boom", storage.addFile("boom".toByteArray())).copy(oneShot = true))
        director.refresh()
        // The schedule ignores triggers, so the only real show still plays by itself.
        assertEquals("ambient", director.playback.value.nowPlaying.showId)

        director.play("boom")
        val first = director.playback.value
        assertEquals("boom", first.nowPlaying.showId)
        assertEquals(true, first.once)
        director.play("boom")
        assertEquals(first.firing + 1, director.playback.value.firing)

        director.finished("boom")
        assertEquals("ambient", director.playback.value.nowPlaying.showId)
        assertEquals(false, director.playback.value.once)
    }

    @Test
    fun followsTheScheduleAndOffHours() {
        install("ambient")
        install("evening")
        storage.saveSchedule(
            Schedule(
                default = "ambient",
                rules = listOf(ScheduleRule("evening", days = listOf(Weekday.FRI), window = TimeWindow("18:00", "23:00"))),
                offHours = TimeWindow("23:30", "07:00"),
            ),
        )
        setTime("2026-10-16T19:00:00-03:00[America/Sao_Paulo]") // a Friday evening
        assertEquals("evening", director.playback.value.nowPlaying.showId)
        setTime("2026-10-16T12:00:00-03:00[America/Sao_Paulo]")
        assertEquals("ambient", director.playback.value.nowPlaying.showId)
        setTime("2026-10-17T02:00:00-03:00[America/Sao_Paulo]")
        assertEquals(PlayReason.OFF_HOURS, director.playback.value.nowPlaying.reason)
        assertNull(director.playback.value.file)
    }

    @Test
    fun overridesWinUntilResumed() {
        install("ambient")
        install("test")
        storage.saveSchedule(Schedule(default = "ambient"))
        director.play("test")
        assertEquals(PlayReason.OVERRIDE, director.playback.value.nowPlaying.reason)
        director.black()
        assertEquals(PlayReason.BLACK, director.playback.value.nowPlaying.reason)
        director.resumeSchedule()
        assertEquals("ambient", director.playback.value.nowPlaying.showId)
    }

    @Test
    fun clockOffsetSurvivesRestart() {
        setTime("2026-10-16T19:00:00-03:00[America/Sao_Paulo]")
        val restarted = PlayerClock(storage) { deviceNow }
        assertEquals(clock.scheduleTime(), restarted.scheduleTime())
    }
}
