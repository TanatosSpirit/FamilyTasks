package com.familytasks.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.familytasks.app.domain.model.Priority
import com.familytasks.app.domain.model.Recurrence
import com.familytasks.app.domain.model.Task
import com.familytasks.app.domain.model.TaskStatus

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    val assignedTo: String?,
    val status: String,
    val priority: String,
    val dueDate: Long?,
    val createdBy: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
    val completedAt: Long? = null,
    val repeatRule: String = "none",
    val isSecret: Boolean = false
)

fun TaskEntity.toDomain() = Task(
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
    recurrence = Recurrence.parse(repeatRule),
    isSecret = isSecret
)

fun Task.toEntity() = TaskEntity(
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
    repeatRule = recurrence.serialize(),
    isSecret = isSecret
)
