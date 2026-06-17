package com.familytasks.app.domain.usecase

import com.familytasks.app.core.AppLogger
import com.familytasks.app.core.DeviceIdManager
import com.familytasks.app.domain.model.Task
import com.familytasks.app.domain.model.TaskStatus
import com.familytasks.app.domain.repository.TaskRepository
import com.familytasks.app.notifications.NotificationScheduler
import javax.inject.Inject

class MergeTasksUseCase @Inject constructor(
    private val taskRepository: TaskRepository,
    private val notificationScheduler: NotificationScheduler,
    private val deviceIdManager: DeviceIdManager
) {
    suspend operator fun invoke(remoteTasks: List<Task>): List<Task> {
        val myDeviceId = deviceIdManager.deviceId
        val localTasks = taskRepository.getAllTasksSnapshot().associateBy { it.id }.toMutableMap()

        AppLogger.i(TAG, "Merge: ${remoteTasks.size} remote, ${localTasks.size} local")

        var added = 0; var updated = 0; var unchanged = 0
        val toUpsert = mutableListOf<Task>()

        for (remote in remoteTasks) {
            val local = localTasks[remote.id]
            when {
                local == null -> {
                    localTasks[remote.id] = remote
                    toUpsert.add(remote)
                    added++
                    AppLogger.d(TAG, "New: '${remote.title}' (assigned=${if (remote.assignedTo == myDeviceId) "me" else "other"}, deleted=${remote.deleted}, status=${remote.status})")
                    if (!remote.deleted && remote.assignedTo == myDeviceId) {
                        AppLogger.i(TAG, "Notify: new task assigned to me '${remote.title}'")
                        notificationScheduler.notifyNewTask(remote)
                        if (remote.status != TaskStatus.DONE) {
                            AppLogger.d(TAG, "New task '${remote.title}' is open — scheduling due reminders")
                            notificationScheduler.scheduleDueNotifications(remote)
                        } else {
                            AppLogger.d(TAG, "New task '${remote.title}' arrived already done — skipping due reminders")
                        }
                    }
                }
                remote.updatedAt > local.updatedAt -> {
                    localTasks[remote.id] = remote
                    toUpsert.add(remote)
                    updated++
                    AppLogger.d(TAG, "Updated: '${remote.title}' (local=${local.updatedAt}, remote=${remote.updatedAt})")
                    if (!remote.deleted && remote.createdBy == myDeviceId && remote.status != local.status) {
                        AppLogger.i(TAG, "Notify: status changed on my task '${remote.title}' ${local.status} → ${remote.status}")
                        notificationScheduler.notifyStatusChanged(remote)
                    }
                    val wasEligible = local.assignedTo == myDeviceId && local.status != TaskStatus.DONE && !local.deleted
                    val isEligible = remote.assignedTo == myDeviceId && remote.status != TaskStatus.DONE && !remote.deleted
                    if (isEligible && !wasEligible) {
                        AppLogger.d(TAG, "Sync: '${remote.title}' became active+mine — scheduling due reminders")
                        notificationScheduler.scheduleDueNotifications(remote)
                    } else if (!isEligible && wasEligible) {
                        AppLogger.d(
                            TAG,
                            "Sync: '${remote.title}' no longer active+mine (assignedTo=${if (remote.assignedTo == myDeviceId) "me" else "other"}, " +
                                "status=${remote.status}, deleted=${remote.deleted}) — cancelling due reminders"
                        )
                        notificationScheduler.cancelDueNotifications(remote.id)
                    }
                }
                else -> unchanged++
                // tie-break: keep local
            }
        }

        AppLogger.i(TAG, "Merge done: +$added new, ~$updated updated, =$unchanged unchanged — writing ${toUpsert.size}/${localTasks.size} tasks to DB")

        if (toUpsert.isNotEmpty()) {
            taskRepository.upsertTasks(toUpsert)
        }
        return localTasks.values.toList()
    }

    companion object {
        private const val TAG = "MergeTasksUseCase"
    }
}
