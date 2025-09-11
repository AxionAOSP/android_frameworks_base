/*
 * Copyright (C) 2025 AxionOS
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
package com.android.server.am;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.Temperature;
import android.util.Slog;

public class ProcessManager {

    private static final String TAG = "ProcessManager";

    private ActivityManagerService mActivityManagerService;
    private Context mContext;
    private HandlerThread mHandlerThread;
    private Handler mHandler;

    private static final long MEMORY_RELEASE_INTERVAL_MS = 2 * 60 * 1000L;
    private long mLastReleaseTime = 0;

    private IThermalService mThermalService;
    private final IThermalEventListener mThermalListener = new IThermalEventListener.Stub() {
        @Override
        public void notifyThrottling(Temperature temperature) {
            int status = temperature.getStatus();
            logger("Thermal event: " + temperature + " (status=" + status + ")");
            if (status != Temperature.THROTTLING_NONE) {
                releaseMemory();
            } else {
                logger("Throttling ended, no mitigation");
            }
        }
    };

    public ProcessManager() {
    }

    private void initHandlerThread() {
        mHandlerThread = new HandlerThread("ProcessManager");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
    }

    private void registerThermalCallback() {
        mThermalService = IThermalService.Stub.asInterface(
                ServiceManager.getService(Context.THERMAL_SERVICE));

        if (mThermalService == null) {
            logger("IThermalService not available");
            return;
        }

        try {
            mThermalService.registerThermalEventListener(mThermalListener);
            logger("Thermal listener registered");
        } catch (Exception e) {
            Slog.e(TAG, "Failed to register thermal listener", e);
        }
    }

    private void releaseMemory() {
        long now = System.currentTimeMillis();
        if (now - mLastReleaseTime < MEMORY_RELEASE_INTERVAL_MS) {
            return;
        }
        mLastReleaseTime = now;
        logger("Performing thermal mitigation: releasing memory");
        mActivityManagerService.releaseMemory(900, 20, false, false);
    }

    public void systemReady(ActivityManagerService ams, Context context) {
        logger("ProcessManager enabled");
        mActivityManagerService = ams;
        mContext = context;
        initHandlerThread();
        registerThermalCallback();
    }

    private void logger(String msg) {
        if (SystemProperties.getBoolean("persist.sys.ax_thermal_debug", false)) Slog.d(TAG, msg);
    }
}
