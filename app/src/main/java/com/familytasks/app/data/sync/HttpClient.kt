package com.familytasks.app.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

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
            try {
                val body = json.encodeToString(payload).toRequestBody(mediaType)
                val request = Request.Builder()
                    .url("http://$peerIp:$port/sync")
                    .post(body)
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    response.body?.string()?.let { json.decodeFromString<SyncPayload>(it) }
                } else null
            } catch (e: Exception) {
                null
            }
        }
}
