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
}
