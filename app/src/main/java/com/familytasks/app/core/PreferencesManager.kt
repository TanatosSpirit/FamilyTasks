package com.familytasks.app.core

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
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

    var lastKnownOwnIp: String?
        get() = prefs.getString(KEY_LAST_OWN_IP, null)
        set(value) = prefs.edit().putString(KEY_LAST_OWN_IP, value).apply()

    var lastKnownPeerIp: String?
        get() = prefs.getString(KEY_LAST_PEER_IP, null)
        set(value) = prefs.edit().putString(KEY_LAST_PEER_IP, value).apply()

    var lastConfirmedSyncTimestamp: Long
        get() = prefs.getLong(KEY_LAST_CONFIRMED_SYNC_TS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CONFIRMED_SYNC_TS, value).apply()

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
        private const val KEY_LAST_OWN_IP = "last_own_ip"
        private const val KEY_LAST_PEER_IP = "last_peer_ip"
        private const val KEY_LAST_CONFIRMED_SYNC_TS = "last_confirmed_sync_ts"
    }
}
