package com.joke.taximeter

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.sin

/** 미터기 화면을 Canvas에 그린다. 차 화면(Surface)과 폰 화면(View)이 같이 쓴다. */
class MeterRenderer {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val digitFace: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val boldFace: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val normalFace: Typeface = Typeface.DEFAULT
    private val clock = SimpleDateFormat("HH:mm", Locale.KOREA)

    fun draw(c: Canvas, area: Rect, s: MeterEngine.Snapshot) {
        c.drawColor(BG)
        val outer = RectF(area)
        val pad = min(outer.width(), outer.height()) * 0.025f
        outer.inset(pad, pad)
        if (outer.width() <= 0 || outer.height() <= 0) return

        val receipt = s.state == MeterEngine.State.PAID
        val wide = outer.width() >= outer.height() * 1.2f
        val gap = pad
        val main: RectF
        var receiptRect: RectF? = null
        if (receipt && wide) {
            val rw = outer.width() * 0.30f
            main = RectF(outer.left, outer.top, outer.right - rw - gap, outer.bottom)
            receiptRect = RectF(main.right + gap, outer.top, outer.right, outer.bottom)
        } else if (receipt) {
            val rh = outer.height() * 0.42f
            main = RectF(outer.left, outer.top, outer.right, outer.bottom - rh - gap)
            receiptRect = RectF(outer.left, main.bottom + gap, outer.right, outer.bottom)
        } else {
            main = outer
        }

        drawMeter(c, main, s)
        receiptRect?.let { drawReceipt(c, it, s) }
    }

    private fun drawMeter(c: Canvas, r: RectF, s: MeterEngine.Snapshot) {
        val u = min(r.width() / 900f, r.height() / 500f).coerceAtLeast(0.25f)
        fillRound(c, r, 18 * u, PANEL)
        strokeRound(c, r, 18 * u, LINE, 2 * u)

        val left = r.left + 20 * u
        val right = r.right - 20 * u
        var y = r.top + 18 * u

        // 머리글
        text(c, "KOREA DIGITAL TAXIMETER", left, y + 16 * u, 16 * u, MUTED, boldFace, Paint.Align.LEFT)
        text(c, "${s.config.regionName} · ${clock.format(Date())}", right, y + 16 * u, 16 * u, MUTED, boldFace, Paint.Align.RIGHT)
        y += 30 * u

        // 상태 표시등
        val chips = listOf(
            Triple("빈차", s.state == MeterEngine.State.VACANT, RED),
            Triple("주행", s.state == MeterEngine.State.RUNNING, GREEN),
            Triple("할증", s.surchargePct > 0, ORANGE),
            Triple("시외", s.outside, ORANGE),
            Triple("지불", s.state == MeterEngine.State.PAID, BLUE),
        )
        val chipGap = 8 * u
        val chipH = 36 * u
        val chipW = (right - left - chipGap * (chips.size - 1)) / chips.size
        chips.forEachIndexed { i, (label, on, color) ->
            val x = left + i * (chipW + chipGap)
            val cr = RectF(x, y, x + chipW, y + chipH)
            fillRound(c, cr, 8 * u, if (on) color else CHIP_OFF)
            textCentered(c, label, cr, 18 * u, if (on) Color.WHITE else MUTED, boldFace)
        }
        y += chipH + 14 * u

        // 요금 창
        val statsH = 78 * u
        val footerH = 52 * u
        val box = RectF(left, y, right, r.bottom - statsH - footerH - 16 * u)
        if (box.height() > 20 * u) drawFareBox(c, box, s, u)
        y = box.bottom + 12 * u

        // 주행거리 / 주행시간 / 대기시간
        val cols = listOf(
            "주행거리(km)" to "%.2f".format(s.distanceM / 1000.0),
            "주행시간" to hms(s.driveSec),
            "대기시간" to hms(s.waitSec),
        )
        val colW = (right - left) / cols.size
        cols.forEachIndexed { i, (label, value) ->
            val cx = left + colW * i + colW / 2
            text(c, label, cx, y + 18 * u, 15 * u, MUTED, normalFace, Paint.Align.CENTER)
            text(c, value, cx, y + 58 * u, 36 * u, DIGIT, digitFace, Paint.Align.CENTER)
        }
        y += statsH

        // 요금표와 GPS 상태
        text(c, s.config.summary(), left, y + 16 * u, 13 * u, MUTED, normalFace, Paint.Align.LEFT, right - left)
        val gps = when {
            s.state != MeterEngine.State.RUNNING -> "장난용 미터기 · 실제 요금 아님"
            !s.gpsActive -> "⚠ 위치 권한이 없어 시간요금만 올라가요 (폰에서 허용)"
            !s.gpsFresh -> "GPS 신호 찾는 중… (정차로 계산)"
            else -> "GPS 수신 중 · %.0f km/h".format(s.speedKmh)
        }
        val gpsColor = if (s.state == MeterEngine.State.RUNNING && !s.gpsActive) ORANGE else MUTED
        text(c, gps, left, y + 38 * u, 13 * u, gpsColor, normalFace, Paint.Align.LEFT, right - left)
    }

