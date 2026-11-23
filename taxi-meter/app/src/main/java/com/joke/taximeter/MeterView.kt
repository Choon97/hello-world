package com.joke.taximeter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View

/** 폰 화면용 미터기. */
class MeterView(ctx: Context) : View(ctx) {
    private val renderer = MeterRenderer()
    private var snap: MeterEngine.Snapshot? = null
    private val area = Rect()
    private val listener = MeterEngine.Listener { s, _ ->
        snap = s
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        MeterEngine.addListener(listener)
    }

    override fun onDetachedFromWindow() {
        MeterEngine.removeListener(listener)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val s = snap ?: return
        area.set(0, 0, width, height)
        renderer.draw(canvas, area, s)
    }
}
