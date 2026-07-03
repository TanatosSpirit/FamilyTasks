package com.familytasks.app.domain.usecase

import com.familytasks.app.domain.model.Recurrence
import com.familytasks.app.domain.model.Task
import com.familytasks.app.domain.model.TaskStatus
import com.familytasks.app.domain.repository.TaskRepository
import com.familytasks.app.notifications.NotificationScheduler
import java.util.UUID
import javax.inject.Inject

/**
 * Два способа закрыть повторяющуюся задачу: оборвать цепочку (текущее поведение)
 * или закрыть только текущее вхождение и сразу заспавнить следующее — не дожидаясь,
 * пока [com.familytasks.app.notifications.RecurrenceSpawnWorker] сработает по dueDate.
 */
class RecurringTaskCompletionUseCase @Inject constructor(
    private val taskRepository: TaskRepository,
    private val notificationScheduler: NotificationScheduler
) {

    suspend fun completeEndingChain(task: Task): Task {
        val now = System.currentTimeMillis()
        val updated = task.copy(status = TaskStatus.DONE, updatedAt = now, completedAt = now)
        taskRepository.upsertTask(updated)
        notificationScheduler.cancelDueNotifications(updated.id)
        notificationScheduler.cancelRecurrenceSpawn(updated.id)
        return updated
    }

    suspend fun completeAndContinue(task: Task): Task {
        val now = System.currentTimeMillis()
        val doneParent = task.copy(
            status = TaskStatus.DONE,
            updatedAt = now,
            completedAt = now,
            recurrence = Recurrence.None
        )
        taskRepository.upsertTask(doneParent)
        notificationScheduler.cancelDueNotifications(doneParent.id)
        notificationScheduler.cancelRecurrenceSpawn(doneParent.id)

        val nextDue = task.dueDate?.let { task.recurrence.nextDueDate(it) } ?: return doneParent

        val clone = task.copy(
            id = UUID.randomUUID().toString(),
            status = TaskStatus.TODO,
            completedAt = null,
            dueDate = nextDue,
            createdAt = now,
            updatedAt = now
        )
        taskRepository.upsertTask(clone)
        notificationScheduler.scheduleDueNotifications(clone)
        notificationScheduler.scheduleRecurrenceSpawn(clone)
        return doneParent
    }
}
