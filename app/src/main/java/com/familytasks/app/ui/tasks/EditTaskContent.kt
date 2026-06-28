package com.familytasks.app.ui.tasks

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.familytasks.app.domain.model.Device
import com.familytasks.app.domain.model.Priority
import com.familytasks.app.domain.model.Recurrence
import com.familytasks.app.domain.model.Task
import com.familytasks.app.domain.model.TaskStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun EditTaskContent(
    task: Task,
    devices: List<Device>,
    myDeviceId: String,
    onSave: (Task) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(task.title) }
    var description by remember { mutableStateOf(task.description ?: "") }
    var priority by remember { mutableStateOf(task.priority) }
    var status by remember { mutableStateOf(task.status) }
    var assignedTo by remember { mutableStateOf(task.assignedTo) }
    var dueDate by remember { mutableLongStateOf(task.dueDate ?: 0L) }
    var recurrence by remember { mutableStateOf(task.recurrence) }

    fun pickDateTime() {
        val cal = Calendar.getInstance()
        if (dueDate > 0L) cal.timeInMillis = dueDate
        DatePickerDialog(context, { _, y, m, d ->
            cal.set(y, m, d)
            TimePickerDialog(context, { _, h, min ->
                cal.set(Calendar.HOUR_OF_DAY, h)
                cal.set(Calendar.MINUTE, min)
                cal.set(Calendar.SECOND, 0)
                dueDate = cal.timeInMillis
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Название") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("Описание") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        Text("Приоритет")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Priority.entries.forEach { p ->
                FilterChip(
                    selected = priority == p,
                    onClick = { priority = p },
                    label = {
                        Text(when (p) { Priority.LOW -> "Низкий"; Priority.NORMAL -> "Обычный"; Priority.HIGH -> "Высокий" })
                    }
                )
            }
        }
        Text("Статус")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskStatus.entries.forEach { s ->
                FilterChip(
                    selected = status == s,
                    onClick = { status = s },
                    label = {
                        Text(when (s) { TaskStatus.TODO -> "Новая"; TaskStatus.DONE -> "Готово" })
                    }
                )
            }
        }
        if (devices.isNotEmpty()) {
            Text("Назначить")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = assignedTo == null,
                    onClick = { assignedTo = null },
                    label = { Text("Никому") }
                )
                devices.forEach { d ->
                    FilterChip(
                        selected = assignedTo == d.deviceId,
                        onClick = { assignedTo = d.deviceId },
                        label = { Text(d.displayName.ifBlank { d.deviceId.take(4) }) }
                    )
                }
            }
        }
        Text("Срок")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickDateTime() }) {
                Text(if (dueDate > 0L) SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(dueDate)) else "Выбрать дату")
            }
            if (dueDate > 0L) {
                OutlinedButton(onClick = { dueDate = 0L }) { Text("Убрать") }
            }
        }
        // Повтор завязан на владельца и дедлайн — показываем только когда оба заданы
        if (assignedTo != null && dueDate > 0L) {
            Text("Повторение")
            RecurrencePicker(value = recurrence, onValueChange = { recurrence = it })
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                val completedAt = when {
                    status == TaskStatus.DONE && task.status != TaskStatus.DONE -> System.currentTimeMillis()
                    status != TaskStatus.DONE -> null
                    else -> task.completedAt
                }
                onSave(task.copy(
                    title = title.trim(),
                    description = description.trim().ifBlank { null },
                    priority = priority,
                    status = status,
                    assignedTo = assignedTo,
                    dueDate = if (dueDate > 0L) dueDate else null,
                    completedAt = completedAt,
                    // Повтор имеет смысл только с ответственным и дедлайном — иначе гасим
                    recurrence = if (assignedTo != null && dueDate > 0L) recurrence else Recurrence.None
                ))
            },
            enabled = title.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Сохранить") }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Отмена")
        }
    }
}