    private fun drawFareBox(c: Canvas, box: RectF, s: MeterEngine.Snapshot, u: Float) {
        fillRound(c, box, 12 * u, FARE_BG)
        strokeRound(c, box, 12 * u, LINE, 2 * u)

        text(c, "요금", box.left + 16 * u, box.top + 26 * u, 16 * u, MUTED, boldFace, Paint.Align.LEFT)
        if (s.surchargePct > 0) {
            text(c, "할증 ${s.surchargePct}%", box.right - 16 * u, box.top + 26 * u, 16 * u, ORANGE, boldFace, Paint.Align.RIGHT)
        }

        val h = box.height()
        // 달리는 말
        val horseSize = min(h * 0.40f, box.width() * 0.16f)
        val running = s.state == MeterEngine.State.RUNNING
        val bob = if (running) sin(System.currentTimeMillis() / 160.0).toFloat() * horseSize * 0.06f else 0f
        val horseX = box.left + 16 * u
        val centerY = box.top + h * 0.55f
        p.reset(); p.isAntiAlias = true
        p.textSize = horseSize
        p.textAlign = Paint.Align.LEFT
        p.alpha = if (running) 255 else 110
        c.drawText("🐎", horseX, centerY + horseSize * 0.35f + bob, p)
        val horseW = p.measureText("🐎")
        p.alpha = 255

        // 숫자 (뒤에 흐린 8888 깔아서 세그먼트 느낌)
        val wonSize = h * 0.20f
        p.typeface = boldFace; p.textSize = wonSize
        val wonW = p.measureText("원")
        val digitsRight = box.right - 20 * u - wonW - 8 * u
        val digitsLeft = horseX + horseW + 16 * u
        var size = h * 0.62f
        p.typeface = digitFace; p.textSize = size
        val ghost = "888888"
        val maxW = digitsRight - digitsLeft
        val gw = p.measureText(ghost)
        if (gw > maxW && maxW > 0) size *= maxW / gw
        val baseline = centerY + size * 0.36f
        text(c, ghost, digitsRight, baseline, size, GHOST, digitFace, Paint.Align.RIGHT)
        text(c, s.fareWon.toString(), digitsRight, baseline, size, DIGIT, digitFace, Paint.Align.RIGHT)
        text(c, "원", box.right - 20 * u, baseline, wonSize, DIGIT, boldFace, Paint.Align.RIGHT)

        // 다음 요금까지 진행 막대
        val barH = 6 * u
        val barTop = box.bottom - 16 * u - barH
        val bar = RectF(digitsLeft, barTop, digitsRight, barTop + barH)
        fillRound(c, bar, barH / 2, CHIP_OFF)
        if (s.progress > 0f) {
            fillRound(c, RectF(bar.left, bar.top, bar.left + bar.width() * s.progress, bar.bottom), barH / 2, DIGIT)
        }
    }

