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

    // ---- plan(): 자동/수동 전송 대상
    private val first = LocalDate.of(2026, 10, 1)
    private fun dates(l: List<PlannedDay>) = l.map { it.date.toString() }

    @Test fun autoSendsFinishedDaysAfter0015AndNeverToday() {
        val plan = UploadSchedule.plan(first, LocalDate.of(2026, 10, 5).toEpochDay(), ms(2026, 10, 8, 0, 20), zone, manual = false)
        assertEquals(listOf("2026-10-06", "2026-10-07"), dates(plan))
        assertEquals(false, plan.any { it.partial })
    }

    @Test fun autoBefore0015DoesNotSendYesterday() {
        val plan = UploadSchedule.plan(first, LocalDate.of(2026, 10, 5).toEpochDay(), ms(2026, 10, 8, 0, 10), zone, manual = false)
        assertEquals(listOf("2026-10-06"), dates(plan))
    }

    @Test fun autoNothingToSendWhenCaughtUp() {
        val plan = UploadSchedule.plan(first, LocalDate.of(2026, 10, 7).toEpochDay(), ms(2026, 10, 8, 9, 0), zone, manual = false)
        assertEquals(emptyList<PlannedDay>(), plan)
    }

    @Test fun autoBackfillIsLimitedTo14Days() {
        val plan = UploadSchedule.plan(LocalDate.of(2026, 1, 1), -1L, ms(2026, 10, 8, 9, 0), zone, manual = false)
        assertEquals(15, plan.size)                      // 14일 전 ~ 어제
        assertEquals("2026-09-23", plan.first().date.toString())
        assertEquals("2026-10-07", plan.last().date.toString())
    }

    @Test fun manualIncludesTodayAsPartialEvenWhenCaughtUp() {
        val plan = UploadSchedule.plan(first, LocalDate.of(2026, 10, 7).toEpochDay(), ms(2026, 10, 8, 9, 0), zone, manual = true)
        assertEquals("2026-10-08", dates(plan).last())
        assertEquals(true, plan.last().partial)
        assertEquals(false, plan.first { it.date == LocalDate.of(2026, 10, 7) }.partial)
        assertEquals(8, plan.size)                       // 10/1 ~ 10/8
    }

    @Test fun manualBefore0015MarksYesterdayPartialToo() {
        val plan = UploadSchedule.plan(first, LocalDate.of(2026, 10, 6).toEpochDay(), ms(2026, 10, 8, 0, 5), zone, manual = true)
        assertEquals(true, plan.first { it.date == LocalDate.of(2026, 10, 7) }.partial)
        assertEquals(false, plan.first { it.date == LocalDate.of(2026, 10, 6) }.partial)
    }

    @Test fun manualOnFirstDayOnlySendsToday() {
        val plan = UploadSchedule.plan(LocalDate.of(2026, 10, 8), -1L, ms(2026, 10, 8, 9, 0), zone, manual = true)
        assertEquals(listOf("2026-10-08"), dates(plan))
    }

    @Test fun manualWindowIs30Days() {
        val plan = UploadSchedule.plan(LocalDate.of(2026, 1, 1), -1L, ms(2026, 10, 8, 9, 0), zone, manual = true)
        assertEquals(31, plan.size)
        assertEquals("2026-09-08", plan.first().date.toString())
    }
}
