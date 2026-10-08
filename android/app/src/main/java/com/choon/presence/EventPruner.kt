package com.choon.presence

import java.time.Instant
import java.time.ZoneId

/**
 * 기기에는 최근 기록만 보관한다 (Android 의존성 없음).
 * 보관 시작 = [RETENTION_DAYS]일 전 자정. 화면/수동 전송은 그보다 하루 뒤(6일 전)부터만 쓰므로,
 * 경계에서 잘린 하루(첫귀가가 잘못 계산됨)는 서버로 올라가지 않는다.
 */
object EventPruner {
    const val RETENTION_DAYS = 7L

    fun cutoffMillis(nowMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            .minusDays(RETENTION_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * [cutoff] 이전 이벤트를 지운다. 경계를 걸쳐 집에 있던 중이면 상태가 유지되도록 cutoff 시각에 ENTER 를 넣는다.
     */
    fun prune(events: List<PresenceEvent>, cutoff: Long): List<PresenceEvent> {
        val sorted = events.sortedBy { it.timeMillis }
        val before = sorted.filter { it.timeMillis < cutoff }
        if (before.isEmpty()) return sorted
        val after = sorted.filter { it.timeMillis >= cutoff }
        val homeAtCutoff = before.last().type == EventType.ENTER
        return if (homeAtCutoff && after.firstOrNull()?.type != EventType.ENTER) {
            listOf(PresenceEvent(cutoff, EventType.ENTER)) + after
        } else {
            after
        }
    }
}