    private fun drawReceipt(c: Canvas, r: RectF, s: MeterEngine.Snapshot) {
        val u = min(r.width() / 280f, r.height() / 440f).coerceAtLeast(0.25f)
        fillRound(c, r, 10 * u, Color.WHITE)
        val left = r.left + 16 * u
        val right = r.right - 16 * u
        var y = r.top + 34 * u
        text(c, "영 수 증", (r.left + r.right) / 2, y, 22 * u, INK, boldFace, Paint.Align.CENTER)
        y += 14 * u
        dashed(c, left, right, y, u)
        y += 8 * u

        val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
        val rows = listOf(
            "지역" to s.config.regionName,
            "승차" to if (s.startedAtWall > 0) fmt.format(Date(s.startedAtWall)) else "-",
            "하차" to if (s.endedAtWall > 0) fmt.format(Date(s.endedAtWall)) else "-",
            "주행거리" to "%.2f km".format(s.distanceM / 1000.0),
            "주행시간" to hms(s.driveSec),
            "대기시간" to hms(s.waitSec),
            "시외할증" to if (s.outside) "적용" else "-",
        )
        val rowH = 24 * u
        for ((k, v) in rows) {
            y += rowH
            text(c, k, left, y, 15 * u, INK_MUTED, normalFace, Paint.Align.LEFT)
            text(c, v, right, y, 15 * u, INK, normalFace, Paint.Align.RIGHT)
        }
        y += 14 * u
        dashed(c, left, right, y, u)
        y += 32 * u
        text(c, "합계", left, y, 18 * u, INK, boldFace, Paint.Align.LEFT)
        text(c, "%,d원".format(s.fareWon), right, y, 22 * u, RED, boldFace, Paint.Align.RIGHT)

        // 바코드 (모양만)
        val bcTop = y + 18 * u
        val bcBottom = (r.bottom - 34 * u).coerceAtLeast(bcTop)
        if (bcBottom - bcTop > 10 * u) {
            p.reset(); p.color = INK
            var x = left
            var seed = s.fareWon * 31 + s.startedAtWall
            while (x < right) {
                seed = seed * 1103515245 + 12345
                val w = (1 + ((seed ushr 16) and 3)) * 1.5f * u
                c.drawRect(x, bcTop, (x + w).coerceAtMost(right), bcBottom, p)
                x += w + (1 + ((seed ushr 20) and 1)) * 2f * u
            }
        }
        text(c, "※ 장난용 · 실제 요금 청구 아님", (r.left + r.right) / 2, r.bottom - 14 * u, 12 * u, INK_MUTED, normalFace, Paint.Align.CENTER)
    }

    // ---- 그리기 도우미 ----

    private fun text(
        c: Canvas, s: String, x: Float, baseline: Float, size: Float, color: Int,
        face: Typeface, align: Paint.Align, maxWidth: Float = 0f,
    ) {
        p.reset(); p.isAntiAlias = true
        p.typeface = face; p.textSize = size; p.color = color; p.textAlign = align
        if (maxWidth > 0f) {
            val w = p.measureText(s)
            if (w > maxWidth) p.textSize = size * maxWidth / w
        }
        c.drawText(s, x, baseline, p)
    }

    private fun textCentered(c: Canvas, s: String, r: RectF, size: Float, color: Int, face: Typeface) {
        p.reset(); p.isAntiAlias = true
        p.typeface = face; p.textSize = size; p.color = color; p.textAlign = Paint.Align.CENTER
        val fm = p.fontMetrics
        c.drawText(s, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, p)
    }

    private fun fillRound(c: Canvas, r: RectF, rad: Float, color: Int) {
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.FILL; p.color = color
        c.drawRoundRect(r, rad, rad, p)
    }

    private fun strokeRound(c: Canvas, r: RectF, rad: Float, color: Int, w: Float) {
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = w; p.color = color
        c.drawRoundRect(r, rad, rad, p)
    }

    private fun dashed(c: Canvas, l: Float, r: Float, y: Float, u: Float) {
        p.reset(); p.color = INK_MUTED; p.strokeWidth = 1.5f * u
        var x = l
        while (x < r) {
            c.drawLine(x, y, (x + 6 * u).coerceAtMost(r), y, p)
            x += 10 * u
        }
    }

    private fun hms(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    companion object {
        private val BG = Color.parseColor("#0B0F14")
        private val PANEL = Color.parseColor("#121821")
        private val LINE = Color.parseColor("#2A3340")
        private val CHIP_OFF = Color.parseColor("#1C2430")
        private val FARE_BG = Color.parseColor("#05080B")
        private val MUTED = Color.parseColor("#8A97A8")
        private val DIGIT = Color.parseColor("#7FE7FF")
        private val GHOST = Color.parseColor("#0F2A33")
        private val RED = Color.parseColor("#E0393E")
        private val GREEN = Color.parseColor("#22B455")
        private val ORANGE = Color.parseColor("#F08A00")
        private val BLUE = Color.parseColor("#2D7FF9")
        private val INK = Color.parseColor("#111111")
        private val INK_MUTED = Color.parseColor("#666666")
    }
}
