package com.familytasks.app.data.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.familytasks.app.core.AppLogger
import com.familytasks.app.core.DeviceIdManager
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.domain.model.Device
import com.familytasks.app.domain.usecase.SyncUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExportImportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncUseCase: SyncUseCase,
    private val deviceIdManager: DeviceIdManager,
    private val prefs: PreferencesManager
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun buildShareIntent(): Intent {
        val tasks = syncUseCase.getLocalSnapshot()
        val me = Device(
            deviceId = deviceIdManager.deviceId,
            displayName = prefs.displayName,
            color = prefs.avatarColor
        )
        val others = syncUseCase.getLocalDevices().filter { it.deviceId != deviceIdManager.deviceId }
        val payload = SyncPayload(
            deviceId = deviceIdManager.deviceId,
            sinceTimestamp = 0L,
            tasks = tasks.map { it.toDto() },
            devices = (listOf(me) + others).map { it.toDto() }
        )

        val jsonString = json.encodeToString(payload)
        val exportDir = File(context.cacheDir, "export").apply { mkdirs() }
        val file = File(exportDir, "familytasks_export.json").apply { writeText(jsonString) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        AppLogger.i(TAG, "Export: ${tasks.size} tasks → familytasks_export.json")

        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "FamilyTasks — задачи")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    suspend fun importFromUri(uri: Uri): String {
        val pairedId = prefs.pairedDeviceId
        if (pairedId == null) {
            AppLogger.w(TAG, "Import rejected: device not paired")
            return "Устройство не сопряжено — импорт невозможен"
        }

        return try {
            val jsonString = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.readText()
                ?: return "Не удалось прочитать файл"

            val payload = json.decodeFromString<SyncPayload>(jsonString)

            if (payload.deviceId != pairedId) {
                AppLogger.w(TAG, "Import rejected: unknown deviceId ${payload.deviceId.take(8)}")
                return "Файл от незнакомого устройства"
            }

            val remoteTasks = payload.tasks.map { it.toDomain() }
            val remoteDevices = payload.devices.map { it.toDomain() }
            syncUseCase.mergeAll(remoteTasks, remoteDevices)

            val senderName = payload.devices.find { it.deviceId == payload.deviceId }?.displayName
                ?: "партнёра"
            val activeCount = remoteTasks.count { !it.deleted }
            AppLogger.i(TAG, "Import done: $activeCount active tasks from $senderName")
            "Получено $activeCount задач от $senderName"
        } catch (e: Exception) {
            AppLogger.e(TAG, "Import failed", e)
            "Ошибка импорта: ${e.message}"
        }
    }

    companion object {
        private const val TAG = "ExportImport"
    }
}
