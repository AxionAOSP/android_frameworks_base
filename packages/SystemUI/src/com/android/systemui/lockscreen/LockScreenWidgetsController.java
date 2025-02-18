/*
     Copyright (C) 2024 the risingOS Android Project
     Licensed under the Apache License, Version 2.0 (the "License");
     you may not use this file except in compliance with the License.
     You may obtain a copy of the License at
          http://www.apache.org/licenses/LICENSE-2.0
     Unless required by applicable law or agreed to in writing, software
     distributed under the License is distributed on an "AS IS" BASIS,
     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
     See the License for the specific language governing permissions and
     limitations under the License.
*/
package com.android.systemui.lockscreen;

import android.annotation.NonNull;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.session.MediaSessionLegacyHelper;
import android.os.Handler;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.provider.Settings;
import android.os.UserHandle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.settingslib.net.DataUsageController;
import com.android.settingslib.Utils;

import com.android.systemui.res.R;
import com.android.systemui.Dependency;
import com.android.systemui.animation.Expandable;
import com.android.systemui.animation.view.LaunchableImageView;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.bluetooth.qsdialog.BluetoothTileDialogViewModel;
import com.android.systemui.qs.tiles.dialog.InternetDialogManager;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.BluetoothController.Callback;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.connectivity.AccessPointController;
import com.android.systemui.statusbar.connectivity.IconState;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.connectivity.SignalCallback;
import com.android.systemui.statusbar.connectivity.MobileDataIndicators;
import com.android.systemui.statusbar.connectivity.WifiIndicators;
import com.android.systemui.util.ActivityLauncherUtils;
import com.android.systemui.util.MediaSessionManagerHelper;

