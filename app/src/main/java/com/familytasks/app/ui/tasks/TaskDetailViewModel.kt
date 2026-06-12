package com.familytasks.app.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familytasks.app.core.DeviceIdManager
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.domain.model.Device
import com.familytasks.app.domain.model.Task
import com.familytasks.app.domain.model.TaskStatus
import com.familytasks.app.domain.repository.DeviceRepository
import com.familytasks.app.domain.repository.TaskRepository
import com.familytasks.app.notifications.NotificationScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TaskDetailViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val deviceRepository: DeviceRepository,
    private val deviceIdManager: DeviceIdManager,
    private val prefs: PreferencesManager,
    private val notificationScheduler: NotificationScheduler
) : ViewModel() {

    private val _task = MutableStateFlow<Task?>(null)
    val task: StateFlow<Task?> = _task

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices

    fun load(taskId: String) {
        viewModelScope.launch {
            _task.value = taskRepository.getTaskById(taskId)
            val me = Device(
                deviceId = deviceIdManager.deviceId,
                displayName = prefs.displayName.ifBlank { "Я" },
                color = prefs.avatarColor
            )
            val others = deviceRepository.getAllDevices()
                .filter { it.deviceId != deviceIdManager.deviceId }
            _devices.value = listOf(me) + others
        }
    }

    fun cycleStatus() {
        val current = _task.value ?: return
        val now = System.currentTimeMillis()
        val next = when (current.status) {
            TaskStatus.TODO -> TaskStatus.IN_PROGRESS
            TaskStatus.IN_PROGRESS -> TaskStatus.DONE
            TaskStatus.DONE -> TaskStatus.TODO
        }
        val completedAt = if (next == TaskStatus.DONE) now else null
        update(current.copy(status = next, updatedAt = now, completedAt = completedAt))
    }

    fun delete(onDone: () -> Unit) {
        val task = _task.value ?: return
        viewModelScope.launch {
            taskRepository.deleteTask(task.id, System.currentTimeMillis())
            launch(kotlinx.coroutines.Dispatchers.Main) { onDone() }
        }
    }

    fun updateTask(task: Task, onDone: () -> Unit) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val completedAt = when {
                task.status == TaskStatus.DONE && _task.value?.status != TaskStatus.DONE -> now
                task.status != TaskStatus.DONE -> null
                else -> task.completedAt
            }
            val updated = task.copy(updatedAt = now, completedAt = completedAt)
            taskRepository.upsertTask(updated)
            _task.value = updated
            if (updated.assignedTo == deviceIdManager.deviceId) {
                notificationScheduler.scheduleDueNotifications(updated)
            } else {
                notificationScheduler.cancelDueNotifications(updated.id)
            }
            launch(kotlinx.coroutines.Dispatchers.Main) { onDone() }
        }
    }

    private fun update(task: Task) {
        viewModelScope.launch {
            taskRepository.upsertTask(task)
            _task.value = task
        }
    }

    val myDeviceId: String get() = deviceIdManager.deviceId
}
