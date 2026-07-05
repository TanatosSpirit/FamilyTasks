package com.familytasks.app.ui.tasks

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familytasks.app.core.AppLogger
import com.familytasks.app.core.DeviceIdManager
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.data.sync.SyncManager
import com.familytasks.app.notifications.NotificationScheduler
import kotlinx.coroutines.launch
import com.familytasks.app.data.sync.SyncState
import com.familytasks.app.domain.model.AssigneeFilter
import com.familytasks.app.domain.model.Device
import com.familytasks.app.domain.model.Priority
import com.familytasks.app.domain.model.Recurrence
import com.familytasks.app.domain.model.SortOrder
import com.familytasks.app.domain.model.StatusFilter
import com.familytasks.app.domain.model.Task
import com.familytasks.app.domain.model.TaskStatus
import com.familytasks.app.domain.repository.DeviceRepository
import com.familytasks.app.domain.repository.TaskRepository
import com.familytasks.app.domain.repository.TaskSyncStateRepository
import com.familytasks.app.domain.usecase.RecurringTaskCompletionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class TaskListUiState(
    val tasks: List<Task> = emptyList(),
    val devices: Map<String, Device> = emptyMap(),
    val assigneeFilter: AssigneeFilter = AssigneeFilter.ALL,
    val statusFilter: StatusFilter = StatusFilter.ACTIVE,
    val sortOrder: SortOrder = SortOrder.DUE_DATE,
    val syncState: SyncState = SyncState.IDLE,
    val lastSyncAt: Long = 0L,
    val peerOnline: Boolean = false,
    val wifiConnected: Boolean = true,
    val myName: String = "",
    val peerName: String? = null,
    val hasPairedDevice: Boolean = false,
    val showPendingSyncIndicator: Boolean = false,
    val confirmedSyncMap: Map<String, Long> = emptyMap(),
    val syncPaused: Boolean = false
)

private data class Filters(
    val assigneeFilter: AssigneeFilter,
    val statusFilter: StatusFilter,
    val sortOrder: SortOrder,
    val hideSecretTasks: Boolean
)

private data class SyncStatus(
    val state: SyncState,
    val lastSyncAt: Long,
    val peerIp: String?,
    val wifiConnected: Boolean,
    val syncPaused: Boolean
)

private data class SyncAndIndicator(
    val sync: SyncStatus,
    val showPendingSyncIndicator: Boolean,
    val confirmedSyncMap: Map<String, Long>
)

