package com.msc.ozprojectionmapperwizard.core.schedule

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which show plays when, stored on the player as schedule.json.
 *
 * The most specific matching rule wins: a date range beats a weekday rule, which beats a rule
 * with neither; ties go to the earlier rule. Off hours project black. Otherwise [default] plays.
 */
@Serializable
data class Schedule(
    val version: Int = 1,
    val default: String? = null,
    val rules: List<ScheduleRule> = emptyList(),
    val offHours: TimeWindow? = null,
) {
    /** Show id to play at [at], or null for black. */
    fun resolve(at: ScheduleTime): String? {
        if (offHours?.contains(at.minuteOfDay) == true) return null
        return rules
            .filter { it.matches(at) }
            .maxByOrNull { it.specificity }
            ?.show
            ?: default
    }
}

@Serializable
data class ScheduleRule(
    val show: String,
    /** Empty means every day. */
    val days: List<Weekday> = emptyList(),
    val window: TimeWindow = TimeWindow.ALL_DAY,
    /** Inclusive, as YYYY-MM-DD. */
    val dateFrom: String? = null,
    val dateTo: String? = null,
) {
    internal val specificity: Int
        get() = when {
            dateFrom != null || dateTo != null -> 2
            days.isNotEmpty() -> 1
            else -> 0
        }

    internal fun matches(at: ScheduleTime): Boolean =
        (days.isEmpty() || at.dayOfWeek in days) &&
            (dateFrom == null || at.date >= dateFrom) &&
            (dateTo == null || at.date <= dateTo) &&
            window.contains(at.minuteOfDay)
}

/** A daily window as "HH:mm". A window ending before it starts runs past midnight. */
@Serializable
data class TimeWindow(val from: String, val to: String) {
    private val fromMinute get() = parseMinutes(from)
    private val toMinute get() = parseMinutes(to)

    fun contains(minuteOfDay: Int): Boolean =
        if (fromMinute <= toMinute) minuteOfDay in fromMinute until toMinute
        else minuteOfDay >= fromMinute || minuteOfDay < toMinute

    companion object {
        val ALL_DAY = TimeWindow("00:00", "24:00")

        fun parseMinutes(hhmm: String): Int {
            val parts = hhmm.split(":")
            require(parts.size == 2) { "Expected HH:mm, got '$hhmm'" }
            val h = parts[0].toInt()
            val m = parts[1].toInt()
            require(h in 0..24 && m in 0..59 && (h < 24 || m == 0)) { "Invalid time '$hhmm'" }
            return h * 60 + m
        }
    }
}

/** A local wall-clock moment on the player. [date] is YYYY-MM-DD so it compares as a string. */
data class ScheduleTime(val date: String, val dayOfWeek: Weekday, val minuteOfDay: Int)

@Serializable
enum class Weekday {
    @SerialName("mon") MON,
    @SerialName("tue") TUE,
    @SerialName("wed") WED,
    @SerialName("thu") THU,
    @SerialName("fri") FRI,
    @SerialName("sat") SAT,
    @SerialName("sun") SUN,
}
