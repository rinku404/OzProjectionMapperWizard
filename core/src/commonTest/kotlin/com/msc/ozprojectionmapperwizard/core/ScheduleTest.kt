package com.msc.ozprojectionmapperwizard.core

import com.msc.ozprojectionmapperwizard.core.schedule.Schedule
import com.msc.ozprojectionmapperwizard.core.schedule.ScheduleRule
import com.msc.ozprojectionmapperwizard.core.schedule.ScheduleTime
import com.msc.ozprojectionmapperwizard.core.schedule.TimeWindow
import com.msc.ozprojectionmapperwizard.core.schedule.Weekday
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScheduleTest {
    private val schedule = Schedule(
        default = "ambient",
        rules = listOf(
            ScheduleRule("evening", days = listOf(Weekday.FRI, Weekday.SAT), window = TimeWindow("18:00", "23:30")),
            ScheduleRule("xmas", window = TimeWindow("17:00", "22:00"), dateFrom = "2026-12-01", dateTo = "2026-12-31"),
        ),
        offHours = TimeWindow("23:30", "07:00"),
    )

    private fun at(date: String, day: Weekday, hhmm: String) = ScheduleTime(date, day, TimeWindow.parseMinutes(hhmm))

    @Test
    fun fallsBackToDefault() = assertEquals("ambient", schedule.resolve(at("2026-10-14", Weekday.WED, "19:00")))

    @Test
    fun weekdayRuleMatches() = assertEquals("evening", schedule.resolve(at("2026-10-16", Weekday.FRI, "19:00")))

    @Test
    fun dateRangeBeatsWeekday() = assertEquals("xmas", schedule.resolve(at("2026-12-04", Weekday.FRI, "19:00")))

    @Test
    fun offHoursPastMidnightIsBlack() = assertNull(schedule.resolve(at("2026-10-17", Weekday.SAT, "02:00")))

    @Test
    fun jsonRoundTrips() = assertEquals(schedule, ProjectJson.decodeSchedule(ProjectJson.encodeSchedule(schedule)))
}
