package com.familytasks.app.ui.logs

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
}
