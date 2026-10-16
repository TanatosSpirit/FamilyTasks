package com.familytasks.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familytasks.app.core.AppLogger
import com.familytasks.app.core.DeviceIdManager
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.data.backup.BackupManager
import com.familytasks.app.data.sync.ExportImportManager
import com.familytasks.app.data.sync.SyncManager
import com.familytasks.app.data.sync.SyncService
import com.familytasks.app.domain.model.Device
import com.familytasks.app.domain.repository.DeviceRepository
import com.familytasks.app.domain.usecase.FormerSelfUseCase
import com.familytasks.app.domain.usecase.OrphanDevice
import com.familytasks.app.notifications.NotificationScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val myDevice: Device? = null,
    val pairedDevice: Device? = null,
    val notifyDueDate: Boolean = true,
    val notifyNewTask: Boolean = true,
    val notifyStatusChange: Boolean = true,
    val lastSyncAt: Long = 0L,
    val showSecretTasks: Boolean = false,
    val showPendingSyncIndicator: Boolean = false,
    val webUiEnabled: Boolean = false,
    val syncPaused: Boolean = false,
    val ownIp: String? = null,
    val dailySummaryEnabled: Boolean = false,
    val dailySummaryHour: Int = 8,
    val dailySummaryMinute: Int = 0,
    val orphanDevices: List<OrphanDevice> = emptyList()
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesManager,
    private val deviceIdManager: DeviceIdManager,
    private val deviceRepository: DeviceRepository,
    private val syncManager: SyncManager,
    private val exportImportManager: ExportImportManager,
    private val backupManager: BackupManager,
    private val formerSelfUseCase: FormerSelfUseCase,
    private val notificationScheduler: NotificationScheduler
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state

    init {
        load()
        viewModelScope.launch {
            syncManager.lastSyncAt.collect { ts ->
                _state.update { it.copy(lastSyncAt = ts) }
            }
        }
        // Из flow, а не разовым чтением: флаг сбрасывается извне при уходе UI в фон.
        viewModelScope.launch {
            prefs.showSecretTasksFlow.collect { v ->
                _state.update { it.copy(showSecretTasks = v) }
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            val my = deviceRepository.getDeviceById(deviceIdManager.deviceId)
            val paired = prefs.pairedDeviceId?.let { deviceRepository.getDeviceById(it) }
            _state.value = SettingsUiState(
                myDevice = my,
                pairedDevice = paired,
                notifyDueDate = prefs.notifyDueDate,
                notifyNewTask = prefs.notifyNewTask,
                notifyStatusChange = prefs.notifyStatusChange,
                lastSyncAt = prefs.lastSyncAt,
                showSecretTasks = prefs.showSecretTasksFlow.value,
                showPendingSyncIndicator = prefs.showPendingSyncIndicator,
                webUiEnabled = prefs.webUiEnabled,
                syncPaused = prefs.syncPaused,
                ownIp = prefs.lastKnownOwnIp,
                dailySummaryEnabled = prefs.dailySummaryEnabled,
                dailySummaryHour = prefs.dailySummaryHour,
                dailySummaryMinute = prefs.dailySummaryMinute,
                orphanDevices = formerSelfUseCase.orphans()
            )
        }
    }

    fun claimFormerSelf(deviceId: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val changed = formerSelfUseCase.claim(deviceId)
            onResult("Задач перенесено на вас: $changed")
            load()
        }
    }

    fun setNotifyDueDate(v: Boolean) {
        prefs.notifyDueDate = v
        _state.update { it.copy(notifyDueDate = v) }
    }

    fun setNotifyNewTask(v: Boolean) {
        prefs.notifyNewTask = v
        _state.update { it.copy(notifyNewTask = v) }
    }

    fun setNotifyStatusChange(v: Boolean) {
        prefs.notifyStatusChange = v
        _state.update { it.copy(notifyStatusChange = v) }
    }

    fun setShowSecretTasks(v: Boolean) {
        prefs.showSecretTasksFlow.value = v
    }

    fun setShowPendingSyncIndicator(v: Boolean) {
        prefs.showPendingSyncIndicator = v
        _state.update { it.copy(showPendingSyncIndicator = v) }
    }

    fun setWebUiEnabled(v: Boolean) {
        prefs.webUiEnabled = v
        _state.update { it.copy(webUiEnabled = v, ownIp = prefs.lastKnownOwnIp) }
        if (v) {
            ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java))
        } else if (prefs.syncPaused) {
            context.stopService(Intent(context, SyncService::class.java))
        }
    }

    fun setSyncPaused(v: Boolean) {
        prefs.syncPaused = v
        _state.update { it.copy(syncPaused = v) }
        if (v) {
            AppLogger.i(TAG, "Sync paused by user")
            SyncService.cancelKeepAliveAlarm(context)
            if (!prefs.webUiEnabled) {
                context.stopService(Intent(context, SyncService::class.java))
            }
        } else {
            AppLogger.i(TAG, "Sync resumed by user")
            ContextCompat.startForegroundService(
                context,
                Intent(context, SyncService::class.java).apply { action = SyncService.ACTION_SYNC_NOW }
            )
        }
    }

    fun setDailySummaryEnabled(v: Boolean) {
        prefs.dailySummaryEnabled = v
        _state.update { it.copy(dailySummaryEnabled = v) }
        if (v) {
            notificationScheduler.scheduleDailySummaryAlarm(prefs.dailySummaryHour, prefs.dailySummaryMinute)
        } else {
            notificationScheduler.cancelDailySummaryAlarm()
        }
    }

    fun setDailySummaryTime(hour: Int, minute: Int) {
        prefs.dailySummaryHour = hour
        prefs.dailySummaryMinute = minute
        _state.update { it.copy(dailySummaryHour = hour, dailySummaryMinute = minute) }
        if (_state.value.dailySummaryEnabled) {
            notificationScheduler.scheduleDailySummaryAlarm(hour, minute)
        }
    }

    val deviceShortId: String get() = deviceIdManager.deviceId.take(8)

    fun buildExportIntent(onIntent: (Intent) -> Unit) {
        viewModelScope.launch {
            onIntent(exportImportManager.buildShareIntent())
        }
    }

    fun backupFileName(): String = backupManager.suggestedFileName()

    fun saveBackup(uri: Uri, onResult: (String) -> Unit) {
        viewModelScope.launch { onResult(backupManager.writeBackup(uri)) }
    }

    fun restoreBackup(uri: Uri, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(backupManager.restoreBackup(uri))
            // Восстановление могло добавить запись о партнёре в devices.
            load()
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"
    }
}
