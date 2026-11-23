package com.joke.taximeter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat

/** 폰 GPS로 이동 거리를 재서 [MeterEngine]에 넘긴다. */
object LocationTracker {
    private var manager: LocationManager? = null
    private var last: Location? = null

    var isRunning = false
        private set

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = handle(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun start(ctx: Context): Boolean {
        if (isRunning) return true
        if (!hasPermission(ctx)) return false
        val m = ctx.applicationContext.getSystemService(LocationManager::class.java) ?: return false
        return try {
            last = null
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            manager = m
            isRunning = true
            true
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    fun stop() {
        manager?.removeUpdates(listener)
        manager = null
        last = null
        isRunning = false
    }

    private fun handle(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > 35f) return
        val prev = last
        last = loc
        if (prev == null) return
        val d = prev.distanceTo(loc).toDouble()
        val dt = (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9
        if (dt <= 0) return
        val v = d / dt
        if (v > 70) return // 순간 튀는 위치는 버림 (시속 250km 이상)
        val speed = if (loc.hasSpeed()) loc.speed else v.toFloat()
        // 정차 중 GPS 흔들림은 거리로 안 침
        val counted = if (speed < 1.0f && d < 8) 0.0 else d
        MeterEngine.onLocationDelta(counted, speed)
    }
}
