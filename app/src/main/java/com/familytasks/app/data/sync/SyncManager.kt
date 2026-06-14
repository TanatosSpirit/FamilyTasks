package com.familytasks.app.data.sync

import com.familytasks.app.core.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class SyncState { IDLE, SYNCING, SUCCESS, ERROR }

@Singleton
class SyncManager @Inject constructor(private val prefs: PreferencesManager) {
    private val _peerIp = MutableStateFlow<String?>(prefs.lastKnownPeerIp)
    val peerIp: StateFlow<String?> = _peerIp

    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState

    private val _lastSyncAt = MutableStateFlow(0L)
    val lastSyncAt: StateFlow<Long> = _lastSyncAt

    fun setPeerIp(ip: String?) {
        _peerIp.value = ip
        if (ip != null) prefs.lastKnownPeerIp = ip
    }
    fun setSyncState(state: SyncState) { _syncState.value = state }
    fun setLastSyncAt(time: Long) { _lastSyncAt.value = time }
}
