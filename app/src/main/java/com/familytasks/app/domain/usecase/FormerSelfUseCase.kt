package com.familytasks.app.domain.usecase

import com.familytasks.app.core.AppLogger
import com.familytasks.app.core.DeviceIdManager
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.domain.model.Device
import com.familytasks.app.domain.model.Recurrence
import com.familytasks.app.domain.model.TaskStatus
import com.familytasks.app.domain.repository.DeviceRepository
import com.familytasks.app.domain.repository.TaskRepository
import com.familytasks.app.notifications.NotificationScheduler
import javax.inject.Inject

/** Устройство из `devices`, которое не мы и не текущий партнёр. */
data class OrphanDevice(val device: Device, val taskCount: Int)

/**
 * Ручное «Это моё прежнее устройство» (Настройки → Диагностика).
 *
 * Автоматически отличить свой прежний id от id бывшего партнёра нельзя — бывшего партнёра
 * перепривязывает диалог сопряжения (`PairDeviceUseCase`), а свой прежний id (хвост
 * переустановки до появления ручного бэкапа) пользователь отмечает сам.
 * См. docs/device-rebinding.md.
 */
class FormerSelfUseCase @Inject constructor(
    private val taskRepository: TaskRepository,
    private val deviceRepository: DeviceRepository,
    private val notificationScheduler: NotificationScheduler,
    private val prefs: PreferencesManager,
    private val deviceIdManager: DeviceIdManager
) {
    suspend fun orphans(): List<OrphanDevice> {
        val selfId = deviceIdManager.deviceId
        val pairedId = prefs.pairedDeviceId
        val tasks = taskRepository.getAllTasksSnapshot()
        return deviceRepository.getAllDevices()
            .filter { it.deviceId != selfId && it.deviceId != pairedId }
            .map { device ->
                OrphanDevice(
                    device = device,
                    taskCount = tasks.count { it.assignedTo == device.deviceId || it.createdBy == device.deviceId }
                )
            }
    }

    /** Переписывает задачи [oldId] на текущий deviceId и забывает [oldId]. Возвращает число задач. */
    suspend fun claim(oldId: String): Int {
        val selfId = deviceIdManager.deviceId
        require(oldId != selfId && oldId != prefs.pairedDeviceId) { "Not an orphan device: $oldId" }

        // Задачи, которые станут «моими» — для них до сих пор не было напоминаний и спавна.
        val becomingMine = taskRepository.getAllTasksSnapshot()
            .filter { it.assignedTo == oldId }
            .map { it.id }
            .toSet()

        // updatedAt поднимается — обычные задачи уйдут партнёру и перезапишут его копии.
        val changed = taskRepository.reassignDevice(listOf(oldId), selfId, System.currentTimeMillis())
        deviceRepository.deleteDevices(listOf(oldId))

        taskRepository.getAllTasksSnapshot()
            .filter { it.id in becomingMine && it.status != TaskStatus.DONE && !it.deleted }
            .forEach { task ->
                notificationScheduler.scheduleDueNotifications(task)
                if (task.recurrence != Recurrence.None) notificationScheduler.scheduleRecurrenceSpawn(task)
            }

        AppLogger.i(TAG, "Former self ${oldId.take(8)} → ${selfId.take(8)}: $changed tasks reassigned")
        return changed
    }

    companion object {
        private const val TAG = "FormerSelf"
    }
}
