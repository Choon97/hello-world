package com.joke.taximeter

import android.content.Context

/**
 * 요금 설정. 기본값은 나주시 택시운임표(나주시청 홈페이지) 기준.
 * 거리요금과 시간요금의 추가 금액이 같다고 보고(130m당 140원 = 30초당 140원) 하나로 다룬다.
 */
data class FareConfig(
    val regionName: String = "나주",
    val baseFare: Int = 4300,        // 기본운임(원)
    val baseDistanceM: Int = 2000,   // 기본거리(m)
    val unitFare: Int = 140,         // 거리·시간 추가 요금(원)
    val distanceUnitM: Int = 130,    // 거리 단위(m)
    val timeUnitSec: Int = 30,       // 시간 단위(초)
    val slowSpeedKmh: Int = 15,      // 이 속도 이하에서는 시간요금
    val nightStartHour: Int = 0,     // 심야 할증 시작(시)
    val nightEndHour: Int = 4,       // 심야 할증 끝(시)
    val nightPct: Int = 20,          // 심야 할증(%)
    val outsidePct: Int = 35,        // 시계 외 할증(%)
    val combinedPct: Int = 40,       // 심야 + 시계 외(%)
) {
    fun summary(): String =
        "기본 %,d원(%skm) · %dm당 %d원 · %d초당 %d원 · 심야 %02d~%02d시 %d%% · 시외 %d%%".format(
            baseFare, km(baseDistanceM), distanceUnitM, unitFare, timeUnitSec, unitFare,
            nightStartHour, nightEndHour, nightPct, outsidePct,
        )

    private fun km(m: Int): String =
        if (m % 1000 == 0) (m / 1000).toString() else "%.1f".format(m / 1000.0)

    companion object {
        private const val PREFS = "fare_config"

        fun load(ctx: Context): FareConfig {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = FareConfig()
            return FareConfig(
                regionName = p.getString("regionName", d.regionName) ?: d.regionName,
                baseFare = p.getInt("baseFare", d.baseFare),
                baseDistanceM = p.getInt("baseDistanceM", d.baseDistanceM),
                unitFare = p.getInt("unitFare", d.unitFare),
                distanceUnitM = p.getInt("distanceUnitM", d.distanceUnitM).coerceAtLeast(1),
                timeUnitSec = p.getInt("timeUnitSec", d.timeUnitSec).coerceAtLeast(1),
                slowSpeedKmh = p.getInt("slowSpeedKmh", d.slowSpeedKmh),
                nightStartHour = p.getInt("nightStartHour", d.nightStartHour),
                nightEndHour = p.getInt("nightEndHour", d.nightEndHour),
                nightPct = p.getInt("nightPct", d.nightPct),
                outsidePct = p.getInt("outsidePct", d.outsidePct),
                combinedPct = p.getInt("combinedPct", d.combinedPct),
            )
        }

        fun save(ctx: Context, c: FareConfig) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("regionName", c.regionName)
                .putInt("baseFare", c.baseFare)
                .putInt("baseDistanceM", c.baseDistanceM)
                .putInt("unitFare", c.unitFare)
                .putInt("distanceUnitM", c.distanceUnitM)
                .putInt("timeUnitSec", c.timeUnitSec)
                .putInt("slowSpeedKmh", c.slowSpeedKmh)
                .putInt("nightStartHour", c.nightStartHour)
                .putInt("nightEndHour", c.nightEndHour)
                .putInt("nightPct", c.nightPct)
                .putInt("outsidePct", c.outsidePct)
                .putInt("combinedPct", c.combinedPct)
                .apply()
        }
    }
}
