package com.familytasks.app.domain.model

data class TaskSyncConfirmation(
    val taskId: String,
    val confirmedUpdatedAt: Long
)
