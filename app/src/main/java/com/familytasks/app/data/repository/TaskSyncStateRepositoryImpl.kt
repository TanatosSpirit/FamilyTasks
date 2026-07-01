package com.familytasks.app.data.repository

import com.familytasks.app.data.local.TaskSyncStateDao
import com.familytasks.app.data.local.toDomain
import com.familytasks.app.data.local.toEntity
import com.familytasks.app.domain.model.TaskSyncConfirmation
import com.familytasks.app.domain.repository.TaskSyncStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class TaskSyncStateRepositoryImpl @Inject constructor(
    private val dao: TaskSyncStateDao
) : TaskSyncStateRepository {

    override fun observeAll(): Flow<List<TaskSyncConfirmation>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun confirm(entries: List<TaskSyncConfirmation>) =
        dao.upsertAll(entries.map { it.toEntity() })

    override suspend fun deleteByIds(ids: List<String>) =
        dao.deleteByIds(ids)
}
