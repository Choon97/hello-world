package com.choon.presence

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PayloadBuilderTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val d0 = LocalDate.of(2026, 10, 7).atStartOfDay(zone).toInstant().toEpochMilli()
    private val h = 3_600_000L

    @Test fun buildsValidJsonWithExpectedFields() {
        val sessions = SessionCalculator.buildSessions(
            listOf(PresenceEvent(d0 + 7 * h, EventType.ENTER), PresenceEvent(d0 + 9 * h, EventType.EXIT)),
            d0 + 20 * h, d0 + 20 * h, 10 * 60_000L)
        val sum = SessionCalculator.summarize(sessions, LocalDate.of(2026, 10, 7), zone)
        val json = JSONObject(PayloadBuilder.build("lab-01", sum, zone, 10, "0.3.0"))
        assertEquals(1, json.getInt("schema"))
        assertEquals("lab-01", json.getString("participantId"))
        assertEquals("2026-10-07", json.getString("date"))
        assertEquals("Asia/Seoul", json.getString("timezone"))
        assertEquals(120, json.getInt("totalMinutes"))
        assertEquals("2026-10-07T07:00:00+09:00", json.getString("firstEnter"))
        assertEquals(1, json.getJSONArray("sessions").length())
        assertEquals(120, json.getJSONArray("sessions").getJSONObject(0).getInt("minutes"))
        assertFalse(json.toString().contains("ssid", ignoreCase = true))
        assertFalse(json.getBoolean("partial"))
        val mid = JSONObject(PayloadBuilder.build("lab-01", sum, zone, 10, "0.3.3", partial = true))
        assertTrue(mid.getBoolean("partial"))
    }

    @Test fun emptyDayHasNullsAndEscapesIds() {
        val sum = SessionCalculator.summarize(emptyList(), LocalDate.of(2026, 10, 7), zone)
        val json = JSONObject(PayloadBuilder.build("a\"b\\c", sum, zone, 10, "0.3.0"))
        assertEquals("a\"b\\c", json.getString("participantId"))
        assertEquals(0, json.getInt("totalMinutes"))
        assertTrue(json.isNull("firstEnter"))
        assertEquals(0, json.getJSONArray("sessions").length())
    }

    @Test fun participantIdValidationMatchesServerRule() {
        assertTrue(PayloadBuilder.isValidParticipantId("lab-01"))
        assertTrue(PayloadBuilder.isValidParticipantId("A_b-9"))
        assertFalse(PayloadBuilder.isValidParticipantId(""))
        assertFalse(PayloadBuilder.isValidParticipantId("홍길동"))
        assertFalse(PayloadBuilder.isValidParticipantId("a b"))
        assertFalse(PayloadBuilder.isValidParticipantId("../x"))
        assertFalse(PayloadBuilder.isValidParticipantId("a".repeat(65)))
    }
}
