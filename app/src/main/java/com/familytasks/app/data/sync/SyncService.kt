package com.familytasks.app.data.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.familytasks.app.R
import com.familytasks.app.core.DeviceIdManager
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.domain.model.Device
import com.familytasks.app.domain.repository.DeviceRepository
import com.familytasks.app.domain.repository.TaskRepository
import com.familytasks.app.domain.usecase.SyncUseCase
import com.familytasks.app.notifications.NotificationScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SyncService : Service() {

    @Inject lateinit var deviceIdManager: DeviceIdManager
    @Inject lateinit var prefs: PreferencesManager
    @Inject lateinit var syncUseCase: SyncUseCase
    @Inject lateinit var httpClient: HttpClient
    @Inject lateinit var syncManager: SyncManager
    @Inject lateinit var deviceRepository: DeviceRepository
    @Inject lateinit var taskRepository: TaskRepository
    @Inject lateinit var notificationScheduler: NotificationScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var httpServer: HttpServer? = null
    private var udpDiscovery: UdpDiscovery? = null

    @Suppress("DEPRECATION")
    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val info = intent.getParcelableExtra<android.net.NetworkInfo>(
                WifiManager.EXTRA_NETWORK_INFO
            ) ?: return
            when {
                info.isConnected -> {
                    if (syncManager.peerIp.value == null) {
                        udpDiscovery?.restartDiscovery(scope)
                    }
                }
                !info.isConnectedOrConnecting -> {
                    syncManager.setPeerIp(null)
                    udpDiscovery?.pauseDiscovery()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat()
        startHttpServer()
        startUdpDiscovery()
        observeTaskChanges()
        registerReceiver(wifiReceiver, IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SYNC_NOW) {
            triggerSync()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(wifiReceiver)
        httpServer?.stop()
        udpDiscovery?.stop()
    }

    private fun startHttpServer() {
        httpServer = HttpServer(8080) { incoming ->
            val localTasks = syncUseCase.getLocalSnapshot()
            val localDevices = buildDevicesPayload()

            syncUseCase.mergeAll(
                incoming.tasks.map { it.toDomain() },
                incoming.devices.map { it.toDomain() }
            )

            SyncPayload(
                deviceId = deviceIdManager.deviceId,
                tasks = localTasks.map { it.toDto() },
                devices = localDevices.map { it.toDto() }
            )
        }.also { it.start() }
    }

    private fun startUdpDiscovery() {
        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
        udpDiscovery = UdpDiscovery(
            wifiManager = wifiManager,
            ownDeviceId = deviceIdManager.deviceId,
            pairedDeviceId = prefs.pairedDeviceId,
            onPeerDiscovered = { ip, deviceId ->
                syncManager.setPeerIp(ip)
                udpDiscovery?.pauseDiscovery()
                scope.launch {
                    val existing = deviceRepository.getDeviceById(deviceId)
                    deviceRepository.upsertDevice(
                        (existing ?: Device(deviceId = deviceId, displayName = "", color = 0))
                            .copy(lastSeenAt = System.currentTimeMillis())
                    )
                }
                triggerSync()
            }
        ).also { it.start(scope) }
    }

    private fun observeTaskChanges() {
        scope.launch {
            taskRepository.observeAllTasks()
                .drop(1)
                .distinctUntilChanged()
                .debounce(3_000L)
                .collect { triggerSync() }
        }
    }

    private fun triggerSync() {
        val peerIp = syncManager.peerIp.value ?: return
        if (syncManager.syncState.value == SyncState.SYNCING) return
        scope.launch {
            syncManager.setSyncState(SyncState.SYNCING)
            try {
                val localTasks = syncUseCase.getLocalSnapshot()
                val localDevices = buildDevicesPayload()
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
                    syncManager.setSyncState(SyncState.SUCCESS)
                } else {
                    syncManager.setSyncState(SyncState.ERROR)
                }
            } catch (e: PeerUnreachableException) {
                syncManager.setPeerIp(null)
                syncManager.setSyncState(SyncState.ERROR)
                // Б ушёл из сети — его обязанность объявить о себе по возвращению через UDP burst
            } catch (e: Exception) {
                syncManager.setSyncState(SyncState.ERROR)
            }
        }
    }

    private suspend fun buildDevicesPayload(): List<Device> {
        val me = Device(
            deviceId = deviceIdManager.deviceId,
            displayName = prefs.displayName,
            color = prefs.avatarColor
        )
        val others = syncUseCase.getLocalDevices()
            .filter { it.deviceId != deviceIdManager.deviceId }
        return listOf(me) + others
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Sync Service", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.sync_service_notification_title))
            .setContentText(getString(R.string.sync_service_notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    companion object {
        const val CHANNEL_ID = "sync_service"
        const val NOTIFICATION_ID = 1
        const val ACTION_SYNC_NOW = "com.familytasks.app.SYNC_NOW"
    }
}
