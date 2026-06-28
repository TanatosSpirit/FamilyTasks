package com.familytasks.app.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.familytasks.app.core.AppLogger
import com.familytasks.app.core.DeviceIdManager
import com.familytasks.app.domain.model.Recurrence
import com.familytasks.app.domain.model.Task
import com.familytasks.app.domain.model.TaskStatus
import com.familytasks.app.domain.repository.TaskRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.UUID

/**
 * Срабатывает в момент дедлайна повторяющейся задачи на устройстве ОТВЕТСТВЕННОГО.
 * Создаёт клон со сдвинутым дедлайном и гасит повтор у родителя (`recurrence = None`)
 * — это маркер «бесплоден», дающий идемпотентность без отдельного поля схемы.
 * Клон уносит правило дальше и заводит свой spawn-воркер (см. [NotificationScheduler]).
 *
 * Спавн привязан к владельцу (`assignedTo == myDeviceId`): второе устройство клон не
 * порождает, а получает его обычным `/sync`. Это убирает распределённую дедупликацию.
 *
 * Catch-up: если устройство было выключено несколько периодов, цикл доплодит все
 * пропущенные клоны разом (без лимита — каждый просроченный период остаётся как
 * отдельное напоминание).
 */
@HiltWorker
class RecurrenceSpawnWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val taskRepository: TaskRepository,
    private val deviceIdManager: DeviceIdManager,
    private val notificationScheduler: NotificationScheduler
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.success()
        val expectedDueDate = inputData.getLong(KEY_EXPECTED_DUE, -1L)

        val task = taskRepository.getTaskById(taskId)
        if (task == null) {
            AppLogger.d(TAG, "spawn[${taskId.take(8)}]: task gone — no-op")
            return Result.success()
        }

        val myId = deviceIdManager.deviceId
        val due = task.dueDate
        val notEligible = task.deleted ||
            task.status == TaskStatus.DONE ||
            task.recurrence == Recurrence.None ||
            due == null ||
            task.assignedTo != myId
        if (notEligible) {
            AppLogger.d(
                TAG,
                "spawn['${task.title}']: not eligible (deleted=${task.deleted}, status=${task.status}, " +
                    "recurrence=${task.recurrence.serialize()}, assignedToMe=${task.assignedTo == myId}) — no-op"
            )
            return Result.success()
        }
        // Устаревший воркер: дедлайн изменили правкой → актуальный воркер уже перепланирован
        if (expectedDueDate > 0 && due != expectedDueDate) {
            AppLogger.d(TAG, "spawn['${task.title}']: stale (expected=$expectedDueDate, actual=$due) — no-op")
            return Result.success()
        }

        val now = System.currentTimeMillis()
        var parent: Task = task
        var spawned = 0
        // Пока дедлайн родителя в прошлом — плодим следующего и гасим родителя.
        while (true) {
            val parentDue = parent.dueDate ?: break
            if (parentDue > now) break
            val nextDue = parent.recurrence.nextDueDate(parentDue) ?: break
            val clone = parent.copy(
                id = UUID.randomUUID().toString(),
                status = TaskStatus.TODO,
                completedAt = null,
                dueDate = nextDue,
                createdAt = now,
                updatedAt = now
                // recurrence наследуется — клон продолжит цепочку
            )
            taskRepository.upsertTask(clone)
            // Гасим родителя: он больше не плодит (идемпотентность при повторном/устаревшем запуске)
            taskRepository.upsertTask(parent.copy(recurrence = Recurrence.None, updatedAt = now))
            // Автоспавн — уведомляем владельца, как если бы задачу назначил кто-то другой
            notificationScheduler.notifyNewTask(clone)
            // У просроченных промежуточных клонов метки −1д/−1ч в прошлом → scheduleDue само отменит их
            notificationScheduler.scheduleDueNotifications(clone)
            spawned++
            parent = clone
        }

        if (spawned == 0) {
            AppLogger.d(TAG, "spawn['${task.title}']: due not reached yet — no-op")
            return Result.success()
        }
        // Финальный живой клон (dueDate в будущем) — заводим его spawn-воркер
        notificationScheduler.scheduleRecurrenceSpawn(parent)
        AppLogger.i(TAG, "spawn['${task.title}']: created $spawned occurrence(s), live clone due ${parent.dueDate}")
        return Result.success()
    }

    companion object {
        private const val TAG = "RecurrenceSpawnWorker"
        const val KEY_TASK_ID = "taskId"
        const val KEY_EXPECTED_DUE = "expectedDueDate"
    }
}
