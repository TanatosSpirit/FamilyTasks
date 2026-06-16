package com.familytasks.app.data.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, SyncService::class.java).apply {
            action = SyncService.ACTION_KEEPALIVE
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
