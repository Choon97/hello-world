package com.joke.taximeter

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 폰 화면: 미터기 + 버튼 + 요금 설정. 안드로이드 오토 없이도 이것만으로 쓸 수 있다. */
class MainActivity : Activity() {

    private lateinit var mainBtn: Button
    private lateinit var outsideBtn: Button

    private val listener = MeterEngine.Listener { s, structural -> if (structural) updateButtons(s) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MeterEngine.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F14"))
        }
        root.addView(MeterView(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        mainBtn = Button(this).apply { setOnClickListener { onMainClick() } }
        outsideBtn = Button(this).apply { setOnClickListener { MeterEngine.toggleOutside() } }
        val settingsBtn = Button(this).apply {
            text = "요금 설정"
            setOnClickListener { showSettings() }
        }
        for (b in listOf(mainBtn, outsideBtn, settingsBtn)) {
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(row)

        val help = TextView(this).apply {
            text = "차에서 쓰려면: 안드로이드 오토 설정 → 맨 아래 '버전' 10번 탭 → 오른쪽 위 ⋮ → 개발자 설정 → '알 수 없는 출처' 체크"
            setTextColor(Color.parseColor("#8A97A8"))
            textSize = 12f
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        root.addView(help)
        setContentView(root)

        requestNeededPermissions()
    }

    override fun onStart() {
        super.onStart()
        MeterEngine.addListener(listener)
    }

    override fun onStop() {
        MeterEngine.removeListener(listener)
        super.onStop()
    }

    private fun onMainClick() {
        when (MeterEngine.snapshot().state) {
            MeterEngine.State.VACANT -> MeterEngine.start(this)
            MeterEngine.State.RUNNING -> MeterEngine.finish(this)
            MeterEngine.State.PAID -> MeterEngine.clear(this)
        }
    }

    private fun updateButtons(s: MeterEngine.Snapshot) {
        mainBtn.text = when (s.state) {
            MeterEngine.State.VACANT -> "운행 시작"
            MeterEngine.State.RUNNING -> "운행 종료"
            MeterEngine.State.PAID -> "빈차로"
        }
        outsideBtn.text = if (s.outside) "시외할증 ON" else "시외할증 OFF"
    }

    private fun requestNeededPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private class Field(
        val label: String,
        val get: (FareConfig) -> Int,
        val set: (FareConfig, Int) -> FareConfig,
        val min: Int = 0,
        val max: Int = 1_000_000,
    )

    private val fields = listOf(
        Field("기본요금 (원)", { it.baseFare }, { c, v -> c.copy(baseFare = v) }),
        Field("기본거리 (m)", { it.baseDistanceM }, { c, v -> c.copy(baseDistanceM = v) }),
        Field("추가 요금 (원, 거리·시간 공통)", { it.unitFare }, { c, v -> c.copy(unitFare = v) }),
        Field("거리 단위 (m)", { it.distanceUnitM }, { c, v -> c.copy(distanceUnitM = v) }, min = 1),
        Field("시간 단위 (초)", { it.timeUnitSec }, { c, v -> c.copy(timeUnitSec = v) }, min = 1),
        Field("시간요금 적용 속도 (km/h 이하)", { it.slowSpeedKmh }, { c, v -> c.copy(slowSpeedKmh = v) }),
        Field("심야 시작 (시, 0~23)", { it.nightStartHour }, { c, v -> c.copy(nightStartHour = v) }, max = 23),
        Field("심야 끝 (시, 0~23)", { it.nightEndHour }, { c, v -> c.copy(nightEndHour = v) }, max = 23),
        Field("심야 할증 (%)", { it.nightPct }, { c, v -> c.copy(nightPct = v) }),
        Field("시외 할증 (%)", { it.outsidePct }, { c, v -> c.copy(outsidePct = v) }),
        Field("심야 + 시외 할증 (%)", { it.combinedPct }, { c, v -> c.copy(combinedPct = v) }),
    )

    private fun showSettings() {
        val cur = MeterEngine.config
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val region = EditText(this).apply {
            setText(cur.regionName)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        box.addView(label("지역 이름"))
        box.addView(region)
        val edits = fields.map { f ->
            box.addView(label(f.label))
            EditText(this).apply {
                setText(f.get(cur).toString())
                inputType = InputType.TYPE_CLASS_NUMBER
                gravity = Gravity.END
            }.also { box.addView(it) }
        }
        AlertDialog.Builder(this)
            .setTitle("요금 설정")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("저장") { _, _ ->
                var c = cur.copy(regionName = region.text.toString().ifBlank { cur.regionName })
                fields.forEachIndexed { i, f ->
                    val v = edits[i].text.toString().toIntOrNull() ?: f.get(cur)
                    c = f.set(c, v.coerceIn(f.min, f.max))
                }
                MeterEngine.updateConfig(this, c)
            }
            .setNeutralButton("나주 기본값") { _, _ -> MeterEngine.updateConfig(this, FareConfig()) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun label(s: String) = TextView(this).apply {
        text = s
        textSize = 13f
        setPadding(0, dp(10), 0, 0)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