@HiltViewModel
class TaskListViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskRepository: TaskRepository,
    private val deviceRepository: DeviceRepository,
    private val taskSyncStateRepository: TaskSyncStateRepository,
    private val syncManager: SyncManager,
    val deviceIdManager: DeviceIdManager,
    val prefs: PreferencesManager,
    private val notificationScheduler: NotificationScheduler,
    private val recurringCompletion: RecurringTaskCompletionUseCase
) : ViewModel() {

    private val _pendingRecurrenceTask = MutableStateFlow<Task?>(null)
    val pendingRecurrenceTask: StateFlow<Task?> = _pendingRecurrenceTask

    private val _assigneeFilter = MutableStateFlow(AssigneeFilter.ALL)
    private val _statusFilter = MutableStateFlow(StatusFilter.ACTIVE)
    private val _sortOrder = MutableStateFlow(SortOrder.DUE_DATE)

    private val _wifiConnected = MutableStateFlow(true)

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshWifiState()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refreshWifiState()
        override fun onLost(network: Network) = refreshWifiState()
    }

    init {
        cm.registerDefaultNetworkCallback(networkCallback)
        refreshWifiState()
    }

    override fun onCleared() {
        super.onCleared()
        cm.unregisterNetworkCallback(networkCallback)
    }

    private fun refreshWifiState() {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        _wifiConnected.value = caps != null &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
             caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    private val filters = combine(_assigneeFilter, _statusFilter, _sortOrder, prefs.hideSecretTasksFlow) { a, s, o, h ->
        Filters(a, s, o, h)
    }

    private val syncStatus = combine(
        syncManager.syncState,
        syncManager.lastSyncAt,
        syncManager.peerIp,
        _wifiConnected,
        prefs.syncPausedFlow
    ) { st, ts, ip, wifi, paused ->
        SyncStatus(st, ts, ip, wifi, paused)
    }

    private val syncAndIndicator = combine(
        syncStatus,
        prefs.showPendingSyncIndicatorFlow,
        taskSyncStateRepository.observeAll()
    ) { sync, showPendingSyncIndicator, confirmations ->
        SyncAndIndicator(
            sync = sync,
            showPendingSyncIndicator = showPendingSyncIndicator,
            confirmedSyncMap = confirmations.associate { it.taskId to it.confirmedUpdatedAt }
        )
    }

    val uiState: StateFlow<TaskListUiState> = combine(
        taskRepository.observeAllTasks(),
        deviceRepository.observeDevices(),
        filters,
        syncAndIndicator
    ) { rawTasks, deviceList, f, syncInd ->
        val deviceMap = deviceList.associateBy { it.deviceId }
        val filtered = rawTasks
            .filter { !it.deleted }
            .filter { task -> !f.hideSecretTasks || !task.isSecret }
            .filter { task ->
                when (f.statusFilter) {
                    StatusFilter.ACTIVE -> task.status != TaskStatus.DONE
                    StatusFilter.DONE -> task.status == TaskStatus.DONE
                    StatusFilter.ALL -> true
                }
            }
            .filter { task ->
                when (f.assigneeFilter) {
                    AssigneeFilter.ALL -> true
                    AssigneeFilter.MINE -> task.assignedTo == deviceIdManager.deviceId
                    AssigneeFilter.OTHERS -> task.assignedTo != null && task.assignedTo != deviceIdManager.deviceId
                }
            }
            .sortedWith(
                if (f.statusFilter == StatusFilter.DONE) {
                    compareByDescending { it.completedAt ?: it.updatedAt }
                } else {
                    when (f.sortOrder) {
                        SortOrder.DUE_DATE -> compareBy { it.dueDate ?: Long.MAX_VALUE }
                        SortOrder.PRIORITY -> compareByDescending {
                            when (it.priority) {
                                Priority.HIGH -> 2; Priority.NORMAL -> 1; Priority.LOW -> 0
                            }
                        }
                        SortOrder.CREATED_AT -> compareByDescending { it.createdAt }
                    }
                }
            )
        TaskListUiState(
            tasks = filtered,
            devices = deviceMap,
            assigneeFilter = f.assigneeFilter,
            statusFilter = f.statusFilter,
            sortOrder = f.sortOrder,
            syncState = syncInd.sync.state,
            lastSyncAt = syncInd.sync.lastSyncAt,
            peerOnline = syncInd.sync.peerIp != null,
            wifiConnected = syncInd.sync.wifiConnected,
            myName = prefs.displayName,
            peerName = prefs.pairedDeviceId?.let { deviceMap[it]?.displayName },
            hasPairedDevice = prefs.pairedDeviceId != null,
            showPendingSyncIndicator = syncInd.showPendingSyncIndicator,
            confirmedSyncMap = syncInd.confirmedSyncMap,
            syncPaused = syncInd.sync.syncPaused
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TaskListUiState())

    fun setAssigneeFilter(f: AssigneeFilter) { _assigneeFilter.value = f }
    fun setStatusFilter(f: StatusFilter) { _statusFilter.value = f }
    fun setSortOrder(s: SortOrder) { _sortOrder.value = s }

    fun completeTask(task: Task) {
        if (task.status == TaskStatus.DONE) return
        if (isRecurringEligible(task)) {
            _pendingRecurrenceTask.value = task
            return
        }
        viewModelScope.launch {
            recurringCompletion.completeEndingChain(task)
            AppLogger.d("TaskListViewModel", "completeTask: '${task.title}' marked done via swipe")
        }
    }

    fun dismissRecurrenceChoice() {
        _pendingRecurrenceTask.value = null
    }

    fun confirmCompleteChain(task: Task) {
        viewModelScope.launch {
            recurringCompletion.completeEndingChain(task)
            _pendingRecurrenceTask.value = null
        }
    }

    fun confirmCompleteAndContinue(task: Task) {
        viewModelScope.launch {
            recurringCompletion.completeAndContinue(task)
            _pendingRecurrenceTask.value = null
        }
    }

    private fun isRecurringEligible(task: Task) =
        task.recurrence != Recurrence.None && task.dueDate != null &&
            task.assignedTo == deviceIdManager.deviceId
}
