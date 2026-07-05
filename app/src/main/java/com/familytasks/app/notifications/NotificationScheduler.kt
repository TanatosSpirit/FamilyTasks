package com.familytasks.app.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.familytasks.app.MainActivity
import com.familytasks.app.core.AppLogger
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.domain.model.Recurrence
import com.familytasks.app.domain.model.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import java.time.ZonedDateTime
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
            NotificationChannel(CH_STATUS, "Статус задачи", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_DAILY_SUMMARY, "Ежедневная сводка", NotificationManager.IMPORTANCE_DEFAULT)
        ).forEach { manager.createNotificationChannel(it) }
    }

    fun scheduleDueNotifications(task: Task) {
        if (!prefs.notifyDueDate) {
            AppLogger.d(TAG, "scheduleDueNotifications skipped for '${task.title}' — reminders disabled in settings")
            return
        }
        val dueDate = task.dueDate
        if (dueDate == null) {
            AppLogger.d(TAG, "scheduleDueNotifications skipped for '${task.title}' — no due date")
            return
        }
        val now = System.currentTimeMillis()
        val displayTitle = if (task.isSecret) "Задача" else task.title

        val oneDayBefore = dueDate - TimeUnit.DAYS.toMillis(1)
        val oneHourBefore = dueDate - TimeUnit.HOURS.toMillis(1)

        if (oneDayBefore > now) {
            scheduleNotificationWorker(
                task.id + "_day",
                task.id,
                displayTitle,
                "Осталось 1 день",
                oneDayBefore - now
            )
        } else {
            AppLogger.d(TAG, "Day-before mark for '${task.title}' already in the past — cancelling any stale work")
            cancelWork(task.id + "_day")
        }
        if (oneHourBefore > now) {
            scheduleNotificationWorker(
                task.id + "_hour",
                task.id,
                displayTitle,
                "Осталось 1 час",
                oneHourBefore - now
            )
        } else {
            AppLogger.d(TAG, "Hour-before mark for '${task.title}' already in the past — cancelling any stale work")
            cancelWork(task.id + "_hour")
        }
    }

    fun notifyNewTask(task: Task) {
        if (!prefs.notifyNewTask) return
        showNotification(CH_NEW_TASK, task.id.hashCode(), "Тебе назначена задача", task.title, task.id)
    }

    fun notifyStatusChanged(task: Task) {
        if (!prefs.notifyStatusChange) return
        val statusLabel = when (task.status.value) {
            "done" -> "выполнена"
            else -> "обновлена"
        }
        showNotification(CH_STATUS, task.id.hashCode() + 1000, "Задача $statusLabel", task.title, task.id)
    }

    fun cancelDueNotifications(taskId: String) {
        AppLogger.d(TAG, "Cancel notifications for task ${taskId.take(8)}")
        cancelWork(taskId + "_day")
        cancelWork(taskId + "_hour")
    }

    /**
     * Заводит [RecurrenceSpawnWorker] на момент дедлайна задачи. Вызывать только когда
     * задача назначена на это устройство (`assignedTo == myDeviceId`) и активна —
     * owner-only гарантирует, что клон породит ровно одно устройство. Воркер на старте
     * перепроверяет все условия сам. Политика REPLACE: правка дедлайна перепланирует.
     */
    fun scheduleRecurrenceSpawn(task: Task) {
        val dueDate = task.dueDate
        if (task.recurrence == Recurrence.None || dueDate == null) {
            AppLogger.d(TAG, "scheduleRecurrenceSpawn skipped for '${task.title}' — no recurrence or due date")
            return
        }
        val delay = (dueDate - System.currentTimeMillis()).coerceAtLeast(0L)
        AppLogger.d(TAG, "Schedule recurrence spawn [${task.id.take(8)}_spawn] in ${delay / 60_000}m (rule=${task.recurrence.serialize()})")
        val request = OneTimeWorkRequestBuilder<RecurrenceSpawnWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    RecurrenceSpawnWorker.KEY_TASK_ID to task.id,
                    RecurrenceSpawnWorker.KEY_EXPECTED_DUE to dueDate
                )
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(task.id + "_spawn", ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelRecurrenceSpawn(taskId: String) {
        AppLogger.d(TAG, "Cancel recurrence spawn for task ${taskId.take(8)}")
        cancelWork(taskId + "_spawn")
    }

    /**
     * Следующее наступление [hour]:[minute] по местному времени: сегодня, если ещё не
     * наступило, иначе завтра. `ZonedDateTime` (в отличие от "+24*60*60*1000") корректно
     * учитывает переход на летнее/зимнее время при прибавлении суток.
     */
    fun scheduleDailySummaryAlarm(hour: Int, minute: Int) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = dailySummaryAlarmPendingIntent()
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        var next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!next.isAfter(now)) {
            next = next.plusDays(1)
        }
        val triggerAt = next.toInstant().toEpochMilli()
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        AppLogger.d(TAG, "Daily summary alarm scheduled for $hour:$minute — next trigger at $triggerAt")
    }

    fun cancelDailySummaryAlarm() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.cancel(dailySummaryAlarmPendingIntent())
        AppLogger.d(TAG, "Daily summary alarm cancelled")
    }

    private fun dailySummaryAlarmPendingIntent(): PendingIntent {
        val intent = Intent(context, DailySummaryReceiver::class.java).apply {
            action = DailySummaryReceiver.ACTION_DAILY_SUMMARY
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE_DAILY_SUMMARY_ALARM, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Всегда показывает число, включая 0 — отсутствие уведомления не должно путаться с «дел нет». */
    fun showDailySummaryNotification(count: Int) {
        val notification = NotificationCompat.Builder(context, CH_DAILY_SUMMARY)
            .setContentTitle("Ежедневная сводка")
            .setContentText(taskCountLabel(count))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent())
            .build()
        manager.notify(NOTIFICATION_ID_DAILY_SUMMARY, notification)
    }

    private fun taskCountLabel(count: Int): String {
        val mod100 = count % 100
        val mod10 = count % 10
        val word = when {
            mod100 in 11..14 -> "дел"
            mod10 == 1 -> "дело"
            mod10 in 2..4 -> "дела"
            else -> "дел"
        }
        return "$count $word на сегодня"
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, REQUEST_CODE_DAILY_SUMMARY_CONTENT, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelWork(tag: String) {
        WorkManager.getInstance(context).cancelUniqueWork(tag)
    }

    private fun scheduleNotificationWorker(tag: String, taskId: String, title: String, text: String, delayMs: Long) {
        AppLogger.d(TAG, "Schedule notification [$tag] in ${delayMs / 60_000}m")
        val request = OneTimeWorkRequestBuilder<DueNotificationWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("taskId" to taskId, "title" to title, "text" to text))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(tag, ExistingWorkPolicy.REPLACE, request)
    }

    private fun showNotification(channel: String, id: Int, title: String, text: String, taskId: String) {
        val notification = NotificationCompat.Builder(context, channel)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(taskDetailPendingIntent(id, taskId))
            .build()
        manager.notify(id, notification)
    }

    private fun taskDetailPendingIntent(requestCode: Int, taskId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "NotificationScheduler"
        const val CH_DUE = "ch_due_date"
        const val CH_NEW_TASK = "ch_new_task"
        const val CH_STATUS = "ch_status"
        const val CH_DAILY_SUMMARY = "ch_daily_summary"
        private const val REQUEST_CODE_DAILY_SUMMARY_ALARM = 100
        private const val REQUEST_CODE_DAILY_SUMMARY_CONTENT = 101
        private const val NOTIFICATION_ID_DAILY_SUMMARY = 9001
    }
}
