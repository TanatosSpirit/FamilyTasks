package com.familytasks.app.data.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat

class WifiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
        if (state == WifiManager.WIFI_STATE_ENABLED) {
            val serviceIntent = Intent(context, SyncService::class.java)
                .apply { action = SyncService.ACTION_SYNC_NOW }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
