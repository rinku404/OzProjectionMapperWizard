package com.msc.ozprojectionmapperwizard.player

import com.msc.ozprojectionmapperwizard.core.model.ShowManifest
import com.msc.ozprojectionmapperwizard.core.protocol.NowPlaying
import com.msc.ozprojectionmapperwizard.core.protocol.PlayReason
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the screen should show right now. [file] is null for black or the setup screen. */
data class Playback(val nowPlaying: NowPlaying, val show: ShowManifest? = null, val file: File? = null)

/**
 * Decides what plays: a controller override, otherwise the schedule, otherwise the setup screen.
 * Platform players observe [playback] and only handle decoding and drawing.
 */
class PlaybackDirector(private val storage: PlayerStorage, private val clock: PlayerClock) {
    private sealed interface Override {
        data class Show(val id: String) : Override
        data object Black : Override
    }

    @Volatile private var override: Override? = null
    private val state = MutableStateFlow(compute())
    val playback: StateFlow<Playback> = state.asStateFlow()

    fun play(showId: String): Boolean {
        if (storage.show(showId) == null) return false
        override = Override.Show(showId)
        refresh()
        return true
    }

    fun black() {
        override = Override.Black
        refresh()
    }

    fun resumeSchedule() {
        override = null
        refresh()
    }

    /** Re-evaluates after the shows, schedule or clock changed. */
    fun refresh() {
        state.value = compute()
    }

    /** Re-checks the schedule every [periodMillis] so rule windows start and end on time. */
    fun start(scope: CoroutineScope, periodMillis: Long = 15_000): Job = scope.launch {
        while (isActive) {
            refresh()
            delay(periodMillis)
        }
    }

    private fun compute(): Playback {
        when (val o = override) {
            is Override.Black -> return Playback(NowPlaying(null, PlayReason.BLACK))
            is Override.Show -> storage.show(o.id)?.let { return playing(it, PlayReason.OVERRIDE) }
            null -> Unit
        }
        val schedule = storage.schedule()
        val at = clock.scheduleTime()
        if (schedule.offHours?.contains(at.minuteOfDay) == true) return Playback(NowPlaying(null, PlayReason.OFF_HOURS))
        val shows = storage.shows()
        val chosen = schedule.resolve(at)?.let { id -> shows.firstOrNull { it.id == id } }
            // With no usable schedule yet, a freshly uploaded show should just play.
            ?: shows.singleOrNull()
        return if (chosen != null) playing(chosen, PlayReason.SCHEDULE) else Playback(NowPlaying(null, PlayReason.NO_SHOW))
    }

    private fun playing(show: ShowManifest, reason: PlayReason) =
        Playback(NowPlaying(show.id, reason), show, storage.file(show.sha256))
}