import com.android.internal.util.android.VibrationUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LockScreenWidgetsController implements MediaSessionManagerHelper.MediaMetadataListener {

    private static final String LOCKSCREEN_WIDGETS_ENABLED =
            "lockscreen_widgets_enabled";

    private static final String LOCKSCREEN_WIDGETS_EXTRAS =
            "lockscreen_widgets_extras";

    private static final int[] WIDGETS_VIEW_IDS = {
            R.id.kg_item_placeholder1,
            R.id.kg_item_placeholder2,
            R.id.kg_item_placeholder3,
            R.id.kg_item_placeholder4
    };
    
    public static final int BT_ACTIVE = R.drawable.qs_bluetooth_icon_on;
    public static final int BT_INACTIVE = R.drawable.qs_bluetooth_icon_off;
    public static final int DATA_ACTIVE = R.drawable.ic_signal_cellular_alt_24;
    public static final int DATA_INACTIVE = R.drawable.ic_mobiledata_off_24;
    public static final int RINGER_ACTIVE = R.drawable.ic_vibration_24;
    public static final int RINGER_INACTIVE = R.drawable.ic_ring_volume_24;
    public static final int TORCH_RES_ACTIVE = R.drawable.ic_flashlight_on;
    public static final int TORCH_RES_INACTIVE = R.drawable.ic_flashlight_off;
    public static final int WIFI_ACTIVE = R.drawable.ic_wifi_24;
    public static final int WIFI_INACTIVE = R.drawable.ic_wifi_off_24;
    public static final int HOTSPOT_ACTIVE = R.drawable.qs_hotspot_icon_on;
    public static final int HOTSPOT_INACTIVE = R.drawable.qs_hotspot_icon_off;

    private final AccessPointController mAccessPointController;
    private final BluetoothController mBluetoothController;
    private final BluetoothTileDialogViewModel mBluetoothTileDialogViewModel;
    private final ConfigurationController mConfigurationController;
    private final DataUsageController mDataController;
    private final FlashlightController mFlashlightController;
    private final InternetDialogManager mInternetDialogManager;
    private final NetworkController mNetworkController;
    private final StatusBarStateController mStatusBarStateController;
    private final MediaSessionManagerHelper mMediaSessionManagerHelper;
    private final LockscreenWidgetsObserver mLockscreenWidgetsObserver;
    private final ActivityLauncherUtils mActivityLauncherUtils;
    private final HotspotController mHotspotController;

    protected final CellSignalCallback mCellSignalCallback = new CellSignalCallback();
    protected final WifiSignalCallback mWifiSignalCallback = new WifiSignalCallback();
    private final HotspotCallback mHotspotCallback = new HotspotCallback();

    private Context mContext;
    private LaunchableImageView mWidget1, mWidget2, mWidget3, mWidget4, mediaButton, torchButton;
    private LaunchableImageView wifiButton, dataButton, ringerButton, btButton, hotspotButton;
    private int mDarkColor, mDarkColorActive, mLightColor, mLightColorActive;

    private CameraManager mCameraManager;
    private String mCameraId;
    private boolean isFlashOn = false;

    private String mMainLockscreenWidgetsList;
    private LaunchableImageView[] mMainWidgetViews;
    private List<String> mMainWidgetsList = new ArrayList<>();

    private AudioManager mAudioManager;

    private boolean mDozing;
    
    private boolean mIsInflated = false;

    private boolean mLockscreenWidgetsEnabled;

    final ConfigurationListener mConfigurationListener = new ConfigurationListener() {
        @Override
        public void onUiModeChanged() {
            updateWidgetViews();
        }
        @Override
        public void onThemeChanged() {
            updateWidgetViews();
        }
    };

    private final View mView;
    private final Handler mHandler = new Handler();

    public LockScreenWidgetsController(View view) {
        mView = view;
        mContext = mView.getContext();
        mAccessPointController = Dependency.get(AccessPointController.class);
        mBluetoothTileDialogViewModel = Dependency.get(BluetoothTileDialogViewModel.class);
        mConfigurationController = Dependency.get(ConfigurationController.class);
        mFlashlightController = Dependency.get(FlashlightController.class);
        mInternetDialogManager = Dependency.get(InternetDialogManager.class);
        mStatusBarStateController = Dependency.get(StatusBarStateController.class);
        mBluetoothController = Dependency.get(BluetoothController.class);
        mNetworkController = Dependency.get(NetworkController.class);
        mDataController = mNetworkController.getMobileDataController();
        mHotspotController = Dependency.get(HotspotController.class);
        mMediaSessionManagerHelper = MediaSessionManagerHelper.Companion.getInstance(mContext);

        mActivityLauncherUtils = new ActivityLauncherUtils(mContext);

        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        
        initResources();

        try {
            mCameraId = mCameraManager.getCameraIdList()[0];
        } catch (Exception e) {}
        
        IntentFilter ringerFilter = new IntentFilter(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION);
        mContext.registerReceiver(mRingerModeReceiver, ringerFilter);
        
        mLockscreenWidgetsObserver = new LockscreenWidgetsObserver();
    }

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
        @Override
        public void onStateChanged(int newState) {}
        @Override
        public void onDozingChanged(boolean dozing) {
            if (mDozing == dozing) {
                return;
            }
            mDozing = dozing;
            updateContainerVisibility();
        }
    };

    private final FlashlightController.FlashlightListener mFlashlightCallback =
            new FlashlightController.FlashlightListener() {
        @Override
        public void onFlashlightChanged(boolean enabled) {
            isFlashOn = enabled;
            updateTorchButtonState();
        }
        @Override
        public void onFlashlightError() {
        }
        @Override
        public void onFlashlightAvailabilityChanged(boolean available) {
            isFlashOn = mFlashlightController.isEnabled() && available;
            updateTorchButtonState();
        }
    };

    private void initResources() {
        mDarkColor = mContext.getResources().getColor(R.color.lockscreen_widget_background_color_dark);
        mLightColor = mContext.getResources().getColor(R.color.lockscreen_widget_background_color_light);
        mDarkColorActive = mContext.getResources().getColor(R.color.lockscreen_widget_active_color_dark);
        mLightColorActive = mContext.getResources().getColor(R.color.lockscreen_widget_active_color_light);
    }
    
    public void registerCallbacks() {
        mLockscreenWidgetsObserver.observe();
        mConfigurationController.addCallback(mConfigurationListener);
        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mStatusBarStateListener.onDozingChanged(mStatusBarStateController.isDozing());
        mMediaSessionManagerHelper.addMediaMetadataListener(this);
        updateWidgetViews();
        updateMediaPlaybackState();
        initViews();
    }
    
    public void unregisterCallbacks() {
        mConfigurationController.removeCallback(mConfigurationListener);
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
        mContext.unregisterReceiver(mRingerModeReceiver);
        mLockscreenWidgetsObserver.unobserve();
        mHandler.removeCallbacksAndMessages(null);
        mMediaSessionManagerHelper.removeMediaMetadataListener(this);
    }
    
    public void initViews() {
        mMainWidgetViews = new LaunchableImageView[WIDGETS_VIEW_IDS.length];
        for (int i = 0; i < mMainWidgetViews.length; i++) {
            mMainWidgetViews[i] = mView.findViewById(WIDGETS_VIEW_IDS[i]);
        }
        mIsInflated = true;
        updateWidgetViews();
    }
    
    public void updateWidgetViews() {
        if (!mIsInflated) return;
        if (mMainWidgetViews != null && mMainWidgetsList != null) {
            for (int i = 0; i < mMainWidgetViews.length; i++) {
                if (mMainWidgetViews[i] != null) {
                    mMainWidgetViews[i].setVisibility(i < mMainWidgetsList.size() ? View.VISIBLE : View.GONE);
                }
            }
            for (int i = 0; i < Math.min(mMainWidgetsList.size(), mMainWidgetViews.length); i++) {
                String widgetType = mMainWidgetsList.get(i);
                if (widgetType != null && i < mMainWidgetViews.length && mMainWidgetViews[i] != null) {
                    setUpWidgetWiews(mMainWidgetViews[i], widgetType);
                    updateWidgetsResources(mMainWidgetViews[i]);
                }
            }
        }
        updateContainerVisibility();
    }

    private void updateContainerVisibility() {
        final boolean isMainWidgetsEmpty = mMainLockscreenWidgetsList == null 
            || TextUtils.isEmpty(mMainLockscreenWidgetsList);
        final boolean isEmpty = isMainWidgetsEmpty;
        final View mainWidgetsContainer = mView.findViewById(R.id.main_widgets_container);
        if (mainWidgetsContainer != null) {
            mainWidgetsContainer.setVisibility(isMainWidgetsEmpty ? View.GONE : View.VISIBLE);
        }
        final boolean shouldHideContainer = isEmpty || mDozing || !mLockscreenWidgetsEnabled;
        mView.setVisibility(shouldHideContainer ? View.GONE : View.VISIBLE);
    }
    
    private void updateWidgetsResources(LaunchableImageView iv) {
        if (iv == null) return;
        iv.setBackgroundResource(R.drawable.lockscreen_widget_background_circle);
        setButtonActiveState(iv, false);
    }

    private boolean isNightMode() {
        final Configuration config = mContext.getResources().getConfiguration();
        return (config.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }
    
    private void setUpWidgetWiews(LaunchableImageView iv, String type) {
        View.OnClickListener clickListener = null;
        View.OnLongClickListener longClickListener = null;
        int drawableRes = 0;

        switch (type) {
            case "none":
                if (iv != null) iv.setVisibility(View.GONE);
                return;
            case "wifi":
                clickListener = v -> toggleWiFi();
                longClickListener = v -> {
                    showInternetDialog(v);
                    return true;
                };
                drawableRes = WIFI_INACTIVE;
                if (iv != null) wifiButton = iv;
                break;
            case "data":
                clickListener = v -> toggleMobileData();
                longClickListener = v -> {
                    showInternetDialog(v);
                    return true;
                };
                drawableRes = DATA_INACTIVE;
                if (iv != null) dataButton = iv;
                break;
            case "ringer":
                clickListener = v -> toggleRingerMode();
                drawableRes = RINGER_INACTIVE;
                if (iv != null) ringerButton = iv;
                break;
            case "bt":
                clickListener = v -> toggleBluetoothState();
                longClickListener = v -> {
                    showBluetoothDialog(v);
                    return true;
                };
                drawableRes = BT_INACTIVE;
                if (iv != null) btButton = iv;
                break;
            case "torch":
                clickListener = v -> toggleFlashlight();
                drawableRes = TORCH_RES_INACTIVE;
                if (iv != null) torchButton = iv;
                break;
            case "timer":
                clickListener = v -> mActivityLauncherUtils.launchTimer();
                drawableRes = R.drawable.ic_alarm;
                break;
            case "calculator":
                clickListener = v -> mActivityLauncherUtils.launchCalculator();
                drawableRes = R.drawable.ic_calculator;
                break;
            case "media":
                clickListener = v -> toggleMediaPlaybackState();
                longClickListener = v -> {
                    showMediaDialog(v);
                    return true;
                };
                drawableRes = R.drawable.ic_media_play;
                if (iv != null) mediaButton = iv;
                break;
            case "hotspot":
                clickListener = v -> toggleHotspot();
                longClickListener = v -> {
                    showInternetDialog(v);
                    return true;
                };
                drawableRes = HOTSPOT_INACTIVE;
                if (iv != null) hotspotButton = iv;
                break;
            case "wallet":
                clickListener = v -> mActivityLauncherUtils.launchWalletApp();
                drawableRes = R.drawable.ic_wallet_lockscreen;
                break;
            case "qrscanner":
                clickListener = v -> mActivityLauncherUtils.launchQrScanner();
                drawableRes = R.drawable.ic_qr_code_scanner;
                break;
            default:
                return;
        }

        if (iv != null) {
            iv.setOnClickListener(clickListener);
            iv.setOnLongClickListener(longClickListener);
            iv.setImageResource(drawableRes);
        }
    }

    private void setButtonActiveState(LaunchableImageView iv, boolean active) {
        int bgTint;
        int tintColor;
        if (active) {
            bgTint = isNightMode() ? mDarkColorActive : mLightColorActive;
            tintColor = isNightMode() ? mDarkColor : mLightColor;
        } else {
            bgTint = isNightMode() ? mDarkColor : mLightColor;
            tintColor = isNightMode() ? mLightColor : mDarkColor;
        }
        if (iv != null) {
            iv.setBackgroundTintList(ColorStateList.valueOf(bgTint));
            iv.setImageTintList(ColorStateList.valueOf(tintColor));
        }
    }

    private void toggleMediaPlaybackState() {
        if (mMediaSessionManagerHelper.isMediaPlaying()) {
            dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PAUSE);
        } else {
            dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PLAY);
        }
    }
    
    private void showMediaDialog(View view) {
        String lastMediaPkg = getLastUsedMedia();
        if (TextUtils.isEmpty(lastMediaPkg)) return; // Return if null or empty
        mHandler.post(() -> {
            ((LockScreenWidgets) mView).showMediaDialog(view, lastMediaPkg);
            VibrationUtils.triggerVibration(mContext, 2); // Trigger vibration
        });
    }
    
    private String getLastUsedMedia() {
        return Settings.System.getString(mContext.getContentResolver(),
                    "media_session_last_package_name");
    }

    private void dispatchMediaKeyWithWakeLockToMediaSession(final int keycode) {
        final MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(mContext);
        if (helper == null) return;
        KeyEvent event = new KeyEvent(SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keycode, 0);
        helper.sendMediaButtonEvent(event, true);
        event = KeyEvent.changeAction(event, KeyEvent.ACTION_UP);
        helper.sendMediaButtonEvent(event, true);
        mHandler.postDelayed(() -> {
            updateMediaPlaybackState();
        }, 250);
    }

    private void updateMediaPlaybackState() {
        boolean isPlaying = mMediaSessionManagerHelper.isMediaPlaying();
        int stateIcon = isPlaying ? R.drawable.ic_media_pause : R.drawable.ic_media_play;
        if (mediaButton != null) {
            mediaButton.setImageResource(stateIcon);
            setButtonActiveState(mediaButton, isPlaying);
        }
    }

    private void toggleFlashlight() {
        if (torchButton == null) return;
        try {
            mCameraManager.setTorchMode(mCameraId, !isFlashOn);
            isFlashOn = !isFlashOn;
            updateTorchButtonState();
        } catch (Exception e) {}
    }

    private void toggleWiFi() {
        final WifiCallbackInfo cbi = mWifiSignalCallback.mInfo;
        mNetworkController.setWifiEnabled(!cbi.enabled);
        mHandler.postDelayed(() -> {
            updateWiFiButtonState(cbi.enabled);
        }, 250);
    }

    private boolean isMobileDataEnabled() {
        return mDataController.isMobileDataEnabled();
    }

    private void toggleMobileData() {
        mDataController.setMobileDataEnabled(!isMobileDataEnabled());
        mHandler.postDelayed(() -> {
            updateMobileDataState(isMobileDataEnabled());
        }, 250);
    }
    
    private void showInternetDialog(View view) {
        mHandler.post(() -> mInternetDialogManager.create(true,
                mAccessPointController.canConfigMobileData(),
                mAccessPointController.canConfigWifi(), Expandable.fromView(view)));
        VibrationUtils.triggerVibration(mContext, 2);
    }

    private void toggleRingerMode() {
        if (mAudioManager != null) {
            int mode = mAudioManager.getRingerMode();
            if (mode == mAudioManager.RINGER_MODE_NORMAL) {
                mAudioManager.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
            } else {
                mAudioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            }
            updateRingerButtonState();
        }
    }

    private void updateTileButtonState(
            LaunchableImageView iv, 
            boolean active, int activeResource, int inactiveResource) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (iv != null) {
                    iv.setImageResource(active ? activeResource : inactiveResource);
                    setButtonActiveState(iv, active);
                }
            }
        });
    }
    
    public void updateTorchButtonState() {
        updateTileButtonState(torchButton, isFlashOn, 
            TORCH_RES_ACTIVE, TORCH_RES_INACTIVE);
    }

    private BroadcastReceiver mRingerModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateRingerButtonState();
        }
    };

    private final BluetoothController.Callback mBtCallback = new BluetoothController.Callback() {
        @Override
        public void onBluetoothStateChange(boolean enabled) {
            updateBtState();
        }
        @Override
        public void onBluetoothDevicesChanged() {
            updateBtState();
        }
    };

    private void updateWiFiButtonState(boolean enabled) {
        if (wifiButton == null) return;
        updateTileButtonState(wifiButton, enabled, 
            WIFI_ACTIVE, WIFI_INACTIVE);
    }

    private void updateRingerButtonState() {
        if (ringerButton == null) return;
        if (mAudioManager != null) {
            boolean isVibrateActive = mAudioManager.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE;
            updateTileButtonState(ringerButton, isVibrateActive, 
                RINGER_ACTIVE, RINGER_INACTIVE);
        }
    }

    private void updateMobileDataState(boolean enabled) {
        if (dataButton == null) return;
        String networkName = mNetworkController == null ? "" : mNetworkController.getMobileDataNetworkName();
        boolean hasNetwork = !TextUtils.isEmpty(networkName) && mNetworkController != null 
            && mNetworkController.hasMobileDataFeature();
        updateTileButtonState(dataButton, enabled, 
            DATA_ACTIVE, DATA_INACTIVE);
    }
    
    private void toggleBluetoothState() {
        mBluetoothController.setBluetoothEnabled(!isBluetoothEnabled());
        mHandler.postDelayed(() -> {
            updateBtState();
        }, 250);
    }
    
    private void showBluetoothDialog(View view) {
        mHandler.post(() -> 
            mBluetoothTileDialogViewModel.showDialog(Expandable.fromView(view)));
        VibrationUtils.triggerVibration(mContext, 2);
    }
    
    private void updateBtState() {
        if (btButton == null) return;
        updateTileButtonState(btButton, isBluetoothEnabled(), 
            BT_ACTIVE, BT_INACTIVE);
    }
    
    private boolean isBluetoothEnabled() {
        final BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        return mBluetoothAdapter != null && mBluetoothAdapter.isEnabled();
    }

    @Nullable
    private static String removeDoubleQuotes(String string) {
        if (string == null) return null;
        final int length = string.length();
        if ((length > 1) && (string.charAt(0) == '"') && (string.charAt(length - 1) == '"')) {
            return string.substring(1, length - 1);
        }
        return string;
    }

    protected static final class WifiCallbackInfo {
        boolean enabled;
        @Nullable
        String ssid;
    }

    protected final class WifiSignalCallback implements SignalCallback {
        final WifiCallbackInfo mInfo = new WifiCallbackInfo();
        @Override
        public void setWifiIndicators(@NonNull WifiIndicators indicators) {
            if (indicators.qsIcon == null) {
                updateWiFiButtonState(false);
                return;
            }
            mInfo.enabled = indicators.enabled;
            mInfo.ssid = indicators.description;
            updateWiFiButtonState(mInfo.enabled);
        }
    }

    private final class CellSignalCallback implements SignalCallback {
        @Override
        public void setMobileDataIndicators(@NonNull MobileDataIndicators indicators) {
            if (indicators.qsIcon == null) {
                updateMobileDataState(false);
                return;
            }
            updateMobileDataState(isMobileDataEnabled());
        }
        @Override
        public void setNoSims(boolean show, boolean simDetected) {
            updateMobileDataState(simDetected && isMobileDataEnabled());
        }
        @Override
        public void setIsAirplaneMode(@NonNull IconState icon) {
            updateMobileDataState(!icon.visible && isMobileDataEnabled());
        }
    }
    
    @Override
    public void onMediaMetadataChanged() {
        updateMediaPlaybackState();
    }

    @Override
    public void onPlaybackStateChanged() {
        updateMediaPlaybackState();
    }
    
    private class LockscreenWidgetsObserver extends ContentObserver {
        public LockscreenWidgetsObserver() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            updateSettings();
        }

        void observe() {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(LOCKSCREEN_WIDGETS_ENABLED),
                    false,
                    this);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(LOCKSCREEN_WIDGETS_EXTRAS),
                    false,
                    this);
            updateSettings();
        }

        void unobserve() {
            mContext.getContentResolver().unregisterContentObserver(this);
            clearCallbacks();
        }

        void updateSettings() {
            clearCallbacks();
            mLockscreenWidgetsEnabled = Settings.System.getIntForUser(mContext.getContentResolver(),
                    LOCKSCREEN_WIDGETS_ENABLED, 0, UserHandle.USER_CURRENT) == 1;
            mMainLockscreenWidgetsList = Settings.System.getStringForUser(mContext.getContentResolver(),
                    LOCKSCREEN_WIDGETS_EXTRAS, UserHandle.USER_CURRENT);

            updateWidgetLists();

            updateWidgetViews();

            if (mMainWidgetsList.contains("hotspot")) {
                mHotspotController.addCallback(mHotspotCallback);
            }
            if (mMainWidgetsList.contains("wifi")) {
                mNetworkController.addCallback(mWifiSignalCallback);
            }
            if (mMainWidgetsList.contains("data")) {
                mNetworkController.addCallback(mCellSignalCallback);
            }
            if (mMainWidgetsList.contains("bt")) {
                mBluetoothController.addCallback(mBtCallback);
            }
            if (mMainWidgetsList.contains("torch")) {
                mFlashlightController.addCallback(mFlashlightCallback);
            }
        }
        
        private void clearCallbacks() {
            mNetworkController.removeCallback(mWifiSignalCallback);
            mNetworkController.removeCallback(mCellSignalCallback);
            mBluetoothController.removeCallback(mBtCallback);
            mFlashlightController.removeCallback(mFlashlightCallback);
            mHotspotController.removeCallback(mHotspotCallback);
        }

        private void updateWidgetLists() {
            mMainWidgetsList.clear();
            if (mMainLockscreenWidgetsList != null && !mMainLockscreenWidgetsList.isEmpty()) {
                mMainWidgetsList.addAll(Arrays.asList(mMainLockscreenWidgetsList.split(",")));
            }
        }
    };

    private void updateHotspotState() {
        if (hotspotButton == null) return;
        updateTileButtonState(hotspotButton, mHotspotController.isHotspotEnabled(), 
            HOTSPOT_ACTIVE, HOTSPOT_INACTIVE);
    }

    private void toggleHotspot() {
        mHotspotController.setHotspotEnabled(!mHotspotController.isHotspotEnabled());
        updateHotspotState();
        mHandler.postDelayed(() -> {
            updateHotspotState();
        }, 250);
    }
    
    private final class HotspotCallback implements HotspotController.Callback {
        @Override
        public void onHotspotChanged(boolean enabled, int numDevices) {
            updateHotspotState();
        }
        @Override
        public void onHotspotAvailabilityChanged(boolean available) {}
    }
}
