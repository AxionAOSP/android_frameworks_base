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
package com.android.server.wm;

import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.server.DisplayThread;

import java.util.concurrent.atomic.AtomicInteger;

public class AxRefreshRateController {

    // ──────────────────────────────────────────────────────────────────────
    // Interfaces
    // ──────────────────────────────────────────────────────────────────────

    public interface RefreshRateUpdateCallback {
        void onRefreshRateChanged(float min, float peak, int displayId);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Constants
    // ──────────────────────────────────────────────────────────────────────

    private static final String TAG = "AxRefreshRateController";

    private static final String SETTINGS_REFRESH_RATE_MODE = "display_refresh_rate_mode";
    private static final String LOCKSCREEN_LIMIT_REFRESH_RATE = "lockscreen_limit_refresh_rate";
    private static final String PER_APP_REFRESH_RATE = "per_app_refresh_rate";

    private static final int BOOST_TOUCH = 1;
    private static final int BOOST_FOCUS = 1 << 2;
    private static final int BOOST_FLING = 1 << 3;

    private static final long DISPLAY_CHANGE_REQUERY_DELAY_MS = 500;
    private static final float PEAK_REFRESH_RATE_OFFSET = 1.0f;

    // ──────────────────────────────────────────────────────────────────────
    // Static fields
    // ──────────────────────────────────────────────────────────────────────

    private static final AxRefreshRateController sInstance = new AxRefreshRateController();

    private static final boolean sSupportsVrr = SystemProperties.getBoolean(
            "ro.surface_flinger.use_content_detection_for_refresh_rate", false);

    // ──────────────────────────────────────────────────────────────────────
    // Core dependencies
    // ──────────────────────────────────────────────────────────────────────

    private Context mContext;
    private Handler mHandler;
    private WindowManagerService mWmService;
    private volatile RefreshRateUpdateCallback mRateCallback;
    private volatile boolean mInitialized = false;

    // ──────────────────────────────────────────────────────────────────────
    // Display state
    // ──────────────────────────────────────────────────────────────────────

    private volatile float mMaxSupportedHz = 60f;
    private volatile float mDefaultMinHz = 60f;

    // ──────────────────────────────────────────────────────────────────────
    // Mode state — written on bgHandler or WM thread, read from both
    // ──────────────────────────────────────────────────────────────────────

    private volatile boolean mVrrEnabled = false;
    private volatile int mFixedRefreshRate = 60;
    private volatile boolean mLockscreenLimitEnabled = false;
    private volatile boolean mKeyguardDone = true;
    private volatile boolean mAppOverrideActive = false;
    private volatile float mPerAppOverrideRate = 0f;
    private volatile String mFocusedPackage = "";

    // ──────────────────────────────────────────────────────────────────────
    // Boost state
    // ──────────────────────────────────────────────────────────────────────

    private final AtomicInteger mActiveBoosts = new AtomicInteger(0);
    private long mIdleTimeoutMs;
    private long mFocusBoostTimeoutMs;
    private volatile long mLastActivityTime = 0;

    // ──────────────────────────────────────────────────────────────────────
    // Vote state — accessed on WM thread during traversal
    // ──────────────────────────────────────────────────────────────────────

    private boolean mCurrentVoteActive = false;
    private float mCurrentVoteMin = 0f;
    private float mCurrentVoteMax = 0f;

    @GuardedBy("mUserAppRefreshRates")
    private final ArrayMap<String, Float> mUserAppRefreshRates = new ArrayMap<>();

    // ──────────────────────────────────────────────────────────────────────
    // Sync dedup state — accessed on bgHandler
    // ──────────────────────────────────────────────────────────────────────

    private volatile float mLastSyncedPeak = -1f;
    private volatile float mLastSyncedMin = -1f;
    private volatile String mLastSrc = "";

    // ──────────────────────────────────────────────────────────────────────
    // Runnables
    // ──────────────────────────────────────────────────────────────────────

    private final Runnable mSyncRunnable = this::syncDisplaySettings;

    private final Runnable mForceSyncRunnable = () -> {
        invalidateLastSync();
        syncDisplaySettings();
    };

