package com.joke.taximeter.car

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.joke.taximeter.LocationTracker
import com.joke.taximeter.MeterEngine
import com.joke.taximeter.MeterRenderer

/**
 * 차 화면. '내비게이션' 종류 앱은 지도 자리(Surface)에 직접 그릴 수 있어서
 * 거기에 미터기를 그리고, 버튼은 템플릿의 액션 스트립에 둔다.
 * (내비 액션 스트립은 글자 버튼이 1개만 허용돼서 시외할증 버튼은 글자를 그린 아이콘으로 만든다.)
 */
class MeterScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback, DefaultLifecycleObserver {

    private val renderer = MeterRenderer()
    private var container: SurfaceContainer? = null
    private var visibleArea: Rect? = null
    private var snap: MeterEngine.Snapshot = run {
        MeterEngine.init(carContext)
        MeterEngine.snapshot()
    }

    private val listener = MeterEngine.Listener { s, structural ->
        snap = s
        drawFrame()
        if (structural) invalidate()
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        MeterEngine.addListener(listener)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        MeterEngine.removeListener(listener)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
    }

    override fun onGetTemplate(): Template {
        val main = when (snap.state) {
            MeterEngine.State.VACANT -> Action.Builder()
                .setTitle("운행 시작")
                .setOnClickListener { startMeter() }
                .build()
            MeterEngine.State.RUNNING -> Action.Builder()
                .setTitle("운행 종료")
                .setOnClickListener { MeterEngine.finish(carContext) }
                .build()
            MeterEngine.State.PAID -> Action.Builder()
                .setTitle("빈차")
                .setOnClickListener { MeterEngine.clear(carContext) }
                .build()
        }
        val outside = Action.Builder()
            .setIcon(textIcon(if (snap.outside) "시외\nON" else "시외\nOFF", snap.outside))
            .setOnClickListener { MeterEngine.toggleOutside() }
            .build()
        val strip = ActionStrip.Builder()
            .addAction(main)
            .addAction(outside)
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .build()
    }

    private fun startMeter() {
        if (LocationTracker.hasPermission(carContext)) {
            MeterEngine.start(carContext)
            return
        }
        CarToast.makeText(carContext, "폰에서 위치 권한을 허용해 주세요", CarToast.LENGTH_LONG).show()
        carContext.requestPermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION)) { _, _ ->
            // 거부해도 시간요금으로는 돌아가게 시작한다.
            MeterEngine.start(carContext)
        }
    }

    // ---- Surface ----

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        container = surfaceContainer
        drawFrame()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = visibleArea
        drawFrame()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        if (visibleArea == null) visibleArea = stableArea
        drawFrame()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        container = null
    }

    private fun drawFrame() {
        val c = container ?: return
        val surface = c.surface ?: return
        if (!surface.isValid) return
        val canvas = try {
            surface.lockCanvas(null)
        } catch (e: Exception) {
            return
        } ?: return
        try {
            val area = visibleArea?.takeIf { !it.isEmpty } ?: Rect(0, 0, c.width, c.height)
            renderer.draw(canvas, area, snap)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun textIcon(label: String, on: Boolean): CarIcon {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = if (on) Color.parseColor("#F08A00") else Color.parseColor("#3A4452")
        cv.drawRoundRect(RectF(4f, 4f, size - 4f, size - 4f), 24f, 24f, p)
        p.color = Color.WHITE
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        p.textSize = 38f
        val lines = label.split("\n")
        val lineH = 44f
        var y = size / 2f - (lines.size - 1) * lineH / 2f + 13f
        for (line in lines) {
            cv.drawText(line, size / 2f, y, p)
            y += lineH
        }
        return CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
    }
}
