package com.familytasks.app.core

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceIdManager @Inject constructor(
    private val prefs: PreferencesManager
) {
    val deviceId: String
        get() = prefs.deviceId ?: generateAndStore()

    private fun generateAndStore(): String {
        val id = UUID.randomUUID().toString()
        prefs.deviceId = id
        return id
    }
}
