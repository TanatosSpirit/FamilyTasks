package com.familytasks.app.domain.model

import java.util.concurrent.TimeUnit

/**
 * Правило повторения задачи. Хранится в БД и передаётся по sync как строка
 * (`serialize()` / `parse()`), что позволяет добавлять новые виды правил
 * (например, «конкретный день недели/месяца») без миграции схемы — достаточно
 * нового формата строки и ветки в [parse].
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

    /** Повтор каждые [minutes] минут — только для тестирования recurrence. */
    data class EveryNMinutes(val minutes: Int) : Recurrence {
        override fun serialize() = "minutes:$minutes"
        override fun nextDueDate(from: Long): Long = from + TimeUnit.MINUTES.toMillis(minutes.toLong())
    }

    companion object {
        const val MIN_DAYS = 1
        const val MAX_DAYS = 7

        fun parse(raw: String?): Recurrence {
            if (raw.isNullOrBlank() || raw == "none") return None
            if (raw.startsWith("days:")) {
                val n = raw.removePrefix("days:").toIntOrNull()
                if (n != null && n in MIN_DAYS..MAX_DAYS) return EveryNDays(n)
            }
            if (raw.startsWith("minutes:")) {
                val n = raw.removePrefix("minutes:").toIntOrNull()
                if (n != null && n > 0) return EveryNMinutes(n)
            }
            return None
        }
    }
}
