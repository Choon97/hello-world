package com.choon.presence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 재부팅/앱 업데이트 후 감시 서비스를 다시 시작한다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { WifiMonitorService.start(context) }
    }
}
