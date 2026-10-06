package com.choon.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SessionCalculatorTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val min = 60_000L
    private val hour = 60 * min
    private val day = LocalDate.of(2026, 10, 6)
    private val d0 = day.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun enter(t: Long) = PresenceEvent(t, EventType.ENTER)
    private fun exit(t: Long) = PresenceEvent(t, EventType.EXIT)

    @Test fun simpleSession() {
        val s = SessionCalculator.buildSessions(
            listOf(enter(d0 + 8 * hour), exit(d0 + 9 * hour)), d0 + 23 * hour, d0 + 23 * hour, 10 * min)
        assertEquals(1, s.size)
        assertEquals(hour, s[0].durationMillis)
    }

    @Test fun shortGapIsMerged() {
        val s = SessionCalculator.buildSessions(
            listOf(enter(d0), exit(d0 + hour), enter(d0 + hour + 5 * min), exit(d0 + 2 * hour)),
            d0 + 5 * hour, d0 + 5 * hour, 10 * min)
        assertEquals(1, s.size)
        assertEquals(2 * hour, s[0].durationMillis)
    }

    @Test fun longGapIsKept() {
        val s = SessionCalculator.buildSessions(
            listOf(enter(d0), exit(d0 + hour), enter(d0 + 3 * hour), exit(d0 + 4 * hour)),
            d0 + 5 * hour, d0 + 5 * hour, 10 * min)
        assertEquals(2, s.size)
    }

    @Test fun duplicateEventsIgnored() {
        val s = SessionCalculator.buildSessions(
            listOf(enter(d0), enter(d0 + 10 * min), exit(d0 + hour), exit(d0 + 2 * hour)),
            d0 + 5 * hour, d0 + 5 * hour, 0)
        assertEquals(1, s.size)
        assertEquals(hour, s[0].durationMillis)
    }

    @Test fun openSessionEndsAtNowWhenAlive() {
        val s = SessionCalculator.buildSessions(listOf(enter(d0)), d0 + 2 * hour, d0 + 2 * hour, 0)
        assertTrue(s[0].open)
        assertEquals(2 * hour, s[0].durationMillis)
    }

    @Test fun openSessionCappedWhenServiceDied() {
        val s = SessionCalculator.buildSessions(listOf(enter(d0)), d0 + 10 * hour, d0 + hour, 0)
        assertEquals(hour, s[0].durationMillis)
    }

    @Test fun midnightSessionIsSplitAcrossDays() {
        val sessions = SessionCalculator.buildSessions(
            listOf(enter(d0 - 2 * hour), exit(d0 + 7 * hour)), d0 + 12 * hour, d0 + 12 * hour, 10 * min)
        val today = SessionCalculator.summarize(sessions, day, zone)
        val yesterday = SessionCalculator.summarize(sessions, day.minusDays(1), zone)
        assertEquals(7 * hour, today.totalMillis)
        assertEquals(2 * hour, yesterday.totalMillis)
        assertNull(today.firstEnterMillis)           // 어제 들어왔으므로 오늘의 첫 귀가는 없음
        assertEquals(d0 + 7 * hour, today.lastExitMillis)
        assertNull(yesterday.lastExitMillis)         // 어제는 아직 안 나갔음
    }

    @Test fun firstEnterAndLastExit() {
        val sessions = SessionCalculator.buildSessions(
            listOf(enter(d0 + 7 * hour), exit(d0 + 9 * hour), enter(d0 + 18 * hour), exit(d0 + 22 * hour)),
            d0 + 23 * hour, d0 + 23 * hour, 10 * min)
        val sum = SessionCalculator.summarize(sessions, day, zone)
        assertEquals(d0 + 7 * hour, sum.firstEnterMillis)
        assertEquals(d0 + 22 * hour, sum.lastExitMillis)
        assertEquals(6 * hour, sum.totalMillis)
    }
}
