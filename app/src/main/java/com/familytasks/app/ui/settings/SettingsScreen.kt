package com.familytasks.app.ui.settings

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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onPairDevice: () -> Unit,
    vm: SettingsViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Моё устройство", style = MaterialTheme.typography.titleMedium)
            state.myDevice?.let { device ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(device.color))
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(device.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text("ID: ${vm.deviceShortId}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider()
            Text("Сопряжённое устройство", style = MaterialTheme.typography.titleMedium)
            val pairedDevice = state.pairedDevice
            if (pairedDevice != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(pairedDevice.color))
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(pairedDevice.displayName, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } else {
                Text("Устройство не сопряжено", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onPairDevice, modifier = Modifier.fillMaxWidth()) {
                Text(if (pairedDevice != null) "Повторить сопряжение" else "Сопрячь устройство")
            }
            if (state.lastSyncAt > 0) {
                Text(
                    "Последняя синхронизация: ${SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(state.lastSyncAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider()
            Text("Уведомления", style = MaterialTheme.typography.titleMedium)
            SwitchRow(
                label = "Срок выполнения (за 1 день и 1 час)",
                checked = state.notifyDueDate,
                onToggle = vm::setNotifyDueDate
            )
            SwitchRow(
                label = "Новая задача назначена мне",
                checked = state.notifyNewTask,
                onToggle = vm::setNotifyNewTask
            )
            SwitchRow(
                label = "Статус моей задачи изменился",
                checked = state.notifyStatusChange,
                onToggle = vm::setNotifyStatusChange
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}
