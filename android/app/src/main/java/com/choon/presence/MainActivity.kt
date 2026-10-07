package com.choon.presence

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private lateinit var store: PresenceStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PresenceStore(this)
        Diag.init(this)
        setContent { MaterialTheme { Screen() } }
    }

    private fun granted(p: String) =
        checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun startServiceIfPermitted() {
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) WifiMonitorService.start(this)
    }

    @Composable
    private fun Screen() {
        val zone = remember { ZoneId.systemDefault() }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var refresh by remember { mutableLongStateOf(0L) }
        var fineOk by remember { mutableStateOf(granted(Manifest.permission.ACCESS_FINE_LOCATION)) }
        var bgOk by remember { mutableStateOf(granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) }
        var ssidText by remember { mutableStateOf(store.targetSsid) }
        var gapText by remember { mutableStateOf(store.mergeGapMinutes.toString()) }

        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis(); delay(15_000)
            }
        }

        val permLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            fineOk = granted(Manifest.permission.ACCESS_FINE_LOCATION)
            startServiceIfPermitted()
        }
        val bgLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { bgOk = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }

        LaunchedEffect(Unit) { startServiceIfPermitted() }

        // refresh 가 바뀔 때마다 다시 계산
        val events = remember(now, refresh) { store.events() }
        val gapMs = store.mergeGapMinutes * 60_000L
        val sessions = SessionCalculator.buildSessions(
            events, now,
            if (WifiMonitorService.running) now else store.lastAliveMillis + PresenceStore.ALIVE_GRACE_MILLIS,
            gapMs
        )
        val today = SessionCalculator.today(now, zone)
        val days = (0..6).map { SessionCalculator.summarize(sessions, today.minusDays(it.toLong()), zone) }
        val todaySum = days.first()
        val atHome = events.lastOrNull()?.type == EventType.ENTER
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        fun hm(ms: Long?) = ms?.let { timeFmt.format(Instant.ofEpochMilli(it)) } ?: "-"

        LazyColumn(
            Modifier.statusBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(16.dp))
                Text("오늘 재실 시간", style = MaterialTheme.typography.titleMedium)
                Text(fmtDuration(todaySum.totalMillis), style = MaterialTheme.typography.displayMedium)
                Text(
                    (if (atHome) "지금 집에 있음" else "지금 외출 중") +
                        "  ·  첫 귀가 ${hm(todaySum.firstEnterMillis)}  ·  마지막 외출 ${hm(todaySum.lastExitMillis)}"
                )
            }

            if (!fineOk || !bgOk || store.ssidUnreadable) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("권한이 필요해요", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "와이파이 이름(SSID)을 읽으려면 안드로이드가 '위치' 권한을 요구해요. " +
                                "백그라운드에서도 기록하려면 '항상 허용'이 필요합니다. 위치 자체는 저장하지 않아요."
                        )
                        if (!fineOk) Button(onClick = {
                            permLauncher.launch(
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
                            )
                        }) { Text("1. 위치/알림 권한 허용") }
                        if (fineOk && !bgOk) Button(onClick = {
                            bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }) { Text("2. 위치 '항상 허용'으로 변경") }
                        OutlinedButton(onClick = {
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                            )
                        }) { Text("앱 설정 열기 (배터리 → 제한 없음 권장)") }
                    }
                }
            }

            item { Text("최근 7일", style = MaterialTheme.typography.titleMedium) }
            items(days) { d ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("${d.date.monthValue}/${d.date.dayOfMonth}", style = MaterialTheme.typography.titleSmall)
                            Text("${hm(d.firstEnterMillis)} ~ ${hm(d.lastExitMillis)}  ·  ${d.sessions.size}회")
                        }
                        Text(fmtDuration(d.totalMillis), style = MaterialTheme.typography.titleLarge)
                    }
                }
            }

            item {
                Text("설정", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = ssidText, onValueChange = { ssidText = it },
                    label = { Text("집 와이파이 이름 (SSID)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = gapText, onValueChange = { gapText = it.filter(Char::isDigit).take(3) },
                    label = { Text("이 시간(분) 이하로 끊기면 같은 재실로 합치기") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = {
                        store.targetSsid = ssidText
                        startServiceIfPermitted()
                        store.mergeGapMinutes = gapText.toIntOrNull() ?: PresenceStore.DEFAULT_GAP_MIN
                        refresh++
                    }) { Text("저장") }
                    OutlinedButton(onClick = { store.clear(); refresh++ }) { Text("기록 삭제") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { share("재실 기록 내보내기", exportPresence(events, now)) }) {
                        Text("재실 기록 내보내기")
                    }
                    OutlinedButton(onClick = {
                        share("진단 로그 내보내기", Diag.report(this@MainActivity, store))
                    }) { Text("진단 로그 내보내기") }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    private fun share(title: String, text: String) {
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), title))
    }

    /** 일별 요약 + 세션 + 원본 이벤트를 CSV 형태의 텍스트로 만든다. */
    private fun exportPresence(events: List<PresenceEvent>, now: Long): String {
        val zone = ZoneId.systemDefault()
        val sessions = SessionCalculator.buildSessions(
            events, now,
            if (WifiMonitorService.running) now else store.lastAliveMillis + PresenceStore.ALIVE_GRACE_MILLIS,
            store.mergeGapMinutes * 60_000L,
        )
        val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone)
        val hm = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        fun t(ms: Long?) = ms?.let { hm.format(Instant.ofEpochMilli(it)) } ?: ""
        val sb = StringBuilder()
        sb.appendLine("# 일별 요약 (합치기 기준 ${store.mergeGapMinutes}분, SSID ${store.targetSsid})")
        sb.appendLine("date,total_minutes,first_enter,last_exit,sessions")
        val first = events.firstOrNull()?.let { SessionCalculator.today(it.timeMillis, zone) }
        if (first != null) {
            var d: java.time.LocalDate = first
            val today = SessionCalculator.today(now, zone)
            while (!d.isAfter(today)) {
                val sum = SessionCalculator.summarize(sessions, d, zone)
                sb.appendLine("$d,${sum.totalMillis / 60_000},${t(sum.firstEnterMillis)},${t(sum.lastExitMillis)},${sum.sessions.size}")
                d = d.plusDays(1)
            }
        }
        sb.appendLine()
        sb.appendLine("# 세션 (짧은 끊김 합친 결과)")
        sb.appendLine("start,end,minutes,open")
        sessions.forEach {
            sb.appendLine("${iso.format(Instant.ofEpochMilli(it.startMillis))},${iso.format(Instant.ofEpochMilli(it.endMillis))},${it.durationMillis / 60_000},${it.open}")
        }
        sb.appendLine()
        sb.appendLine("# 원본 이벤트")
        sb.append(exportCsv(events))
        return sb.toString()
    }

    private fun exportCsv(events: List<PresenceEvent>): String {
        val f = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())
        return "time,event\n" + events.joinToString("\n") {
            "${f.format(Instant.ofEpochMilli(it.timeMillis))},${if (it.type == EventType.ENTER) "ENTER" else "EXIT"}"
        }
    }

    private fun fmtDuration(ms: Long): String {
        val m = ms / 60_000
        return "${m / 60}시간 ${m % 60}분"
    }
}
