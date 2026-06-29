package com.familytasks.app.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familytasks.app.data.sync.SyncState
import com.familytasks.app.domain.model.AssigneeFilter
import com.familytasks.app.domain.model.Priority
import com.familytasks.app.domain.model.Recurrence
import com.familytasks.app.domain.model.SortOrder
import com.familytasks.app.domain.model.StatusFilter
import com.familytasks.app.domain.model.Task
import com.familytasks.app.domain.model.TaskStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    onTaskClick: (String) -> Unit,
    onCreateTask: () -> Unit,
    onSettings: () -> Unit,
    vm: TaskListViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var sortMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("FamilyTasks")
                        Text(
                            text = syncStatusText(state.syncState, state.lastSyncAt, state.peerOnline, state.wifiConnected),
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                !state.wifiConnected -> MaterialTheme.colorScheme.onSurfaceVariant
                                state.peerOnline -> Color(0xFF4CAF50)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.Default.Sort, "Сортировка")
                        }
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("По сроку") },
                                onClick = { vm.setSortOrder(SortOrder.DUE_DATE); sortMenuOpen = false }
                            )
                            DropdownMenuItem(
                                text = { Text("По приоритету") },
                                onClick = { vm.setSortOrder(SortOrder.PRIORITY); sortMenuOpen = false }
                            )
                            DropdownMenuItem(
                                text = { Text("По дате создания") },
                                onClick = { vm.setSortOrder(SortOrder.CREATED_AT); sortMenuOpen = false }
                            )
                        }
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, "Настройки")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateTask) {
                Icon(Icons.Default.Add, "Создать задачу")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            FilterRow(
                assigneeFilter = state.assigneeFilter,
                statusFilter = state.statusFilter,
                onAssigneeFilter = vm::setAssigneeFilter,
                onStatusFilter = vm::setStatusFilter,
                myName = state.myName,
                peerName = state.peerName
            )
            if (state.tasks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Нет задач", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val showDateHeaders = state.sortOrder == SortOrder.DUE_DATE &&
                    state.statusFilter != StatusFilter.DONE
                val listItems = if (showDateHeaders) {
                    buildDateGroupedList(state.tasks)
                } else {
                    state.tasks.map { TaskListItem.Item(it) }
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(listItems, key = { item ->
                        when (item) {
                            is TaskListItem.Header -> "header_${item.label}"
                            is TaskListItem.Item -> item.task.id
                        }
                    }) { item ->
                        when (item) {
                            is TaskListItem.Header -> DateGroupHeader(item.label)
                            is TaskListItem.Item -> TaskCard(
                                task = item.task,
                                assigneeColor = item.task.assignedTo?.let { state.devices[it]?.color },
                                onClick = { onTaskClick(item.task.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

private sealed class TaskListItem {
    data class Header(val label: String) : TaskListItem()
    data class Item(val task: Task) : TaskListItem()
}

private fun buildDateGroupedList(tasks: List<Task>): List<TaskListItem> {
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val todayStart = today.timeInMillis
    val tomorrowStart = todayStart + 86_400_000L

    val overdue = tasks.filter { it.dueDate != null && it.dueDate < todayStart }
    val withDue = tasks.filter { it.dueDate != null && it.dueDate >= todayStart }
    val noDue = tasks.filter { it.dueDate == null }

    val result = mutableListOf<TaskListItem>()

    if (overdue.isNotEmpty()) {
        result += TaskListItem.Header("Просрочено")
        overdue.forEach { result += TaskListItem.Item(it) }
    }

    val byDay = withDue.groupBy { task ->
        val cal = Calendar.getInstance().apply { timeInMillis = task.dueDate!! }
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }
    byDay.keys.sorted().forEach { dayStart ->
        val label = when {
            dayStart == todayStart -> "Сегодня"
            dayStart == tomorrowStart -> "Завтра"
            else -> SimpleDateFormat("EE d MMMM", Locale("ru")).format(Date(dayStart))
                .replaceFirstChar { it.uppercaseChar() }
        }
        result += TaskListItem.Header(label)
        byDay[dayStart]!!.forEach { result += TaskListItem.Item(it) }
    }

    if (noDue.isNotEmpty()) {
        result += TaskListItem.Header("Без срока")
        noDue.forEach { result += TaskListItem.Item(it) }
    }

    return result
}

@Composable
private fun DateGroupHeader(label: String) {
    Column {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
        )
    }
}

@Composable
private fun FilterRow(
    assigneeFilter: AssigneeFilter,
    statusFilter: StatusFilter,
    onAssigneeFilter: (AssigneeFilter) -> Unit,
    onStatusFilter: (StatusFilter) -> Unit,
    myName: String,
    peerName: String?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = assigneeFilter == AssigneeFilter.ALL,
            onClick = { onAssigneeFilter(AssigneeFilter.ALL) },
            label = { Text("Все") }
        )
        FilterChip(
            selected = assigneeFilter == AssigneeFilter.MINE,
            onClick = { onAssigneeFilter(AssigneeFilter.MINE) },
            label = { Text(myName.ifEmpty { "Мои" }) }
        )
        FilterChip(
            selected = assigneeFilter == AssigneeFilter.OTHERS,
            onClick = { onAssigneeFilter(AssigneeFilter.OTHERS) },
            label = { Text(peerName ?: "Чужие") }
        )
        Spacer(modifier = Modifier.weight(1f))
        FilterChip(
            selected = statusFilter == StatusFilter.DONE,
            onClick = {
                onStatusFilter(if (statusFilter == StatusFilter.DONE) StatusFilter.ACTIVE else StatusFilter.DONE)
            },
            label = { Text("Готово") }
        )
    }
}

@Composable
private fun TaskCard(
    task: Task,
    assigneeColor: Int?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = priorityContainerColor(task.priority)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (task.status == TaskStatus.DONE) TextDecoration.LineThrough else null
                )
                if (task.dueDate != null) {
                    Text(
                        text = "До: ${formatDate(task.dueDate)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = dueDateColor(task.dueDate, task.status, task.completedAt)
                    )
                }
            }
            if (task.recurrence != Recurrence.None) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Повторяется",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            if (assigneeColor != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(assigneeColor))
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            StatusBadge(task.status)
        }
    }
}

@Composable
private fun StatusBadge(status: TaskStatus) {
    if (status == TaskStatus.DONE) {
        Text(
            text = "Готово",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF4CAF50)
        )
    }
}

