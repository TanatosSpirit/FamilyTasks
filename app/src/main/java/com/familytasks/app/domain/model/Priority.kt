package com.familytasks.app.domain.model

enum class Priority(val value: String) {
    LOW("low"),
    NORMAL("normal"),
    HIGH("high");

    companion object {
        fun fromString(value: String) = entries.firstOrNull { it.value == value } ?: NORMAL
    }
}
