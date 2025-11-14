/*
 * Copyright (C) 2025 AxionOS Project
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
package com.android.server.wm;

import android.content.Context;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.app.IGameSpaceCallback;
import com.android.server.am.ActivityManagerService;

import java.util.ArrayList;
import java.util.List;

class GameStateDispatcher {
    private final Context mContext;
    private final List<IGameSpaceCallback> mCallbacks;
    private final ActivityManagerService mAm;
    private final Handler mBgHandler;

    private static final String TAG = "GameStateDispatcher";

    private PowerManager.WakeLock mWakeLock;

    GameStateDispatcher(Context context,
                        List<IGameSpaceCallback> callbacks,
                        ActivityManagerService am) {
        this.mContext = context;
        this.mCallbacks = callbacks;
        this.mAm = am;

        HandlerThread thread = new HandlerThread("GameStateDispatcher-Thread");
        thread.start();
        mBgHandler = new Handler(thread.getLooper());
    }

    void dispatchGameState(boolean isActive, String activeGame) {
        boolean suppress = Settings.System.getIntForUser(
                mContext.getContentResolver(),
                "gamespace_suppress_fullscreen_intent",
                0,
                UserHandle.USER_CURRENT
        ) == 1;

        int suppressStatus = suppress && isActive ? 1 : 0;

        Settings.System.putIntForUser(
                mContext.getContentResolver(),
                "gamespace_suppress_fullscreen_intent_status",
                suppressStatus,
                UserHandle.USER_CURRENT
        );

        notifyDispatchGameState(isActive, activeGame);

        if (isActive) {
            mBgHandler.post(this::setGameModeSettings);
        } else {
            mBgHandler.post(this::resetGameModeSettings);
        }
    }

    private void notifyDispatchGameState(boolean active, String activeGame) {
        for (IGameSpaceCallback callback : new ArrayList<>(mCallbacks)) {
            try {
                if (active && activeGame != null) {
                    callback.onGameStart(activeGame);
                } else {
                    callback.onGameLeave();
                }
            } catch (Exception e) {
                Slog.w(TAG, "Removing dead callback", e);
                mCallbacks.remove(callback);
            }
        }
    }

    private void setGameModeSettings() {
        if (stayAwakeEnabled()) acquireWakeLock();
        Settings.System.putIntForUser(mContext.getContentResolver(),
                "gamespace_stay_awake_status", 1, UserHandle.USER_CURRENT);

        if (lockGestureEnabled()) {
            Settings.Secure.putInt(mContext.getContentResolver(),
                    "nt_game_mode_mistouch_prevention", 1);
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    "gamespace_lock_gesture_status", 1, UserHandle.USER_CURRENT);
        }

        if (bypassEnabled()) {
            setBypassActive(true);
            setSmartChargeLvl(battLevel());
        }
    }

    private void resetGameModeSettings() {
        if (stayAwakeEnabled()) releaseWakeLock();
        Settings.System.putIntForUser(mContext.getContentResolver(),
                "gamespace_stay_awake_status", 0, UserHandle.USER_CURRENT);

        if (lockGestureEnabled()) {
            Settings.Secure.putInt(mContext.getContentResolver(),
                    "nt_game_mode_mistouch_prevention", 0);
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    "gamespace_lock_gesture_status", 0, UserHandle.USER_CURRENT);
        }

        if (bypassEnabled()) {
            setBypassActive(false);

            int restoreLevel = smartChargeByUser() ? 80 : 100;
            setSmartChargeLvl(restoreLevel);
        }
    }

    public void boostGame(boolean enable) {
        final boolean perfModeEnabledByUser = Settings.System.getIntForUser(
                mContext.getContentResolver(), "power_mode_perf_by_user", 0,
                UserHandle.USER_CURRENT) == 1;
        if (perfModeEnabledByUser) return;

        mBgHandler.post(() -> {
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    "persist.sys.power_mode_perf", enable ? 1 : 0,
                    UserHandle.USER_CURRENT);
            SystemProperties.set("persist.sys.power_mode_perf", enable ? "1" : "0");
        });
    }

    void setStayAwake(boolean enable) {
        if (!stayAwakeEnabled()) return;

        mBgHandler.post(() -> {
            if (enable) acquireWakeLock();
            else releaseWakeLock();

            Settings.System.putIntForUser(mContext.getContentResolver(),
                    "gamespace_stay_awake_status", enable ? 1 : 0,
                    UserHandle.USER_CURRENT);
        });
    }

    private void acquireWakeLock() {
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                mWakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, TAG + ":WakeLock");
            }
        }
        if (mWakeLock != null && !mWakeLock.isHeld()) mWakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) mWakeLock.release();
    }

    boolean stayAwakeEnabled() {
        return Settings.System.getIntForUser(
                mContext.getContentResolver(),
                "gamespace_stay_awake", 0, UserHandle.USER_CURRENT
        ) == 1;
    }

    void setLockGesture(boolean enable) {
        if (!lockGestureEnabled()) return;

        mBgHandler.post(() -> {
            Settings.Secure.putInt(mContext.getContentResolver(),
                    "nt_game_mode_mistouch_prevention", enable ? 1 : 0);
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    "gamespace_lock_gesture_status", enable ? 1 : 0,
                    UserHandle.USER_CURRENT);
        });
    }

    boolean lockGestureEnabled() {
        return Settings.System.getIntForUser(
                mContext.getContentResolver(),
                "gamespace_lock_gesture", 0, UserHandle.USER_CURRENT
        ) == 1;
    }

    void setBypassCharge(boolean enable) {
        if (!bypassEnabled()) return;

        mBgHandler.post(() -> {
            setBypassActive(enable);

            int newLevel;
            if (enable) {
                newLevel = battLevel();
            } else if (smartChargeByUser()) {
                newLevel = 80;
            } else {
                newLevel = 100;
            }

            setSmartChargeLvl(newLevel);
        });
    }

    boolean bypassEnabled() {
        return Settings.System.getIntForUser(
                mContext.getContentResolver(),
                "bypass_charge_enabled", 0, UserHandle.USER_CURRENT
        ) == 1;
    }

    boolean smartChargeByUser() {
        return Settings.System.getIntForUser(
                mContext.getContentResolver(),
                "smart_charge_by_user", 0, UserHandle.USER_CURRENT
        ) == 1;
    }

    int battLevel() {
        BatteryManager bm = (BatteryManager) mContext.getSystemService(Context.BATTERY_SERVICE);
        return bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
    }

    int getSmartChargeLvl() {
        return SystemProperties.getInt("persist.sys.smart_charge_level", 100);
    }

    void setSmartChargeLvl(int value) {
        SystemProperties.set("persist.sys.smart_charge_level", Integer.toString(value));
    }

    boolean isBypassActive() {
        return SystemProperties.getInt("persist.sys.gs_charge_bypass_active", 0) == 1;
    }

    void setBypassActive(boolean value) {
        SystemProperties.set("persist.sys.gs_charge_bypass_active", value ? "1" : "0");
    }
}
