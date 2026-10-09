package com.familytasks.app.core

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)

    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var displayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DISPLAY_NAME, value).apply()

    var avatarColor: Int
        get() = prefs.getInt(KEY_AVATAR_COLOR, 0xFF4CAF50.toInt())
        set(value) = prefs.edit().putInt(KEY_AVATAR_COLOR, value).apply()

    var isOnboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()

    var pairedDeviceId: String?
        get() = prefs.getString(KEY_PAIRED_DEVICE_ID, null)
        set(value) = prefs.edit().putString(KEY_PAIRED_DEVICE_ID, value).apply()

    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    var notifyDueDate: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_DUE, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_DUE, value).apply()

    var notifyNewTask: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_NEW, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_NEW, value).apply()

    var notifyStatusChange: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_STATUS, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_STATUS, value).apply()

    var dailySummaryEnabled: Boolean
        get() = prefs.getBoolean(KEY_DAILY_SUMMARY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DAILY_SUMMARY_ENABLED, value).apply()

    var dailySummaryHour: Int
        get() = prefs.getInt(KEY_DAILY_SUMMARY_HOUR, 8)
        set(value) = prefs.edit().putInt(KEY_DAILY_SUMMARY_HOUR, value).apply()

    var dailySummaryMinute: Int
        get() = prefs.getInt(KEY_DAILY_SUMMARY_MINUTE, 0)
        set(value) = prefs.edit().putInt(KEY_DAILY_SUMMARY_MINUTE, value).apply()

    var lastKnownOwnIp: String?
        get() = prefs.getString(KEY_LAST_OWN_IP, null)
        set(value) = prefs.edit().putString(KEY_LAST_OWN_IP, value).apply()

    var lastKnownPeerIp: String?
        get() = prefs.getString(KEY_LAST_PEER_IP, null)
        set(value) = prefs.edit().putString(KEY_LAST_PEER_IP, value).apply()

    val lastConfirmedSyncTimestampFlow = MutableStateFlow(prefs.getLong(KEY_LAST_CONFIRMED_SYNC_TS, 0L))

    var lastConfirmedSyncTimestamp: Long
        get() = prefs.getLong(KEY_LAST_CONFIRMED_SYNC_TS, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_CONFIRMED_SYNC_TS, value).apply()
            lastConfirmedSyncTimestampFlow.value = value
        }

    // Только в памяти, не в SharedPreferences: после рестарта процесса секретные задачи
    // скрыты. Дополнительно сбрасывается в false при уходе UI в фон (FamilyTasksApplication).
    val showSecretTasksFlow = MutableStateFlow(false)

    val showPendingSyncIndicatorFlow = MutableStateFlow(prefs.getBoolean(KEY_SHOW_PENDING_SYNC_INDICATOR, false))

    var showPendingSyncIndicator: Boolean
        get() = prefs.getBoolean(KEY_SHOW_PENDING_SYNC_INDICATOR, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_PENDING_SYNC_INDICATOR, value).apply()
            showPendingSyncIndicatorFlow.value = value
        }

    val webUiEnabledFlow = MutableStateFlow(prefs.getBoolean(KEY_WEB_UI_ENABLED, false))

    var webUiEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEB_UI_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_WEB_UI_ENABLED, value).apply()
            webUiEnabledFlow.value = value
        }

    val syncPausedFlow = MutableStateFlow(prefs.getBoolean(KEY_SYNC_PAUSED, false))

    var syncPaused: Boolean
        get() = prefs.getBoolean(KEY_SYNC_PAUSED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SYNC_PAUSED, value).apply()
            syncPausedFlow.value = value
        }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_AVATAR_COLOR = "avatar_color"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_PAIRED_DEVICE_ID = "paired_device_id"
        private const val KEY_LAST_SYNC = "last_sync_at"
        private const val KEY_NOTIFY_DUE = "notify_due_date"
        private const val KEY_NOTIFY_NEW = "notify_new_task"
        private const val KEY_NOTIFY_STATUS = "notify_status_change"
        private const val KEY_DAILY_SUMMARY_ENABLED = "daily_summary_enabled"
        private const val KEY_DAILY_SUMMARY_HOUR = "daily_summary_hour"
        private const val KEY_DAILY_SUMMARY_MINUTE = "daily_summary_minute"
        private const val KEY_LAST_OWN_IP = "last_own_ip"
        private const val KEY_LAST_PEER_IP = "last_peer_ip"
        private const val KEY_LAST_CONFIRMED_SYNC_TS = "last_confirmed_sync_ts"
        private const val KEY_SHOW_PENDING_SYNC_INDICATOR = "show_pending_sync_indicator"
        private const val KEY_WEB_UI_ENABLED = "web_ui_enabled"
        private const val KEY_SYNC_PAUSED = "sync_paused"
    }
}
