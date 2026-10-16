package com.familytasks.app.data.repository

import com.familytasks.app.data.local.DeviceDao
import com.familytasks.app.data.local.toDomain
import com.familytasks.app.data.local.toEntity
import com.familytasks.app.domain.model.Device
import com.familytasks.app.domain.repository.DeviceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class DeviceRepositoryImpl @Inject constructor(
    private val dao: DeviceDao
) : DeviceRepository {

    override fun observeDevices(): Flow<List<Device>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getDeviceById(id: String): Device? =
        dao.getById(id)?.toDomain()

    override suspend fun upsertDevice(device: Device) =
        dao.upsert(device.toEntity())

    override suspend fun getAllDevices(): List<Device> =
        dao.getAll().map { it.toDomain() }

    override suspend fun deleteDevices(ids: List<String>) =
        dao.deleteByIds(ids)
}
