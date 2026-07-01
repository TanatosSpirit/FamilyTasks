package com.familytasks.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.familytasks.app.domain.model.TaskSyncConfirmation

@Entity(tableName = "task_sync_state")
data class TaskSyncStateEntity(
    @PrimaryKey val taskId: String,
    val confirmedUpdatedAt: Long
)

fun TaskSyncStateEntity.toDomain() = TaskSyncConfirmation(
    taskId = taskId,
    confirmedUpdatedAt = confirmedUpdatedAt
)

fun TaskSyncConfirmation.toEntity() = TaskSyncStateEntity(
    taskId = taskId,
    confirmedUpdatedAt = confirmedUpdatedAt
)
