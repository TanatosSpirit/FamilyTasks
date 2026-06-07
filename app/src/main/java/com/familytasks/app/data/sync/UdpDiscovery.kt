package com.familytasks.app.data.sync

import android.net.wifi.WifiManager
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
private const val BROADCAST_INTERVAL_MS = 30_000L
private const val BROADCAST_ADDRESS = "255.255.255.255"

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

    fun start(scope: CoroutineScope) {
        multicastLock = wifiManager.createMulticastLock("FamilyTasksLock").also {
            it.setReferenceCounted(true)
            it.acquire()
        }
        startListening(scope)
        startBroadcasting(scope)
    }

    fun stop() {
        broadcastJob?.cancel()
        listenJob?.cancel()
        listenSocket?.close()
        multicastLock?.release()
        multicastLock = null
    }

    fun broadcastOnce(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) { sendBroadcast() }
    }

    private fun startBroadcasting(scope: CoroutineScope) {
        broadcastJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                sendBroadcast()
                delay(BROADCAST_INTERVAL_MS)
            }
        }
    }

    private fun startListening(scope: CoroutineScope) {
        listenJob = scope.launch(Dispatchers.IO) {
            val socket = DatagramSocket(UDP_PORT).also { listenSocket = it }
            val buffer = ByteArray(1024)
            while (isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val data = String(packet.data, 0, packet.length)
                    val discovery = json.decodeFromString<DiscoveryPacket>(data)
                    if (discovery.deviceId != ownDeviceId &&
                        (pairedDeviceId == null || discovery.deviceId == pairedDeviceId)
                    ) {
                        onPeerDiscovered(packet.address.hostAddress ?: return@launch, discovery.deviceId)
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
        } catch (_: Exception) {}
    }
}
