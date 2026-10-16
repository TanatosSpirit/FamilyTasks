package com.familytasks.app.domain.usecase

import com.familytasks.app.core.AppLogger
import com.familytasks.app.core.DeviceIdManager
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.domain.model.Device
import com.familytasks.app.domain.repository.DeviceRepository
import com.familytasks.app.domain.repository.TaskRepository
import com.familytasks.app.domain.repository.TaskSyncStateRepository
import javax.inject.Inject

/**
 * Сопряжение с устройством по QR + перепривязка задач прежнего партнёра.
 *
 * Инвариант после сопряжения: в таблице `devices` только мы и текущий партнёр.
 * Любой другой deviceId — «бывший партнёр» (переустановил приложение → новый id,
 * либо сменилось само устройство). Его задачи (assignedTo / createdBy) переходят
 * к новому партнёру, запись о нём удаляется. Подробнее: docs/device-rebinding.md.
 */
class PairDeviceUseCase @Inject constructor(
    private val taskRepository: TaskRepository,
    private val deviceRepository: DeviceRepository,
    private val taskSyncStateRepository: TaskSyncStateRepository,
    private val prefs: PreferencesManager,
    private val deviceIdManager: DeviceIdManager
) {
    /**
     * Бывшие партнёры, которых заменит [newPartnerId]. Пустой список — перепривязка
     * не нужна (первое сопряжение или повторный скан того же партнёра без «хвостов»).
     * Для id, о котором нет записи в Room, имя подставляется заглушкой.
     */
    suspend fun formerPartners(newPartnerId: String): List<Device> {
        val selfId = deviceIdManager.deviceId
        val known = deviceRepository.getAllDevices()
        val ids = buildSet {
            prefs.pairedDeviceId?.let { add(it) }
            known.forEach { add(it.deviceId) }
            remove(selfId)
            remove(newPartnerId)
        }
        return ids.map { id ->
            known.find { it.deviceId == id }
                ?: Device(deviceId = id, displayName = "Устройство ${id.take(8)}", color = 0xFF9E9E9E.toInt())
        }
    }

    suspend fun pair(newPartner: Device) {
        val formerIds = formerPartners(newPartner.deviceId).map { it.deviceId }

        if (formerIds.isNotEmpty()) {
            // updatedAt поднимаем, чтобы перепривязанные задачи гарантированно победили
            // по LWW у нового партнёра, если у него остались копии со старым id.
            val changed = taskRepository.reassignDevice(formerIds, newPartner.deviceId, System.currentTimeMillis())
            deviceRepository.deleteDevices(formerIds)
            // Подтверждения доставки и delta-watermark относились к прежнему партнёру —
            // новый ничего из этого не получал.
            taskSyncStateRepository.clearAll()
            prefs.lastConfirmedSyncTimestamp = 0L
            AppLogger.i(
                TAG,
                "Rebind: ${formerIds.joinToString { it.take(8) }} → ${newPartner.deviceId.take(8)}, $changed tasks reassigned"
            )
        }

        deviceRepository.upsertDevice(newPartner)
        prefs.pairedDeviceId = newPartner.deviceId
        AppLogger.i(TAG, "Paired with ${newPartner.deviceId.take(8)} '${newPartner.displayName}'")
    }

    companion object {
        private const val TAG = "PairDevice"
    }
}
