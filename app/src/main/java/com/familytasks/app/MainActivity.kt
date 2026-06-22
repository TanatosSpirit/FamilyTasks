package com.familytasks.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.data.sync.SyncService
import com.familytasks.app.ui.navigation.NavGraph
import com.familytasks.app.ui.theme.FamilyTasksTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var prefs: PreferencesManager

    private var pendingTaskId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startSyncService()
        pendingTaskId = intent?.getStringExtra(EXTRA_TASK_ID)
        setContent {
            FamilyTasksTheme {
                NavGraph(
                    prefs = prefs,
                    pendingTaskId = pendingTaskId,
                    onPendingTaskIdConsumed = { pendingTaskId = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingTaskId = intent.getStringExtra(EXTRA_TASK_ID)
    }

    private fun startSyncService() {
        try {
            startForegroundService(Intent(this, SyncService::class.java))
        } catch (_: Exception) {}
    }

    companion object {
        const val EXTRA_TASK_ID = "taskId"
    }
}
