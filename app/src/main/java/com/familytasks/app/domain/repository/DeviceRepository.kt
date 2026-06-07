package com.familytasks.app.domain.repository

import com.familytasks.app.domain.model.Device
import kotlinx.coroutines.flow.Flow

interface DeviceRepository {
    fun observeDevices(): Flow<List<Device>>
    suspend fun getDeviceById(id: String): Device?
    suspend fun upsertDevice(device: Device)
    suspend fun getAllDevices(): List<Device>
}
