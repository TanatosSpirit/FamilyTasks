package com.familytasks.app.ui.onboarding

import androidx.lifecycle.ViewModel
import com.familytasks.app.core.DeviceIdManager
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.domain.model.Device
import com.familytasks.app.domain.repository.DeviceRepository
import com.familytasks.app.ui.theme.AvatarColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val prefs: PreferencesManager,
    private val deviceIdManager: DeviceIdManager,
    private val deviceRepository: DeviceRepository
) : ViewModel() {

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name

    private val _selectedColor = MutableStateFlow(AvatarColors[0])
    val selectedColor: StateFlow<Int> = _selectedColor

    fun setName(value: String) { _name.value = value }
    fun setColor(color: Int) { _selectedColor.value = color }

    fun complete(onDone: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            prefs.displayName = _name.value.trim()
            prefs.avatarColor = _selectedColor.value
            prefs.isOnboardingComplete = true
            deviceRepository.upsertDevice(
                Device(
                    deviceId = deviceIdManager.deviceId,
                    displayName = prefs.displayName,
                    color = prefs.avatarColor
                )
            )
            launch(Dispatchers.Main) { onDone() }
        }
    }

    val deviceShortId: String
        get() = deviceIdManager.deviceId.take(8)
}
