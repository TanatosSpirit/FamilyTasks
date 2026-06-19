package com.familytasks.app.domain.repository

import com.familytasks.app.domain.model.Task
import kotlinx.coroutines.flow.Flow

interface TaskRepository {
    fun observeActiveTasks(): Flow<List<Task>>
    fun observeAllTasks(): Flow<List<Task>>
    suspend fun getTaskById(id: String): Task?
    suspend fun upsertTask(task: Task)
    suspend fun upsertTasks(tasks: List<Task>)
    suspend fun deleteTask(id: String, updatedAt: Long)
    suspend fun getAllTasksSnapshot(): List<Task>
    suspend fun purgeTasks(ids: List<String>)
}
