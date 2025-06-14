/*
 * Copyright (C) 2025 the AxionAOSP Project
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
package com.android.server;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

public class ThermalControlService extends SystemService {

    private static final String TAG = "ThermalControlService";

    private static final String CPUSET_PATH = "/dev/cpuset/";
    private static final String FG_GROUP = CPUSET_PATH + "foreground/cpus";
    private static final String FG_WINDOW_GROUP = CPUSET_PATH + "foreground_window/cpus";
    private static final String SYS_BG_GROUP = CPUSET_PATH + "system-background/cpus";
    private static final String BG_GROUP = CPUSET_PATH + "background/cpus";

    private static final String CPUS_PARAMS_UI_LIMIT = SystemProperties.get("persist.sys.axion_cpu_limit_ui", "0-4");
    private static final String CPUS_PARAMS_FG_UNLIMIT = SystemProperties.get("persist.sys.axion_cpu_fg", "0-5");
    private static final String CPUS_PARAMS_BG_LIMIT = SystemProperties.get("persist.sys.axion_cpu_limit_bg", "0-1");
    private static final String CPUS_PARAMS_BG_UNLIMIT = SystemProperties.get("persist.sys.axion_cpu_bg", "0-2");

    private static final float DEFAULT_TEMP_THRESHOLD_C = 37.0f;
    private static final float HIGH_TEMP_THRESHOLD_C = 43.0f;

    private static final String PROPERTY_CFS_TEMP_CTRL = "persist.sys.cfs_temp_control";

    private final Context mContext;
    private final Handler mHandler;
    private ContentObserver mSettingsObserver;

    private int mPerfGameIsRunning = 0;
    private int mPowerModePerfByUser = 0;

    public ThermalControlService(Context context) {
        super(context);
        mContext = context;
        HandlerThread handlerThread = new HandlerThread("ThermalControlHandler");
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
    }

    @Override
    public void onStart() {}

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            mHandler.postDelayed(this::initThermalService, 5000);
        }
    }

    private void initThermalService() {
        Slog.i(TAG, "ThermalControlService starting...");

        SystemProperties.set(PROPERTY_CFS_TEMP_CTRL, null);

        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        mContext.registerReceiver(mBatteryReceiver, filter, null, mHandler);
        Slog.i(TAG, "Battery temperature listener registered");

        mSettingsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                updateSettings();
            }
        };

        ContentResolver resolver = mContext.getContentResolver();
        resolver.registerContentObserver(Settings.System.getUriFor("perf_game_is_running"), false, mSettingsObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor("power_mode_perf_by_user"), false, mSettingsObserver, UserHandle.USER_ALL);

        updateSettings();
    }

    private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            if (tempTenths == -1) {
                Slog.w(TAG, "Battery temperature unavailable");
                return;
            }

            float temp = tempTenths / 10.0f;

            if (mPerfGameIsRunning == 1) {
                setThermalControl("0", temp, true);
                return;
            }

            float threshold = (mPowerModePerfByUser == 1) ? HIGH_TEMP_THRESHOLD_C : DEFAULT_TEMP_THRESHOLD_C;
            boolean shouldThrottle = temp >= threshold;

            String bgLimit = shouldThrottle ? CPUS_PARAMS_BG_LIMIT : CPUS_PARAMS_BG_UNLIMIT;
            String fgLimit = shouldThrottle ? CPUS_PARAMS_UI_LIMIT : CPUS_PARAMS_FG_UNLIMIT;

            adjustCpusetCpus(FG_GROUP, fgLimit);
            adjustCpusetCpus(FG_WINDOW_GROUP, fgLimit);
            adjustCpusetCpus(SYS_BG_GROUP, bgLimit);
            adjustCpusetCpus(BG_GROUP, bgLimit);

            setThermalControl(shouldThrottle ? "1" : "0", temp, false);
        }
    };

    private void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();
        mPerfGameIsRunning = Settings.System.getIntForUser(resolver, "perf_game_is_running", 0, UserHandle.USER_CURRENT);
        mPowerModePerfByUser = Settings.System.getIntForUser(resolver, "power_mode_perf_by_user", 0, UserHandle.USER_CURRENT);
    }

    private void setThermalControl(String newValue, float temp, boolean forcedByGameMode) {
        String currentValue = SystemProperties.get(PROPERTY_CFS_TEMP_CTRL);
        if (currentValue == null || !newValue.equals(currentValue)) {
            SystemProperties.set(PROPERTY_CFS_TEMP_CTRL, newValue);
            String reason = forcedByGameMode ? "forced by game mode" : String.format("based on temp threshold %.1f°C", temp);
            Slog.i(TAG, String.format("Set %s to %s (%s)", PROPERTY_CFS_TEMP_CTRL, newValue, reason));
        }
    }

    private void adjustCpusetCpus(String path, String cpus) {
        try {
            android.app.ActivityManager.getService().executeAdjustCpusetCpus(path, cpus);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to adjust cpuset for " + path, e);
        }
    }
}
