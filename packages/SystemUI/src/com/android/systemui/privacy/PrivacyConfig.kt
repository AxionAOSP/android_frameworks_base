/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.privacy

import android.content.Context
import android.database.ContentObserver
import android.location.flags.Flags.locationIndicatorsEnabled
import android.os.Handler
import android.provider.DeviceConfig
import android.provider.Settings
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.annotations.WeaklyReferencedCallback
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.res.R
import com.android.systemui.util.DeviceConfigProxy
import com.android.systemui.util.asIndenting
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.withIncreasedIndent
import java.io.PrintWriter
import java.lang.ref.WeakReference
import javax.inject.Inject

@SysUISingleton
class PrivacyConfig
@Inject
constructor(
    private val context: Context,
    @Main private val uiExecutor: DelayableExecutor,
    @Main private val mainHandler: Handler,
    private val deviceConfigProxy: DeviceConfigProxy,
    dumpManager: DumpManager,
) : Dumpable {

    @VisibleForTesting
    companion object {
        const val TAG = "PrivacyConfig"
        private const val MIC_CAMERA = SystemUiDeviceConfigFlags.PROPERTY_MIC_CAMERA_ENABLED
        private const val MEDIA_PROJECTION =
            SystemUiDeviceConfigFlags.PROPERTY_MEDIA_PROJECTION_INDICATORS_ENABLED
        private const val DEFAULT_MIC_CAMERA = true
        private const val DEFAULT_MEDIA_PROJECTION = true
        private const val SETTING_PRIVACY_CAMERA = "privacy_camera_indicator"
        private const val SETTING_PRIVACY_MIC = "privacy_mic_indicator"
        private const val SETTING_PRIVACY_LOCATION = "privacy_location_indicator"
        private const val SETTING_PRIVACY_PROJECTION = "privacy_projection_indicator"

        fun getPrivacyColor(locationOnly: Boolean): Int {
            if (locationOnly) {
                return R.color.privacy_chip_location_only_background
            }
            return R.color.privacy_chip_background
        }

        fun privacyItemsAreLocationOnly(privacyItems: List<PrivacyItem>): Boolean {
            return privacyItems.isNotEmpty() &&
                privacyItems.all { it.privacyType == PrivacyType.TYPE_LOCATION }
        }
    }

    private val callbacks = mutableListOf<WeakReference<Callback>>()

    var micCameraAvailable = isMicCameraEnabled()
        private set

    var locationAvailable = locationIndicatorsEnabled()
        private set

    var mediaProjectionAvailable = isMediaProjectionEnabled()
        private set

    var userCameraEnabled = isUserSettingEnabled(SETTING_PRIVACY_CAMERA)
        private set

    var userMicEnabled = isUserSettingEnabled(SETTING_PRIVACY_MIC)
        private set

    var userLocationEnabled = isUserSettingEnabled(SETTING_PRIVACY_LOCATION)
        private set

    var userProjectionEnabled = isUserSettingEnabled(SETTING_PRIVACY_PROJECTION)
        private set

    private val devicePropertiesChangedListener =
        DeviceConfig.OnPropertiesChangedListener { properties ->
            if (DeviceConfig.NAMESPACE_PRIVACY == properties.namespace) {
                // Running on the ui executor so can iterate on callbacks
                if (properties.keyset.contains(MIC_CAMERA)) {
                    micCameraAvailable = properties.getBoolean(MIC_CAMERA, DEFAULT_MIC_CAMERA)
                    callbacks.forEach { it.get()?.onFlagMicCameraChanged(micCameraAvailable) }
                }

                if (locationAvailable) {
                    callbacks.forEach { it.get()?.onFlagLocationChanged(locationAvailable) }
                }

                if (properties.keyset.contains(MEDIA_PROJECTION)) {
                    mediaProjectionAvailable =
                        properties.getBoolean(MEDIA_PROJECTION, DEFAULT_MEDIA_PROJECTION)
                    callbacks.forEach {
                        it.get()?.onFlagMediaProjectionChanged(mediaProjectionAvailable)
                    }
                }
            }
        }

    private val settingsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            uiExecutor.execute {
                userCameraEnabled = isUserSettingEnabled(SETTING_PRIVACY_CAMERA)
                userMicEnabled = isUserSettingEnabled(SETTING_PRIVACY_MIC)
                userLocationEnabled = isUserSettingEnabled(SETTING_PRIVACY_LOCATION)
                userProjectionEnabled = isUserSettingEnabled(SETTING_PRIVACY_PROJECTION)
                callbacks.forEach { it.get()?.onPrivacyIndicatorSettingsChanged() }
            }
        }
    }

    init {
        dumpManager.registerNormalDumpable(TAG, this)
        deviceConfigProxy.addOnPropertiesChangedListener(
            DeviceConfig.NAMESPACE_PRIVACY,
            uiExecutor,
            devicePropertiesChangedListener,
        )
        val resolver = context.contentResolver
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(SETTING_PRIVACY_CAMERA), false, settingsObserver)
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(SETTING_PRIVACY_MIC), false, settingsObserver)
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(SETTING_PRIVACY_LOCATION), false, settingsObserver)
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(SETTING_PRIVACY_PROJECTION), false, settingsObserver)
    }

    fun isPrivacyTypeEnabled(type: PrivacyType): Boolean {
        return when (type) {
            PrivacyType.TYPE_CAMERA -> userCameraEnabled
            PrivacyType.TYPE_MICROPHONE -> userMicEnabled
            PrivacyType.TYPE_LOCATION -> userLocationEnabled
            PrivacyType.TYPE_MEDIA_PROJECTION -> userProjectionEnabled
        }
    }

    private fun isUserSettingEnabled(key: String): Boolean {
        return Settings.Secure.getInt(context.contentResolver, key, 1) == 1
    }

    private fun isMicCameraEnabled(): Boolean {
        return deviceConfigProxy.getBoolean(
            DeviceConfig.NAMESPACE_PRIVACY,
            MIC_CAMERA,
            DEFAULT_MIC_CAMERA,
        )
    }

    private fun isMediaProjectionEnabled(): Boolean {
        return deviceConfigProxy.getBoolean(
            DeviceConfig.NAMESPACE_PRIVACY,
            MEDIA_PROJECTION,
            DEFAULT_MEDIA_PROJECTION,
        )
    }

    fun addCallback(callback: Callback) {
        addCallback(WeakReference(callback))
    }

    fun removeCallback(callback: Callback) {
        removeCallback(WeakReference(callback))
    }

    private fun addCallback(callback: WeakReference<Callback>) {
        uiExecutor.execute { callbacks.add(callback) }
    }

    private fun removeCallback(callback: WeakReference<Callback>) {
        uiExecutor.execute {
            // Removes also if the callback is null
            callbacks.removeIf { it.get()?.equals(callback.get()) ?: true }
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        val ipw = pw.asIndenting()
        ipw.println("PrivacyConfig state:")
        ipw.withIncreasedIndent {
            ipw.println("micCameraAvailable: $micCameraAvailable")
            ipw.println("locationAvailable: $locationAvailable")
            ipw.println("mediaProjectionAvailable: $mediaProjectionAvailable")
            ipw.println("userCameraEnabled: $userCameraEnabled")
            ipw.println("userMicEnabled: $userMicEnabled")
            ipw.println("userLocationEnabled: $userLocationEnabled")
            ipw.println("userProjectionEnabled: $userProjectionEnabled")
            ipw.println("Callbacks:")
            ipw.withIncreasedIndent {
                callbacks.forEach { callback -> callback.get()?.let { ipw.println(it) } }
            }
        }
        ipw.flush()
    }

    @WeaklyReferencedCallback
    interface Callback {
        fun onFlagMicCameraChanged(flag: Boolean) {}

        fun onFlagLocationChanged(flag: Boolean) {}

        fun onFlagMediaProjectionChanged(flag: Boolean) {}

        fun onPrivacyIndicatorSettingsChanged() {}
    }
}
