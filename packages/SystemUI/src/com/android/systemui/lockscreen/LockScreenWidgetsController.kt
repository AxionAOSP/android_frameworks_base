/*
 * Copyright (C) 2025 the AxionAOSP Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.lockscreen

import android.annotation.NonNull
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.session.MediaSessionLegacyHelper
import android.os.SystemClock
import android.provider.Settings
import android.os.UserHandle
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import androidx.annotation.Nullable
import com.android.settingslib.net.DataUsageController
import com.android.settingslib.Utils
import com.android.systemui.res.R
import com.android.systemui.Dependency
import com.android.systemui.animation.Expandable
import com.android.systemui.animation.view.LaunchableImageView
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.bluetooth.qsdialog.BluetoothTileDialogViewModel
import com.android.systemui.qs.tiles.dialog.InternetDialogManager
import com.android.systemui.statusbar.policy.BluetoothController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.statusbar.policy.HotspotController
import com.android.systemui.statusbar.connectivity.AccessPointController
import com.android.systemui.statusbar.connectivity.IconState
import com.android.systemui.statusbar.connectivity.NetworkController
import com.android.systemui.statusbar.connectivity.SignalCallback
import com.android.systemui.statusbar.connectivity.MobileDataIndicators
import com.android.systemui.statusbar.connectivity.WifiIndicators
import com.android.systemui.util.ActivityLauncherUtils
import com.android.systemui.util.MediaSessionManagerHelper
import com.android.internal.util.android.VibrationUtils

class LockScreenWidgetsController(private val view: View) :
    MediaSessionManagerHelper.MediaMetadataListener {

    companion object {
        private const val LOCKSCREEN_WIDGETS_ENABLED = "lockscreen_widgets_enabled"
        private const val LOCKSCREEN_WIDGETS_EXTRAS = "lockscreen_widgets_extras"
        private val WIDGETS_VIEW_IDS = intArrayOf(
            R.id.kg_item_placeholder1,
            R.id.kg_item_placeholder2,
            R.id.kg_item_placeholder3,
            R.id.kg_item_placeholder4
        )
        val BT_ACTIVE = R.drawable.qs_bluetooth_icon_on
        val BT_INACTIVE = R.drawable.qs_bluetooth_icon_off
        val DATA_ACTIVE = R.drawable.ic_signal_cellular_alt_24
        val DATA_INACTIVE = R.drawable.ic_mobiledata_off_24
        val RINGER_ACTIVE = R.drawable.ic_vibration_24
        val RINGER_INACTIVE = R.drawable.ic_ring_volume_24
        val TORCH_RES_ACTIVE = R.drawable.ic_flashlight_on
        val TORCH_RES_INACTIVE = R.drawable.ic_flashlight_off
        val WIFI_ACTIVE = R.drawable.ic_wifi_24
        val WIFI_INACTIVE = R.drawable.ic_wifi_off_24
        val HOTSPOT_ACTIVE = R.drawable.qs_hotspot_icon_on
        val HOTSPOT_INACTIVE = R.drawable.qs_hotspot_icon_off
    }

    private val mContext: Context = view.context
    private val mAccessPointController: AccessPointController =
        Dependency.get(AccessPointController::class.java)
    private val mBluetoothController: BluetoothController =
        Dependency.get(BluetoothController::class.java)
    private val mBluetoothTileDialogViewModel: BluetoothTileDialogViewModel =
        Dependency.get(BluetoothTileDialogViewModel::class.java)
    private val mConfigurationController: ConfigurationController =
        Dependency.get(ConfigurationController::class.java)
    private val mFlashlightController: FlashlightController =
        Dependency.get(FlashlightController::class.java)
    private val mInternetDialogManager: InternetDialogManager =
        Dependency.get(InternetDialogManager::class.java)
    private val mNetworkController: NetworkController =
        Dependency.get(NetworkController::class.java)
    private val mStatusBarStateController: StatusBarStateController =
        Dependency.get(StatusBarStateController::class.java)
    private val mMediaSessionManagerHelper: MediaSessionManagerHelper =
        MediaSessionManagerHelper.getInstance(mContext)
    private val mActivityLauncherUtils = ActivityLauncherUtils(mContext)
    private val mHotspotController: HotspotController =
        Dependency.get(HotspotController::class.java)
    private val mAudioManager: AudioManager =
        mContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var wifiButton: LaunchableImageView? = null
    private var dataButton: LaunchableImageView? = null
    private var ringerButton: LaunchableImageView? = null
    private var btButton: LaunchableImageView? = null
    private var torchButton: LaunchableImageView? = null
    private var mediaButton: LaunchableImageView? = null
    private var hotspotButton: LaunchableImageView? = null

    private var mDarkColor: Int = 0
    private var mDarkColorActive: Int = 0
    private var mLightColor: Int = 0
    private var mLightColorActive: Int = 0

    private val mCameraManager: CameraManager =
        mContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var mCameraId: String? = null
    private var isFlashOn = false
    private var mCallbacksRegistered = false

    private var mMainLockscreenWidgetsList: String? = null
    private lateinit var mMainWidgetViews: Array<LaunchableImageView?>
    private val mMainWidgetsList = mutableListOf<String>()

    private var mDozing = false
    private var mLockscreenWidgetsEnabled = false

    private val mConfigurationListener = object : ConfigurationController.ConfigurationListener {
        override fun onUiModeChanged() {
            updateWidgetViews()
        }
        override fun onThemeChanged() {
            updateWidgetViews()
        }
    }

    private val mStatusBarStateListener = object : StatusBarStateController.StateListener {
        override fun onStateChanged(newState: Int) {}
        override fun onDozingChanged(dozing: Boolean) {
            if (mDozing == dozing) return
            mDozing = dozing
            updateContainerVisibility()
        }
    }

    private val mFlashlightCallback = object : FlashlightController.FlashlightListener {
        override fun onFlashlightChanged(enabled: Boolean) {
            isFlashOn = enabled
            updateTorchButtonState()
        }
        override fun onFlashlightError() {}
        override fun onFlashlightAvailabilityChanged(available: Boolean) {
            isFlashOn = mFlashlightController.isEnabled() && available
            updateTorchButtonState()
        }
    }

    private val mRingerModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateRingerButtonState()
        }
    }

    private val mBtCallback = object : BluetoothController.Callback {
        override fun onBluetoothStateChange(enabled: Boolean) {
            updateBtState()
        }
        override fun onBluetoothDevicesChanged() {
            updateBtState()
        }
    }

    private val mWifiSignalCallback = WifiSignalCallback()
    private val mCellSignalCallback = CellSignalCallback()
    private val mHotspotCallback = HotspotCallback()
    private val mLockscreenWidgetsObserver = LockscreenWidgetsObserver()

    private val mDataController: DataUsageController = mNetworkController.mobileDataController

    init {
        try {
            mCameraId = mCameraManager.cameraIdList[0]
        } catch (e: Exception) {}
    }

    fun registerCallbacks() {
        if (mCallbacksRegistered) return
        mLockscreenWidgetsObserver.observe()
        mConfigurationController.addCallback(mConfigurationListener)
        mStatusBarStateController.addCallback(mStatusBarStateListener)
        mStatusBarStateListener.onDozingChanged(mStatusBarStateController.isDozing)
        mMediaSessionManagerHelper.addMediaMetadataListener(this)
        val ringerFilter = IntentFilter(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION)
        mContext.registerReceiver(mRingerModeReceiver, ringerFilter)
        updateWidgetViews()
        updateMediaPlaybackState()
        mCallbacksRegistered = true
    }

    fun unregisterCallbacks() {
        if (!mCallbacksRegistered) return
        mConfigurationController.removeCallback(mConfigurationListener)
        mStatusBarStateController.removeCallback(mStatusBarStateListener)
        mContext.unregisterReceiver(mRingerModeReceiver)
        mLockscreenWidgetsObserver.unobserve()
        mMediaSessionManagerHelper.removeMediaMetadataListener(this)
        mCallbacksRegistered = false
    }

    fun updateWidgetViews() {
        updateColors()
        mMainWidgetViews = Array(WIDGETS_VIEW_IDS.size) { index ->
            view.findViewById(WIDGETS_VIEW_IDS[index])
        }
        for (i in mMainWidgetViews.indices) {
            mMainWidgetViews[i]?.visibility =
                if (i < mMainWidgetsList.size) View.VISIBLE else View.GONE
        }
        for (i in 0 until minOf(mMainWidgetsList.size, mMainWidgetViews.size)) {
            val widgetType = mMainWidgetsList[i]
            mMainWidgetViews[i]?.let {
                setUpWidgetViews(it, widgetType)
                updateWidgetsResources(it)
            }
        }
        updateContainerVisibility()
    }

    private fun updateContainerVisibility() {
        val isMainWidgetsEmpty = mMainLockscreenWidgetsList.isNullOrEmpty()
        val mainWidgetsContainer = view.findViewById<View>(R.id.main_widgets_container)
        mainWidgetsContainer?.visibility = if (isMainWidgetsEmpty) View.GONE else View.VISIBLE
        val shouldHideContainer = isMainWidgetsEmpty || mDozing || !mLockscreenWidgetsEnabled
        view.visibility = if (shouldHideContainer) View.GONE else View.VISIBLE
    }

    private fun updateWidgetsResources(iv: LaunchableImageView) {
        iv.setBackgroundResource(R.drawable.lockscreen_widget_background_circle)
        setButtonActiveState(iv, false)
    }

    private fun updateColors() {
        mDarkColor = mContext.resources.getColor(R.color.lockscreen_widget_background_color_dark)
        mLightColor = mContext.resources.getColor(R.color.lockscreen_widget_background_color_light)
        mDarkColorActive =
            mContext.resources.getColor(R.color.lockscreen_widget_active_color_dark)
        mLightColorActive =
            mContext.resources.getColor(R.color.lockscreen_widget_active_color_light)
    }

    private fun isNightMode(): Boolean {
        val config = mContext.resources.configuration
        return (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun setUpWidgetViews(iv: LaunchableImageView, type: String) {
        var clickListener: View.OnClickListener? = null
        var longClickListener: View.OnLongClickListener? = null
        var drawableRes = 0

        when (type) {
            "none" -> {
                iv.visibility = View.GONE
                return
            }
            "wifi" -> {
                clickListener = View.OnClickListener { toggleWiFi() }
                longClickListener = View.OnLongClickListener { v ->
                    showInternetDialog(v)
                    true
                }
                drawableRes = WIFI_INACTIVE
                wifiButton = iv
            }
            "data" -> {
                clickListener = View.OnClickListener { toggleMobileData() }
                longClickListener = View.OnLongClickListener { v ->
                    showInternetDialog(v)
                    true
                }
                drawableRes = DATA_INACTIVE
                dataButton = iv
            }
            "ringer" -> {
                clickListener = View.OnClickListener { toggleRingerMode() }
                drawableRes = RINGER_INACTIVE
                ringerButton = iv
            }
            "bt" -> {
                clickListener = View.OnClickListener { toggleBluetoothState() }
                longClickListener = View.OnLongClickListener { v ->
                    showBluetoothDialog(v)
                    true
                }
                drawableRes = BT_INACTIVE
                btButton = iv
            }
            "torch" -> {
                clickListener = View.OnClickListener { toggleFlashlight() }
                drawableRes = TORCH_RES_INACTIVE
                torchButton = iv
            }
            "timer" -> {
                clickListener = View.OnClickListener { mActivityLauncherUtils.launchTimer() }
                drawableRes = R.drawable.ic_alarm
            }
            "calculator" -> {
                clickListener = View.OnClickListener { mActivityLauncherUtils.launchCalculator() }
                drawableRes = R.drawable.ic_calculator
            }
            "media" -> {
                clickListener = View.OnClickListener { toggleMediaPlaybackState() }
                longClickListener = View.OnLongClickListener { v ->
                    showMediaDialog(v)
                    true
                }
                drawableRes = R.drawable.ic_media_play
                mediaButton = iv
            }
            "hotspot" -> {
                clickListener = View.OnClickListener { toggleHotspot() }
                longClickListener = View.OnLongClickListener { v ->
                    showInternetDialog(v)
                    true
                }
                drawableRes = HOTSPOT_INACTIVE
                hotspotButton = iv
            }
            "wallet" -> {
                clickListener = View.OnClickListener { mActivityLauncherUtils.launchWalletApp() }
                drawableRes = R.drawable.ic_wallet_lockscreen
            }
            "qrscanner" -> {
                clickListener = View.OnClickListener { mActivityLauncherUtils.launchQrScanner() }
                drawableRes = R.drawable.ic_qr_code_scanner
            }
            else -> return
        }
        iv.setOnClickListener(clickListener)
        iv.setOnLongClickListener(longClickListener)
        iv.setImageResource(drawableRes)
    }

    private fun setButtonActiveState(iv: LaunchableImageView?, active: Boolean) {
        val (bgTint, tintColor) = if (active) {
            Pair(if (isNightMode()) mDarkColorActive else mLightColorActive,
                 if (isNightMode()) mDarkColor else mLightColor)
        } else {
            Pair(if (isNightMode()) mDarkColor else mLightColor,
                 if (isNightMode()) mLightColor else mDarkColor)
        }
        iv?.apply {
            backgroundTintList = ColorStateList.valueOf(bgTint)
            imageTintList = ColorStateList.valueOf(tintColor)
        }
    }

    private fun toggleMediaPlaybackState() {
        if (mMediaSessionManagerHelper.isMediaPlaying()) {
            dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PAUSE)
        } else {
            dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PLAY)
        }
    }

    private fun showMediaDialog(view: View) {
        val lastMediaPkg = getLastUsedMedia()
        if (lastMediaPkg.isNullOrEmpty()) return
        this.view.post {
            (this.view as? LockScreenWidgets)?.showMediaDialog(view, lastMediaPkg)
            VibrationUtils.triggerVibration(mContext, 2)
        }
    }

    private fun getLastUsedMedia(): String? {
        return Settings.System.getString(mContext.contentResolver, "media_session_last_package_name")
    }

    private fun dispatchMediaKeyWithWakeLockToMediaSession(keycode: Int) {
        val helper = MediaSessionLegacyHelper.getHelper(mContext) ?: return
        var event = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
            KeyEvent.ACTION_DOWN, keycode, 0)
        helper.sendMediaButtonEvent(event, true)
        event = KeyEvent.changeAction(event, KeyEvent.ACTION_UP)
        helper.sendMediaButtonEvent(event, true)
        view.postDelayed({ updateMediaPlaybackState() }, 250)
    }

    private fun updateMediaPlaybackState() {
        val isPlaying = mMediaSessionManagerHelper.isMediaPlaying()
        val stateIcon = if (isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play
        mediaButton?.let {
            it.setImageResource(stateIcon)
            setButtonActiveState(it, isPlaying)
        }
    }

    private fun toggleFlashlight() {
        torchButton ?: return
        try {
            mCameraManager.setTorchMode(mCameraId ?: "", !isFlashOn)
            isFlashOn = !isFlashOn
            updateTorchButtonState()
        } catch (e: Exception) {}
    }

    private fun toggleWiFi() {
        val cbi = mWifiSignalCallback.mInfo
        mNetworkController.setWifiEnabled(!cbi.enabled)
        view.postDelayed({ updateWiFiButtonState(cbi.enabled) }, 250)
    }

    private fun isMobileDataEnabled(): Boolean {
        return mDataController.isMobileDataEnabled
    }

    private fun toggleMobileData() {
        mDataController.setMobileDataEnabled(!isMobileDataEnabled())
        view.postDelayed({ updateMobileDataState(isMobileDataEnabled()) }, 250)
    }

    private fun showInternetDialog(view: View) {
        view.post {
            mInternetDialogManager.create(
                true,
                mAccessPointController.canConfigMobileData(),
                mAccessPointController.canConfigWifi(),
                Expandable.fromView(view)
            )
        }
        VibrationUtils.triggerVibration(mContext, 2)
    }

    private fun toggleRingerMode() {
        mAudioManager.let {
            val mode = it.ringerMode
            if (mode == AudioManager.RINGER_MODE_NORMAL) {
                it.ringerMode = AudioManager.RINGER_MODE_VIBRATE
            } else {
                it.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
            updateRingerButtonState()
        }
    }

    private fun updateTileButtonState(
        iv: LaunchableImageView?,
        active: Boolean,
        activeResource: Int,
        inactiveResource: Int
    ) {
        view.post {
            iv?.apply {
                setImageResource(if (active) activeResource else inactiveResource)
                setButtonActiveState(this, active)
            }
        }
    }

    fun updateTorchButtonState() {
        updateTileButtonState(torchButton, isFlashOn, TORCH_RES_ACTIVE, TORCH_RES_INACTIVE)
    }

    private fun updateWiFiButtonState(enabled: Boolean) {
        wifiButton ?: return
        updateTileButtonState(wifiButton, enabled, WIFI_ACTIVE, WIFI_INACTIVE)
    }

    private fun updateRingerButtonState() {
        ringerButton ?: return
        val isVibrateActive = mAudioManager.ringerMode == AudioManager.RINGER_MODE_VIBRATE
        updateTileButtonState(ringerButton, isVibrateActive, RINGER_ACTIVE, RINGER_INACTIVE)
    }

    private fun updateMobileDataState(enabled: Boolean) {
        dataButton ?: return
        val networkName = mNetworkController.mobileDataNetworkName ?: ""
        updateTileButtonState(dataButton, enabled, DATA_ACTIVE, DATA_INACTIVE)
    }

    private fun toggleBluetoothState() {
        mBluetoothController.setBluetoothEnabled(!isBluetoothEnabled())
        view.postDelayed({ updateBtState() }, 250)
    }

    private fun showBluetoothDialog(view: View) {
        view.post { mBluetoothTileDialogViewModel.showDialog(Expandable.fromView(view)) }
        VibrationUtils.triggerVibration(mContext, 2)
    }

    private fun updateBtState() {
        btButton ?: return
        updateTileButtonState(btButton, isBluetoothEnabled(), BT_ACTIVE, BT_INACTIVE)
    }

    private fun isBluetoothEnabled(): Boolean {
        val mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        return mBluetoothAdapter?.isEnabled == true
    }

    @Nullable
    private fun removeDoubleQuotes(string: String?): String? {
        if (string == null) return null
        if (string.length > 1 && string.first() == '"' && string.last() == '"') {
            return string.substring(1, string.length - 1)
        }
        return string
    }

    protected class WifiCallbackInfo {
        var enabled: Boolean = false
        var ssid: String? = null
    }

    protected inner class WifiSignalCallback : SignalCallback {
        val mInfo = WifiCallbackInfo()
        override fun setWifiIndicators(indicators: WifiIndicators) {
            if (indicators.qsIcon == null) {
                updateWiFiButtonState(false)
                return
            }
            mInfo.enabled = indicators.enabled
            mInfo.ssid = indicators.description
            updateWiFiButtonState(mInfo.enabled)
        }
    }

    private inner class CellSignalCallback : SignalCallback {
        override fun setMobileDataIndicators(indicators: MobileDataIndicators) {
            if (indicators.qsIcon == null) {
                updateMobileDataState(false)
                return
            }
            updateMobileDataState(isMobileDataEnabled())
        }

        override fun setNoSims(show: Boolean, simDetected: Boolean) {
            updateMobileDataState(simDetected && isMobileDataEnabled())
        }

        override fun setIsAirplaneMode(icon: IconState) {
            updateMobileDataState(!icon.visible && isMobileDataEnabled())
        }
    }

    private inner class LockscreenWidgetsObserver : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            updateSettings()
        }

        fun observe() {
            mContext.contentResolver.registerContentObserver(
                Settings.System.getUriFor(LOCKSCREEN_WIDGETS_ENABLED),
                false,
                this
            )
            mContext.contentResolver.registerContentObserver(
                Settings.System.getUriFor(LOCKSCREEN_WIDGETS_EXTRAS),
                false,
                this
            )
            updateSettings()
        }

        fun unobserve() {
            mContext.contentResolver.unregisterContentObserver(this)
            clearCallbacks()
        }

        fun updateSettings() {
            clearCallbacks()
            mLockscreenWidgetsEnabled =
                Settings.System.getIntForUser(
                    mContext.contentResolver,
                    LOCKSCREEN_WIDGETS_ENABLED,
                    0,
                    UserHandle.USER_CURRENT
                ) == 1
            mMainLockscreenWidgetsList =
                Settings.System.getStringForUser(
                    mContext.contentResolver,
                    LOCKSCREEN_WIDGETS_EXTRAS,
                    UserHandle.USER_CURRENT
                )
            updateWidgetsCallbacks()
            updateWidgetViews()
        }
    }

    private fun clearCallbacks() {
        mNetworkController.removeCallback(mWifiSignalCallback)
        mNetworkController.removeCallback(mCellSignalCallback)
        mBluetoothController.removeCallback(mBtCallback)
        mFlashlightController.removeCallback(mFlashlightCallback)
        mHotspotController.removeCallback(mHotspotCallback)
    }

    private fun updateWidgetsCallbacks() {
        mMainWidgetsList.clear()
        if (!mMainLockscreenWidgetsList.isNullOrEmpty()) {
            mMainWidgetsList.addAll(mMainLockscreenWidgetsList!!.split(","))
        }
        if (mMainWidgetsList.contains("hotspot")) {
            mHotspotController.addCallback(mHotspotCallback)
        }
        if (mMainWidgetsList.contains("wifi")) {
            mNetworkController.addCallback(mWifiSignalCallback)
        }
        if (mMainWidgetsList.contains("data")) {
            mNetworkController.addCallback(mCellSignalCallback)
        }
        if (mMainWidgetsList.contains("bt")) {
            mBluetoothController.addCallback(mBtCallback)
        }
        if (mMainWidgetsList.contains("torch")) {
            mFlashlightController.addCallback(mFlashlightCallback)
        }
    }

    private fun updateHotspotState() {
        hotspotButton ?: return
        updateTileButtonState(
            hotspotButton,
            mHotspotController.isHotspotEnabled,
            HOTSPOT_ACTIVE,
            HOTSPOT_INACTIVE
        )
    }

    private fun toggleHotspot() {
        mHotspotController.setHotspotEnabled(!mHotspotController.isHotspotEnabled)
        view.postDelayed({ updateHotspotState() }, 250)
    }

    private inner class HotspotCallback : HotspotController.Callback {
        override fun onHotspotChanged(enabled: Boolean, numDevices: Int) {
            updateHotspotState()
        }
        override fun onHotspotAvailabilityChanged(available: Boolean) {}
    }

    override fun onMediaMetadataChanged() {
        updateMediaPlaybackState()
    }

    override fun onPlaybackStateChanged() {
        updateMediaPlaybackState()
    }
}
