package com.familytasks.app.ui.tasks

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familytasks.app.domain.model.Priority
import com.familytasks.app.domain.model.Recurrence
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTaskScreen(
    onBack: () -> Unit,
    vm: CreateTaskViewModel = hiltViewModel()
) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(Priority.NORMAL) }
    var assignedTo by remember { mutableStateOf<String?>(null) }
    var dueDate by remember { mutableLongStateOf(0L) }
    var recurrence by remember { mutableStateOf<Recurrence>(Recurrence.None) }

    fun pickDateTime() {
        val cal = Calendar.getInstance()
        DatePickerDialog(context, { _, y, m, d ->
            cal.set(y, m, d)
            TimePickerDialog(context, { _, h, min ->
                cal.set(Calendar.HOUR_OF_DAY, h)
                cal.set(Calendar.MINUTE, min)
                dueDate = cal.timeInMillis
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Новая задача") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Название *") },
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
            Text("Приоритет", style = MaterialTheme.typography.titleSmall)
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
            if (devices.isNotEmpty()) {
                Text("Назначить", style = MaterialTheme.typography.titleSmall)
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
            Text("Срок выполнения", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDateTime() }) {
                    Text(if (dueDate > 0) SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(dueDate)) else "Выбрать дату")
                }
                if (dueDate > 0) {
                    OutlinedButton(onClick = { dueDate = 0L }) { Text("Убрать") }
                }
            }
            // Повтор завязан на владельца и дедлайн — показываем только когда оба заданы
            if (assignedTo != null && dueDate > 0) {
                Text("Повторение", style = MaterialTheme.typography.titleSmall)
                RecurrencePicker(value = recurrence, onValueChange = { recurrence = it })
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    vm.save(
                        title,
                        description,
                        priority,
                        assignedTo,
                        if (dueDate > 0) dueDate else null,
                        recurrence,
                        onBack
                    )
                },
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Создать")
            }
        }
    }
}
