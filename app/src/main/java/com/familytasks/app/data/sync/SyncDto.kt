package com.familytasks.app.data.sync

import com.familytasks.app.domain.model.Device
import com.familytasks.app.domain.model.Priority
import com.familytasks.app.domain.model.Recurrence
import com.familytasks.app.domain.model.Task
import com.familytasks.app.domain.model.TaskStatus
import kotlinx.serialization.Serializable

@Serializable
data class SyncPayload(
    val deviceId: String,
    val tasks: List<TaskDto>,
    val devices: List<DeviceDto>
)

@Serializable
data class TaskDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val assignedTo: String? = null,
    val status: String,
    val priority: String,
    val dueDate: Long? = null,
    val createdBy: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val completedAt: Long? = null,
    val repeatRule: String = "none"
)

@Serializable
data class DeviceDto(
    val deviceId: String,
    val displayName: String,
    val color: Int
)

fun Task.toDto() = TaskDto(
    id = id,
    title = title,
    description = description,
    assignedTo = assignedTo,
    status = status.value,
    priority = priority.value,
    dueDate = dueDate,
    createdBy = createdBy,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deleted = deleted,
    completedAt = completedAt,
    repeatRule = recurrence.serialize()
)

fun TaskDto.toDomain() = Task(
    id = id,
    title = title,
    description = description,
    assignedTo = assignedTo,
    status = TaskStatus.fromString(status),
    priority = Priority.fromString(priority),
    dueDate = dueDate,
    createdBy = createdBy,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deleted = deleted,
    completedAt = completedAt,
    recurrence = Recurrence.parse(repeatRule)
)

fun Device.toDto() = DeviceDto(deviceId = deviceId, displayName = displayName, color = color)

fun DeviceDto.toDomain() = Device(deviceId = deviceId, displayName = displayName, color = color)

@Serializable
data class IdentifyResponse(val deviceId: String)
