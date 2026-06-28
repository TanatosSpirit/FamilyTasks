package com.familytasks.app.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.familytasks.app.domain.model.Recurrence
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Человекочитаемая подпись правила повтора (русская плюрализация дней). */
fun recurrenceLabel(recurrence: Recurrence): String = when (recurrence) {
    is Recurrence.None -> "Без повторения"
    is Recurrence.EveryNDays -> when (recurrence.days) {
        1 -> "Каждый день"
        in 2..4 -> "Каждые ${recurrence.days} дня"
        else -> "Каждые ${recurrence.days} дней"
    }
    is Recurrence.Weekly -> "По дням: " + recurrence.days
        .sortedBy { it.value }
        .joinToString(", ") { it.dowShortRu() }
}

private fun DayOfWeek.dowShortRu(): String =
    getDisplayName(TextStyle.SHORT, Locale("ru")).replaceFirstChar { it.uppercase(Locale("ru")) }

/**
 * Выпадающий список выбора повтора + чипы дней недели при выборе «По дням недели».
 * Показывать только когда задача имеет ответственного и дедлайн.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecurrencePicker(
    value: Recurrence,
    onValueChange: (Recurrence) -> Unit,
    modifier: Modifier = Modifier
) {
    val options: List<Recurrence> = remember {
        listOf(Recurrence.None) +
            (Recurrence.MIN_DAYS..Recurrence.MAX_DAYS).map { Recurrence.EveryNDays(it) } +
            listOf(Recurrence.Weekly(setOf(DayOfWeek.MONDAY)))
    }
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = recurrenceLabel(value),
                onValueChange = {},
                readOnly = true,
                label = { Text("Повторение") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    val label = if (option is Recurrence.Weekly) "По дням недели" else recurrenceLabel(option)
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            // При повторном выборе «По дням недели» сохраняем текущие дни
                            val toEmit = if (option is Recurrence.Weekly && value is Recurrence.Weekly) value else option
                            onValueChange(toEmit)
                            expanded = false
                        }
                    )
                }
            }
        }

        if (value is Recurrence.Weekly) {
            WeekdayChips(
                days = value.days,
                onDaysChange = { newDays -> onValueChange(Recurrence.Weekly(newDays)) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdayChips(
    days: Set<DayOfWeek>,
    onDaysChange: (Set<DayOfWeek>) -> Unit
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DayOfWeek.values().forEach { dow ->
            val selected = dow in days
            FilterChip(
                selected = selected,
                onClick = {
                    val newDays = if (selected) days - dow else days + dow
                    if (newDays.isNotEmpty()) onDaysChange(newDays)
                },
                label = { Text(dow.dowShortRu()) }
            )
        }
    }
}
