package com.familytasks.app.domain.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Правило повторения задачи. Хранится в БД и передаётся по sync как строка
 * (`serialize()` / `parse()`), что позволяет добавлять новые виды правил
 * без миграции схемы — достаточно нового формата строки и ветки в [parse].
 */
sealed interface Recurrence {

    /** Сериализация для хранения в Room и передачи в DTO. */
    fun serialize(): String

    /**
     * Следующий дедлайн после [from] (мс), либо null если повторения нет.
     * Движок повторений зовёт только этот метод — вычисление инкапсулировано здесь.
     */
    fun nextDueDate(from: Long): Long?

    data object None : Recurrence {
        override fun serialize() = "none"
        override fun nextDueDate(from: Long): Long? = null
    }

    /** Повтор каждые [days] суток (1..7). */
    data class EveryNDays(val days: Int) : Recurrence {
        override fun serialize() = "days:$days"
        override fun nextDueDate(from: Long): Long = from + TimeUnit.DAYS.toMillis(days.toLong())
    }

    /** Повтор в конкретные дни недели. [days] не пуст. */
    data class Weekly(val days: Set<DayOfWeek>) : Recurrence {
        override fun serialize(): String {
            val sorted = days.sortedBy { it.value }.joinToString(",") { it.name.take(3).lowercase() }
            return "weekdays:$sorted"
        }

        override fun nextDueDate(from: Long): Long {
            val fromDow = Instant.ofEpochMilli(from).atZone(ZoneId.systemDefault()).dayOfWeek
            val minDelta = days.minOf { dow ->
                val d = (dow.value - fromDow.value + 7) % 7
                if (d == 0) 7 else d
            }
            return from + TimeUnit.DAYS.toMillis(minDelta.toLong())
        }
    }

    companion object {
        const val MIN_DAYS = 1
        const val MAX_DAYS = 7

        private val DOW_MAP = mapOf(
            "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY,
            "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY,
            "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY,
            "sun" to DayOfWeek.SUNDAY
        )

        fun parse(raw: String?): Recurrence {
            if (raw.isNullOrBlank() || raw == "none") return None
            if (raw.startsWith("days:")) {
                val n = raw.removePrefix("days:").toIntOrNull()
                if (n != null && n in MIN_DAYS..MAX_DAYS) return EveryNDays(n)
            }
            if (raw.startsWith("weekdays:")) {
                val days = raw.removePrefix("weekdays:")
                    .split(",")
                    .mapNotNull { DOW_MAP[it.trim()] }
                    .toSet()
                if (days.isNotEmpty()) return Weekly(days)
            }
            return None
        }
    }
}
