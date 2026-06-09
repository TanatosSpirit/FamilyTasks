package com.familytasks.app.data.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.familytasks.app.core.AppLogger

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AppLogger.i(TAG, "Boot completed — starting SyncService")
            ContextCompat.startForegroundService(
                context,
                Intent(context, SyncService::class.java)
            )
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
