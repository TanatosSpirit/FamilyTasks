package com.familytasks.app.data.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.familytasks.app.core.AppLogger
import com.familytasks.app.core.PreferencesManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var prefs: PreferencesManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (prefs.syncPaused && !prefs.webUiEnabled) {
                AppLogger.i(TAG, "Boot completed — sync paused, not starting SyncService")
                return
            }
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
