package com.familytasks.app.ui.logs

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familytasks.app.core.AppLogger
import com.familytasks.app.core.LogEntry
import com.familytasks.app.core.LogLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class LogsViewModel @Inject constructor() : ViewModel() {

    private val _filter = MutableStateFlow<LogLevel?>(null)
    val filter: StateFlow<LogLevel?> = _filter.asStateFlow()

    val entries: StateFlow<List<LogEntry>> = combine(AppLogger.entries, _filter) { all, level ->
        val filtered = if (level == null) all else all.filter { it.level == level }
        filtered.asReversed()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleFilter(level: LogLevel) {
        _filter.value = if (_filter.value == level) null else level
    }

    fun clearLogs() = AppLogger.clear()

    fun buildShareIntent(context: Context): Intent {
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val text = AppLogger.entries.value
            .asReversed()
            .joinToString("\n") { e ->
                "${timeFmt.format(Date(e.time))}  ${e.level.name}  ${e.tag.padEnd(14)}  ${e.message}"
            }

        val logsDir = File(context.cacheDir, "logs").apply { mkdirs() }
        val file = File(logsDir, "familytasks_log.txt").apply { writeText(text) }
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "FamilyTasks логи синхронизации")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
