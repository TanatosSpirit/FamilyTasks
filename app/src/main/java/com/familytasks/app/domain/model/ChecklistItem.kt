package com.familytasks.app.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ChecklistItem(val text: String, val checked: Boolean = false)

/** Сериализация для хранения в Room и передачи в DTO, по аналогии с [Recurrence.serialize]. */
fun List<ChecklistItem>.serialize(): String =
    if (isEmpty()) "[]" else Json.encodeToString(this)

/** Никогда не бросает исключение — битая строка превращается в пустой список. */
fun String?.parseChecklist(): List<ChecklistItem> {
    if (isNullOrBlank()) return emptyList()
    return try {
        Json.decodeFromString<List<ChecklistItem>>(this)
    } catch (e: Exception) {
        emptyList()
    }
}
