package com.familytasks.app.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familytasks.app.domain.model.Priority
import com.familytasks.app.domain.model.Task
import com.familytasks.app.domain.model.TaskStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    taskId: String,
    onBack: () -> Unit,
    vm: TaskDetailViewModel = hiltViewModel()
) {
    LaunchedEffect(taskId) { vm.load(taskId) }

    val task by vm.task.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Удалить задачу?") },
            text = { Text("Задача будет помечена как удалённая и исчезнет из списка.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(onBack) }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Отмена") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Задача") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { editMode = !editMode }) {
                        Icon(Icons.Default.Edit, "Редактировать")
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, "Удалить", tint = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }
    ) { padding ->
        val t = task
        if (t == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Загрузка...")
            }
        } else if (editMode) {
            EditTaskContent(
                task = t,
                devices = devices,
                myDeviceId = vm.myDeviceId,
                onSave = { updated -> vm.updateTask(updated) { editMode = false } },
                onCancel = { editMode = false },
                modifier = Modifier.padding(padding)
            )
        } else {
            ViewTaskContent(
                task = t,
                devices = devices,
                onCycleStatus = vm::cycleStatus,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun ViewTaskContent(
    task: Task,
    devices: List<com.familytasks.app.domain.model.Device>,
    onCycleStatus: () -> Unit,
    modifier: Modifier = Modifier
) {
    val devicesMap = devices.associateBy { it.deviceId }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(task.title, style = MaterialTheme.typography.headlineSmall)
        if (!task.description.isNullOrBlank()) {
            Text(task.description, style = MaterialTheme.typography.bodyMedium)
        }
        InfoRow("Приоритет", priorityLabel(task.priority))
        InfoRow("Статус", statusLabel(task.status))
        if (task.dueDate != null) {
            InfoRow("Срок", SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(task.dueDate)))
        }
        if (task.completedAt != null) {
            InfoRow("Завершена", SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(task.completedAt)))
        }
        val creator = devicesMap[task.createdBy]
        if (creator != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Создал: ", style = MaterialTheme.typography.bodyMedium)
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Color(creator.color))
                )
                Text(" ${creator.displayName}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        val assignee = task.assignedTo?.let { devicesMap[it] }
        if (assignee != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Назначено: ", style = MaterialTheme.typography.bodyMedium)
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Color(assignee.color))
                )
                Text(" ${assignee.displayName}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onCycleStatus, modifier = Modifier.fillMaxWidth()) {
            Text(
                when (task.status) {
                    TaskStatus.TODO -> "Отметить выполненной"
                    TaskStatus.DONE -> "Вернуть в работу"
                }
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text("$label: ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun priorityLabel(p: Priority) = when (p) {
    Priority.LOW -> "Низкий"
    Priority.NORMAL -> "Обычный"
    Priority.HIGH -> "Высокий"
}

private fun statusLabel(s: TaskStatus) = when (s) {
    TaskStatus.TODO -> "Новая"
    TaskStatus.DONE -> "Выполнена"
}
