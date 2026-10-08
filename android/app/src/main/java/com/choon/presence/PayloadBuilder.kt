package com.choon.presence

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 하루치 재실 요약을 서버로 보낼 JSON 으로 만든다 (Android 의존성 없음). 와이파이 이름(SSID)은 포함하지 않는다. */
object PayloadBuilder {
    const val SCHEMA = 1

    fun build(
        participantId: String,
        summary: DaySummary,
        zone: ZoneId,
        mergeGapMinutes: Int,
        appVersion: String,
        partial: Boolean = false,
    ): String {
        val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone)
        fun ts(ms: Long?) = if (ms == null) "null" else q(iso.format(Instant.ofEpochMilli(ms)))
        val sessions = summary.sessions.joinToString(",") {
            """{"start":${ts(it.startMillis)},"end":${ts(it.endMillis)},"minutes":${it.durationMillis / 60_000}}"""
        }
        return buildString {
            append('{')
            append(""""schema":$SCHEMA,""")
            append(""""participantId":${q(participantId)},""")
            append(""""date":${q(summary.date.toString())},""")
            append(""""timezone":${q(zone.id)},""")
            append(""""totalMinutes":${summary.totalMillis / 60_000},""")
            append(""""firstEnter":${ts(summary.firstEnterMillis)},""")
            append(""""lastExit":${ts(summary.lastExitMillis)},""")
            append(""""mergeGapMinutes":$mergeGapMinutes,""")
            append(""""appVersion":${q(appVersion)},""")
            append(""""partial":$partial,""")
            append(""""sessions":[$sessions]""")
            append('}')
        }
    }

    private fun q(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
