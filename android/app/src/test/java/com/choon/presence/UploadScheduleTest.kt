package com.choon.presence

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class UploadScheduleTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test fun beforeThe0015WindowOnlyDayBeforeYesterdayIsEligible() {
        assertEquals(LocalDate.of(2026, 10, 6), UploadSchedule.lastEligibleDay(ms(2026, 10, 8, 0, 14), zone))
        assertEquals(LocalDate.of(2026, 10, 6), UploadSchedule.lastEligibleDay(ms(2026, 10, 8, 0, 0), zone))
    }

    @Test fun fromMidnightPlus15YesterdayIsEligible() {
        assertEquals(LocalDate.of(2026, 10, 7), UploadSchedule.lastEligibleDay(ms(2026, 10, 8, 0, 15), zone))
        assertEquals(LocalDate.of(2026, 10, 7), UploadSchedule.lastEligibleDay(ms(2026, 10, 8, 23, 59), zone))
    }

    @Test fun nextRunIsTodayIfNot0015YetElseTomorrow() {
        assertEquals(ms(2026, 10, 8, 0, 15), UploadSchedule.nextRunMillis(ms(2026, 10, 8, 0, 14), zone))
        assertEquals(ms(2026, 10, 9, 0, 15), UploadSchedule.nextRunMillis(ms(2026, 10, 8, 0, 15), zone))
        assertEquals(ms(2026, 10, 9, 0, 15), UploadSchedule.nextRunMillis(ms(2026, 10, 8, 13, 0), zone))
        assertEquals(ms(2026, 10, 9, 0, 15), UploadSchedule.nextRunMillis(ms(2026, 10, 8, 23, 59), zone))
    }

    @Test fun monthAndYearBoundaries() {
        assertEquals(LocalDate.of(2026, 12, 31), UploadSchedule.lastEligibleDay(ms(2027, 1, 1, 0, 30), zone))
        assertEquals(ms(2027, 1, 1, 0, 15), UploadSchedule.nextRunMillis(ms(2026, 12, 31, 20, 0), zone))
    }
}
