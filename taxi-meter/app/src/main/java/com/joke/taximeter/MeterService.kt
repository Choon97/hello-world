package com.joke.taximeter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** 운행 중 화면이 꺼지거나 앱이 뒤로 가도 GPS가 계속 돌도록 붙잡아 두는 포그라운드 서비스. */
class MeterService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "미터기 운행", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_meter)
            .setContentTitle("택시 미터기 운행 중")
            .setContentText("GPS로 주행 거리를 재고 있어요")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, 1, n, type)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    companion object {
        private const val TAG = "MeterService"
        private const val CHANNEL = "meter"

        fun start(ctx: Context) {
            if (!LocationTracker.hasPermission(ctx)) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, MeterService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "cannot start service", e)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MeterService::class.java))
        }
    }
}
