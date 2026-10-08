package com.choon.presence

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.ZoneId

/** 매일 00:15 전후에 깨어나 어제 기록을 서버로 보낸다. 하루 한 번이라 배터리 영향은 거의 없다. */
class UploadAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        schedule(context)              // 다음날 알람을 먼저 예약해 둔다
        Uploader.maybeUpload(context)
    }

    companion object {
        /** 권한이 필요 없는 근사(inexact) 알람: 절전 모드에서는 몇 분 늦을 수 있다. */
        fun schedule(context: Context) {
            val app = context.applicationContext
            val at = UploadSchedule.nextRunMillis(System.currentTimeMillis(), ZoneId.systemDefault())
            val pi = PendingIntent.getBroadcast(
                app, 0, Intent(app, UploadAlarmReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            app.getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}
