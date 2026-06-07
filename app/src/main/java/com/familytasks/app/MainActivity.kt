package com.familytasks.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.data.sync.SyncService
import com.familytasks.app.ui.navigation.NavGraph
import com.familytasks.app.ui.theme.FamilyTasksTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var prefs: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startSyncService()
        setContent {
            FamilyTasksTheme {
                NavGraph(prefs = prefs)
            }
        }
    }

    private fun startSyncService() {
        try {
            startForegroundService(Intent(this, SyncService::class.java))
        } catch (_: Exception) {}
    }
}
