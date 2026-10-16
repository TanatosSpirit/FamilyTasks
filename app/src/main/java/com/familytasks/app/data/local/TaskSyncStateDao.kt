package com.familytasks.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskSyncStateDao {
    @Query("SELECT * FROM task_sync_state")
    fun observeAll(): Flow<List<TaskSyncStateEntity>>

    @Upsert
    suspend fun upsertAll(entries: List<TaskSyncStateEntity>)

    @Query("DELETE FROM task_sync_state WHERE taskId IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM task_sync_state")
    suspend fun deleteAll()
}
