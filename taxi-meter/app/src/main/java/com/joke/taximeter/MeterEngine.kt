package com.joke.taximeter

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Calendar

/**
 * 미터기 계산 엔진. 폰 화면과 차 화면이 같은 상태를 본다. 모든 호출은 메인 스레드에서.
 *
 * 요금 방식(거리·시간 병산):
 *  - 시속 [FareConfig.slowSpeedKmh] 초과: 실제 달린 거리가 쌓인다.
 *  - 그 이하(정차·서행): 시간 [timeUnitSec]초가 거리 [distanceUnitM]m로 환산되어 쌓인다.
 *  - 쌓인 거리가 기본거리를 넘으면 단위 거리마다 [unitFare]원이 붙는다.
 *  - 할증은 요금이 오르는 그 순간의 할증률로 붙는다(중간에 할증을 켜면 그 뒤부터 적용).
 */
object MeterEngine {

    enum class State { VACANT, RUNNING, PAID }

    data class Snapshot(
        val state: State,
        val fareWon: Long,
        val distanceM: Double,
        val driveSec: Long,
        val waitSec: Long,
        val outside: Boolean,
        val surchargePct: Int,
        val progress: Float,
        val speedKmh: Float,
        val gpsActive: Boolean,
        val gpsFresh: Boolean,
        val startedAtWall: Long,
        val endedAtWall: Long,
        val config: FareConfig,
    )

    fun interface Listener {
        /** [structural]: 상태·버튼이 바뀌었을 때 true, 매초 갱신은 false. */
        fun onMeterChanged(s: Snapshot, structural: Boolean)
    }

    private val listeners = LinkedHashSet<Listener>()
    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    var config = FareConfig()
        private set

    private var state = State.VACANT
    private var fare = 0.0
    private var equivalentM = 0.0
    private var distanceM = 0.0
    private var driveMs = 0L
    private var waitMs = 0L
    private var units = 0
    private var outside = false
    private var startedAtWall = 0L
    private var endedAtWall = 0L
    private var lastTick = 0L
    private var pendingM = 0.0
    private var gpsSpeedMps = -1f
    private var lastFixAt = 0L
    private var speedKmh = 0f

    private val tick = object : Runnable {
        override fun run() {
            step(true)
            if (state == State.RUNNING) handler.postDelayed(this, 1000L)
        }
    }

    fun init(ctx: Context) {
        if (appContext == null) {
            appContext = ctx.applicationContext
            config = FareConfig.load(ctx.applicationContext)
        }
    }

    fun updateConfig(ctx: Context, c: FareConfig) {
        init(ctx)
        config = c
        FareConfig.save(ctx.applicationContext, c)
        notifyAll(true)
    }

    fun addListener(l: Listener) {
        listeners += l
        l.onMeterChanged(snapshot(), true)
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    fun start(ctx: Context) {
        init(ctx)
        if (state == State.RUNNING) return
        resetCounters()
        state = State.RUNNING
        startedAtWall = System.currentTimeMillis()
        fare = config.baseFare * (1 + currentPct() / 100.0)
        lastTick = SystemClock.elapsedRealtime()
        LocationTracker.start(ctx)
        MeterService.start(ctx)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000L)
        notifyAll(true)
    }

    fun finish(ctx: Context) {
        if (state != State.RUNNING) return
        step(false)
        handler.removeCallbacks(tick)
        state = State.PAID
        endedAtWall = System.currentTimeMillis()
        LocationTracker.stop()
        MeterService.stop(ctx)
        notifyAll(true)
    }

    fun clear(ctx: Context) {
        if (state == State.RUNNING) {
            handler.removeCallbacks(tick)
            LocationTracker.stop()
            MeterService.stop(ctx)
        }
        resetCounters()
        state = State.VACANT
        notifyAll(true)
    }

    fun toggleOutside() {
        outside = !outside
        notifyAll(true)
    }

    /** GPS에서 받은 이동 거리. */
    fun onLocationDelta(meters: Double, speedMps: Float) {
        if (state != State.RUNNING) return
        pendingM += meters
        gpsSpeedMps = speedMps
        lastFixAt = SystemClock.elapsedRealtime()
    }

    fun snapshot(): Snapshot {
        val pct = currentPct()
        val shownFare = when (state) {
            State.VACANT -> config.baseFare * (1 + pct / 100.0)
            else -> fare
        }
        val c = config
        val progress = if (state == State.VACANT) 0.0
        else if (equivalentM < c.baseDistanceM) equivalentM / c.baseDistanceM
        else ((equivalentM - c.baseDistanceM) % c.distanceUnitM) / c.distanceUnitM
        val now = SystemClock.elapsedRealtime()
        return Snapshot(
            state = state,
            fareWon = (shownFare / 10).toLong() * 10,
            distanceM = distanceM,
            driveSec = driveMs / 1000,
            waitSec = waitMs / 1000,
            outside = outside,
            surchargePct = pct,
            progress = progress.toFloat().coerceIn(0f, 1f),
            speedKmh = speedKmh,
            gpsActive = LocationTracker.isRunning,
            gpsFresh = lastFixAt != 0L && now - lastFixAt < 3000,
            startedAtWall = startedAtWall,
            endedAtWall = endedAtWall,
            config = c,
        )
    }

    fun currentPct(): Int {
        val night = isNight()
        return when {
            night && outside -> config.combinedPct
            night -> config.nightPct
            outside -> config.outsidePct
            else -> 0
        }
    }

    private fun isNight(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val s = config.nightStartHour
        val e = config.nightEndHour
        return when {
            s == e -> false
            s < e -> h in s until e
            else -> h >= s || h < e
        }
    }

    private fun step(notify: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val dt = (now - lastTick).coerceAtLeast(0L)
        lastTick = now
        val dd = pendingM
        pendingM = 0.0

        // 위치 권한을 나중에 허용했으면 그때부터 GPS 시작
        if (!LocationTracker.isRunning) appContext?.let { LocationTracker.start(it) }

        val fresh = lastFixAt != 0L && now - lastFixAt < 3000
        speedKmh = when {
            fresh && gpsSpeedMps >= 0f -> gpsSpeedMps * 3.6f
            dt > 0 -> (dd / (dt / 1000.0) * 3.6).toFloat()
            else -> 0f
        }

        val c = config
        distanceM += dd
        driveMs += dt
        if (speedKmh <= c.slowSpeedKmh) {
            waitMs += dt
            equivalentM += dt / 1000.0 * c.distanceUnitM / c.timeUnitSec
        } else {
            equivalentM += dd
        }

        val mult = 1 + currentPct() / 100.0
        while (equivalentM >= c.baseDistanceM + (units + 1).toDouble() * c.distanceUnitM) {
            units++
            fare += c.unitFare * mult
        }
        if (notify) notifyAll(false)
    }

    private fun resetCounters() {
        fare = 0.0
        equivalentM = 0.0
        distanceM = 0.0
        driveMs = 0L
        waitMs = 0L
        units = 0
        startedAtWall = 0L
        endedAtWall = 0L
        pendingM = 0.0
        gpsSpeedMps = -1f
        lastFixAt = 0L
        speedKmh = 0f
    }

    private fun notifyAll(structural: Boolean) {
        val s = snapshot()
        for (l in listeners.toList()) l.onMeterChanged(s, structural)
    }
}
