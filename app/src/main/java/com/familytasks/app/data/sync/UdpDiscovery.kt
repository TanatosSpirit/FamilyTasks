package com.familytasks.app.data.sync

import android.net.wifi.WifiManager
import com.familytasks.app.core.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

private const val UDP_PORT = 9090
private const val BROADCAST_ADDRESS = "255.255.255.255"
private const val BURST_COUNT = 5
private const val BURST_INTERVAL_MS = 1_500L
private const val BURST_PAUSE_FAST_MS = 30_000L    // cycles 1–4  (~2 min)
private const val BURST_PAUSE_MEDIUM_MS = 300_000L // cycles 5–10 (~25 min)
private const val BURST_PAUSE_SLOW_MS = 900_000L   // cycles 11+  (15 min)

@Serializable
data class DiscoveryPacket(val deviceId: String, val httpPort: Int)

class UdpDiscovery(
    private val wifiManager: WifiManager,
    private val ownDeviceId: String,
    private val pairedDeviceId: String?,
    private val onPeerDiscovered: (ip: String, deviceId: String) -> Unit
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var broadcastJob: Job? = null
    private var listenJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listenSocket: DatagramSocket? = null
    @Volatile private var cycleCount = 0

    fun start(scope: CoroutineScope) {
        acquireLock()
        startListening(scope)
        startBurst(scope, resetCycle = true)
    }

    fun stop() {
        broadcastJob?.cancel()
        listenJob?.cancel()
        listenSocket?.close()
        listenSocket = null
        releaseLock()
    }

    // Peer IP is now known — stop sending, but keep listening for reconnects
    fun pauseDiscovery() {
        AppLogger.i(TAG, "UDP broadcast paused — peer found")
        broadcastJob?.cancel()
        broadcastJob = null
    }

    // resetCycle=true for WiFi reconnect (start fresh); false after HTTP failure (preserve backoff)
    fun restartDiscovery(scope: CoroutineScope, resetCycle: Boolean = true) {
        broadcastJob?.cancel()
        startBurst(scope, resetCycle)
    }

    private fun acquireLock() {
        if (multicastLock?.isHeld == true) return
        multicastLock = wifiManager.createMulticastLock("FamilyTasksLock").also {
            it.setReferenceCounted(false)
            it.acquire()
            AppLogger.i(TAG, "MulticastLock acquired")
        }
    }

    private fun releaseLock() {
        if (multicastLock?.isHeld == true) {
            AppLogger.i(TAG, "MulticastLock released")
            multicastLock?.release()
        }
        multicastLock = null
    }

    private fun startBurst(scope: CoroutineScope, resetCycle: Boolean) {
        if (resetCycle) cycleCount = 0
        broadcastJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                repeat(BURST_COUNT) {
                    if (!isActive) return@launch
                    sendBroadcast()
                    delay(BURST_INTERVAL_MS)
                }
                cycleCount++
                val pause = when {
                    cycleCount <= 4 -> BURST_PAUSE_FAST_MS
                    cycleCount <= 10 -> BURST_PAUSE_MEDIUM_MS
                    else -> BURST_PAUSE_SLOW_MS
                }
                AppLogger.d(TAG, "Burst cycle $cycleCount — next in ${pause / 1000}s")
                delay(pause)
            }
        }
    }

    private fun startListening(scope: CoroutineScope) {
        listenJob = scope.launch(Dispatchers.IO) {
            val socket = try {
                DatagramSocket(UDP_PORT).also {
                    listenSocket = it
                    AppLogger.i(TAG, "UDP listening on port $UDP_PORT")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "UDP socket failed: ${e.message}", e)
                return@launch
            }
            val buffer = ByteArray(1024)
            while (isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val data = String(packet.data, 0, packet.length)
                    val discovery = json.decodeFromString<DiscoveryPacket>(data)
                    val senderIp = packet.address.hostAddress ?: continue
                    if (discovery.deviceId != ownDeviceId &&
                        (pairedDeviceId == null || discovery.deviceId == pairedDeviceId)
                    ) {
                        AppLogger.i(TAG, "UDP packet from $senderIp (${discovery.deviceId.take(8)})")
                        sendBroadcast() // respond so peer learns our IP
                        onPeerDiscovered(senderIp, discovery.deviceId)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun sendBroadcast() {
        try {
            val packet = DiscoveryPacket(deviceId = ownDeviceId, httpPort = 8080)
            val data = json.encodeToString(packet).toByteArray()
            val address = InetAddress.getByName(BROADCAST_ADDRESS)
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.send(DatagramPacket(data, data.size, address, UDP_PORT))
            }
            AppLogger.d(TAG, "UDP broadcast sent → $BROADCAST_ADDRESS:$UDP_PORT")
        } catch (e: Exception) {
            AppLogger.e(TAG, "UDP broadcast failed: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "UdpDiscovery"
    }
}
