package com.familytasks.app.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.familytasks.app.domain.model.ChecklistItem

/**
 * Редактор пунктов чек-листа: чекбокс + текст + удаление у каждой строки,
 * снизу — добавление новой. Управляемый composable, как [RecurrencePicker].
 */
@Composable
fun ChecklistEditor(
    items: List<ChecklistItem>,
    onItemsChange: (List<ChecklistItem>) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Чек-лист", style = MaterialTheme.typography.titleSmall)
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = item.checked,
                    onCheckedChange = { checked ->
                        onItemsChange(items.toMutableList().also { it[index] = item.copy(checked = checked) })
                    }
                )
                OutlinedTextField(
                    value = item.text,
                    onValueChange = { text ->
                        onItemsChange(items.toMutableList().also { it[index] = item.copy(text = text) })
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(onClick = { onItemsChange(items.toMutableList().also { it.removeAt(index) }) }) {
                    Icon(Icons.Default.Close, "Удалить пункт")
                }
            }
        }
        TextButton(onClick = { onItemsChange(items + ChecklistItem(text = "")) }) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("Добавить пункт")
        }
    }
}
