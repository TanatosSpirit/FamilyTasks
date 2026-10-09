package com.familytasks.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.notifications.NotificationScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FamilyTasksApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var notificationScheduler: NotificationScheduler
    @Inject lateinit var prefs: PreferencesManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        notificationScheduler.createChannels()
        // UI ушёл в фон (Home, другое приложение, блокировка экрана) — снова скрыть
        // секретные задачи. Процесс при этом живёт дальше благодаря SyncService.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                prefs.showSecretTasksFlow.value = false
            }
        })
        if (prefs.dailySummaryEnabled) {
            notificationScheduler.scheduleDailySummaryAlarm(prefs.dailySummaryHour, prefs.dailySummaryMinute)
        }
    }
}
