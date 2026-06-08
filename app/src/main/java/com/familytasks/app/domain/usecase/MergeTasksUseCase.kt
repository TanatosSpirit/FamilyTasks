package com.familytasks.app.domain.usecase

import com.familytasks.app.core.DeviceIdManager
import com.familytasks.app.domain.model.Task
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

        for (remote in remoteTasks) {
            val local = localTasks[remote.id]
            when {
                local == null -> {
                    localTasks[remote.id] = remote
                    if (!remote.deleted && remote.assignedTo == myDeviceId) {
                        notificationScheduler.notifyNewTask(remote)
                        notificationScheduler.scheduleDueNotifications(remote)
                    }
                }
                remote.updatedAt > local.updatedAt -> {
                    localTasks[remote.id] = remote
                    if (!remote.deleted && remote.createdBy == myDeviceId && remote.status != local.status) {
                        notificationScheduler.notifyStatusChanged(remote)
                    }
                }
                // tie-break: keep local
            }
        }

        val merged = localTasks.values.toList()
        taskRepository.upsertTasks(merged)
        return merged
    }
}
