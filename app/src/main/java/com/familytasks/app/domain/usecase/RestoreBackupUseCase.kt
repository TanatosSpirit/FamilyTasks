package com.familytasks.app.domain.usecase

import com.familytasks.app.core.AppLogger
import com.familytasks.app.core.DeviceIdManager
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.domain.model.Device
import com.familytasks.app.domain.model.Recurrence
import com.familytasks.app.domain.model.Task
import com.familytasks.app.domain.model.TaskStatus
import com.familytasks.app.domain.repository.DeviceRepository
import com.familytasks.app.domain.repository.TaskRepository
import com.familytasks.app.notifications.NotificationScheduler
import javax.inject.Inject

/** Содержимое резервной копии в доменных типах. id — на момент создания копии. */
data class BackupContent(
    val selfDeviceId: String,
    val partner: Device?,
    val tasks: List<Task>
)

data class RestoreResult(val added: Int, val updated: Int, val unchanged: Int)

class BackupFromPartnerException : IllegalArgumentException("Это резервная копия устройства партнёра")

/**
 * Восстановление из ручной резервной копии (см. docs/manual-backup.md).
 *
 * 1. Перепривязка id: `backup.self → текущий deviceId`, `backup.partner → текущий pairedDeviceId`.
 *    Сопоставление явное — оба id записаны в файле.
 * 2. Слияние по LWW с **исходными** `updatedAt` (не поднимаем!): копия может быть старше
 *    того, что партнёр уже прислал после переустановки, и не должна затирать свежие версии.
 * 3. Если партнёр ещё не сопряжён — запись о нём из копии кладётся в `devices`, чтобы при
 *    будущем сопряжении `PairDeviceUseCase` распознал его как бывшего и перепривязал задачи.
 */
class RestoreBackupUseCase @Inject constructor(
    private val taskRepository: TaskRepository,
    private val deviceRepository: DeviceRepository,
    private val notificationScheduler: NotificationScheduler,
    private val prefs: PreferencesManager,
    private val deviceIdManager: DeviceIdManager
) {
    suspend operator fun invoke(backup: BackupContent): RestoreResult {
        val selfId = deviceIdManager.deviceId
        val pairedId = prefs.pairedDeviceId

        if (backup.selfDeviceId == pairedId) throw BackupFromPartnerException()

        val idMap = buildMap {
            if (backup.selfDeviceId != selfId) put(backup.selfDeviceId, selfId)
            val oldPartnerId = backup.partner?.deviceId
            if (oldPartnerId != null && pairedId != null && oldPartnerId != pairedId) put(oldPartnerId, pairedId)
        }
        fun remap(id: String) = idMap[id] ?: id

        val localTasks = taskRepository.getAllTasksSnapshot().associateBy { it.id }
        var added = 0; var updated = 0; var unchanged = 0
        val toUpsert = mutableListOf<Task>()

        for (backupTask in backup.tasks) {
            val restored = backupTask.copy(
                assignedTo = backupTask.assignedTo?.let(::remap),
                createdBy = remap(backupTask.createdBy)
            )
            val local = localTasks[restored.id]
            when {
                // Как в MergeTasksUseCase: tombstone неизвестной задачи не воскрешаем.
                local == null && restored.deleted -> unchanged++
                local == null -> { toUpsert.add(restored); added++ }
                restored.updatedAt > local.updatedAt -> { toUpsert.add(restored); updated++ }
                else -> unchanged++
            }
        }

        if (toUpsert.isNotEmpty()) {
            taskRepository.upsertTasks(toUpsert)
            // Восстановленные задачи могут быть старше delta-watermark — без сброса
            // партнёр их не получит. Следующий sync уйдёт полным снимком.
            prefs.lastConfirmedSyncTimestamp = 0L
            toUpsert.forEach { applyScheduling(it, selfId) }
        }

        if (backup.selfDeviceId != selfId) {
            deviceRepository.deleteDevices(listOf(backup.selfDeviceId))
        }
        if (pairedId == null && backup.partner != null && backup.partner.deviceId != selfId) {
            deviceRepository.upsertDevice(backup.partner)
        }

        AppLogger.i(
            TAG,
            "Restore: +$added new, ~$updated updated, =$unchanged unchanged; " +
                "remap ${idMap.entries.joinToString { "${it.key.take(8)}→${it.value.take(8)}" }.ifEmpty { "none" }}"
        )
        return RestoreResult(added, updated, unchanged)
    }

    private fun applyScheduling(task: Task, selfId: String) {
        val eligible = task.assignedTo == selfId && task.status != TaskStatus.DONE && !task.deleted
        if (eligible) {
            notificationScheduler.scheduleDueNotifications(task)
            if (task.recurrence != Recurrence.None) notificationScheduler.scheduleRecurrenceSpawn(task)
        } else {
            notificationScheduler.cancelDueNotifications(task.id)
            notificationScheduler.cancelRecurrenceSpawn(task.id)
        }
    }

    companion object {
        private const val TAG = "RestoreBackup"
    }
}
