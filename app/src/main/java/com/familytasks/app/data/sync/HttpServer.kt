package com.familytasks.app.data.sync

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream

class HttpServer(
    port: Int,
    private val onSyncRequest: suspend (SyncPayload) -> SyncPayload
) : NanoHTTPD(port) {

    private val json = Json { ignoreUnknownKeys = true }

    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.POST || session.uri != "/sync") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }

        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(contentLength)
        DataInputStream(session.inputStream).readFully(body)

        return try {
            val payload = json.decodeFromString<SyncPayload>(String(body))
            val response = runBlocking { onSyncRequest(payload) }
            val responseJson = json.encodeToString(response)
            newFixedLengthResponse(Response.Status.OK, "application/json", responseJson)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Error")
        }
    }
}
