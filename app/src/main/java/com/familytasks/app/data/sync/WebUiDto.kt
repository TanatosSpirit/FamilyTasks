package com.familytasks.app.data.sync

import kotlinx.serialization.Serializable

@Serializable
data class WebCreateTaskRequest(
    val title: String,
    val description: String? = null,
    val priority: String = "normal",
    val dueDate: Long? = null,
    val assignedTo: String? = null
)
