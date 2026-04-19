/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.routines.domain.action

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.hardware.SensorPrivacyManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.InetAddresses
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.os.Binder
import android.util.Log
import com.android.systemui.ax.AxPlatformFeatureController
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import org.json.JSONObject
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.routines.model.Action
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@SysUISingleton
class ActionExecutor @Inject constructor(
    @Application private val context: Context,
    private val featureController: AxPlatformFeatureController,
    private val sensorPrivacyController: IndividualSensorPrivacyController,
    @Main private val mainHandler: Handler,
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val connectivityManager: ConnectivityManager,
) {

    private val audioManager by lazy {
        context.getSystemService(AudioManager::class.java)
    }

    private val notificationManager by lazy {
        context.getSystemService(NotificationManager::class.java)
    }

    private val resolver: ContentResolver = context.contentResolver
    private var activeRingtone: Ringtone? = null

    @Volatile
    private var channelCreated = false

    suspend fun executeActions(actions: List<Action>, routineName: String) {
        for (action in actions) {
            runCatching {
                execute(action, routineName)
            }.onFailure { e ->
                Log.e(TAG, "Failed to execute action: $action [${e.javaClass.simpleName}: ${e.message}]", e)
            }
        }
    }

    private suspend fun execute(action: Action, routineName: String) {
        when (action) {
            is Action.SetFeature -> featureController.setEnabled(action.feature, action.enabled)
            is Action.ToggleFeature -> featureController.toggle(action.feature)
            is Action.SetVolume -> setVolume(action)
            is Action.SetBrightness -> setBrightness(action)
            is Action.SetRingerMode -> setRingerMode(action)
            is Action.LaunchApp -> launchApp(action)
            is Action.SendBroadcast -> sendBroadcast(action)
            is Action.ShowNotification -> showNotification(action, routineName)
            is Action.Delay -> delay(action.durationMs)
            is Action.SetSetting -> setSetting(action)
            is Action.SetSensorPrivacy -> setSensorPrivacy(action)
            is Action.PlaySound -> playSound(action)
            is Action.HttpRequest -> executeHttpRequest(action)
        }
    }

    private fun setVolume(action: Action.SetVolume) {
        val maxVolume = audioManager?.getStreamMaxVolume(action.streamType) ?: return
        val targetVolume = (action.level * maxVolume / 100).coerceIn(0, maxVolume)
        audioManager?.setStreamVolume(
            action.streamType,
            targetVolume,
            0,
        )
    }

    private fun setBrightness(action: Action.SetBrightness) {
        Settings.System.putIntForUser(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            UserHandle.USER_CURRENT,
        )
        val brightnessInt = (action.level.coerceIn(0, 100) * 255 / 100)
        Settings.System.putIntForUser(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS,
            brightnessInt,
            UserHandle.USER_CURRENT,
        )
    }

    private fun setRingerMode(action: Action.SetRingerMode) {
        audioManager?.ringerMode = action.mode
    }

    private fun launchApp(action: Action.LaunchApp) {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(action.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivityAsUser(intent, UserHandle.CURRENT)
    }

    private fun sendBroadcast(action: Action.SendBroadcast) {
        val intent = Intent(action.action)
        action.extras.forEach { (key, value) -> intent.putExtra(key, value) }
        context.sendBroadcastAsUser(intent, UserHandle.CURRENT)
    }

    private fun showNotification(action: Action.ShowNotification, routineName: String) {
        ensureNotificationChannel()
        val notification = Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(action.title)
            .setContentText(action.text)
            .setSubText(routineName)
            .setAutoCancel(true)
            .build()
        val notificationId = (routineName + action.title + action.text).hashCode()
        notificationManager?.notify(
            notificationId,
            notification,
        )
    }

    private fun setSetting(action: Action.SetSetting) {
        when (action.table) {
            Action.SetSetting.SettingsTable.SECURE ->
                Settings.Secure.putStringForUser(
                    resolver, action.key, action.value, UserHandle.USER_CURRENT,
                )
            Action.SetSetting.SettingsTable.GLOBAL ->
                Settings.Global.putString(resolver, action.key, action.value)
            Action.SetSetting.SettingsTable.SYSTEM ->
                Settings.System.putStringForUser(
                    resolver, action.key, action.value, UserHandle.USER_CURRENT,
                )
        }
    }

    private fun setSensorPrivacy(action: Action.SetSensorPrivacy) {
        sensorPrivacyController.setSensorBlocked(
            SensorPrivacyManager.Sources.OTHER,
            action.sensor,
            action.blocked,
        )
    }

    private fun playSound(action: Action.PlaySound) {
        activeRingtone?.stop()
        val soundUri = action.uri?.let { Uri.parse(it) }
            ?: RingtoneManager.getDefaultUri(action.soundType)
            ?: return
        val ringtone = RingtoneManager.getRingtone(context, soundUri) ?: return
        ringtone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        ringtone.isLooping = false
        activeRingtone = ringtone
        ringtone.play()
        mainHandler.postDelayed({
            ringtone.stop()
            if (activeRingtone === ringtone) activeRingtone = null
        }, SOUND_MAX_DURATION_MS)
    }

    private suspend fun resolveHost(network: Network, host: String): List<InetAddress>? =
        withTimeoutOrNull(DNS_TIMEOUT_MS) {
            suspendCancellableCoroutine<List<InetAddress>?> { cont ->
                DnsResolver.getInstance().query(
                    network,
                    host,
                    DnsResolver.FLAG_EMPTY,
                    { it.run() },
                    null,
                    object : DnsResolver.Callback<List<InetAddress>> {
                        override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                            if (cont.isActive) cont.resumeWith(Result.success(answer))
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            Log.e(TAG, "DNS query failed for $host", error)
                            if (cont.isActive) cont.resumeWith(Result.success(null))
                        }
                    },
                )
            }
        }

    private suspend fun awaitInternet(requireValidated: Boolean): Network? {
        connectivityManager.activeNetwork?.let { current ->
            val caps = connectivityManager.getNetworkCapabilities(current)
            val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            if (hasInternet && (!requireValidated || validated)) {
                return current
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .apply {
                if (requireValidated) addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            .build()
        var registered: ConnectivityManager.NetworkCallback? = null
        return try {
            withTimeoutOrNull(NETWORK_WAIT_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            if (cont.isActive) cont.resumeWith(Result.success(network))
                        }
                    }
                    registered = callback
                    connectivityManager.registerNetworkCallback(request, callback)
                    cont.invokeOnCancellation {
                        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                        registered = null
                    }
                }
            }
        } finally {
            registered?.let {
                runCatching { connectivityManager.unregisterNetworkCallback(it) }
            }
        }
    }

    private suspend fun executeHttpRequest(action: Action.HttpRequest) =
        withContext(bgDispatcher) {
            val token = Binder.clearCallingIdentity()
            try {
                val timeout = action.timeoutMs.coerceIn(1000, Action.MAX_HTTP_TIMEOUT_MS)
                val network = awaitInternet(action.requireValidatedInternet)
                    ?: error("No internet within timeout")
                val url = URL(action.url)
                val host = url.host
                val resolved = if (InetAddresses.isNumericAddress(host)) {
                    listOf(InetAddresses.parseNumericAddress(host))
                } else {
                    resolveHost(network, host)?.takeIf { it.isNotEmpty() }
                        ?: resolveViaDoh(network, host)
                } ?: error("Unable to resolve $host")
                val ip = resolved.first()
                val responseCode = performRawHttpRequest(network, ip, url, action, timeout)
                Log.d(TAG, "HTTP ${action.method} ${action.url} -> $responseCode " +
                    "(via ${ip.hostAddress})")
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        }

    private suspend fun resolveViaDoh(network: Network, host: String): List<InetAddress>? =
        withTimeoutOrNull(DNS_TIMEOUT_MS) {
            runCatching {
                val dohUrl = URL("https://1.1.1.1/dns-query?name=$host&type=A")
                val conn = network.openConnection(dohUrl) as HttpURLConnection
                conn.setRequestProperty("Accept", "application/dns-json")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val answers = JSONObject(json).optJSONArray("Answer") ?: return@runCatching null
                val results = mutableListOf<InetAddress>()
                for (i in 0 until answers.length()) {
                    val data = answers.getJSONObject(i).optString("data")
                    if (data.isNotBlank()) {
                        runCatching { InetAddress.getByName(data) }
                            .getOrNull()?.let { results.add(it) }
                    }
                }
                Log.d(TAG, "DoH $host -> ${results.map { it.hostAddress }}")
                results.ifEmpty { null }
            }.getOrNull()
        }

    private fun performRawHttpRequest(
        network: Network,
        ip: InetAddress,
        url: URL,
        action: Action.HttpRequest,
        timeoutMs: Int,
    ): Int {
        val host = url.host
        val isHttps = url.protocol.equals("https", ignoreCase = true)
        val port = if (url.port != -1) url.port else if (isHttps) 443 else 80
        val path = (url.path.ifEmpty { "/" }) +
            (url.query?.let { "?$it" } ?: "")

        val rawSocket: Socket = network.socketFactory.createSocket()
        rawSocket.connect(InetSocketAddress(ip, port), timeoutMs)
        rawSocket.soTimeout = timeoutMs

        val socket: Socket = if (isHttps) {
            val ctx = SSLContext.getInstance("TLS")
            val trustManagers = if (action.ignoreSslErrors) arrayOf(TRUST_ALL_CERTS) else null
            ctx.init(null, trustManagers, null)
            val ssl = ctx.socketFactory.createSocket(rawSocket, host, port, true) as SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply {
                if (!InetAddresses.isNumericAddress(host)) {
                    serverNames = listOf(SNIHostName(host))
                }
                if (action.ignoreSslErrors) endpointIdentificationAlgorithm = null
            }
            ssl.startHandshake()
            ssl
        } else rawSocket

        return socket.use { s ->
            val req = buildString {
                append("${action.method} $path HTTP/1.1\r\n")
                append("Host: $host\r\n")
                append("Connection: close\r\n")
                action.headers.forEach { (k, v) -> append("$k: $v\r\n") }
                val bodyBytes = action.body?.toByteArray(Charsets.UTF_8)
                if (bodyBytes != null) {
                    append("Content-Length: ${bodyBytes.size}\r\n")
                }
                append("\r\n")
                if (action.body != null) append(action.body)
            }
            s.getOutputStream().apply {
                write(req.toByteArray(Charsets.UTF_8))
                flush()
            }
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val statusLine = reader.readLine() ?: return@use -1
            statusLine.split(" ", limit = 3).getOrNull(1)?.toIntOrNull() ?: -1
        }
    }

    private fun ensureNotificationChannel() {
        if (channelCreated) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.ax_routines_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        notificationManager?.createNotificationChannel(channel)
        channelCreated = true
    }

    companion object {
        private const val TAG = "RoutinesActionExecutor"
        private const val NOTIFICATION_CHANNEL_ID = "ax_routines"
        private const val SOUND_MAX_DURATION_MS = 5000L
        private const val NETWORK_WAIT_TIMEOUT_MS = 30_000L
        private const val DNS_TIMEOUT_MS = 10_000L

        private val TRUST_ALL_CERTS = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }
}
