package com.choon.presence

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 서버 전송 시각 규칙: 매일 00:15(폰의 현지 시각) 이후에 "어제"까지 보낸다 (Android 의존성 없음). */
/** 서버로 보낼 하루. [partial] 이면 아직 끝나지 않은 날(중간값)이다. */
data class PlannedDay(val date: LocalDate, val partial: Boolean)

object UploadSchedule {
    val RUN_AT: LocalTime = LocalTime.of(0, 15)
    const val MAX_BACKFILL_DAYS = 14L
    const val MANUAL_WINDOW_DAYS = 30L

    /**
     * 이번에 보낼 날짜 목록.
     * - 자동: 아직 안 보낸 날부터 "보내도 되는 마지막 날"(00:15 이후 어제)까지, 최대 [MAX_BACKFILL_DAYS]일 전부터. 전부 확정값.
     * - 수동: 최근 [MANUAL_WINDOW_DAYS]일 전체를 오늘(진행 중)까지 다시 보낸다. 확정 전 날짜는 partial.
     *   서버는 같은 (날짜, 참여자)를 덮어쓰므로 여러 번 보내도 안전하다.
     */
    fun plan(firstDay: LocalDate, uploadedThrough: Long, nowMillis: Long, zone: ZoneId, manual: Boolean): List<PlannedDay> {
        val today = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().toEpochDay()
        val lastFinal = lastEligibleDay(nowMillis, zone).toEpochDay()
        val start: Long
        val end: Long
        if (manual) {
            start = maxOf(firstDay.toEpochDay(), today - MANUAL_WINDOW_DAYS)
            end = today
        } else {
            start = maxOf(uploadedThrough + 1, firstDay.toEpochDay(), lastFinal - MAX_BACKFILL_DAYS)
            end = lastFinal
        }
        if (start > end) return emptyList()
        return (start..end).map { PlannedDay(LocalDate.ofEpochDay(it), it > lastFinal) }
    }

    /** [nowMillis] 시점에 서버로 보내도 되는 마지막 날짜. 00:15 이전이면 그저께, 이후면 어제. */
    fun lastEligibleDay(nowMillis: Long, zone: ZoneId): LocalDate {
        val now = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone)
        return if (now.toLocalTime() >= RUN_AT) now.toLocalDate().minusDays(1) else now.toLocalDate().minusDays(2)
    }

    /** 다음 00:15 의 epoch millis (지금이 정확히 00:15 이후면 내일). */
    fun nextRunMillis(nowMillis: Long, zone: ZoneId): Long {
        val now = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone)
        var next = now.toLocalDate().atTime(RUN_AT).atZone(zone)
        if (!next.toInstant().isAfter(now.toInstant())) next = now.toLocalDate().plusDays(1).atTime(RUN_AT).atZone(zone)
        return next.toInstant().toEpochMilli()
    }
}
