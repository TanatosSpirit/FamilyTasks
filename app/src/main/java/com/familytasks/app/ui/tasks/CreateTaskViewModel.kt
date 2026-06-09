package com.familytasks.app.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familytasks.app.core.DeviceIdManager
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.domain.model.Device
import com.familytasks.app.domain.model.Priority
import com.familytasks.app.domain.model.Task
import com.familytasks.app.domain.model.TaskStatus
import com.familytasks.app.domain.repository.DeviceRepository
import com.familytasks.app.domain.repository.TaskRepository
import com.familytasks.app.notifications.NotificationScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class CreateTaskViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val deviceRepository: DeviceRepository,
    private val deviceIdManager: DeviceIdManager,
    private val prefs: PreferencesManager,
    private val notificationScheduler: NotificationScheduler
) : ViewModel() {

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices

    init {
        viewModelScope.launch {
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

    fun save(
        title: String,
        description: String?,
        priority: Priority,
        assignedTo: String?,
        dueDate: Long?,
        onDone: () -> Unit
    ) {
        val now = System.currentTimeMillis()
        val task = Task(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            description = description?.trim()?.ifBlank { null },
            assignedTo = assignedTo,
            status = TaskStatus.TODO,
            priority = priority,
            dueDate = dueDate,
            createdBy = deviceIdManager.deviceId,
            createdAt = now,
            updatedAt = now
        )
        viewModelScope.launch {
            taskRepository.upsertTask(task)
            if (task.assignedTo == deviceIdManager.deviceId) {
                notificationScheduler.scheduleDueNotifications(task)
            }
            launch(kotlinx.coroutines.Dispatchers.Main) { onDone() }
        }
    }

    val myDeviceId: String get() = deviceIdManager.deviceId
}
