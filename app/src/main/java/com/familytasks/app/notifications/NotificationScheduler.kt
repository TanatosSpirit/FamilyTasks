package com.familytasks.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.familytasks.app.core.AppLogger
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.domain.model.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesManager
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        listOf(
            NotificationChannel(CH_DUE, "Срок задачи", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_NEW_TASK, "Новые задачи", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_STATUS, "Статус задачи", NotificationManager.IMPORTANCE_DEFAULT)
        ).forEach { manager.createNotificationChannel(it) }
    }

    fun scheduleDueNotifications(task: Task) {
        if (!prefs.notifyDueDate) return
        val dueDate = task.dueDate ?: return
        val now = System.currentTimeMillis()

        val oneDayBefore = dueDate - TimeUnit.DAYS.toMillis(1)
        val oneHourBefore = dueDate - TimeUnit.HOURS.toMillis(1)

        if (oneDayBefore > now) {
            scheduleNotificationWorker(
                task.id + "_day",
                task.title,
                "Осталось 1 день",
                oneDayBefore - now
            )
        }
        if (oneHourBefore > now) {
            scheduleNotificationWorker(
                task.id + "_hour",
                task.title,
                "Осталось 1 час",
                oneHourBefore - now
            )
        }
    }

    fun notifyNewTask(task: Task) {
        if (!prefs.notifyNewTask) return
        showNotification(CH_NEW_TASK, task.id.hashCode(), "Тебе назначена задача", task.title)
    }

    fun notifyStatusChanged(task: Task) {
        if (!prefs.notifyStatusChange) return
        val statusLabel = when (task.status.value) {
            "done" -> "выполнена"
            "in_progress" -> "в процессе"
            else -> "обновлена"
        }
        showNotification(CH_STATUS, task.id.hashCode() + 1000, "Задача $statusLabel", task.title)
    }

    fun cancelDueNotifications(taskId: String) {
        AppLogger.d(TAG, "Cancel notifications for task ${taskId.take(8)}")
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(taskId + "_day")
        wm.cancelUniqueWork(taskId + "_hour")
    }

    private fun scheduleNotificationWorker(tag: String, title: String, text: String, delayMs: Long) {
        AppLogger.d(TAG, "Schedule notification [$tag] in ${delayMs / 60_000}m")
        val request = OneTimeWorkRequestBuilder<DueNotificationWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("title" to title, "text" to text))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(tag, ExistingWorkPolicy.REPLACE, request)
    }

    private fun showNotification(channel: String, id: Int, title: String, text: String) {
        val notification = NotificationCompat.Builder(context, channel)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }

    companion object {
        private const val TAG = "NotificationScheduler"
        const val CH_DUE = "ch_due_date"
        const val CH_NEW_TASK = "ch_new_task"
        const val CH_STATUS = "ch_status"
    }
}
