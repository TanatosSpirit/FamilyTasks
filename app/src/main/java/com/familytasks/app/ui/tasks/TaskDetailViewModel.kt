package com.familytasks.app.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familytasks.app.core.AppLogger
import com.familytasks.app.core.DeviceIdManager
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.domain.model.Device
import com.familytasks.app.domain.model.Task
import com.familytasks.app.domain.model.TaskStatus
import com.familytasks.app.domain.model.Recurrence
import com.familytasks.app.domain.repository.DeviceRepository
import com.familytasks.app.domain.repository.TaskRepository
import com.familytasks.app.domain.usecase.RecurringTaskCompletionUseCase
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
    private val notificationScheduler: NotificationScheduler,
    private val recurringCompletion: RecurringTaskCompletionUseCase
) : ViewModel() {

    private val _task = MutableStateFlow<Task?>(null)
    val task: StateFlow<Task?> = _task

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices

    private val _showRecurrenceChoice = MutableStateFlow(false)
    val showRecurrenceChoice: StateFlow<Boolean> = _showRecurrenceChoice

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
            TaskStatus.TODO -> TaskStatus.DONE
            TaskStatus.DONE -> TaskStatus.TODO
        }
        val completedAt = if (next == TaskStatus.DONE) now else null
        val updated = current.copy(status = next, updatedAt = now, completedAt = completedAt)
        update(updated)
        applyDueNotificationState(updated, "cycleStatus")
    }

    fun toggleChecklistItem(index: Int) {
        val current = _task.value ?: return
        val items = current.checklist.toMutableList()
        if (index !in items.indices) return
        items[index] = items[index].copy(checked = !items[index].checked)
        // Условие due-уведомлений (assignedTo/status/deleted) чек-листом не затрагивается —
        // applyDueNotificationState здесь не нужен.
        update(current.copy(checklist = items, updatedAt = System.currentTimeMillis()))
    }

    fun onCompleteButtonClicked() {
        val current = _task.value ?: return
        if (current.status == TaskStatus.TODO && isRecurringEligible(current)) {
            _showRecurrenceChoice.value = true
        } else {
            cycleStatus()
        }
    }

    fun dismissRecurrenceChoice() {
        _showRecurrenceChoice.value = false
    }

    fun confirmCompleteChain() {
        val current = _task.value ?: return
        viewModelScope.launch {
            _task.value = recurringCompletion.completeEndingChain(current)
            _showRecurrenceChoice.value = false
        }
    }

    fun confirmCompleteAndContinue() {
        val current = _task.value ?: return
        viewModelScope.launch {
            _task.value = recurringCompletion.completeAndContinue(current)
            _showRecurrenceChoice.value = false
        }
    }

    private fun isRecurringEligible(task: Task) =
        task.recurrence != Recurrence.None && task.dueDate != null &&
            task.assignedTo == deviceIdManager.deviceId

    fun delete(onDone: () -> Unit) {
        val task = _task.value ?: return
        viewModelScope.launch {
            taskRepository.deleteTask(task.id, System.currentTimeMillis())
            AppLogger.d(TAG, "delete: '${task.title}' deleted — cancelling due reminders + recurrence")
            notificationScheduler.cancelDueNotifications(task.id)
            notificationScheduler.cancelRecurrenceSpawn(task.id)
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
            applyDueNotificationState(updated, "updateTask")
            launch(kotlinx.coroutines.Dispatchers.Main) { onDone() }
        }
    }

    private fun applyDueNotificationState(updated: Task, source: String) {
        val eligible = updated.assignedTo == deviceIdManager.deviceId &&
            updated.status != TaskStatus.DONE &&
            !updated.deleted
        if (eligible) {
            AppLogger.d(TAG, "$source: '${updated.title}' active and assigned to me — scheduling due reminders + recurrence")
            notificationScheduler.scheduleDueNotifications(updated)
            // scheduleRecurrenceSpawn сам no-op, если recurrence == None или нет дедлайна
            notificationScheduler.scheduleRecurrenceSpawn(updated)
        } else {
            AppLogger.d(
                TAG,
                "$source: '${updated.title}' not active+mine (assignedTo=${if (updated.assignedTo == deviceIdManager.deviceId) "me" else "other"}, " +
                    "status=${updated.status}, deleted=${updated.deleted}) — cancelling due reminders + recurrence"
            )
            notificationScheduler.cancelDueNotifications(updated.id)
            notificationScheduler.cancelRecurrenceSpawn(updated.id)
        }
    }

    private fun update(task: Task) {
        viewModelScope.launch {
            taskRepository.upsertTask(task)
            _task.value = task
        }
    }

    val myDeviceId: String get() = deviceIdManager.deviceId

    companion object {
        private const val TAG = "TaskDetailViewModel"
    }
}
