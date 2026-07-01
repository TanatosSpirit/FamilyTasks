package com.familytasks.app.ui.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familytasks.app.core.DeviceIdManager
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.data.sync.ExportImportManager
import com.familytasks.app.data.sync.SyncManager
import com.familytasks.app.domain.model.Device
import com.familytasks.app.domain.repository.DeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val hideSecretTasks: Boolean = false,
    val showPendingSyncIndicator: Boolean = false,
    val webUiEnabled: Boolean = false,
    val ownIp: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: PreferencesManager,
    private val deviceIdManager: DeviceIdManager,
    private val deviceRepository: DeviceRepository,
    private val syncManager: SyncManager,
    private val exportImportManager: ExportImportManager
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
                hideSecretTasks = prefs.hideSecretTasks,
                showPendingSyncIndicator = prefs.showPendingSyncIndicator,
                webUiEnabled = prefs.webUiEnabled,
                ownIp = prefs.lastKnownOwnIp
            )
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

    fun setHideSecretTasks(v: Boolean) {
        prefs.hideSecretTasks = v
        _state.update { it.copy(hideSecretTasks = v) }
    }

    fun setShowPendingSyncIndicator(v: Boolean) {
        prefs.showPendingSyncIndicator = v
        _state.update { it.copy(showPendingSyncIndicator = v) }
    }

    fun setWebUiEnabled(v: Boolean) {
        prefs.webUiEnabled = v
        _state.update { it.copy(webUiEnabled = v, ownIp = prefs.lastKnownOwnIp) }
    }

    val deviceShortId: String get() = deviceIdManager.deviceId.take(8)

    fun buildExportIntent(onIntent: (Intent) -> Unit) {
        viewModelScope.launch {
            onIntent(exportImportManager.buildShareIntent())
        }
    }
}
