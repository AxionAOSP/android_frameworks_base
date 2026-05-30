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
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.hardware.SensorPrivacyManager
import android.hardware.display.DisplayManager
import android.location.Location
import android.location.LocationManager
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
import android.os.Binder
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import android.view.Display
import com.android.settingslib.display.BrightnessUtils
import com.android.systemui.ax.AxPlatformFeatureController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.routines.model.Action
import com.android.systemui.routines.model.Trigger
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import javax.inject.Inject

@SysUISingleton
class ActionExecutor @Inject constructor(
    @Application private val context: Context,
    private val featureController: AxPlatformFeatureController,
    private val sensorPrivacyController: IndividualSensorPrivacyController,
    @Main private val mainHandler: Handler,
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val connectivityManager: ConnectivityManager,
    private val displayManager: DisplayManager,
    private val locationManager: LocationManager,
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

    suspend fun executeActions(
        actions: List<Action>,
        routineName: String,
        sourceTrigger: Trigger? = null,
    ) {
        for (action in actions) {
            runCatching {
                execute(action, routineName, sourceTrigger)
            }.onFailure { e ->
                Log.e(TAG, "Failed to execute action: $action [${e.javaClass.simpleName}: ${e.message}]", e)
            }
        }
    }

    private suspend fun execute(action: Action, routineName: String, sourceTrigger: Trigger?) {
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
            is Action.SendLocationSms -> sendLocationSms(action, sourceTrigger)
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
        val percent = action.level.coerceIn(0, 100)
        val display = context.display ?: context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        val info = display?.brightnessInfo
        val min = info?.brightnessMinimum ?: 0f
        val max = info?.brightnessMaximum ?: 1f
        val gammaVal = (percent * BrightnessUtils.GAMMA_SPACE_MAX) / 100
        val valFloat = BrightnessUtils.convertGammaToLinearFloat(gammaVal, min, max)
            .coerceIn(min, max)
        runCatching {
            displayManager.setBrightness(Display.DEFAULT_DISPLAY, valFloat)
        }.onFailure { e ->
            Log.e(TAG, "Failed to set display brightness via DisplayManager", e)
        }
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
        if (action.action.isNullOrBlank() &&
            (action.componentPackage.isNullOrBlank() || action.componentClass.isNullOrBlank())) {
            Log.w(TAG, "SendBroadcast missing both action and component; skipping")
            return
        }
        val intent = Intent().apply {
            action.action?.takeIf { it.isNotBlank() }?.let { setAction(it) }
            if (!action.componentPackage.isNullOrBlank() &&
                !action.componentClass.isNullOrBlank()) {
                component = ComponentName(action.componentPackage, action.componentClass)
            } else if (!action.componentPackage.isNullOrBlank()) {
                setPackage(action.componentPackage)
            }
            action.extras.forEach { (key, extra) -> putTypedExtra(this, key, extra) }
        }
        runCatching {
            when (action.mode) {
                Action.SendBroadcast.Mode.BROADCAST ->
                    context.sendBroadcastAsUser(intent, UserHandle.CURRENT)
                Action.SendBroadcast.Mode.START_SERVICE ->
                    context.startServiceAsUser(intent, UserHandle.CURRENT)
                Action.SendBroadcast.Mode.START_FOREGROUND_SERVICE ->
                    context.startForegroundServiceAsUser(intent, UserHandle.CURRENT)
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to dispatch ${action.mode} for $intent", e)
        }
    }

    private fun putTypedExtra(intent: Intent, key: String, extra: Action.SendBroadcast.IntentExtra) {
        val raw = extra.value
        runCatching {
            when (extra.type) {
                Action.SendBroadcast.IntentExtra.ExtraType.STRING -> intent.putExtra(key, raw)
                Action.SendBroadcast.IntentExtra.ExtraType.INT -> intent.putExtra(key, raw.toInt())
                Action.SendBroadcast.IntentExtra.ExtraType.LONG -> intent.putExtra(key, raw.toLong())
                Action.SendBroadcast.IntentExtra.ExtraType.BOOLEAN ->
                    intent.putExtra(key, raw.equals("true", ignoreCase = true) || raw == "1")
                Action.SendBroadcast.IntentExtra.ExtraType.FLOAT -> intent.putExtra(key, raw.toFloat())
                Action.SendBroadcast.IntentExtra.ExtraType.DOUBLE -> intent.putExtra(key, raw.toDouble())
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed to coerce extra '$key' (${extra.type}) value '$raw'; sending as String", e)
            intent.putExtra(key, raw)
        }
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

    private fun sendLocationSms(action: Action.SendLocationSms, sourceTrigger: Trigger?) {
        val target = action.phoneNumber?.takeIf { it.isNotBlank() }
            ?: sourcePhoneNumber(sourceTrigger)
        if (target.isNullOrBlank()) {
            Log.w(TAG, "SendLocationSms missing target phone number")
            return
        }
        val location = getLastKnownLocation()
        val message = location?.let {
            context.getString(R.string.ax_routines_sms_location_reply, formatLocationUrl(it))
        } ?: context.getString(R.string.ax_routines_sms_location_unavailable)
        SmsManager.getDefault().sendTextMessage(target, null, message, null, null)
    }

    private fun sourcePhoneNumber(sourceTrigger: Trigger?): String? = when (sourceTrigger) {
        is Trigger.SmsMessage -> sourceTrigger.senderNumbers.firstOrNull()
        is Trigger.IncomingCall -> sourceTrigger.phoneNumbers.firstOrNull()
        else -> null
    }

    private fun getLastKnownLocation(): Location? =
        runCatching {
            locationManager.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()

    private fun formatLocationUrl(location: Location): String =
        String.format(Locale.US, MAP_URL_FORMAT, location.latitude, location.longitude)

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
        val defaultPort = if (isHttps) 443 else 80
        val port = if (url.port != -1) url.port else defaultPort
        val hostHeader = if (port == defaultPort) host else "$host:$port"
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
                append("Host: $hostHeader\r\n")
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
        private const val MAP_URL_FORMAT = "https://maps.google.com/?q=%.6f,%.6f"

        private val TRUST_ALL_CERTS = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }
}
