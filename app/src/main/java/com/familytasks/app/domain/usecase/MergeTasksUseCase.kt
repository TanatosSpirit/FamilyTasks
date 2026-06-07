package com.familytasks.app.domain.usecase

import com.familytasks.app.domain.model.Task
import com.familytasks.app.domain.repository.TaskRepository
import javax.inject.Inject

class MergeTasksUseCase @Inject constructor(
    private val taskRepository: TaskRepository
) {
    suspend operator fun invoke(remoteTasks: List<Task>): List<Task> {
        val localTasks = taskRepository.getAllTasksSnapshot().associateBy { it.id }.toMutableMap()

        for (remote in remoteTasks) {
            val local = localTasks[remote.id]
            when {
                local == null -> localTasks[remote.id] = remote
                remote.updatedAt > local.updatedAt -> localTasks[remote.id] = remote
                // tie-break: keep local
            }
        }

        val merged = localTasks.values.toList()
        taskRepository.upsertTasks(merged)
        return merged
    }
}
