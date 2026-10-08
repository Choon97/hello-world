package com.choon.presence

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 하루가 끝난 날의 재실 요약을 서버로 보낸다 (하루 1건, 같은 날짜는 서버에서 덮어쓰기).
 * 서비스의 5분 하트비트에 얹혀 호출되므로 별도 타이머/알람이 없다.
 */
object Uploader {
    private const val MAX_BACKFILL_DAYS = 14L
    private const val RETRY_DELAY_MILLIS = 30 * 60_000L

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    @Volatile private var nextAttemptAt = 0L

    fun isConfigured(store: PresenceStore) =
        store.serverUrl.startsWith("https://") && store.participantId.isNotBlank()

    /** [force] 이면 재시도 대기시간을 무시한다 ("지금 전송" 버튼). */
    fun maybeUpload(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val store = PresenceStore(app)
        if (!isConfigured(store)) return
        val now = System.currentTimeMillis()
        if (!force && now < nextAttemptAt) return
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try { run(app, store) } finally { running.set(false) }
        }
    }

    private fun run(context: Context, store: PresenceStore) {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val events = store.events()
        val firstDay = events.firstOrNull()?.let { SessionCalculator.today(it.timeMillis, zone) } ?: return
        val today = SessionCalculator.today(now, zone)
        val start = maxOf(store.uploadedThrough + 1, firstDay.toEpochDay(), today.toEpochDay() - MAX_BACKFILL_DAYS)
        if (start >= today.toEpochDay()) {
            if (store.lastUploadStatus.isEmpty()) status(store, now, "대기 중 (전송할 지난 날짜 없음)")
            return
        }
        val sessions = SessionCalculator.buildSessions(
            events, now,
            if (WifiMonitorService.running) now else store.lastAliveMillis + PresenceStore.ALIVE_GRACE_MILLIS,
            store.mergeGapMinutes * 60_000L,
        )
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        var sent = 0
        for (epochDay in start until today.toEpochDay()) {
            val date = LocalDate.ofEpochDay(epochDay)
            val body = PayloadBuilder.build(
                store.participantId, SessionCalculator.summarize(sessions, date, zone),
                zone, store.mergeGapMinutes, version,
            )
            val r = HttpUploader.postJson(store.serverUrl, store.serverToken, body)
            if (!r.ok) {
                Diag.event("UPLOAD_FAIL", "$date $r")
                nextAttemptAt = System.currentTimeMillis() + RETRY_DELAY_MILLIS
                status(store, now, "$r — $date 전송 중단" + if (r.permanent) " (설정 확인 필요)" else ", 30분 후 재시도")
                return
            }
            store.uploadedThrough = epochDay
            sent++
        }
        nextAttemptAt = 0L
        Diag.event("UPLOAD_OK", "days=$sent through=${LocalDate.ofEpochDay(store.uploadedThrough)}")
        status(store, now, "성공: ${sent}일치 전송 (마지막 ${LocalDate.ofEpochDay(store.uploadedThrough)})")
    }

    private fun status(store: PresenceStore, now: Long, msg: String) {
        val f = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
        store.lastUploadStatus = "${f.format(java.time.Instant.ofEpochMilli(now))} $msg"
    }
}
