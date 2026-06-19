package com.familytasks.app.data.repository

import com.familytasks.app.data.local.TaskDao
import com.familytasks.app.data.local.toDomain
import com.familytasks.app.data.local.toEntity
import com.familytasks.app.domain.model.Task
import com.familytasks.app.domain.repository.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class TaskRepositoryImpl @Inject constructor(
    private val dao: TaskDao
) : TaskRepository {

    override fun observeActiveTasks(): Flow<List<Task>> =
        dao.observeActiveTasks().map { list -> list.map { it.toDomain() } }

    override fun observeAllTasks(): Flow<List<Task>> =
        dao.observeAllTasks().map { list -> list.map { it.toDomain() } }

    override suspend fun getTaskById(id: String): Task? =
        dao.getById(id)?.toDomain()

    override suspend fun upsertTask(task: Task) =
        dao.upsert(task.toEntity())

    override suspend fun upsertTasks(tasks: List<Task>) =
        dao.upsertAll(tasks.map { it.toEntity() })

    override suspend fun deleteTask(id: String, updatedAt: Long) =
        dao.softDelete(id, updatedAt)

    override suspend fun getAllTasksSnapshot(): List<Task> =
        dao.getAll().map { it.toDomain() }

    override suspend fun purgeTasks(ids: List<String>) =
        dao.purgeByIds(ids)
}
