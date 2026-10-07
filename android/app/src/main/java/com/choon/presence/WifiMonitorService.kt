package com.choon.presence

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * 와이파이 연결 상태를 감시해서 지정한 SSID 에 붙고/떨어질 때 이벤트를 기록하는 포그라운드 서비스.
 */
class WifiMonitorService : Service() {

    private lateinit var store: PresenceStore
    private lateinit var cm: ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var target: String   // 신호 변화마다 prefs 를 읽지 않도록 캐시

    private val heartbeat = object : Runnable {
        override fun run() {
            store.touchAlive()
            Diag.count(Diag.C.BEATS)
            Diag.flush(this@WifiMonitorService)
            handler.postDelayed(this, PresenceStore.HEARTBEAT_MILLIS)
        }
    }

    private var unreadable: Boolean? = null

    private fun setUnreadable(v: Boolean) {
        if (unreadable != v) { unreadable = v; store.ssidUnreadable = v }
    }

    // FLAG_INCLUDE_LOCATION_INFO: Android 12+ 에서 콜백으로 SSID 를 받으려면 필요
    private val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            Diag.count(Diag.C.CAPS)
            val info = caps.transportInfo as? WifiInfo ?: return
            val ssid = info.ssid?.trim('"')
            if (ssid == null || ssid == WifiManager_UNKNOWN_SSID || ssid.isEmpty()) {
                Diag.count(Diag.C.UNREADABLE)
                setUnreadable(true)   // 권한 부족 등: 상태는 건드리지 않는다
                return
            }
            setUnreadable(false)
            store.touchAlive()
            val changed = if (ssid == target) store.record(EventType.ENTER) else store.record(EventType.EXIT)
            if (changed) Diag.count(Diag.C.CHANGES)
        }

        override fun onLost(network: Network) {
            Diag.count(Diag.C.LOST)
            if (store.record(EventType.EXIT)) Diag.count(Diag.C.CHANGES)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = PresenceStore(this)
        Diag.init(this)
        Diag.logProcessExits(this)
        Diag.event("SERVICE_CREATE", "isHome=${store.isHome()}")
        target = store.targetSsid
        running = true
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        startForegroundCompat()
        // 서비스가 죽어 있던 동안 열려 있던 세션은 마지막 생존 시각에서 닫는다
        val last = store.lastAliveMillis
        if (store.isHome() && last > 0 &&
            System.currentTimeMillis() - last > PresenceStore.ALIVE_GRACE_MILLIS
        ) {
            store.record(EventType.EXIT, last)
        }
        handler.post(heartbeat)
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            callback,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        target = store.targetSsid   // 설정 저장 후 start() 가 다시 불리면 새 SSID 반영
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Diag.event("SERVICE_DESTROY")
        Diag.flush(this)
        handler.removeCallbacks(heartbeat)
        runCatching { cm.unregisterNetworkCallback(callback) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "재실 감지", NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("재실 시간 기록 중")
            .setContentText("지정한 와이파이 연결을 감시하고 있어요")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    companion object {
        /** 서비스와 화면은 같은 프로세스이므로 이 값으로 서비스 생존 여부를 정확히 알 수 있다. */
        @Volatile var running = false
            private set

        private const val CHANNEL_ID = "presence"
        private const val WifiManager_UNKNOWN_SSID = "<unknown ssid>"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WifiMonitorService::class.java))
        }
    }
}
