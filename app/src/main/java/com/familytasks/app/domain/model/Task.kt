package com.familytasks.app.domain.model

data class Task(
    val id: String,
    val title: String,
    val description: String? = null,
    val assignedTo: String? = null,
    val status: TaskStatus = TaskStatus.TODO,
    val priority: Priority = Priority.NORMAL,
    val dueDate: Long? = null,
    val createdBy: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val completedAt: Long? = null,
    val recurrence: Recurrence = Recurrence.None,
    val isSecret: Boolean = false
)
