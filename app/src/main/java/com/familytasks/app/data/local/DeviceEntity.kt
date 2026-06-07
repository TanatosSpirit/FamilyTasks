package com.familytasks.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.familytasks.app.domain.model.Device

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val color: Int,
    val lastSeenAt: Long?
)

fun DeviceEntity.toDomain() = Device(
    deviceId = deviceId,
    displayName = displayName,
    color = color,
    lastSeenAt = lastSeenAt
)

fun Device.toEntity() = DeviceEntity(
    deviceId = deviceId,
    displayName = displayName,
    color = color,
    lastSeenAt = lastSeenAt
)
