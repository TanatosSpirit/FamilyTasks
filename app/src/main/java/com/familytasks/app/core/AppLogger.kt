package com.familytasks.app.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LogEntry(val time: Long, val level: LogLevel, val tag: String, val message: String)

enum class LogLevel { D, I, W, E }

// To remove: delete this file + ui/logs/, remove Screen.Logs from Screen.kt and NavGraph.kt,
// remove the "Логи" button from SettingsScreen, replace AppLogger.* with Log.* in sync files.
object AppLogger {
    private const val MAX_ENTRIES = 500
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun d(tag: String, msg: String) { append(LogLevel.D, tag, msg); Log.d(tag, msg) }
    fun i(tag: String, msg: String) { append(LogLevel.I, tag, msg); Log.i(tag, msg) }
    fun w(tag: String, msg: String) { append(LogLevel.W, tag, msg); Log.w(tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable? = null) { append(LogLevel.E, tag, msg); Log.e(tag, msg, tr) }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
        _entries.value = emptyList()
    }

    private fun append(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)
        synchronized(buffer) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
            _entries.value = buffer.toList()
        }
    }
}
