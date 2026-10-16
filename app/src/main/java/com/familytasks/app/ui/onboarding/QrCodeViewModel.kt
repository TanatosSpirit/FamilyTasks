package com.familytasks.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familytasks.app.core.AppLogger
import com.familytasks.app.core.DeviceIdManager
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.domain.model.Device
import com.familytasks.app.domain.usecase.PairDeviceUseCase
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

/** Сопряжение, ожидающее подтверждения: задачи [formerPartners] перейдут к [newPartner]. */
data class PendingPairing(val newPartner: Device, val formerPartners: List<Device>)

@HiltViewModel
class QrCodeViewModel @Inject constructor(
    private val prefs: PreferencesManager,
    private val deviceIdManager: DeviceIdManager,
    private val pairDeviceUseCase: PairDeviceUseCase
) : ViewModel() {

    private val _paired = MutableStateFlow(false)
    val paired: StateFlow<Boolean> = _paired

    private val _pendingPairing = MutableStateFlow<PendingPairing?>(null)
    val pendingPairing: StateFlow<PendingPairing?> = _pendingPairing

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
                if (info.deviceId == deviceIdManager.deviceId) {
                    AppLogger.w(TAG, "Scanned own QR code — ignoring")
                    return@launch
                }
                val newPartner = Device(deviceId = info.deviceId, displayName = info.displayName, color = info.color)
                val former = pairDeviceUseCase.formerPartners(newPartner.deviceId)
                if (former.isEmpty()) {
                    completePairing(newPartner, onDone)
                } else {
                    _pendingPairing.value = PendingPairing(newPartner, former)
                }
            } catch (_: Exception) {}
        }
    }

    fun confirmPairing(onDone: () -> Unit) {
        val pending = _pendingPairing.value ?: return
        _pendingPairing.value = null
        viewModelScope.launch { completePairing(pending.newPartner, onDone) }
    }

    fun cancelPairing() {
        _pendingPairing.value = null
    }

    private suspend fun completePairing(newPartner: Device, onDone: () -> Unit) {
        pairDeviceUseCase.pair(newPartner)
        _paired.value = true
        onDone()
    }

    fun skip(onDone: () -> Unit) = onDone()

    companion object {
        private const val TAG = "QrCode"
    }
}
