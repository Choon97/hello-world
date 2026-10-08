package com.choon.presence

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 서버 전송 시각 규칙: 매일 00:15(폰의 현지 시각) 이후에 "어제"까지 보낸다 (Android 의존성 없음). */
object UploadSchedule {
    val RUN_AT: LocalTime = LocalTime.of(0, 15)

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
