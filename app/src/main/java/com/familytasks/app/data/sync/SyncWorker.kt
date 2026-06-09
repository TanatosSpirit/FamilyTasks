package com.familytasks.app.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.familytasks.app.core.AppLogger
import com.familytasks.app.core.DeviceIdManager
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.domain.usecase.SyncUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncUseCase: SyncUseCase,
    private val httpClient: HttpClient,
    private val syncManager: SyncManager,
    private val deviceIdManager: DeviceIdManager,
    private val prefs: PreferencesManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val peerIp = syncManager.peerIp.value ?: run {
            AppLogger.d(TAG, "WorkManager sync: no peer IP, skipped")
            return Result.success()
        }
        AppLogger.i(TAG, "WorkManager sync triggered → $peerIp")
        return try {
            val localTasks = syncUseCase.getLocalSnapshot()
            val localDevices = syncUseCase.getLocalDevices()
            val payload = SyncPayload(
                deviceId = deviceIdManager.deviceId,
                tasks = localTasks.map { it.toDto() },
                devices = localDevices.map { it.toDto() }
            )
            val response = httpClient.sync(peerIp, 8080, payload)
            if (response != null) {
                syncUseCase.mergeAll(
                    response.tasks.map { it.toDomain() },
                    response.devices.map { it.toDomain() }
                )
                val now = System.currentTimeMillis()
                prefs.lastSyncAt = now
                syncManager.setLastSyncAt(now)
            }
            AppLogger.i(TAG, "WorkManager sync: success")
            Result.success()
        } catch (e: Exception) {
            AppLogger.w(TAG, "WorkManager sync: error, will retry — ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
    }
}
