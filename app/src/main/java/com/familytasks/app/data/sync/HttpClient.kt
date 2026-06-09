package com.familytasks.app.data.sync

import com.familytasks.app.core.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class PeerUnreachableException : Exception()

@Singleton
class HttpClient @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json".toMediaType()

    suspend fun sync(peerIp: String, port: Int = 8080, payload: SyncPayload): SyncPayload? =
        withContext(Dispatchers.IO) {
            AppLogger.d(TAG, "POST /sync → $peerIp:$port (${payload.tasks.size} tasks)")
            try {
                val body = json.encodeToString(payload).toRequestBody(mediaType)
                val request = Request.Builder()
                    .url("http://$peerIp:$port/sync")
                    .post(body)
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    response.body?.string()?.let { json.decodeFromString<SyncPayload>(it) }
                } else {
                    AppLogger.w(TAG, "HTTP ${response.code} from $peerIp")
                    null
                }
            } catch (e: SocketTimeoutException) {
                AppLogger.w(TAG, "Timeout connecting to $peerIp:$port")
                throw PeerUnreachableException()
            } catch (e: ConnectException) {
                AppLogger.w(TAG, "Connection refused $peerIp:$port")
                throw PeerUnreachableException()
            } catch (e: Exception) {
                AppLogger.e(TAG, "HTTP error: ${e.message}", e)
                null
            }
        }

    companion object {
        private const val TAG = "HttpClient"
    }
}
