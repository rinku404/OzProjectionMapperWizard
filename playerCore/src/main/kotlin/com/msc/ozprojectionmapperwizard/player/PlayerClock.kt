package com.msc.ozprojectionmapperwizard.player

import com.msc.ozprojectionmapperwizard.core.protocol.ClockSync
import com.msc.ozprojectionmapperwizard.core.schedule.ScheduleTime
import com.msc.ozprojectionmapperwizard.core.schedule.Weekday
import java.time.Instant
import java.time.ZoneId

/**
 * Wall clock used for the schedule. Apps cannot set the system clock without root, and a projector
 * with Wi-Fi turned off never gets network time, so the player keeps an offset from the controller's
 * clock (and its time zone) and applies it on top of the device clock.
 */
class PlayerClock(private val storage: PlayerStorage, private val deviceMillis: () -> Long = System::currentTimeMillis) {
    @Volatile private var offsetMillis: Long = 0
    @Volatile var zone: ZoneId = ZoneId.systemDefault()
        private set

    init {
        storage.readText(FILE)?.split("|")?.takeIf { it.size == 2 }?.let { (offset, zoneId) ->
            offsetMillis = offset.toLongOrNull() ?: 0
            zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(zone)
        }
    }

    fun nowMillis(): Long = deviceMillis() + offsetMillis

    fun sync(sync: ClockSync) {
        offsetMillis = sync.epochMillis - deviceMillis()
        zone = ZoneId.of(sync.timeZone)
        storage.writeText(FILE, "$offsetMillis|${zone.id}")
    }

    fun scheduleTime(): ScheduleTime {
        val t = Instant.ofEpochMilli(nowMillis()).atZone(zone)
        return ScheduleTime(
            date = t.toLocalDate().toString(),
            dayOfWeek = Weekday.entries[t.dayOfWeek.value - 1],
            minuteOfDay = t.hour * 60 + t.minute,
        )
    }

    private companion object {
        const val FILE = "clock.txt"
    }
}
