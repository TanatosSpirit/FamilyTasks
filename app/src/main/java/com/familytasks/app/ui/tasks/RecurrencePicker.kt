package com.familytasks.app.ui.tasks

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.familytasks.app.domain.model.Recurrence

/** Человекочитаемая подпись правила повтора (русская плюрализация дней). */
fun recurrenceLabel(recurrence: Recurrence): String = when (recurrence) {
    is Recurrence.None -> "Без повторения"
    is Recurrence.EveryNDays -> when (recurrence.days) {
        1 -> "Каждый день"
        in 2..4 -> "Каждые ${recurrence.days} дня"
        else -> "Каждые ${recurrence.days} дней"
    }
    is Recurrence.EveryNMinutes -> when {
        recurrence.minutes % 60 == 0 -> "Каждый ${recurrence.minutes / 60} ч (тест)"
        else -> "Каждые ${recurrence.minutes / 60} ч ${recurrence.minutes % 60} мин (тест)"
    }
}

/**
 * Выпадающий список выбора повтора: «Без повторения» + «Каждые 1..7 дней».
 * Показывать только когда задача имеет ответственного и дедлайн — повтор завязан
 * на владельца и срабатывает по дедлайну.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurrencePicker(
    value: Recurrence,
    onValueChange: (Recurrence) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = remember {
        listOf<Recurrence>(Recurrence.None) +
            (Recurrence.MIN_DAYS..Recurrence.MAX_DAYS).map { Recurrence.EveryNDays(it) } +
            listOf(Recurrence.EveryNMinutes(60), Recurrence.EveryNMinutes(65))
    }
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
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
                DropdownMenuItem(
                    text = { Text(recurrenceLabel(option)) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}