    private final Runnable mDisplayChangeRequeryRunnable = () -> {
        queryAndApplyDisplayModes();
        invalidateLastSync();
        syncDisplaySettings();
    };

    private final Runnable mFlingBoostTimeoutRunnable = () -> {
        if (clearBoost(BOOST_FLING)) syncDisplaySettings();
    };

    // ──────────────────────────────────────────────────────────────────────
    // Constructor + singleton
    // ──────────────────────────────────────────────────────────────────────

    private AxRefreshRateController() {}

    public static AxRefreshRateController getInstance() { return sInstance; }

    public void init(Context context, WindowManagerService wms) {
        if (mInitialized) return;
        mContext = context;
        mWmService = wms;

        mHandler = DisplayThread.getHandler();

        mIdleTimeoutMs = SystemProperties.getLong(
                "persist.sys.ax.idle_timeout_ms", 5000);
        mFocusBoostTimeoutMs = SystemProperties.getLong(
                "persist.sys.ax.focus_boost_timeout_ms", 5000);

        queryAndApplyDisplayModes();

        loadRefreshRateSetting();
        loadPerAppRefreshRates();

        mInitialized = true;
        new SettingsObserver();
        Slog.i(TAG, "init: maxHz=" + mMaxSupportedHz + " minHz=" + mDefaultMinHz
                + " vrr=" + mVrrEnabled + " supportsVRR=" + sSupportsVrr
                + " fixedRate=" + mFixedRefreshRate
                + " idleTimeout=" + mIdleTimeoutMs);
        forceResync();
        mHandler.postDelayed(mDisplayChangeRequeryRunnable, DISPLAY_CHANGE_REQUERY_DELAY_MS);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Public API — callbacks
    // ──────────────────────────────────────────────────────────────────────

    public void setRefreshRateUpdateCallback(RefreshRateUpdateCallback callback) {
        mRateCallback = callback;
        forceResync();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Public API — input & animation events
    // ──────────────────────────────────────────────────────────────────────

    public void onPointerEvent() {
        if (!mInitialized || !mVrrEnabled) return;
        if (mAppOverrideActive) return;
        if (mLockscreenLimitEnabled && !mKeyguardDone) return;
        if (setBoost(BOOST_TOUCH)) mHandler.post(mSyncRunnable);
    }

    public void setFlingBoost(long durationMillis) {
        if (!mInitialized || !mVrrEnabled) return;
        mHandler.post(() -> setFlingBoostInternal(durationMillis));
    }

    private void setFlingBoostInternal(long durationMillis) {
        mHandler.removeCallbacks(mFlingBoostTimeoutRunnable);
        if (mAppOverrideActive
                || (mLockscreenLimitEnabled && !mKeyguardDone)
                || durationMillis <= 0) {
            if (clearBoost(BOOST_FLING)) syncDisplaySettings();
            return;
        }

        if (setBoost(BOOST_FLING)) syncDisplaySettings();
        mHandler.postDelayed(mFlingBoostTimeoutRunnable, durationMillis);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Public API — system state changes
    // ──────────────────────────────────────────────────────────────────────

    public void setKeyguardDone(boolean done) {
        mKeyguardDone = done;
        if (mInitialized) {
            if (done && mVrrEnabled) {
                if (!mAppOverrideActive) setBoost(BOOST_FOCUS);
            }
            mHandler.post(mSyncRunnable);
            mWmService.requestTraversal();
        }
    }

    public void updateFocusedApp(final ActivityRecord activityRecord) {
        if (!mInitialized) return;
        String pkg = activityRecord.packageName == null ? "" : activityRecord.packageName;
        mFocusedPackage = pkg;
        updateFocusedAppOverride();
        if (mVrrEnabled && !mAppOverrideActive) setBoost(BOOST_FOCUS);
        mHandler.post(() -> {
            syncDisplaySettings();
            mWmService.requestTraversal();
        });
    }

    public void onDisplayChanged() {
        if (!mInitialized) return;
        queryAndApplyDisplayModes();
        invalidateLastSync();
        mHandler.removeCallbacks(mDisplayChangeRequeryRunnable);
        mHandler.post(mSyncRunnable);
        mHandler.postDelayed(mDisplayChangeRequeryRunnable, DISPLAY_CHANGE_REQUERY_DELAY_MS);
    }

    public void forceResync() {
        if (!mInitialized) return;
        mHandler.post(mForceSyncRunnable);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Public API — vote cycle (called on WM thread during traversal)
    // ──────────────────────────────────────────────────────────────────────

    public void updateVoteResult() {
        if (!mInitialized) return;
        clearCurrentVote();

        if (mLockscreenLimitEnabled && !mKeyguardDone) {
            setCurrentVote(0f, mDefaultMinHz);
        } else if (mAppOverrideActive && mPerAppOverrideRate > 0f) {
            setCurrentVote(mPerAppOverrideRate, mPerAppOverrideRate);
        } else if (!mVrrEnabled) {
            float rate = resolveLockedRefreshRate();
            setCurrentVote(rate, rate);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Public API — vote accessors
    // ──────────────────────────────────────────────────────────────────────

    public boolean hasActiveVote() {
        return mCurrentVoteActive;
    }

    public boolean shouldSuppressAppRefreshRateRequests() {
        return mInitialized;
    }

    public float getMaxPreferredRate() {
        return mCurrentVoteMax;
    }

    public float getMinPreferredRate() {
        return mCurrentVoteMin;
    }

    private long getVrrTimeOut() {
        return hasBoost(BOOST_FOCUS) ? mFocusBoostTimeoutMs : mIdleTimeoutMs;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Private — boost helpers
    // ──────────────────────────────────────────────────────────────────────

    private boolean setBoost(int flag) {
        mLastActivityTime = SystemClock.uptimeMillis();
        int activeBoosts;
        do {
            activeBoosts = mActiveBoosts.get();
            if ((activeBoosts & flag) != 0) return false;
        } while (!mActiveBoosts.compareAndSet(activeBoosts, activeBoosts | flag));
        return true;
    }

    private boolean clearBoost(int flag) {
        int activeBoosts;
        int newBoosts;
        do {
            activeBoosts = mActiveBoosts.get();
            if ((activeBoosts & flag) == 0) return false;
            newBoosts = activeBoosts & ~flag;
        } while (!mActiveBoosts.compareAndSet(activeBoosts, newBoosts));
        return true;
    }

    private void clearBoosts() {
        if (isBoosted()) mActiveBoosts.set(0);
    }

    private boolean hasBoost(int flag) {
        return (mActiveBoosts.get() & flag) != 0;
    }

    private boolean isBoosted() {
        return mActiveBoosts.get() != 0;
    }

    private float resolveUserRefreshRate(Float rate) {
        if (rate == null || rate <= 0) {
            return 0f;
        }
        return Math.max(mDefaultMinHz, Math.min(rate, mMaxSupportedHz));
    }

    private void scheduleSyncDisplaySettings(long delayMillis) {
        mHandler.removeCallbacks(mSyncRunnable);
        mHandler.postDelayed(mSyncRunnable, delayMillis);
    }

    private void invalidateLastSync() {
        mLastSyncedPeak = -1f;
        mLastSyncedMin = -1f;
        mLastSrc = "";
    }

    private void setCurrentVote(float min, float max) {
        mCurrentVoteActive = true;
        mCurrentVoteMin = min;
        mCurrentVoteMax = max;
    }

    private void clearCurrentVote() {
        mCurrentVoteActive = false;
        mCurrentVoteMin = 0f;
        mCurrentVoteMax = 0f;
    }

    private void queryAndApplyDisplayModes() {
        final DisplayManager dm = mContext.getSystemService(DisplayManager.class);
        Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) return;

        float newMax = 60f;
        float newMin = 0f;

        String customList = SystemProperties.get("persist.sys.display_refresh_rates_list", "");
        boolean customListParsed = false;
        if (!customList.isEmpty()) {
            try {
                String[] rates = customList.split(",");
                float parsedMin = Float.MAX_VALUE;
                float parsedMax = 0;
                for (String rateStr : rates) {
                    float rate = Float.parseFloat(rateStr.trim());
                    if (rate < parsedMin) parsedMin = rate;
                    if (rate > parsedMax) parsedMax = rate;
                }
                if (parsedMax > 0) {
                    newMax = parsedMax;
                    newMin = parsedMin;
                    customListParsed = true;
                }
            } catch (Exception e) {
                Slog.e(TAG, "Failed to parse persist.sys.display_refresh_rates_list: "
                        + customList, e);
            }
        }

        if (!customListParsed) {
            Display.Mode[] supportedModes = display.getSupportedModes();
            newMin = Float.MAX_VALUE;
            for (Display.Mode mode : supportedModes) {
                float hz = mode.getRefreshRate();
                if (hz > newMax) newMax = hz;
                if (hz < newMin) newMin = hz;
            }
            if (newMin == Float.MAX_VALUE) newMin = 60f;
        }

        if (newMax <= 0 || newMax < newMin) return;

        mDefaultMinHz = newMin;
        mMaxSupportedHz = newMax;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Private — display sync
    // ──────────────────────────────────────────────────────────────────────

    private void syncDisplaySettings() {
        final boolean vrr = mVrrEnabled;
        final boolean appOverrideActive = mAppOverrideActive && mPerAppOverrideRate > 0f;
        boolean boosted = !appOverrideActive && isBoosted();
        final long vrrTimeout = getVrrTimeOut();
        long nextVrrIdleCheckDelay = -1;

        if (vrr && boosted) {
            final long elapsed = SystemClock.uptimeMillis() - mLastActivityTime;
            if (elapsed >= vrrTimeout && !hasBoost(BOOST_FLING)) {
                clearBoosts();
                boosted = false;
            } else if (!hasBoost(BOOST_FLING)) {
                nextVrrIdleCheckDelay = Math.max(1, vrrTimeout - elapsed);
            }
        }

        final boolean lsLimit = mLockscreenLimitEnabled;
        final boolean kgDone = mKeyguardDone;
        final float maxHz = mMaxSupportedHz;
        final float minHz = mDefaultMinHz;
        float peak;
        float min;
        String src;
        if (lsLimit && !kgDone) {
            peak = minHz + PEAK_REFRESH_RATE_OFFSET;
            min = 0f;
            src = "LS_LIMIT";
        } else if (appOverrideActive) {
            peak = mPerAppOverrideRate + PEAK_REFRESH_RATE_OFFSET;
            min = mPerAppOverrideRate;
            src = "APP";
        } else if (!vrr) {
            float rate = resolveLockedRefreshRate();
            peak = rate + PEAK_REFRESH_RATE_OFFSET;
            min = rate;
            src = "FIXED";
        } else if (vrr && boosted) {
            peak = maxHz + PEAK_REFRESH_RATE_OFFSET;
            min = maxHz;
            src = "VRR_INTERACTIVE";
        } else {
            peak = minHz + PEAK_REFRESH_RATE_OFFSET;
            min = 0f;
            src = "VRR_IDLE";
        }
        final RefreshRateUpdateCallback callback = mRateCallback;
        if (callback == null) {
            if (nextVrrIdleCheckDelay > 0) {
                scheduleSyncDisplaySettings(nextVrrIdleCheckDelay);
            }
            return;
        }

        if (peak == mLastSyncedPeak && min == mLastSyncedMin && src.equals(mLastSrc)) {
            if (nextVrrIdleCheckDelay > 0) {
                scheduleSyncDisplaySettings(nextVrrIdleCheckDelay);
            }
            return;
        }
        mLastSyncedPeak = peak;
        mLastSyncedMin = min;
        mLastSrc = src;
        callback.onRefreshRateChanged(min, peak, Display.DEFAULT_DISPLAY);
        if (nextVrrIdleCheckDelay > 0) {
            scheduleSyncDisplaySettings(nextVrrIdleCheckDelay);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Private — settings loading
    // ──────────────────────────────────────────────────────────────────────

    private void loadRefreshRateSetting() {
        int value = Settings.Global.getInt(mContext.getContentResolver(),
                SETTINGS_REFRESH_RATE_MODE, sSupportsVrr ? 0 : Math.round(mMaxSupportedHz));
        mVrrEnabled = (value == 0 && sSupportsVrr);
        mFixedRefreshRate = mVrrEnabled || value <= 0 ? Math.round(mMaxSupportedHz) : value;
        mLockscreenLimitEnabled = Settings.System.getInt(mContext.getContentResolver(),
                LOCKSCREEN_LIMIT_REFRESH_RATE, 0) != 0;
    }

    private void loadPerAppRefreshRates() {
        String config = Settings.System.getString(mContext.getContentResolver(),
                PER_APP_REFRESH_RATE);
        ArrayMap<String, Float> parsed = new ArrayMap<>();
        if (config != null && !config.isEmpty()) {
            String[] apps = config.split(",");
            for (String app : apps) {
                String[] parts = app.split(":");
                if (parts.length >= 2) {
                    try {
                        float rate = Float.parseFloat(parts[1]);
                        if (rate > 0) {
                            parsed.put(parts[0], rate);
                        }
                    } catch (NumberFormatException e) {
                        Slog.e(TAG, "Failed to parse refresh rate for app: " + app, e);
                    }
                }
            }
        }
        synchronized (mUserAppRefreshRates) {
            mUserAppRefreshRates.clear();
            mUserAppRefreshRates.putAll(parsed);
        }
    }

    private void refreshFocusedAppOverride() {
        updateFocusedAppOverride();
        if (mVrrEnabled && !mAppOverrideActive) setBoost(BOOST_FOCUS);
        syncDisplaySettings();
        mWmService.requestTraversal();
    }

    private void updateFocusedAppOverride() {
        String pkg = mFocusedPackage;
        if (pkg == null || pkg.isEmpty()) {
            mAppOverrideActive = false;
            mPerAppOverrideRate = 0f;
            return;
        }
        Float userRate;
        synchronized (mUserAppRefreshRates) {
            userRate = mUserAppRefreshRates.get(pkg);
        }
        float resolvedUserRate = resolveUserRefreshRate(userRate);
        if (resolvedUserRate > 0) {
            mAppOverrideActive = true;
            mPerAppOverrideRate = resolvedUserRate;
        } else {
            mAppOverrideActive = false;
            mPerAppOverrideRate = 0f;
        }
    }

    private float resolveLockedRefreshRate() {
        float rate = mFixedRefreshRate > 0 ? mFixedRefreshRate : mMaxSupportedHz;
        if (rate < mDefaultMinHz) return mDefaultMinHz;
        return Math.min(rate, mMaxSupportedHz);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Inner class — settings observer
    // ──────────────────────────────────────────────────────────────────────

    private final class SettingsObserver extends ContentObserver {
        private final Uri mRefreshRateModeUri =
                Settings.Global.getUriFor(SETTINGS_REFRESH_RATE_MODE);
        private final Uri mLockscreenLimitUri =
                Settings.System.getUriFor(LOCKSCREEN_LIMIT_REFRESH_RATE);
        private final Uri mPerAppRefreshRateUri =
                Settings.System.getUriFor(PER_APP_REFRESH_RATE);
        SettingsObserver() {
            super(mHandler);
            mContext.getContentResolver().registerContentObserver(
                    mRefreshRateModeUri, false, this, -1);
            mContext.getContentResolver().registerContentObserver(
                    mLockscreenLimitUri, false, this, -1);
            mContext.getContentResolver().registerContentObserver(
                    mPerAppRefreshRateUri, false, this, -1);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (mPerAppRefreshRateUri.equals(uri)) {
                loadPerAppRefreshRates();
                refreshFocusedAppOverride();
                return;
            }
            boolean wasVrrEnabled = mVrrEnabled;
            loadRefreshRateSetting();
            if (mVrrEnabled && !wasVrrEnabled && !mAppOverrideActive) setBoost(BOOST_FOCUS);
            syncDisplaySettings();
            mWmService.requestTraversal();
        }
    }
}
