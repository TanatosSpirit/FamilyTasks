package com.familytasks.app.domain.usecase

import com.familytasks.app.core.AppLogger
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.domain.model.Device
import com.familytasks.app.domain.model.Task
import com.familytasks.app.domain.repository.DeviceRepository
import com.familytasks.app.domain.repository.TaskRepository
import com.familytasks.app.domain.repository.TaskSyncStateRepository
import javax.inject.Inject

class SyncUseCase @Inject constructor(
    private val taskRepository: TaskRepository,
    private val deviceRepository: DeviceRepository,
    private val taskSyncStateRepository: TaskSyncStateRepository,
    private val mergeTasksUseCase: MergeTasksUseCase,
    private val prefs: PreferencesManager
) {
    suspend fun getLocalSnapshot(): List<Task> = taskRepository.getAllTasksSnapshot()

    suspend fun getLocalDevices(): List<Device> = deviceRepository.getAllDevices()

    suspend fun mergeAll(remoteTasks: List<Task>, remoteDevices: List<Device>) {
        mergeTasksUseCase(remoteTasks)
        // Из чужого списка устройств берём только запись самого партнёра. Всё остальное
        // (в т.ч. наш прежний deviceId, который партнёр помнит после нашей переустановки)
        // не должно попадать в Room — иначе в UI «двоится» имя. См. docs/device-rebinding.md.
        val pairedId = prefs.pairedDeviceId
        for (device in remoteDevices) {
            if (device.deviceId == pairedId) {
                deviceRepository.upsertDevice(device)
            } else {
                AppLogger.d(TAG, "Merge: ignoring foreign device ${device.deviceId.take(8)} '${device.displayName}'")
            }
        }
    }

    // Safe to purge if peer confirmed deleted=true, OR if peer doesn't have the task at all (peer already purged it).
    suspend fun purgeConfirmedTombstones(sentTasks: List<Task>, remoteTasks: List<Task>): Int {
        val remoteIds = remoteTasks.mapTo(mutableSetOf()) { it.id }
        val remoteDeletedIds = remoteTasks.asSequence().filter { it.deleted }.mapTo(mutableSetOf()) { it.id }
        val purgeableIds = sentTasks.filter { it.deleted && (it.id in remoteDeletedIds || it.id !in remoteIds) }.map { it.id }
        if (purgeableIds.isNotEmpty()) {
            taskRepository.purgeTasks(purgeableIds)
            taskSyncStateRepository.deleteByIds(purgeableIds)
        }
        return purgeableIds.size
    }

    companion object {
        private const val TAG = "SyncUseCase"
    }
}