@Composable
private fun priorityContainerColor(priority: Priority): androidx.compose.ui.graphics.Color {
    return when (priority) {
        Priority.HIGH -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        Priority.NORMAL -> MaterialTheme.colorScheme.surface
        Priority.LOW -> MaterialTheme.colorScheme.surfaceVariant
    }
}

private fun dueDateColor(dueDate: Long, status: TaskStatus, completedAt: Long?): Color {
    return if (status == TaskStatus.DONE) {
        when {
            completedAt == null -> Color(0xFF9E9E9E)
            completedAt <= dueDate -> Color(0xFF4CAF50)
            else -> Color(0xFFF44336)
        }
    } else {
        val diff = dueDate - System.currentTimeMillis()
        when {
            diff < 0 -> Color(0xFFF44336)
            diff < 3_600_000 -> Color(0xFFFF9800)
            else -> Color(0xFF9E9E9E)
        }
    }
}

private fun formatDate(ts: Long): String =
    SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(ts))

private fun syncStatusText(state: SyncState, lastSyncAt: Long, peerOnline: Boolean, wifiConnected: Boolean): String {
    return when {
        !wifiConnected -> "Нет подключения к Wi-Fi"
        !peerOnline -> "Офлайн"
        state == SyncState.SYNCING -> "Синхронизация..."
        state == SyncState.ERROR -> "Ошибка синхронизации"
        lastSyncAt == 0L -> "Онлайн"
        else -> "Синхр. в ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastSyncAt))}"
    }
}
