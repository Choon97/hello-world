package com.choon.presence

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 배터리 문제를 폰에서 직접 측정하기 위한 가벼운 진단 로그.
 *
 * - 시간 단위로 "콜백 몇 번 / 실제 상태변경 몇 번 / 하트비트 몇 번 / 배터리 몇 %" 를 집계한다.
 * - 집계는 메모리에서만 하고, 디스크에는 하트비트(5분)마다 한 줄 + 시간이 바뀔 때 파일에 추가한다.
 * - 서비스 시작/종료, 시스템이 프로세스를 종료한 이유(ApplicationExitInfo)를 이벤트로 남긴다.
 */
object Diag {
    enum class C { CAPS, CHANGES, LOST, BEATS, UNREADABLE }

    private val LOCK = Any()
    private const val HOUR = 3_600_000L
    private const val PREF = "diag"
    private lateinit var file: File
    private lateinit var prefs: android.content.SharedPreferences

    private var hour = 0L
    private val n = LongArray(C.values().size)
    private var batFirst = -1
    private var batLast = -1
    private var charging = false

    fun init(context: Context) = synchronized(LOCK) {
        if (this::file.isInitialized) return
        val app = context.applicationContext
        file = File(app.filesDir, "diag.log")
        prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        // 이전 프로세스가 죽기 전에 마지막으로 저장해 둔 집계를 파일로 옮긴다
        prefs.getString("cur", null)?.let { append(it) }
        prefs.edit().remove("cur").apply()
        trim()
        hour = hourOf(System.currentTimeMillis())
        sampleBattery(app)
    }

    fun count(c: C, now: Long = System.currentTimeMillis()) = synchronized(LOCK) {
        if (!this::file.isInitialized) return
        rollIfNeeded(now)
        n[c.ordinal]++
        Unit
    }

    fun event(name: String, detail: String = "", now: Long = System.currentTimeMillis()) = synchronized(LOCK) {
        if (!this::file.isInitialized) return
        append("V,$now,$name,${detail.replace(',', ';').replace('\n', ' ')}")
    }

    /** 하트비트마다 호출: 현재 집계를 prefs 에 저장(프로세스가 죽어도 최대 5분치만 손실). */
    fun flush(context: Context, now: Long = System.currentTimeMillis()) = synchronized(LOCK) {
        if (!this::file.isInitialized) return
        rollIfNeeded(now)
        sampleBattery(context.applicationContext)
        prefs.edit().putString("cur", bucketLine()).apply()
    }

    /** 서비스 시작 시: 이전에 시스템이 우리 프로세스를 종료한 이유를 기록. */
    fun logProcessExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val seen = synchronized(LOCK) { prefs.getLong("exit_ts", 0L) }
            var newest = seen
            am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
                .filter { it.timestamp > seen }
                .sortedBy { it.timestamp }
                .forEach {
                    event("PROC_EXIT", "reason=${reasonName(it.reason)} desc=${it.description}", it.timestamp)
                    newest = maxOf(newest, it.timestamp)
                }
            synchronized(LOCK) { prefs.edit().putLong("exit_ts", newest).apply() }
        }
    }

    fun report(context: Context, store: PresenceStore): String = synchronized(LOCK) {
        val app = context.applicationContext
        if (this::file.isInitialized) {
            rollIfNeeded(System.currentTimeMillis()); sampleBattery(app)
        }
        val z = ZoneId.systemDefault()
        val f = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(z)
        fun t(ms: Long) = f.format(Instant.ofEpochMilli(ms))
        val pm = app.getSystemService(PowerManager::class.java)
        val bucket = runCatching {
            when (app.getSystemService(UsageStatsManager::class.java).appStandbyBucket) {
                10 -> "ACTIVE"; 20 -> "WORKING_SET"; 30 -> "FREQUENT"; 40 -> "RARE"; 45 -> "RESTRICTED"; else -> "?"
            }
        }.getOrDefault("?")
        val sb = StringBuilder()
        sb.appendLine("== 기기 ==")
        sb.appendLine("${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("앱 버전 ${BuildConfig.VERSION_NAME}, 서비스 실행중=${WifiMonitorService.running}")
        sb.appendLine("배터리 최적화 제외=${pm.isIgnoringBatteryOptimizations(app.packageName)}, 앱 대기 버킷=$bucket")
        sb.appendLine("SSID 읽기 실패 상태=${store.ssidUnreadable}, 설정 SSID=${store.targetSsid}")
        sb.appendLine("리포트 생성 ${t(System.currentTimeMillis())}")
        sb.appendLine()
        sb.appendLine("== 시간별 진단 (caps=신호변화 콜백, changes=실제 ENTER/EXIT 기록, lost, beats=하트비트, bat=배터리%) ==")
        sb.appendLine("시각, caps, changes, lost, beats, unreadable, bat시작, bat끝, 충전중")
        val lines = (if (this::file.isInitialized && file.exists()) file.readLines() else emptyList()) +
            (if (this::file.isInitialized) listOf(bucketLine()) else emptyList())
        lines.filter { it.startsWith("B,") }.takeLast(72).forEach { l ->
            val p = l.split(',')
            if (p.size >= 10) sb.appendLine("${t(p[1].toLong())}, ${p[2]}, ${p[3]}, ${p[4]}, ${p[5]}, ${p[6]}, ${p[7]}, ${p[8]}, ${p[9]}")
        }
        sb.appendLine()
        sb.appendLine("== 서비스/프로세스 이벤트 ==")
        lines.filter { it.startsWith("V,") }.takeLast(60).forEach { l ->
            val p = l.split(',', limit = 4)
            if (p.size >= 4) sb.appendLine("${t(p[1].toLong())} ${p[2]} ${p[3]}")
        }
        sb.toString()
    }

    private fun reasonName(r: Int) = when (r) {
        1 -> "EXIT_SELF"; 2 -> "SIGNALED"; 3 -> "LOW_MEMORY"; 4 -> "CRASH"; 5 -> "CRASH_NATIVE"; 6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"; 8 -> "PERMISSION_CHANGE"; 9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"; 11 -> "USER_STOPPED"; 12 -> "DEPENDENCY_DIED"; 13 -> "OTHER"; 14 -> "FREEZER"
        else -> "UNKNOWN($r)"
    }

    private fun hourOf(ms: Long) = ms - ms % HOUR

    private fun rollIfNeeded(now: Long) {
        val h = hourOf(now)
        if (h != hour) {
            append(bucketLine())
            hour = h
            n.fill(0); batFirst = -1
        }
    }

    private fun bucketLine() =
        "B,$hour,${n[0]},${n[1]},${n[2]},${n[3]},${n[4]},$batFirst,$batLast,$charging"

    private fun sampleBattery(context: Context) {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0) return
        batLast = level * 100 / scale
        if (batFirst < 0) batFirst = batLast
        charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun append(line: String) {
        runCatching { file.appendText(line + "\n") }
    }

    /** 진단 로그도 최근 7일만 보관한다. */
    private fun trim() {
        runCatching {
            if (!file.exists()) return
            val cutoff = System.currentTimeMillis() - 7 * 24 * HOUR
            val lines = file.readLines()
            val kept = lines.filter { l -> l.split(',').getOrNull(1)?.toLongOrNull()?.let { it >= cutoff } ?: false }
            if (kept.size != lines.size || file.length() > 200_000) {
                file.writeText(kept.takeLast(1000).joinToString("\n") + if (kept.isEmpty()) "" else "\n")
            }
        }
    }
}
