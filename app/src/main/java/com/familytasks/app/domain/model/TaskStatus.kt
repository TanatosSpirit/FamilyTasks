package com.familytasks.app.domain.model

enum class TaskStatus(val value: String) {
    TODO("todo"),
    DONE("done");

    companion object {
        fun fromString(value: String) = entries.firstOrNull { it.value == value } ?: TODO
    }
}
