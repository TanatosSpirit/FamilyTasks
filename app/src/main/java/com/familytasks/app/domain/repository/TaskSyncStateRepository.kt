package com.familytasks.app.domain.repository

import com.familytasks.app.domain.model.TaskSyncConfirmation
import kotlinx.coroutines.flow.Flow

interface TaskSyncStateRepository {
    fun observeAll(): Flow<List<TaskSyncConfirmation>>
    suspend fun confirm(entries: List<TaskSyncConfirmation>)
    suspend fun deleteByIds(ids: List<String>)
}
