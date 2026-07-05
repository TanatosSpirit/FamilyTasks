package com.familytasks.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
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
        if (prefs.dailySummaryEnabled) {
            notificationScheduler.scheduleDailySummaryAlarm(prefs.dailySummaryHour, prefs.dailySummaryMinute)
        }
    }
}
