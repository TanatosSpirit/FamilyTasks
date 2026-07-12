package com.familytasks.app.ui.settings

import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onPairDevice: () -> Unit,
    onLogs: () -> Unit = {},
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
                .verticalScroll(rememberScrollState())
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
            SwitchRow(
                label = "Пауза синхронизации",
                checked = state.syncPaused,
                onToggle = vm::setSyncPaused
            )
            if (state.syncPaused) {
                Text(
                    "Сканирование сети, sync и keepAlive отключены. Задачи копятся локально и сравняются, как только вы вернёте тумблер обратно.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val context = LocalContext.current
            OutlinedButton(
                onClick = {
                    vm.buildExportIntent { intent ->
                        context.startActivity(Intent.createChooser(intent, "Поделиться задачами"))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Поделиться задачами")
            }
            HorizontalDivider()
            BatteryOptimizationSection()
            HorizontalDivider()
            Text("Диагностика", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onLogs, modifier = Modifier.fillMaxWidth()) {
                Text("Логи синхронизации")
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
            SwitchRow(
                label = "Ежедневная сводка",
                checked = state.dailySummaryEnabled,
                onToggle = vm::setDailySummaryEnabled
            )
            if (state.dailySummaryEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            TimePickerDialog(
                                context,
                                { _, h, min -> vm.setDailySummaryTime(h, min) },
                                state.dailySummaryHour,
                                state.dailySummaryMinute,
                                true
                            ).show()
                        }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Напомнить в %02d:%02d".format(state.dailySummaryHour, state.dailySummaryMinute),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            HorizontalDivider()
            Text("Конфиденциальность", style = MaterialTheme.typography.titleMedium)
            SwitchRow(
                label = "Скрыть секретные задачи",
                checked = state.hideSecretTasks,
                onToggle = vm::setHideSecretTasks
            )
            HorizontalDivider()
            Text("Экспериментальные фичи", style = MaterialTheme.typography.titleMedium)
            SwitchRow(
                label = "Отображать индикатор синхронизации задач",
                checked = state.showPendingSyncIndicator,
                onToggle = vm::setShowPendingSyncIndicator
            )
            SwitchRow(
                label = "Веб-интерфейс без аутентификации (только в надёжной сети!)",
                checked = state.webUiEnabled,
                onToggle = vm::setWebUiEnabled
            )
            if (state.webUiEnabled) {
                Text(
                    "Открой в браузере на компьютере: http://${state.ownIp ?: "?"}:8080/web",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Пока включено — любой в этой WiFi-сети сможет смотреть и менять задачи без пароля. Включай только когда уверен, что в сети нет посторонних.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun BatteryOptimizationSection() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun isExempt(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    var exempt by remember { mutableStateOf(isExempt()) }

    // Re-check on resume: the system dialog returns no result, so refresh status
    // every time the screen comes back to the foreground.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) exempt = isExempt()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Text("Фоновая работа", style = MaterialTheme.typography.titleMedium)
    Text(
        if (exempt) {
            "Оптимизация батареи отключена — приложение может отвечать партнёру в фоне."
        } else {
            "Включена оптимизация батареи. В заблокированном состоянии телефон может перестать " +
                "отвечать партнёру. Отключите оптимизацию для надёжной синхронизации."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (!exempt) {
        OutlinedButton(
            onClick = {
                @SuppressLint("BatteryLife")
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Отключить оптимизацию батареи")
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
