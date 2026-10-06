package com.choon.presence

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class EventType { ENTER, EXIT }

data class PresenceEvent(val timeMillis: Long, val type: EventType)

data class Session(val startMillis: Long, val endMillis: Long, val open: Boolean = false) {
    val durationMillis: Long get() = endMillis - startMillis
}

data class DaySummary(
    val date: LocalDate,
    val totalMillis: Long,
    val firstEnterMillis: Long?,
    val lastExitMillis: Long?,
    val sessions: List<Session>,
)

/**
 * 와이파이 연결/해제 이벤트 -> 재실 세션 -> 일별 요약.
 *
 * - 중복 ENTER/EXIT 는 무시한다.
 * - 끊긴 시간이 [mergeGapMillis] 이하인 세션은 하나로 합친다 (절전 모드 등으로 인한 순간 끊김 보정).
 * - 아직 닫히지 않은 세션의 끝은 min(now, aliveUntilMillis) 로 잡는다.
 *   앱/폰이 꺼져서 EXIT 를 못 받은 경우 세션이 무한정 늘어나는 것을 막기 위함이다.
 */
object SessionCalculator {

    fun buildSessions(
        events: List<PresenceEvent>,
        nowMillis: Long,
        aliveUntilMillis: Long,
        mergeGapMillis: Long,
    ): List<Session> {
        val raw = mutableListOf<Session>()
        var start: Long? = null
        for (e in events.sortedBy { it.timeMillis }) {
            when (e.type) {
                EventType.ENTER -> if (start == null) start = e.timeMillis
                EventType.EXIT -> start?.let {
                    raw += Session(it, maxOf(it, e.timeMillis))
                    start = null
                }
            }
        }
        start?.let {
            val end = maxOf(it, minOf(nowMillis, aliveUntilMillis))
            raw += Session(it, end, open = true)
        }

        val merged = mutableListOf<Session>()
        for (s in raw) {
            val last = merged.lastOrNull()
            if (last != null && s.startMillis - last.endMillis <= mergeGapMillis) {
                merged[merged.lastIndex] =
                    Session(last.startMillis, maxOf(last.endMillis, s.endMillis), s.open)
            } else {
                merged += s
            }
        }
        return merged
    }

    /** [date] 하루(로컬 타임존) 안에 들어오는 부분만 잘라서 요약한다. */
    fun summarize(sessions: List<Session>, date: LocalDate, zone: ZoneId): DaySummary {
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val clipped = sessions.mapNotNull {
            val s = maxOf(it.startMillis, dayStart)
            val e = minOf(it.endMillis, dayEnd)
            if (e > s) Session(s, e, it.open && it.endMillis <= dayEnd) else null
        }
        // 첫 귀가/마지막 외출은 "그날 시작된 입장 / 그날 끝난 퇴장"만 의미가 있으므로 원본 기준으로 판단
        val firstEnter = sessions.firstOrNull { it.startMillis in dayStart until dayEnd }?.startMillis
        val lastExit = sessions.lastOrNull { !it.open && it.endMillis in dayStart until dayEnd }?.endMillis
        return DaySummary(date, clipped.sumOf { it.durationMillis }, firstEnter, lastExit, clipped)
    }

    fun today(nowMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
}
