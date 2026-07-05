package com.familytasks.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.familytasks.app.core.AppLogger
import com.familytasks.app.core.DeviceIdManager
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.domain.model.TaskStatus
import com.familytasks.app.domain.repository.TaskRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@AndroidEntryPoint
class DailySummaryReceiver : BroadcastReceiver() {

    @Inject lateinit var taskRepository: TaskRepository
    @Inject lateinit var deviceIdManager: DeviceIdManager
    @Inject lateinit var notificationScheduler: NotificationScheduler
    @Inject lateinit var prefs: PreferencesManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DAILY_SUMMARY) return
        if (!prefs.dailySummaryEnabled) {
            AppLogger.d(TAG, "Fired while feature disabled — skipping, not rescheduling")
            return
        }

        // Переставляем будильник первым действием (как performKeepAlive вызывает
        // scheduleKeepAlive() первой строкой) — следующий будильник взведён, даже
        // если подсчёт/показ ниже упадёт с исключением.
        notificationScheduler.scheduleDailySummaryAlarm(prefs.dailySummaryHour, prefs.dailySummaryMinute)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val myId = deviceIdManager.deviceId
                val zone = ZoneId.systemDefault()
                val endOfToday = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

                // Правило счётчика (см. proposal/daily-summary-notification.md, развилка 1):
                // только мои задачи (assignedTo == myId); просроченные ВКЛЮЧЕНЫ (dueDate
                // без нижней границы) и isSecret ВКЛЮЧЕНЫ (не фильтруются) — оба пункта
                // по явному решению пользователя, оба противоположны рекомендации документа.
                val count = taskRepository.getAllTasksSnapshot().count { task ->
                    task.assignedTo == myId &&
                        !task.deleted &&
                        task.status == TaskStatus.TODO &&
                        task.dueDate != null &&
                        task.dueDate < endOfToday
                }

                notificationScheduler.showDailySummaryNotification(count)
                AppLogger.i(TAG, "Daily summary shown: $count task(s) due today")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to build daily summary", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "DailySummaryReceiver"
        const val ACTION_DAILY_SUMMARY = "com.familytasks.app.DAILY_SUMMARY"
    }
}
