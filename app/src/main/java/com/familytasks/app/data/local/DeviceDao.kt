package com.familytasks.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE deviceId = :id LIMIT 1")
    suspend fun getById(id: String): DeviceEntity?

    @Query("SELECT * FROM devices")
    suspend fun getAll(): List<DeviceEntity>

    @Upsert
    suspend fun upsert(device: DeviceEntity)

    @Query("DELETE FROM devices WHERE deviceId IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}
