package com.choon.presence

import android.content.Context
import java.io.File

/** 이벤트 로그(파일)와 설정(SharedPreferences) 저장소. */
class PresenceStore(context: Context) {
    private val appContext = context.applicationContext
    private val logFile = File(appContext.filesDir, "events.csv")
    private val prefs = appContext.getSharedPreferences("presence", Context.MODE_PRIVATE)

    var targetSsid: String
        get() = prefs.getString(KEY_SSID, DEFAULT_SSID) ?: DEFAULT_SSID
        set(v) = prefs.edit().putString(KEY_SSID, v.trim()).apply()

    var mergeGapMinutes: Int
        get() = prefs.getInt(KEY_GAP, DEFAULT_GAP_MIN)
        set(v) = prefs.edit().putInt(KEY_GAP, v.coerceIn(0, 120)).apply()

    var lastAliveMillis: Long
        get() = prefs.getLong(KEY_ALIVE, 0L)
        set(v) = prefs.edit().putLong(KEY_ALIVE, v).apply()

    /** 마지막 생존 기록이 [HEARTBEAT_MILLIS] 이상 지났을 때만 디스크에 쓴다. */
    fun touchAlive(now: Long = System.currentTimeMillis()) {
        if (now - lastAliveMillis >= HEARTBEAT_MILLIS) lastAliveMillis = now
    }

    /** 마지막으로 서비스가 SSID 를 읽을 수 없었다면 true (위치 권한 문제 안내용). */
    var ssidUnreadable: Boolean
        get() = prefs.getBoolean(KEY_UNREADABLE, false)
        set(v) = prefs.edit().putBoolean(KEY_UNREADABLE, v).apply()

    fun events(): List<PresenceEvent> = synchronized(LOCK) { readEvents() }

    private fun readEvents(): List<PresenceEvent> {
        if (!logFile.exists()) return emptyList()
        return logFile.readLines().mapNotNull { line ->
            val p = line.split(',')
            val t = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val type = when (p.getOrNull(1)) {
                "E" -> EventType.ENTER
                "X" -> EventType.EXIT
                else -> return@mapNotNull null
            }
            PresenceEvent(t, type)
        }
    }

    fun isHome(): Boolean = synchronized(LOCK) { lastType() } == EventType.ENTER

    /**
     * 현재 상태와 같은 이벤트는 기록하지 않는다.
     * 신호 세기 변화마다 호출되므로 마지막 상태를 메모리에 캐시해서 파일을 매번 읽지 않는다.
     */
    fun record(type: EventType, timeMillis: Long = System.currentTimeMillis()): Boolean = synchronized(LOCK) {
        val last = lastType()
        if (last == type || (last == null && type == EventType.EXIT)) return false
        logFile.appendText("$timeMillis,${if (type == EventType.ENTER) "E" else "X"}\n")
        cachedLast = type
        true
    }

    fun clear() = synchronized(LOCK) {
        logFile.delete()
        cachedLast = null
        cacheLoaded = true
    }

    private fun lastType(): EventType? {
        if (!cacheLoaded) {
            cachedLast = readEvents().lastOrNull()?.type
            cacheLoaded = true
        }
        return cachedLast
    }

    companion object {
        const val DEFAULT_SSID = "skyiptime5g0651"
        const val DEFAULT_GAP_MIN = 10
        private const val KEY_SSID = "ssid"
        private const val KEY_GAP = "gap"
        private const val KEY_ALIVE = "alive"
        private const val KEY_UNREADABLE = "unreadable"
        const val HEARTBEAT_MILLIS = 5 * 60_000L
        /** 하트비트 2번 + 여유 */
        const val ALIVE_GRACE_MILLIS = 12 * 60_000L
        private val LOCK = Any()
        private var cachedLast: EventType? = null
        private var cacheLoaded = false
    }
}
