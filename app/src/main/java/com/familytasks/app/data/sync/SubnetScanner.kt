package com.familytasks.app.data.sync

import com.familytasks.app.core.AppLogger
import com.familytasks.app.core.PreferencesManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

private const val SCAN_CONCURRENCY = 10

@Singleton
class SubnetScanner @Inject constructor(
    private val httpClient: HttpClient,
    private val prefs: PreferencesManager
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun scan(): String? {
        val pairedDeviceId = prefs.pairedDeviceId ?: return null
        val ownIp = prefs.lastKnownOwnIp ?: run {
            AppLogger.w(TAG, "Own IP unknown — skipping scan")
            return null
        }
        val subnet = ownIp.substringBeforeLast('.') + '.'
        AppLogger.i(TAG, "Scanning ${subnet}* for ${pairedDeviceId.take(8)}")

        var responded = 0
        var wrongId = 0

        val result = (1..254)
            .map { "$subnet$it" }
            .filter { it != ownIp }
            .asFlow()
            .flatMapMerge(concurrency = SCAN_CONCURRENCY) { ip ->
                flow {
                    val deviceId = httpClient.identify(ip)
                    if (deviceId != null) {
                        responded++
                        if (deviceId == pairedDeviceId) emit(ip)
                        else wrongId++
                    }
                }
            }
            .firstOrNull()

        if (result != null) {
            AppLogger.i(TAG, "Peer found at $result ($responded hosts responded)")
        } else {
            AppLogger.i(TAG, "Scan complete — peer not on subnet ${subnet}* " +
                "($responded responded, $wrongId wrong deviceId)")
        }
        return result
    }

    companion object {
        private const val TAG = "SubnetScanner"
    }
}
