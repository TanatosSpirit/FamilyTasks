package com.familytasks.app.data.sync

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.familytasks.app.MainActivity
import com.familytasks.app.R
import com.familytasks.app.core.AppLogger
import com.familytasks.app.core.DeviceIdManager
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.domain.model.Device
import com.familytasks.app.domain.model.Priority
import com.familytasks.app.domain.model.Task
import com.familytasks.app.domain.model.TaskStatus
import com.familytasks.app.domain.repository.DeviceRepository
import com.familytasks.app.domain.repository.TaskRepository
import com.familytasks.app.domain.usecase.SyncUseCase
import com.familytasks.app.notifications.NotificationScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.UUID
import javax.inject.Inject

private const val HOUR_MS = 60 * 60 * 1000L

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
    @Inject lateinit var subnetScanner: SubnetScanner

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var httpServer: HttpServer? = null
    private var scanJob: Job? = null
    private var verifyJob: Job? = null
    private val syncMutex = Mutex()
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var powerManager: PowerManager

    private val ipTrackingCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val allAddresses = connectivityManager.getLinkProperties(network)
                ?.linkAddresses
                ?.mapNotNull { it.address.hostAddress }
            AppLogger.d(TAG, "WiFi available, all addresses: $allAddresses")
            val ip = allAddresses?.firstOrNull { it.contains('.') }
            if (ip != null) {
                prefs.lastKnownOwnIp = ip
                AppLogger.i(TAG, "WiFi connected, IP: $ip")
            }
            val peerIp = syncManager.peerIp.value
            if (ip != null && peerIp != null && peerIp == ip) {
                AppLogger.w(TAG, "Own IP ($ip) matches peerIp — self-loop detected on connect, clearing peerIp, starting scan")
                syncManager.setPeerIp(null)
                startSubnetScan(network)
                return
            }
            if (peerIp == null) {
                AppLogger.i(TAG, "WiFi connected — starting subnet scan")
                startSubnetScan(network)
            } else {
                verifyJob = scope.launch {
                    AppLogger.d(TAG, "WiFi reconnected — verifying peer IP $peerIp")
                    delay(1_500L)
                    var deviceId: String? = null
                    for (attempt in 1..3) {
                        deviceId = httpClient.identify(peerIp, senderDeviceId = deviceIdManager.deviceId)
                        if (deviceId == prefs.pairedDeviceId) break
                        if (attempt < 3) {
                            AppLogger.d(TAG, "WiFi reconnected — verify attempt $attempt failed — retrying in 3s")
                            delay(3_000L)
                        }
                    }
                    if (deviceId == prefs.pairedDeviceId) {
                        AppLogger.i(TAG, "WiFi reconnected — peer confirmed at $peerIp")
                        triggerSync()
                    } else {
                        AppLogger.i(TAG, "WiFi reconnected — peer IP stale, clearing IP, starting scan")
                        syncManager.setPeerIp(null)
                        startSubnetScan()
                    }
                }
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            val newIp = linkProperties.linkAddresses
                .firstOrNull { it.address.hostAddress?.contains('.') == true }
                ?.address?.hostAddress ?: return
            val prevIp = prefs.lastKnownOwnIp
            if (newIp != prevIp) {
                AppLogger.w(TAG, "Own IP changed: $prevIp → $newIp")
                prefs.lastKnownOwnIp = newIp
                val peerIp = syncManager.peerIp.value
                if (peerIp != null && peerIp == newIp) {
                    AppLogger.w(TAG, "New IP ($newIp) matches peerIp — self-loop detected on IP change, clearing peerIp, starting scan")
                    syncManager.setPeerIp(null)
                    startSubnetScan()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val info = intent.getParcelableExtra<android.net.NetworkInfo>(
                WifiManager.EXTRA_NETWORK_INFO
            ) ?: return
            if (!info.isConnectedOrConnecting) {
                AppLogger.i(TAG, "WiFi disconnected")
                scanJob?.cancel()
                verifyJob?.cancel()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat()
        AppLogger.i(TAG, "Service started")
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(request, ipTrackingCallback)
        startHttpServer()
        scheduleKeepAlive()
        observeTaskChanges()
        registerReceiver(wifiReceiver, IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SYNC_NOW -> triggerSync()
            ACTION_KEEPALIVE -> performKeepAlive()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.i(TAG, "Service stopped")
        connectivityManager.unregisterNetworkCallback(ipTrackingCallback)
        unregisterReceiver(wifiReceiver)
        httpServer?.stop()
        scanJob?.cancel()
        verifyJob?.cancel()
    }

    private fun startHttpServer() {
        val webUiHtml = try {
            assets.open("webui.html").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load webui.html: ${e.message}", e)
            ""
        }
        httpServer = HttpServer(
            port = 8080,
            ownDeviceId = deviceIdManager.deviceId,
            onSyncRequest = { incoming, senderIp ->
                if (senderIp.isNotEmpty() && incoming.deviceId == prefs.pairedDeviceId) {
                    val currentPeerIp = syncManager.peerIp.value
                    if (currentPeerIp != senderIp) {
                        if (currentPeerIp == null) {
                            AppLogger.i(TAG, "Peer reached us from $senderIp — saving IP")
                        } else {
                            AppLogger.i(TAG, "Peer IP changed: $currentPeerIp → $senderIp (via /sync)")
                        }
                        syncManager.setPeerIp(senderIp)
                        scanJob?.cancel()
                        verifyJob?.cancel()
                    }
                }

                syncUseCase.mergeAll(
                    incoming.tasks.map { it.toDomain() },
                    incoming.devices.map { it.toDomain() }
                )

                val localTasks = syncUseCase.getLocalSnapshot()
                val responseTasks = if (incoming.sinceTimestamp > 0L) {
                    localTasks.filter { !it.isSecret && it.updatedAt > incoming.sinceTimestamp }
                } else {
                    localTasks.filter { !it.isSecret }
                }
                val localDevices = buildDevicesPayload()

                val now = System.currentTimeMillis()
                prefs.lastSyncAt = now
                syncManager.setLastSyncAt(now)

                SyncPayload(
                    deviceId = deviceIdManager.deviceId,
                    tasks = responseTasks.map { it.toDto() },
                    devices = localDevices.map { it.toDto() }
                )
            },
            onPeerIdentified = { callerDeviceId, senderIp ->
                if (callerDeviceId == prefs.pairedDeviceId) {
                    val currentPeerIp = syncManager.peerIp.value
                    if (currentPeerIp != senderIp) {
                        if (currentPeerIp == null) {
                            AppLogger.i(TAG, "Peer reached us from $senderIp via keepAlive — saving IP")
                        } else {
                            AppLogger.i(TAG, "Peer IP changed: $currentPeerIp → $senderIp (via keepAlive)")
                        }
                        syncManager.setPeerIp(senderIp)
                        scanJob?.cancel()
                        verifyJob?.cancel()
                        scope.launch { triggerSync() }
                    }
                }
            },
            isWebUiEnabled = { prefs.webUiEnabled },
            webUiHtml = webUiHtml,
            onWebListTasks = {
                val tasks = taskRepository.getAllTasksSnapshot().filter { !it.isSecret && !it.deleted }
                SyncPayload(
                    deviceId = deviceIdManager.deviceId,
                    tasks = tasks.map { it.toDto() },
                    devices = buildDevicesPayload().map { it.toDto() }
                )
            },
            onWebCreateTask = { req ->
                val now = System.currentTimeMillis()
                val task = Task(
                    id = UUID.randomUUID().toString(),
                    title = req.title,
                    description = req.description,
                    assignedTo = req.assignedTo,
                    priority = Priority.fromString(req.priority),
                    dueDate = req.dueDate,
                    createdBy = deviceIdManager.deviceId,
                    createdAt = now,
                    updatedAt = now
                )
                taskRepository.upsertTask(task)
                AppLogger.i(TAG, "Web UI: created task '${task.title}'")
                if (task.assignedTo == deviceIdManager.deviceId) {
                    notificationScheduler.scheduleDueNotifications(task)
                }
            },
            onWebToggleTask = { id ->
                taskRepository.getTaskById(id)?.let { task ->
                    val now = System.currentTimeMillis()
                    val next = if (task.status == TaskStatus.TODO) TaskStatus.DONE else TaskStatus.TODO
                    val updated = task.copy(
                        status = next,
                        updatedAt = now,
                        completedAt = if (next == TaskStatus.DONE) now else null
                    )
                    taskRepository.upsertTask(updated)
                    val eligible = updated.assignedTo == deviceIdManager.deviceId &&
                        updated.status != TaskStatus.DONE &&
                        !updated.deleted
                    if (eligible) {
                        notificationScheduler.scheduleDueNotifications(updated)
                        notificationScheduler.scheduleRecurrenceSpawn(updated)
                    } else {
                        notificationScheduler.cancelDueNotifications(updated.id)
                        notificationScheduler.cancelRecurrenceSpawn(updated.id)
                    }
                    AppLogger.i(TAG, "Web UI: toggled '${updated.title}' -> ${updated.status}")
                }
            }
        ).also {
            try {
                it.start()
                AppLogger.i(TAG, "HTTP server started on :8080")
            } catch (e: Exception) {
                AppLogger.e(TAG, "HTTP server failed to start: ${e.message}", e)
            }
        }
    }

    private fun isWifiConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun startSubnetScan(confirmedNetwork: Network? = null) {
        if (prefs.pairedDeviceId == null) {
            AppLogger.d(TAG, "startSubnetScan — no paired device, skipping")
            return
        }
        if (confirmedNetwork == null && !isWifiConnected()) {
            AppLogger.d(TAG, "startSubnetScan — WiFi not connected, skipping")
            return
        }
        scanJob?.cancel()
        scanJob = scope.launch {
            val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FamilyTasks:scan")
            wakeLock.acquire(120_000L)
            AppLogger.i(TAG, "Subnet scan started")
            try {
                var ip: String? = null
                for (attempt in 1..3) {
                    ip = subnetScanner.scan()
                    if (ip != null) break
                    if (attempt < 3) {
                        AppLogger.d(TAG, "Scan attempt $attempt found nothing — retrying in 5s")
                        delay(5_000L)
                    }
                }
                if (ip != null) {
                    syncManager.setPeerIp(ip)
                    triggerSync()
                }
            } catch (e: CancellationException) {
                AppLogger.d(TAG, "Subnet scan cancelled")
                throw e
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    private fun scheduleKeepAlive() {
        val alarmManager = getSystemService(AlarmManager::class.java)
        val intent = Intent(this, KeepAliveReceiver::class.java).apply {
            action = ACTION_KEEPALIVE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val now = System.currentTimeMillis()
        val nextBoundary = now - (now % HOUR_MS) + HOUR_MS
        val triggerAt = if (nextBoundary - now < 5_000L) nextBoundary + HOUR_MS else nextBoundary
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
    }

    private fun logDeviceState() {
        val pm = getSystemService(PowerManager::class.java)
        val wm = getSystemService(WifiManager::class.java)
        val network = connectivityManager.activeNetwork
        val caps = network?.let { connectivityManager.getNetworkCapabilities(it) }
        val hasWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
        val isDoze = pm.isDeviceIdleMode
        val battIgnored = pm.isIgnoringBatteryOptimizations(packageName)
        AppLogger.i(TAG, "KeepAlive diag: doze=$isDoze battIgnored=$battIgnored wifiOn=${wm.isWifiEnabled} activeWifi=$hasWifi ownIp=${prefs.lastKnownOwnIp} peerIp=${syncManager.peerIp.value}")
    }

    private fun performKeepAlive() {
        scheduleKeepAlive()
        scope.launch {
            logDeviceState()
            if (!isWifiConnected()) {
                AppLogger.d(TAG, "KeepAlive — WiFi not connected, skipping")
                return@launch
            }
            val peerIp = syncManager.peerIp.value
            if (peerIp == null) {
                if (scanJob?.isActive != true) {
                    AppLogger.d(TAG, "KeepAlive — no peer IP, starting scan")
                    startSubnetScan()
                }
                return@launch
            }
            val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FamilyTasks:keepAlive")
            wakeLock.acquire(30_000L)
            try {
                var deviceId: String? = null
                for (attempt in 1..3) {
                    deviceId = httpClient.identify(peerIp, senderDeviceId = deviceIdManager.deviceId)
                    if (deviceId == prefs.pairedDeviceId) break
                    if (attempt < 3) {
                        AppLogger.d(TAG, "KeepAlive identify attempt $attempt failed — retrying in 3s")
                        delay(3_000L)
                    }
                }
                if (deviceId == prefs.pairedDeviceId) {
                    AppLogger.d(TAG, "KeepAlive OK — peer still at $peerIp")
                } else {
                    if (deviceId != null) {
                        AppLogger.w(TAG, "KeepAlive — unexpected deviceId ${deviceId.take(8)} at $peerIp (expected ${prefs.pairedDeviceId?.take(8)}) — self-loop?")
                    }
                    AppLogger.i(TAG, "KeepAlive failed — peer IP stale, starting scan")
                    syncManager.setPeerIp(null)
                    startSubnetScan()
                }
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeTaskChanges() {
        scope.launch {
            taskRepository.observeAllTasks()
                .map { tasks -> tasks.filter { !it.isSecret } }
                .distinctUntilChanged()
                .drop(1)
                .debounce(3_000L)
                .collect { triggerSync() }
        }
    }

    private fun triggerSync() {
        val peerIp = syncManager.peerIp.value ?: run {
            AppLogger.d(TAG, "triggerSync skipped — no peer IP, starting scan")
            if (scanJob?.isActive != true) startSubnetScan()
            return
        }
        scope.launch {
            if (!syncMutex.tryLock()) {
                AppLogger.d(TAG, "triggerSync skipped — already syncing")
                return@launch
            }
            AppLogger.i(TAG, "triggerSync → $peerIp")
            syncManager.setSyncState(SyncState.SYNCING)
            try {
                val snapshotTimestamp = System.currentTimeMillis()
                val watermark = prefs.lastConfirmedSyncTimestamp
                    .takeIf { snapshotTimestamp >= it } ?: run {
                        AppLogger.w(TAG, "Clock jumped back — resetting sync watermark to 0")
                        prefs.lastConfirmedSyncTimestamp = 0L
                        0L
                    }
                val localTasks = syncUseCase.getLocalSnapshot()
                val delta = localTasks.filter { !it.isSecret && it.updatedAt > watermark }
                val localDevices = buildDevicesPayload()
                val payload = SyncPayload(
                    deviceId = deviceIdManager.deviceId,
                    sinceTimestamp = watermark,
                    tasks = delta.map { it.toDto() },
                    devices = localDevices.map { it.toDto() }
                )
                AppLogger.d(TAG, "Delta: sending ${delta.size}/${localTasks.size} tasks (watermark=$watermark)")
                val response = httpClient.sync(peerIp, 8080, payload)
                if (response != null && response.deviceId == prefs.pairedDeviceId) {
                    val remoteTasks = response.tasks.map { it.toDomain() }
                    syncUseCase.mergeAll(remoteTasks, response.devices.map { it.toDomain() })
                    val purged = syncUseCase.purgeConfirmedTombstones(localTasks, remoteTasks)
                    if (purged > 0) {
                        AppLogger.i(TAG, "Purged $purged confirmed tombstone(s)")
                    }
                    prefs.lastConfirmedSyncTimestamp = snapshotTimestamp
                    val now = System.currentTimeMillis()
                    prefs.lastSyncAt = now
                    syncManager.setLastSyncAt(now)
                    syncManager.setSyncState(SyncState.SUCCESS)
                    AppLogger.i(TAG, "Sync OK: sent ${delta.size}/${localTasks.size}, received ${response.tasks.size}")
                } else if (response != null) {
                    AppLogger.w(TAG, "Sync response from unexpected device ${response.deviceId.take(8)} at $peerIp (expected ${prefs.pairedDeviceId?.take(8)}) — self-loop? Clearing IP, starting scan")
                    syncManager.setPeerIp(null)
                    syncManager.setSyncState(SyncState.ERROR)
                    startSubnetScan()
                } else {
                    syncManager.setSyncState(SyncState.ERROR)
                    AppLogger.w(TAG, "Sync failed: null response from $peerIp")
                }
            } catch (e: PeerUnreachableException) {
                AppLogger.w(TAG, "Peer unreachable: $peerIp — clearing IP, starting scan")
                syncManager.setPeerIp(null)
                syncManager.setSyncState(SyncState.ERROR)
                startSubnetScan()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Sync error: ${e.message}", e)
                syncManager.setSyncState(SyncState.ERROR)
            } finally {
                syncMutex.unlock()
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
            .setContentIntent(openAppPendingIntent())
            .build()

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    companion object {
        private const val TAG = "SyncService"
        const val CHANNEL_ID = "sync_service"
        const val NOTIFICATION_ID = 1
        const val ACTION_SYNC_NOW = "com.familytasks.app.SYNC_NOW"
        const val ACTION_KEEPALIVE = "com.familytasks.app.KEEPALIVE"
    }
}
