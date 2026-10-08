package com.choon.presence

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 재실 요약을 서버(구글 시트)로 보낸다. 같은 (날짜, 참여자)는 서버에서 덮어쓴다.
 * - 자동: 매일 00:15 전후 + 5분 하트비트가 따라잡기. 어제까지 확정값만.
 * - 수동("지금 전송" 버튼): 최근 30일 + 오늘(진행 중, partial)까지 바로 올린다. 같은 시트/같은 형식.
 */
object Uploader {
    private const val RETRY_DELAY_MILLIS = 30 * 60_000L

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    @Volatile private var nextAttemptAt = 0L

    fun isConfigured(store: PresenceStore) =
        store.serverUrl.startsWith("https://") && store.participantId.isNotBlank()

    /** [manual] 이면 재시도 대기시간을 무시하고 오늘(진행 중)까지 보낸다 ("지금 전송" 버튼). */
    fun maybeUpload(context: Context, manual: Boolean = false) {
        val app = context.applicationContext
        val store = PresenceStore(app)
        if (!isConfigured(store)) return
        val now = System.currentTimeMillis()
        if (!manual && now < nextAttemptAt) return
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try { run(app, store, manual) } finally { running.set(false) }
        }
    }

    private fun run(context: Context, store: PresenceStore, manual: Boolean) {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val events = store.events()
        val firstDay = events.firstOrNull()?.let { SessionCalculator.today(it.timeMillis, zone) }
        if (firstDay == null) {
            if (manual) status(store, now, "보낼 기록이 아직 없어요")
            return
        }
        // 자동은 매일 00:15 이후에만 어제까지 (자정을 걸친 세션/짧은 끊김이 정리된 뒤)
        val plan = UploadSchedule.plan(firstDay, store.uploadedThrough, now, zone, manual)
        if (plan.isEmpty()) {
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
        for (day in plan) {
            val date = day.date
            val body = PayloadBuilder.build(
                store.participantId, SessionCalculator.summarize(sessions, date, zone),
                zone, store.mergeGapMinutes, version, partial = day.partial,
            )
            val r = HttpUploader.postJson(store.serverUrl, store.serverToken, body)
            if (!r.ok) {
                Diag.event("UPLOAD_FAIL", "$date $r")
                nextAttemptAt = System.currentTimeMillis() + RETRY_DELAY_MILLIS
                status(store, now, "$r — $date 전송 중단" + if (r.permanent) " (설정 확인 필요)" else ", 30분 후 재시도")
                return
            }
            // 확정된 날만 "전송 완료"로 기록한다 (진행 중인 날은 다음에 확정값으로 다시 보낸다)
            if (!day.partial) store.uploadedThrough = maxOf(store.uploadedThrough, date.toEpochDay())
            sent++
        }
        nextAttemptAt = 0L
        val partialNote = if (plan.any { it.partial }) ", 오늘은 중간값" else ""
        Diag.event("UPLOAD_OK", "manual=$manual days=$sent")
        status(store, now, (if (manual) "수동 " else "") + "성공: ${sent}일치 전송$partialNote")
    }

    private fun status(store: PresenceStore, now: Long, msg: String) {
        val f = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
        store.lastUploadStatus = "${f.format(java.time.Instant.ofEpochMilli(now))} $msg"
    }
}
