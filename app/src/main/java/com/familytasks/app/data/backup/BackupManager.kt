package com.familytasks.app.data.backup

import android.content.Context
import android.net.Uri
import com.familytasks.app.core.AppLogger
import com.familytasks.app.core.DeviceIdManager
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.data.sync.DeviceDto
import com.familytasks.app.data.sync.TaskDto
import com.familytasks.app.data.sync.toDomain
import com.familytasks.app.data.sync.toDto
import com.familytasks.app.domain.model.Device
import com.familytasks.app.domain.repository.DeviceRepository
import com.familytasks.app.domain.repository.TaskRepository
import com.familytasks.app.domain.usecase.BackupContent
import com.familytasks.app.domain.usecase.BackupFromPartnerException
import com.familytasks.app.domain.usecase.RestoreBackupUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Формат файла ручной резервной копии. Отдельный от [com.familytasks.app.data.sync.SyncPayload]:
 * содержит `isSecret` (в sync его нет by design) и id обеих сторон на момент копии —
 * по ним восстановление перепривязывает задачи. См. docs/manual-backup.md.
 */
@Serializable
data class BackupFile(
    val format: String,
    val version: Int,
    val createdAt: Long,
    val self: DeviceDto,
    val partner: DeviceDto? = null,
    val tasks: List<BackupTaskDto>
)

@Serializable
data class BackupTaskDto(val task: TaskDto, val isSecret: Boolean = false)

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskRepository: TaskRepository,
    private val deviceRepository: DeviceRepository,
    private val restoreBackupUseCase: RestoreBackupUseCase,
    private val deviceIdManager: DeviceIdManager,
    private val prefs: PreferencesManager
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun suggestedFileName(): String =
        "familytasks_backup_${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.json"

    suspend fun writeBackup(uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            // Все задачи как есть: секретные и tombstone-ы тоже.
            val tasks = taskRepository.getAllTasksSnapshot()
            val partner = prefs.pairedDeviceId?.let { deviceRepository.getDeviceById(it) }
            val file = BackupFile(
                format = FORMAT,
                version = VERSION,
                createdAt = System.currentTimeMillis(),
                self = Device(deviceIdManager.deviceId, prefs.displayName, prefs.avatarColor).toDto(),
                partner = partner?.toDto(),
                tasks = tasks.map { BackupTaskDto(it.toDto(), it.isSecret) }
            )
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(json.encodeToString(file).toByteArray())
            } ?: return@withContext "Не удалось открыть файл для записи"

            val secret = tasks.count { it.isSecret && !it.deleted }
            val active = tasks.count { !it.deleted }
            AppLogger.i(TAG, "Backup: ${tasks.size} tasks ($active active, $secret secret) written")
            "Сохранено задач: $active (секретных: $secret)"
        } catch (e: Exception) {
            AppLogger.e(TAG, "Backup failed", e)
            "Ошибка сохранения: ${e.message}"
        }
    }

    suspend fun restoreBackup(uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: return@withContext "Не удалось прочитать файл"

            val file = json.decodeFromString<BackupFile>(text)
            if (file.format != FORMAT) {
                AppLogger.w(TAG, "Restore rejected: format='${file.format}'")
                return@withContext "Это не резервная копия FamilyTasks"
            }
            if (file.version > VERSION) {
                AppLogger.w(TAG, "Restore rejected: version ${file.version} > $VERSION")
                return@withContext "Копия создана более новой версией приложения"
            }

            val content = BackupContent(
                selfDeviceId = file.self.deviceId,
                partner = file.partner?.toDomain(),
                tasks = file.tasks.map { it.task.toDomain().copy(isSecret = it.isSecret) }
            )
            val result = restoreBackupUseCase(content)
            "Восстановлено: новых ${result.added}, обновлено ${result.updated}, без изменений ${result.unchanged}"
        } catch (e: BackupFromPartnerException) {
            AppLogger.w(TAG, "Restore rejected: backup belongs to paired device")
            e.message ?: "Ошибка восстановления"
        } catch (e: Exception) {
            AppLogger.e(TAG, "Restore failed", e)
            "Ошибка восстановления: ${e.message}"
        }
    }

    companion object {
        private const val TAG = "Backup"
        private const val FORMAT = "familytasks-backup"
        private const val VERSION = 1
    }
}
