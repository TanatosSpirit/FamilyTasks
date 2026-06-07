package com.familytasks.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familytasks.app.core.DeviceIdManager
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.domain.model.Device
import com.familytasks.app.domain.repository.DeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

@Serializable
data class QrDeviceInfo(val deviceId: String, val displayName: String, val color: Int)

@HiltViewModel
class QrCodeViewModel @Inject constructor(
    private val prefs: PreferencesManager,
    private val deviceIdManager: DeviceIdManager,
    private val deviceRepository: DeviceRepository
) : ViewModel() {

    private val _paired = MutableStateFlow(false)
    val paired: StateFlow<Boolean> = _paired

    val ownQrContent: String
        get() = Json.encodeToString(
            QrDeviceInfo(
                deviceId = deviceIdManager.deviceId,
                displayName = prefs.displayName,
                color = prefs.avatarColor
            )
        )

    fun onQrScanned(content: String, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                val info = Json.decodeFromString<QrDeviceInfo>(content)
                deviceRepository.upsertDevice(
                    Device(deviceId = info.deviceId, displayName = info.displayName, color = info.color)
                )
                prefs.pairedDeviceId = info.deviceId
                _paired.value = true
                onDone()
            } catch (_: Exception) {}
        }
    }

    fun skip(onDone: () -> Unit) = onDone()
}
