package com.familytasks.app.data.sync

import com.familytasks.app.core.AppLogger
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream

class HttpServer(
    port: Int,
    private val ownDeviceId: String,
    private val onSyncRequest: suspend (payload: SyncPayload, senderIp: String) -> SyncPayload,
    private val onPeerIdentified: (callerDeviceId: String, senderIp: String) -> Unit = { _, _ -> }
) : NanoHTTPD(port) {

    private val json = Json { ignoreUnknownKeys = true }

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.method == Method.GET && session.uri == "/identify" -> handleIdentify(session)
            session.method == Method.POST && session.uri == "/sync" -> handleSync(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun handleIdentify(session: IHTTPSession): Response {
        val callerDeviceId = session.parms["deviceId"]
        val senderIp = session.headers["remote-addr"] ?: ""
        if (callerDeviceId != null && senderIp.isNotEmpty()) {
            onPeerIdentified(callerDeviceId, senderIp)
        }
        val body = json.encodeToString(IdentifyResponse(deviceId = ownDeviceId))
        return newFixedLengthResponse(Response.Status.OK, "application/json", body)
    }

    private fun handleSync(session: IHTTPSession): Response {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(contentLength)
        DataInputStream(session.inputStream).readFully(body)

        return try {
            val payload = json.decodeFromString<SyncPayload>(String(body))
            val senderIp = session.headers["remote-addr"] ?: ""
            AppLogger.i(TAG, "Incoming /sync from ${payload.deviceId.take(8)} (${payload.tasks.size} tasks)")
            val response = runBlocking { onSyncRequest(payload, senderIp) }
            val responseJson = json.encodeToString(response)
            AppLogger.i(TAG, "Responded with ${response.tasks.size} tasks")
            newFixedLengthResponse(Response.Status.OK, "application/json", responseJson)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Server error: ${e.message}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Error")
        }
    }

    companion object {
        private const val TAG = "HttpServer"
    }
}
