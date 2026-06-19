package com.familytasks.app.domain.usecase

import com.familytasks.app.domain.model.Device
import com.familytasks.app.domain.model.Task
import com.familytasks.app.domain.repository.DeviceRepository
import com.familytasks.app.domain.repository.TaskRepository
import javax.inject.Inject

class SyncUseCase @Inject constructor(
    private val taskRepository: TaskRepository,
    private val deviceRepository: DeviceRepository,
    private val mergeTasksUseCase: MergeTasksUseCase
) {
    suspend fun getLocalSnapshot(): List<Task> = taskRepository.getAllTasksSnapshot()

    suspend fun getLocalDevices(): List<Device> = deviceRepository.getAllDevices()

    suspend fun mergeAll(remoteTasks: List<Task>, remoteDevices: List<Device>) {
        mergeTasksUseCase(remoteTasks)
        for (device in remoteDevices) {
            deviceRepository.upsertDevice(device)
        }
    }

    // Safe to purge: peer's response shows it already holds the same id as deleted too.
    suspend fun purgeConfirmedTombstones(sentTasks: List<Task>, remoteTasks: List<Task>): Int {
        val remoteDeletedIds = remoteTasks.asSequence().filter { it.deleted }.mapTo(mutableSetOf()) { it.id }
        val purgeableIds = sentTasks.filter { it.deleted && it.id in remoteDeletedIds }.map { it.id }
        if (purgeableIds.isNotEmpty()) {
            taskRepository.purgeTasks(purgeableIds)
        }
        return purgeableIds.size
    }
}
