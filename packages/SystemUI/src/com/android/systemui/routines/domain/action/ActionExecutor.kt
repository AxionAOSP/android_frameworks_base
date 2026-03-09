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
import android.media.AudioManager
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.systemui.ax.AxPlatformFeatureController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.routines.model.Action
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController
import kotlinx.coroutines.delay
import javax.inject.Inject

@SysUISingleton
class ActionExecutor @Inject constructor(
    @Application private val context: Context,
    private val featureController: AxPlatformFeatureController,
    private val sensorPrivacyController: IndividualSensorPrivacyController,
) {

    private val audioManager by lazy {
        context.getSystemService(AudioManager::class.java)
    }

    private val notificationManager by lazy {
        context.getSystemService(NotificationManager::class.java)
    }

    private val resolver: ContentResolver = context.contentResolver

    @Volatile
    private var channelCreated = false

    suspend fun executeActions(actions: List<Action>, routineName: String) {
        for (action in actions) {
            runCatching {
                execute(action, routineName)
            }.onFailure { e ->
                Log.e(TAG, "Failed to execute action: $action", e)
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
    }
}
