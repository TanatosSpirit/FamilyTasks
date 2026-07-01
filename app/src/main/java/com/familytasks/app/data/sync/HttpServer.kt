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
    private val onPeerIdentified: (callerDeviceId: String, senderIp: String) -> Unit = { _, _ -> },
    private val isWebUiEnabled: () -> Boolean = { false },
    private val webUiHtml: String = "",
    private val onWebListTasks: suspend () -> SyncPayload = { SyncPayload(deviceId = "", tasks = emptyList(), devices = emptyList()) },
    private val onWebCreateTask: suspend (WebCreateTaskRequest) -> Unit = {},
    private val onWebToggleTask: suspend (taskId: String) -> Unit = {}
) : NanoHTTPD(port) {

    private val json = Json { ignoreUnknownKeys = true }
    private val toggleUriRegex = Regex("^/api/tasks/([^/]+)/toggle$")

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.method == Method.GET && session.uri == "/identify" -> handleIdentify(session)
            session.method == Method.POST && session.uri == "/sync" -> handleSync(session)
            session.method == Method.GET && session.uri == "/web" && isWebUiEnabled() ->
                newFixedLengthResponse(Response.Status.OK, "text/html", webUiHtml)
            session.method == Method.GET && session.uri == "/api/tasks" && isWebUiEnabled() -> handleWebListTasks()
            session.method == Method.POST && session.uri == "/api/tasks" && isWebUiEnabled() -> handleWebCreateTask(session)
            session.method == Method.POST && isWebUiEnabled() && toggleUriRegex.matches(session.uri) ->
                handleWebToggleTask(toggleUriRegex.find(session.uri)!!.groupValues[1])
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun handleIdentify(session: IHTTPSession): Response {
        val callerDeviceId = session.parameters["deviceId"]?.firstOrNull()
        val senderIp = session.headers["remote-addr"] ?: ""
        AppLogger.d(TAG, "/identify from $senderIp" + if (callerDeviceId != null) " deviceId=${callerDeviceId.take(8)}" else "")
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

    private fun handleWebListTasks(): Response {
        return try {
            val payload = runBlocking { onWebListTasks() }
            newFixedLengthResponse(Response.Status.OK, "application/json", json.encodeToString(payload))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Web UI list tasks error: ${e.message}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Error")
        }
    }

    private fun handleWebCreateTask(session: IHTTPSession): Response {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(contentLength)
        DataInputStream(session.inputStream).readFully(body)

        return try {
            val request = json.decodeFromString<WebCreateTaskRequest>(String(body))
            runBlocking { onWebCreateTask(request) }
            newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Web UI create task error: ${e.message}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Error")
        }
    }

    private fun handleWebToggleTask(taskId: String): Response {
        return try {
            runBlocking { onWebToggleTask(taskId) }
            newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Web UI toggle task error: ${e.message}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Error")
        }
    }

    companion object {
        private const val TAG = "HttpServer"
    }
}
