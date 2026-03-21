/*
 * Copyright 2025-2026 AxionOS
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
package com.android.server.display.brightness;

import static android.hardware.SensorManager.LIGHT_NO_MOON;
import static android.hardware.SensorManager.LIGHT_SHADE;
import static android.hardware.SensorManager.LIGHT_SUNLIGHT;
import static android.hardware.SensorManager.LIGHT_SUNLIGHT_MAX;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.*;
import android.hardware.display.BrightnessInfo;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import android.util.MathUtils;
import android.view.Display;
import com.android.server.SystemService;
import java.io.File;

public class HbmService extends SystemService implements SensorEventListener {

    private static final String TAG = "HbmService";

    private static final String HBM_NODE = SystemProperties.get("persist.sys.hbmservice_file");
    private static final String HBM_OFF = "0";
    private static final String HBM_ON  = "1";

    public static final boolean sDisableGammaConversion =
            SystemProperties.getBoolean("sys.brightness.disable_gamma_conversion", false);

    private static final float DEFAULT_LUX_THRESHOLD_HBM = LIGHT_SUNLIGHT;
    private static final float DEFAULT_LUX_THRESHOLD_OFF = LIGHT_SHADE;
    private static final float HBM_MIN_BRIGHTNESS_PERCENT = 0.9f;

    private static final String KEY_AUTO_HBM_ENABLED = "auto_hbm_enabled";
    private static final String KEY_AUTO_HBM_LUX_ON  = "auto_hbm_lux_on";
    private static final String KEY_AUTO_HBM_LUX_OFF = "auto_hbm_lux_off";

    private static final int AVG_SAMPLE_COUNT = 5;
    private static final long SENSOR_DEBOUNCE_MS = 3000;

    private static final int MSG_SENSOR_CHANGED = 1;
    private static final int MSG_SETTINGS_CHANGED = 2;
    private static final int MSG_SCREEN_STATE_CHANGED = 3;
    private static final int MSG_BRIGHTNESS_CHANGED = 4;

    public static final int GAMMA_SPACE_MIN = 0;
    public static final int GAMMA_SPACE_MAX = 65535;

    private static final float R = 0.5f;
    private static final float A = 0.17883277f;
    private static final float B = 0.28466892f;
    private static final float C = 0.55991073f;

    private final Context mContext;
    private final SensorManager mSensorManager;

    private HandlerThread mHandlerThread;
    private HbmHandler mHandler;

    private Sensor mLightSensor;
    private boolean mScreenOn = true;
    private boolean mAutoEnabled = false;

    private float mLuxThresholdOn = DEFAULT_LUX_THRESHOLD_HBM;
    private float mLuxThresholdOff = DEFAULT_LUX_THRESHOLD_OFF;

    private final float[] mRecentLux = new float[AVG_SAMPLE_COUNT];
    private int mLuxIndex = 0;
    private boolean mBufferFilled = false;
    private long mLastSensorUpdateTime = 0;

    private boolean mSensorRegistered = false;
    private boolean mReceiverRegistered = false;

    private ContentObserver mSettingsObserver;
    private BroadcastReceiver mScreenReceiver;

    private float mCurrentLux = 0f;

    public HbmService(Context context) {
        super(context);
        mContext = context;
        mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
        mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
    }

    @Override
    public void onStart() {
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase != PHASE_BOOT_COMPLETED) return;

        if (mLightSensor == null) {
            logger("No light sensor found, HbmService disabled");
            return;
        }

        startHandlerThread();
        observeSettings();
        updateLightThresholds();
        updateState();
    }

    private void startHandlerThread() {
        mHandlerThread = new HandlerThread("HbmServiceThread");
        mHandlerThread.start();
        mHandler = new HbmHandler(mHandlerThread.getLooper(), this);
    }

    private void observeSettings() {
        ContentResolver resolver = mContext.getContentResolver();
        mSettingsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (uri == null) return;
                String key = uri.getLastPathSegment();
                if (KEY_AUTO_HBM_ENABLED.equals(key) || KEY_AUTO_HBM_LUX_ON.equals(key) ||
                    KEY_AUTO_HBM_LUX_OFF.equals(key)) {
                    mHandler.obtainMessage(MSG_SETTINGS_CHANGED).sendToTarget();
                } else if (Settings.System.SCREEN_BRIGHTNESS.equals(key)) {
                    mHandler.obtainMessage(MSG_BRIGHTNESS_CHANGED).sendToTarget();
                }
            }
        };

        resolver.registerContentObserver(Settings.Secure.getUriFor(KEY_AUTO_HBM_ENABLED), false, mSettingsObserver);
        resolver.registerContentObserver(Settings.Secure.getUriFor(KEY_AUTO_HBM_LUX_ON), false, mSettingsObserver);
        resolver.registerContentObserver(Settings.Secure.getUriFor(KEY_AUTO_HBM_LUX_OFF), false, mSettingsObserver);
        resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, mSettingsObserver);
    }

    private void registerScreenReceiver() {
        if (mReceiverRegistered) return;

        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);

        mScreenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                boolean screenOn = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
                mHandler.obtainMessage(MSG_SCREEN_STATE_CHANGED, screenOn).sendToTarget();
            }
        };

        mContext.registerReceiver(mScreenReceiver, filter);
        mReceiverRegistered = true;
    }

    private void updateState() {
        boolean prevAutoEnabled = mAutoEnabled;
        mAutoEnabled = Settings.Secure.getInt
            (mContext.getContentResolver(), KEY_AUTO_HBM_ENABLED, 0) == 1;

        if (mAutoEnabled != prevAutoEnabled) {
            updateSensors();
        }
    }

    private void updateLightThresholds() {
        float luxOn = Settings.Secure.getFloat(
            mContext.getContentResolver(), KEY_AUTO_HBM_LUX_ON, DEFAULT_LUX_THRESHOLD_HBM);
        float luxOff = Settings.Secure.getFloat(
            mContext.getContentResolver(), KEY_AUTO_HBM_LUX_OFF, DEFAULT_LUX_THRESHOLD_OFF);

        luxOn = clamp(luxOn, LIGHT_NO_MOON, LIGHT_SUNLIGHT_MAX);
        luxOff = clamp(luxOff, LIGHT_NO_MOON, LIGHT_SUNLIGHT_MAX);

        if (luxOff >= luxOn) luxOff = luxOn * 0.8f;

        mLuxThresholdOn = luxOn;
        mLuxThresholdOff = luxOff;

        logger("HBM thresholds updated: on:" + mLuxThresholdOn + " off:" + mLuxThresholdOff);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void updateSensors() {
        boolean shouldListen = mScreenOn && mAutoEnabled && mLightSensor != null;

        if (shouldListen && !mSensorRegistered) {
            logger("Registering light sensor listener for HBM");
            mSensorManager.registerListener(this, mLightSensor, SensorManager.SENSOR_DELAY_NORMAL, mHandler);
            registerScreenReceiver();
            mSensorRegistered = true;
        } else if (!shouldListen && mSensorRegistered) {
            logger("Unregistering light sensor listener for HBM");
            mSensorManager.unregisterListener(this);
            mSensorRegistered = false;
            disableHbm();
        }
    }

    private void handleSensorChanged() {
        if (getBrightnessPercent() < HBM_MIN_BRIGHTNESS_PERCENT) {
            write(HBM_OFF);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - mLastSensorUpdateTime < SENSOR_DEBOUNCE_MS) return;

        mRecentLux[mLuxIndex] = mCurrentLux;
        mLuxIndex = (mLuxIndex + 1) % AVG_SAMPLE_COUNT;
        if (mLuxIndex == 0) mBufferFilled = true;

        int count = mBufferFilled ? AVG_SAMPLE_COUNT : mLuxIndex;
        float sum = 0f;
        for (int i = 0; i < count; i++) sum += mRecentLux[i];
        float avgLux = sum / count;

        mLastSensorUpdateTime = now;
        updateHbmMode();
    }

    private void handleSettingsChanged(String key) {
        if (KEY_AUTO_HBM_ENABLED.equals(key)) {
            updateState();
        } else if (KEY_AUTO_HBM_LUX_ON.equals(key) || KEY_AUTO_HBM_LUX_OFF.equals(key)) {
            updateLightThresholds();
        }
    }

    private void handleScreenStateChanged(boolean screenOn) {
        mScreenOn = screenOn;
        updateSensors();
    }

    private void updateHbmMode() {
        final float lux = mCurrentLux;
        boolean hmbOn = isHbmEnabled();
        if (!hmbOn && lux >= mLuxThresholdOn) {
            final float brightnessPercent = getBrightnessPercent();
            if (brightnessPercent < HBM_MIN_BRIGHTNESS_PERCENT) return;
            write(HBM_ON);
            logger("Enabling HBM brightness: " + brightnessPercent);
        } else if (hmbOn && lux <= mLuxThresholdOff) {
            write(HBM_OFF);
        }
        logger("HBM " + (isHbmEnabled() ? "enabled" : "disabled") + " lux: " + lux);
    }

    private float getBrightnessPercent() {
        Display display = mContext.getDisplay();
        BrightnessInfo info = display.getBrightnessInfo();
        if (info == null) return 0.5f;
        float gamma = convertLinearToGammaFloat(
                info.brightness,
                info.brightnessMinimum,
                info.brightnessMaximum
        );
        float brightnessPercent = ((gamma - GAMMA_SPACE_MIN) /
                             (GAMMA_SPACE_MAX - GAMMA_SPACE_MIN));
        return brightnessPercent;
    }

    private void disableHbm() {
        if (isHbmEnabled()) {
            write(HBM_OFF);
        }
    }

    private boolean isHbmEnabled() {
        try {
            File node = FileUtils.newFileOrNull(HBM_NODE);
            if (node == null) return false;
            String value = FileUtils.readTextFile(node, 0, null).trim();
            return HBM_ON.equals(value);
        } catch (Exception e) {
            logger("Failed to read HBM state, assuming OFF");
            return false;
        }
    }

    private void write(String value) {
        try {
            FileUtils.stringToFile(HBM_NODE, value);
        } catch (Exception e) {
            logger("Failed to write value: " + value);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        mCurrentLux = event.values[0];
        logger("Sensor changed: lux=" + mCurrentLux);
        mHandler.obtainMessage(MSG_SENSOR_CHANGED).sendToTarget();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void logger(String msg) {
        if (SystemProperties.getBoolean("persist.sys.hbmservice_debug", false)) Log.d(TAG, msg);
    }

    public static final int convertLinearToGammaFloat(float val, float min, float max) {
        if (sDisableGammaConversion) {
            final float normalizedVal = MathUtils.norm(min, max, val);
            return Math.round(MathUtils.lerp(GAMMA_SPACE_MIN, GAMMA_SPACE_MAX, normalizedVal));
        }
        final float normalizedVal = MathUtils.norm(min, max, val) * 12;
        final float ret;
        if (normalizedVal <= 1f) {
            ret = MathUtils.sqrt(normalizedVal) * R;
        } else {
            ret = A * MathUtils.log(normalizedVal - B) + C;
        }
        return Math.round(MathUtils.lerp(GAMMA_SPACE_MIN, GAMMA_SPACE_MAX, ret));
    }

    private static class HbmHandler extends Handler {
        private final HbmService mService;

        HbmHandler(Looper looper, HbmService service) {
            super(looper);
            mService = service;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_BRIGHTNESS_CHANGED:
                    mService.updateHbmMode();
                    break;
                case MSG_SENSOR_CHANGED:
                    mService.handleSensorChanged();
                    break;
                case MSG_SETTINGS_CHANGED:
                    mService.handleSettingsChanged((String) msg.obj);
                    break;
                case MSG_SCREEN_STATE_CHANGED:
                    mService.handleScreenStateChanged((Boolean) msg.obj);
                    break;
            }
        }
    }
}
