package com.choon.presence

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class EventPrunerTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun ms(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private fun enter(t: Long) = PresenceEvent(t, EventType.ENTER)
    private fun exit(t: Long) = PresenceEvent(t, EventType.EXIT)

    @Test fun cutoffIsMidnightSevenDaysAgo() {
        assertEquals(ms(2026, 10, 1), EventPruner.cutoffMillis(ms(2026, 10, 8, 13, 0), zone))
        assertEquals(ms(2026, 10, 1), EventPruner.cutoffMillis(ms(2026, 10, 8, 0, 1), zone))
        assertEquals(ms(2026, 9, 24), EventPruner.cutoffMillis(ms(2026, 10, 1, 9, 0), zone))
    }

    @Test fun oldCompletedSessionsAreDropped() {
        val cutoff = ms(2026, 10, 1)
        val events = listOf(enter(ms(2026, 9, 28, 9)), exit(ms(2026, 9, 28, 18)), enter(ms(2026, 10, 2, 9)), exit(ms(2026, 10, 2, 18)))
        assertEquals(listOf(enter(ms(2026, 10, 2, 9)), exit(ms(2026, 10, 2, 18))), EventPruner.prune(events, cutoff))
    }

    @Test fun sessionSpanningCutoffKeepsItsStateWithSyntheticEnter() {
        val cutoff = ms(2026, 10, 1)
        val events = listOf(enter(ms(2026, 9, 30, 22)), exit(ms(2026, 10, 1, 7)))
        assertEquals(listOf(enter(cutoff), exit(ms(2026, 10, 1, 7))), EventPruner.prune(events, cutoff))
    }

    @Test fun stillHomeAcrossCutoffWithNoLaterEvents() {
        val cutoff = ms(2026, 10, 1)
        assertEquals(listOf(enter(cutoff)), EventPruner.prune(listOf(enter(ms(2026, 9, 20, 9))), cutoff))
    }

    @Test fun nothingOldMeansNothingChanges() {
        val events = listOf(enter(ms(2026, 10, 3, 9)), exit(ms(2026, 10, 3, 18)))
        assertEquals(events, EventPruner.prune(events, ms(2026, 10, 1)))
    }

    @Test fun allOldAndAwayMeansEmpty() {
        val events = listOf(enter(ms(2026, 9, 20, 9)), exit(ms(2026, 9, 20, 18)))
        assertEquals(emptyList<PresenceEvent>(), EventPruner.prune(events, ms(2026, 10, 1)))
    }

    @Test fun prunedHistoryYieldsSameTotalsForRecentDays() {
        val cutoff = ms(2026, 10, 1)
        val events = listOf(enter(ms(2026, 9, 30, 22)), exit(ms(2026, 10, 1, 7)), enter(ms(2026, 10, 2, 9)), exit(ms(2026, 10, 2, 17)))
        val now = ms(2026, 10, 3, 12)
        fun total(ev: List<PresenceEvent>, d: java.time.LocalDate) = SessionCalculator.summarize(
            SessionCalculator.buildSessions(ev, now, now, 10 * 60_000L), d, zone).totalMillis
        val pruned = EventPruner.prune(events, cutoff)
        for (day in 2..3) { // 경계 하루(10/1)는 제외하고 비교
            val d = java.time.LocalDate.of(2026, 10, day)
            assertEquals(total(events, d), total(pruned, d))
        }
    }
}
