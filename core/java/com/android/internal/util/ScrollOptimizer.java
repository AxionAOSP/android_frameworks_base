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
package com.android.internal.util;

import android.graphics.BLASTBufferQueue;
import android.os.Process;
import android.view.Choreographer;
import android.view.Surface;
import android.view.DisplayEventReceiver;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Locale;

public class ScrollOptimizer {

    /** @hide */
    public static final int FLING_START = 1;
    /** @hide */
    public static final int FLING_END = 0;

    private static final String TAG = "AxPerf";

    private static final String PROP_SCROLL_OPT = "persist.sys.perf.scroll_opt";
    private static final String PROP_SCROLL_OPT_HEAVY_APP = "persist.sys.perf.scroll_opt.heavy_app";
    private static final String PROP_DEBUG = "persist.sys.perf.scroll_opt_debug";
    private static final String PROP_FRAME_INSERT = "persist.sys.perf.frame_insert";
    private static final String PROP_PRE_ANIM = "persist.sys.perf.pre_anim";

    private static final String TIMER_SLACK_CONTENT = "50000";
    private static final float VELOCITY_EXTRA1 = 1000f;
    private static final float VELOCITY_EXTRA2 = 3000f;
    private static final float VELOCITY_USE_VSYNC = 300f;
    private static final float VELOCITY_BUFF_CAP = 2500f;

    private static final long FRAME_INTERVAL_THRESHOLD_NS = 10_000_000L;
    private static final long DEFAULT_FRAME_DELAY_MS = 10L;
    private static final long OPTIMIZED_FRAME_DELAY_MS = 3L;
    private static final long FLING_END_TIMEOUT_MS = 3000L;
    private static final long TIME_BACKWARD_THRESHOLD_NS = 500_000L;

    private static int sInitialUndequeued = 4;
    private static int sFallbackUndequeued = 3;

    private static final int PROP_UNSET = -1;
    private static final int MOTION_TOUCH = 0;
    private static final int MOTION_FLING = 1;
    private static final int MOTION_SCROLL = 2;
    private static final int APP_TYPE_NORMAL = 1;
    private static final int APP_TYPE_HEAVY = 2;

    private static boolean sPrevUseVsync = true;
    private static boolean sAdjustCalled = false;
    private static boolean sTimerSlackUpdated = false;
    private static boolean sPreAnimationEnable = true;
    private static boolean mIsPreAnim = false;
    private static long mLastAnimFrameTimeNano = 0;
    private static boolean sPreRenderDone = false;
    private static boolean sNeedUpdateBuffer = false;

    private static long sFrameIntervalNs = -1;
    private static long sFrameIntervalMs = -1;
    private static long sHalfFrameIntervalNs = -1;
    private static long sHeavyFrameThresholdNs = -1;
    private static long sLastVsyncTimeNs = -1;
    private static long sLastAdjustedTimeNs = -1;
    private static long sLastFlingStartMs = -1;
    private static long sLastUIStartNs = -1;
    private static long sLastUIEndNs = -1;
    private static long sLastUiDuration = 0;
    private static long sLastDoframeBeginNs = -1;
    private static long sLastDoframeEndNs = -1;
    private static long sLastFrameTimeNs = 0L;
    private static long sOriginalFrameTimeNs = 0L;
    private static long sAdjustedFrameTimeNs = 0L;
    private static long sDriftAccumulator;
    private static float sVelocity;
    private static boolean sIsTimeBackward = false;
    private static boolean sInsertFrameActive;
    private static boolean sIgnoreFling = false;
    private static boolean sWebViewFlutterFling = false;
    private static volatile boolean sVsyncIndexFull = false;
    private static int sInsertNum = 1;
    private static long sVsyncTimestampNs = 0L;
    private static int sVsyncPreferTimelineIndex = 0;
    private static boolean sIsFlingVague = false;
    private static boolean sIsFlingAccurate = false;
    private static boolean sScrollChangedEnable = false;
    private static boolean sIsFirstFrame = false;
    private static long sNonVsyncStartNs = 0;

    private static int sPid = -1;
    private static Choreographer sChoreographer;
    private static int sHeavyAppProp = -1;
    private static int sHeavyApp = 0;
    private static int sLastFrameRate;
    private static int mLastFrameRate;
    private static boolean mFrameRateChanging;
    private static int sHeavyFrameCount = 0;
    private static int sAppType = APP_TYPE_NORMAL;
    private static int sMotionType = PROP_UNSET;
    private static int mLastFlingFlg = 0;

    private static int sActualUndequeued = 0;
    private static int sExpectedUndequeued = 0;
    
    private static BLASTBufferQueue sBlastQueue = null;
    private static Method sSetUndequeuedMethod = null;
    private static Method sGetUndequeuedMethod = null;

    private static FileOutputStream sTimerSlackStream = null;

    private static boolean sDebugEnabled = false;
    private static boolean sInitCalled = false;
    private static boolean sFeatureEnabled = false;
    private static boolean sTemporarilyDisabled = false;
    private static boolean sLastUseVsync = true;
    private static boolean sFrameInsertEnabled = false;
    private static void logger(String msg) {
        if (sDebugEnabled) {
            Log.d(TAG, msg);
        }
    }

    private static int getUndequeuedBufferCount() {
        int undequeued = 0;
        if (sBlastQueue == null) {
            logger("sBlastBufferQueue is null.");
            sFeatureEnabled = false;
            return 0;
        }
        try {
            Object res = sGetUndequeuedMethod.invoke(sBlastQueue);
            undequeued = res instanceof Integer ? (Integer) res : 0;
            logger("undequeuedBufferCount: " + undequeued);
        } catch (Exception e) {
            undequeued = 0;
        }
        return undequeued;
    }

    private static void initIfNeeded() {
        try {
            sFeatureEnabled = SystemProperties.getBoolean(PROP_SCROLL_OPT, true);
            int prop = SystemProperties.getInt(PROP_SCROLL_OPT_HEAVY_APP, 2);
            sHeavyAppProp = prop;
            sHeavyApp = prop;
            sDebugEnabled = SystemProperties.getBoolean(PROP_DEBUG, false);
            sFrameInsertEnabled = SystemProperties.getBoolean(PROP_FRAME_INSERT, true);
            sPreAnimationEnable = SystemProperties.getBoolean(PROP_PRE_ANIM, true);

            Class<?> clazz = Class.forName("android.graphics.BLASTBufferQueue");
            sSetUndequeuedMethod = clazz.getMethod("setUndequeuedBufferCount", Integer.TYPE);
            sGetUndequeuedMethod = clazz.getMethod("getUndequeuedBufferCount");

            sPid = Process.myPid();
            sTimerSlackStream = new FileOutputStream(
                    String.format(Locale.US, "/proc/%d/timerslack_ns", sPid));

            if (Process.myUid() == 1000) {
                logger("Disable for system_server");
                sFeatureEnabled = false;
            }
            sInitCalled = true;
        } catch (Exception e) {
            Log.e(TAG, "Couldn't load BLASTBufferQueue Class");
            sInitCalled = true;
            sFeatureEnabled = false;
        }

        if (sHeavyApp == 1) {
            logger("Heavy app detection is enabled.");
        }
        if (sSetUndequeuedMethod == null || sGetUndequeuedMethod == null) {
            Log.e(TAG, "Couldn't find UndequeuedBufferCount functions");
            sFeatureEnabled = false;
        }
    }

    public static void disableOptimizer(boolean disable) {
        boolean wasTemporarilyDisabled = sTemporarilyDisabled;
        if (wasTemporarilyDisabled == disable) return;
        if (wasTemporarilyDisabled) {
            sFeatureEnabled = true;
            sTemporarilyDisabled = false;
            logger("enable ScrollOptimizer again.");
        } else if (sFeatureEnabled) {
            sFeatureEnabled = false;
            sTemporarilyDisabled = true;
            logger("disable ScrollOptimizer temperarily.");
        }
    }

    private static void resetFlingState() {
        sLastUseVsync = true;
        sAdjustCalled = false;
        sAppType = APP_TYPE_NORMAL;
        sHeavyFrameCount = 0;
        sMotionType = PROP_UNSET;
        mLastFlingFlg = 0;
        sTimerSlackUpdated = false;
        sVsyncIndexFull = false;
        sDriftAccumulator = 0;
        sLastAdjustedTimeNs = 0;
        mFrameRateChanging = false;
        sIsFirstFrame = true;
        sNonVsyncStartNs = 0;
    }

    private static void updateExpectedFromPreRender() {
        if (sPreRenderDone) {
            sPreRenderDone = false;
            int val = getUndequeuedBufferCount();
            sActualUndequeued = val;
            sExpectedUndequeued = val;
            if (val > 1) {
                sExpectedUndequeued = val - 1;
            } else if (val < 1) {
                sExpectedUndequeued = 1;
            }
        }
    }

    public static long getAdjustedAnimationClock(long frameTimeNanos) {
        if (!sFeatureEnabled || Process.myTid() != sPid) {
            return frameTimeNanos;
        }
        sOriginalFrameTimeNs = frameTimeNanos;
        if (sPreAnimationEnable && mIsPreAnim && mLastAnimFrameTimeNano > 0) {
            sLastAdjustedTimeNs = mLastAnimFrameTimeNano;
            return mLastAnimFrameTimeNano;
        }
        if (mLastFlingFlg != 1) {
            sLastAdjustedTimeNs = frameTimeNanos;
            return frameTimeNanos;
        }
        long adjustedClock = sLastAdjustedTimeNs + sFrameIntervalNs;
        if (adjustedClock < frameTimeNanos) {
            adjustedClock = frameTimeNanos;
        }
        sLastAdjustedTimeNs = adjustedClock;
        return adjustedClock;
    }

    public static long getFrameDelay() {
        if (!sFeatureEnabled) {
            return DEFAULT_FRAME_DELAY_MS;
        }
        if (sLastUiDuration > sFrameIntervalNs - 1_000_000) {
            return 0L;
        }
        if (sPreAnimationEnable && mIsPreAnim) {
            return 1L;
        }
        long delayed = Math.max(sFrameIntervalMs / OPTIMIZED_FRAME_DELAY_MS, OPTIMIZED_FRAME_DELAY_MS);
        return delayed;
    }

    private static void setUndequeuedBufferCount(int count) {
        if (sBlastQueue == null) {
            logger("sBlastBufferQueue is null.");
            sFeatureEnabled = false;
            return;
        }
        try {
            sSetUndequeuedMethod.invoke(sBlastQueue, count);
            logger("setUndequeuedBufferCount: " + count);
        } catch (Exception e) {
            e.printStackTrace();
            sFeatureEnabled = false;
        }
    }

    private static void writeTimerSlack() {
        if (sTimerSlackStream == null) {
            sFeatureEnabled = false;
            return;
        }
        StrictMode.ThreadPolicy old = StrictMode.allowThreadViolations();
        try {
            try {
                sTimerSlackStream.write(TIMER_SLACK_CONTENT.getBytes());
                sTimerSlackUpdated = true;
            } catch (IOException e) {
                e.printStackTrace();
                Log.w(TAG, "Failed to update timer slack!");
                sFeatureEnabled = false;
            }
        } finally {
            StrictMode.setThreadPolicy(old);
        }
    }

    public static void setBLASTBufferQueue(BLASTBufferQueue queue) {
        if (sFeatureEnabled && Process.myTid() == sPid && sBlastQueue != queue) {
            sBlastQueue = queue;
            sNeedUpdateBuffer = false;
            setUndequeuedBufferCount(sInitialUndequeued);
        }
    }

    public static void setSurface(Surface surface) {
    }

    public static void setInsertFrameActive(boolean active) {
        sInsertFrameActive = active;
    }

    public static void postFrameCallbackDelay(Choreographer choreographer,
            Runnable action, int frameCount) {
        if (choreographer == null || action == null || frameCount < 0) return;
        if (frameCount == 0) {
            action.run();
            return;
        }
        choreographer.postFrameCallback(new Choreographer.FrameCallback() {
            int remaining = frameCount;
            @Override
            public void doFrame(long frameTimeNanos) {
                if (--remaining > 0) {
                    choreographer.postFrameCallback(this);
                } else {
                    action.run();
                }
            }
        });
    }

    public static void setVelocity(float velocity) {
        sVelocity = Math.abs(velocity);
    }

    public static void setFlingFlag(int flingFlg) {
        if (sFeatureEnabled && Process.myTid() == sPid) {
            logger("setFlingFlag: " + flingFlg);
            if (flingFlg != 1) {
                if (flingFlg < 0) {
                    logger("Fling quit for unknown.");
                }
                if (mLastFlingFlg == 1) {
                    resetFlingState();
                    sIsFlingVague = false;
                    sIsFlingAccurate = false;
                    logger("Fling end.");
                }
                return;
            }
            if (mLastFlingFlg == 1) {
                resetFlingState();
                sIsFlingVague = false;
                sIsFlingAccurate = false;
                logger("avoid concurrent fling");
                return;
            }
            if (sMotionType == MOTION_FLING) {
                mLastFlingFlg = flingFlg;
                sIsFirstFrame = true;
                sIsFlingVague = true;
                sIsFlingAccurate = true;
                if (!sTimerSlackUpdated) {
                    writeTimerSlack();
                }
                sNeedUpdateBuffer = false;
                sLastFlingStartMs = SystemClock.uptimeMillis();
                int extra = 0;
                if (sVelocity >= VELOCITY_EXTRA2 && sVelocity >= VELOCITY_BUFF_CAP) extra = 2;
                else if (sVelocity >= VELOCITY_EXTRA1 && sVelocity >= VELOCITY_BUFF_CAP) extra = 1;
                if (sLastFrameRate > 0 && sLastFrameRate < 60) {
                    extra = Math.min(extra, 1);
                }
                if (extra > 0) setUndequeuedBufferCount(sInitialUndequeued + extra);
                logger("Fling start.");
            } else {
                logger("Fling without touch");
            }
            sMotionType = PROP_UNSET;
        }
    }

    private static int toFps(long frameIntervalNs) {
        if (frameIntervalNs <= 0) return 0;
        return (int) ((1.0E9f / frameIntervalNs) + 0.5f);
    }

    public static void setFrameInterval(long nanos) {
        if (!sInitCalled) {
            initIfNeeded();
        }
        logger("frameIntervalNanos: " + nanos);
        sFrameIntervalNs = nanos;
        sFrameIntervalMs = nanos / 1_000_000;
        sLastFrameRate = toFps(nanos);
        if (mLastFrameRate > 0) {
            int diff = Math.abs(sLastFrameRate - mLastFrameRate);
            if (diff >= 5) {
                mFrameRateChanging = true;
            } else if (diff < 3) {
                mFrameRateChanging = false;
            }
        }
        mLastFrameRate = sLastFrameRate;
        long half = nanos / 2;
        sHalfFrameIntervalNs = half;
        sHeavyFrameThresholdNs = half * OPTIMIZED_FRAME_DELAY_MS;
        if (nanos > FRAME_INTERVAL_THRESHOLD_NS) {
            sInitialUndequeued = 3;
            sFallbackUndequeued = 2;
        } else {
            sInitialUndequeued = 4;
            sFallbackUndequeued = 3;
        }
        if (sHeavyAppProp == PROP_UNSET) {
            if (nanos > FRAME_INTERVAL_THRESHOLD_NS) {
                sHeavyApp = 0;
            } else {
                sHeavyApp = 1;
            }
        }
    }

    public static void setMotionType(int motion) {
        if (sFeatureEnabled && Process.myTid() == sPid) {
            if (motion == MOTION_TOUCH) {
                boolean wasFling = (mLastFlingFlg == 1);
                resetFlingState();
                int curUndequeued = getUndequeuedBufferCount();
                sActualUndequeued = curUndequeued;
                if (sNeedUpdateBuffer && curUndequeued != sFallbackUndequeued) {
                    if (curUndequeued > sFallbackUndequeued ||
                        System.nanoTime() - sLastUIEndNs > (sFrameIntervalNs * 2) + 1_000_000) {
                        setUndequeuedBufferCount(sFallbackUndequeued);
                        sExpectedUndequeued = sFallbackUndequeued;
                    }
                } else if (wasFling || sActualUndequeued > 0) {
                    sExpectedUndequeued = sActualUndequeued;
                } else {
                    sExpectedUndequeued = 1;
                }
            } else if (motion == 1) {
                if (mLastFlingFlg == 1) {
                    logger("touch up during fling");
                    setUndequeuedBufferCount(sFallbackUndequeued);
                    resetFlingState();
                }
            } else if (motion == MOTION_SCROLL) {
                sNeedUpdateBuffer = true;
                int cur = getUndequeuedBufferCount();
                sActualUndequeued = cur;
                if (sExpectedUndequeued > cur && cur > 0) {
                    sExpectedUndequeued = cur;
                }
            }
            sMotionType = motion;
            logger("setMotionType: " + motion);
        }
    }

    public static void setDoframeBegin(Choreographer choreographer) {
        if (!sFeatureEnabled || Process.myTid() != sPid || mLastFlingFlg != 1) {
            return;
        }
        sChoreographer = choreographer;
    }

    public static void setDoframeEnd(long frameTimeNanos) {
        if (!sPreAnimationEnable || Process.myTid() != sPid || sChoreographer == null) {
            return;
        }
        if (sVelocity < VELOCITY_USE_VSYNC || mFrameRateChanging || mLastFlingFlg != 1
                || sLastUiDuration > sFrameIntervalNs - 1_000_000) {
            if (mIsPreAnim) {
                mIsPreAnim = false;
                mLastAnimFrameTimeNano = 0;
                sChoreographer.forceScheduleNexFrame();
            }
            return;
        }
        long newFrameTime = sOriginalFrameTimeNs + sFrameIntervalNs;
        long adjust = sLastAdjustedTimeNs + sFrameIntervalNs;
        long target = adjust < newFrameTime ? newFrameTime : adjust;
        mIsPreAnim = false;
        doPreAnimation(target);
        mIsPreAnim = true;
        mLastAnimFrameTimeNano = target;
    }

    public static boolean isPreAnim() {
        return sPreAnimationEnable && mLastFlingFlg == 1 && mIsPreAnim;
    }

    public static void setPreAnimConsumed() {
        mIsPreAnim = false;
    }

    private static void doPreAnimation(long frameTimeNano) {
        sChoreographer.doPreAnimation(frameTimeNano, sFrameIntervalNs);
    }

    public static void setUITaskStatus(boolean running) {
        if (sFeatureEnabled && Process.myTid() == sPid) {
            long nowNs = System.nanoTime();
            long uiDurationNs;
            if (running) {
                sLastDoframeBeginNs = nowNs;
                sAdjustCalled = false;
                if (mLastFlingFlg == 1) {
                    long durSinceUIStart = nowNs - sLastUIStartNs;
                    if (durSinceUIStart > (sFrameIntervalNs * 2) - 1_000_000) {
                        updateExpectedFromPreRender();
                    }
                    long hf = sHalfFrameIntervalNs;
                    if (durSinceUIStart > (OPTIMIZED_FRAME_DELAY_MS * hf) - 1_000_000 &&
                        nowNs - sLastUIEndNs > hf) {
                        sHeavyFrameCount++;
                    }
                }
                sLastUIStartNs = nowNs;
                uiDurationNs = 0;
            } else {
                sLastUIEndNs = nowNs;
                sLastDoframeEndNs = nowNs;
                sLastUiDuration = sLastDoframeEndNs - sLastDoframeBeginNs;
                sPrevUseVsync = sLastUseVsync;
                uiDurationNs = nowNs - sLastUIStartNs;
                if (mLastFlingFlg == 1 && uiDurationNs > sFrameIntervalNs * 2) {
                    updateExpectedFromPreRender();
                }
            }
            int mode = sHeavyApp;
            if (mode == 0) return;
            if (mode == 2) {
                sAppType = APP_TYPE_HEAVY;
                return;
            }
            if (running) return;
            if (sMotionType == MOTION_SCROLL || mLastFlingFlg == 1) {
                if (uiDurationNs > sFrameIntervalNs) {
                    sHeavyFrameCount++;
                }
                if ((sHeavyFrameCount > 1 || uiDurationNs > sHeavyFrameThresholdNs) &&
                    sAppType != APP_TYPE_HEAVY) {
                    sAppType = APP_TYPE_HEAVY;
                    logger("App type: heavy app");
                }
            }
            logger("UI duration: " + uiDurationNs);
        }
    }

    public static void setVsyncTime(long vsyncTimeNs) {
        if (sFeatureEnabled) {
            sLastVsyncTimeNs = vsyncTimeNs;
            logger("setVsyncTime: " + sLastVsyncTimeNs);
        }
    }

    public static boolean shouldUseVsync() {
        boolean result = true;
        if (sFeatureEnabled && Process.myTid() == sPid) {
            if (mLastFlingFlg != 1) {
                sLastUseVsync = true;
                return true;
            }
            if (sIsFirstFrame) {
                sIsFirstFrame = false;
                sLastUseVsync = true;
                return true;
            }
            if (sVelocity < VELOCITY_USE_VSYNC) {
                sLastUseVsync = true;
                return true;
            }
            if (sVsyncIndexFull) {
                sLastUseVsync = true;
                return true;
            }
            if (sNonVsyncStartNs > 0
                    && Math.abs(System.nanoTime() - sNonVsyncStartNs) > sFrameIntervalNs * 6) {
                sNonVsyncStartNs = 0;
                sLastUseVsync = true;
                return true;
            }
            if (isEnabledFlingScene() && sFrameIntervalNs > 0) {
                long now = System.nanoTime();
                long intendedNext = sLastFrameTimeNs + sFrameIntervalNs;
                if (!reachInsertCountThreshold(now, intendedNext) && hasAvailableBuffer()) {
                    sNonVsyncStartNs = now;
                    sLastUseVsync = false;
                    return false;
                }
            }
            if (sAppType == APP_TYPE_HEAVY) {
                sNonVsyncStartNs = System.nanoTime();
                sLastUseVsync = false;
                return false;
            }
            if (sPreRenderDone) {
                logger("pre-render done");
                sLastUseVsync = true;
                return true;
            }
            long interval = sFrameIntervalNs;
            long timeToNext = interval - ((sLastUIStartNs - sLastVsyncTimeNs) % interval);
            if (timeToNext < 3_000_000L) {
                logger("too close to next vsync");
                sNonVsyncStartNs = System.nanoTime();
                sLastUseVsync = false;
                if (sExpectedUndequeued > 0) {
                    sExpectedUndequeued = sExpectedUndequeued - 1;
                }
                return false;
            }
            if (!sLastUseVsync) {
                logger("use vsync as last frame not use vsync");
                sLastUseVsync = true;
                return true;
            }
            int undequeued = getUndequeuedBufferCount();
            sActualUndequeued = undequeued;
            int expected = sExpectedUndequeued;
            if (undequeued > expected) {
                logger("align undequeued: " + sActualUndequeued + " with expected: " + sExpectedUndequeued);
                sActualUndequeued = expected;
            } else if (undequeued < 1 && expected > 0) {
                sActualUndequeued = 1;
            }
            if (sActualUndequeued > 0) {
                sExpectedUndequeued = sExpectedUndequeued - 1;
                if (sNonVsyncStartNs == 0) sNonVsyncStartNs = System.nanoTime();
                result = false;
            } else {
                sPreRenderDone = true;
                logger("pre-render done");
                result = true;
            }
            sLastUseVsync = result;
        }
        return result;
    }

    public static boolean isEnabledFlingScene() {
        return !sIgnoreFling && !sWebViewFlutterFling;
    }

    public static void setIgnoreFling(boolean state) {
        sIgnoreFling = state;
    }

    public static boolean isIgnoreFling() {
        return sIgnoreFling;
    }

    public static void setWebViewFlutterFling(boolean state) {
        sWebViewFlutterFling = state;
    }

    public static long syncWithVsync(long timeNanos, long referVsyncTimeNanos) {
        long frameIntervalNanos = sFrameIntervalNs;
        if (frameIntervalNanos <= 0) {
            return timeNanos;
        }
        long offset = (timeNanos - referVsyncTimeNanos) % frameIntervalNanos;
        long threshold = (frameIntervalNanos >> 1) + (frameIntervalNanos >> 2);
        long ret;
        if (offset < 0) {
            long absOffset = -offset;
            ret = absOffset > threshold
                    ? timeNanos - (frameIntervalNanos - absOffset)
                    : timeNanos + absOffset;
        } else if (offset > threshold) {
            ret = timeNanos + (frameIntervalNanos - offset);
        } else {
            ret = timeNanos - offset;
            if (ret + threshold < timeNanos) {
                ret = timeNanos;
            }
        }
        return ret;
    }

    public static boolean isTimeBackward() {
        return sIsTimeBackward;
    }

    public static void updateFrameTimeNanos(long frameTimeNanos, long lastFrameTimeNanos,
            long startNanos) {
        if (!sFeatureEnabled || Process.myTid() != sPid) {
            sAdjustedFrameTimeNs = frameTimeNanos;
            return;
        }
        if (!sInsertFrameActive) {
            sOriginalFrameTimeNs = frameTimeNanos;
        }
        long adjusted = frameTimeNanos;
        if (mLastFlingFlg == FLING_START) {
            long frameInterval = sFrameIntervalNs;
            if (frameInterval > 0) {
                long intendedNext = sLastFrameTimeNs + frameInterval;
                long gap = intendedNext - startNanos;
                long candidate;
                if (gap < 0) {
                    long skipped = (-gap) / frameInterval;
                    candidate = (skipped * frameInterval) + intendedNext;
                } else {
                    candidate = intendedNext;
                }
                adjusted = syncWithVsync(candidate, frameTimeNanos);
            }
        }
        if (mLastFlingFlg == FLING_START && sFrameIntervalNs > 0 && sLastFrameTimeNs > 0) {
            long drift = adjusted - sLastFrameTimeNs - sFrameIntervalNs;
            sDriftAccumulator += drift;
            if (Math.abs(sDriftAccumulator) > sFrameIntervalNs / 4) {
                adjusted = sLastFrameTimeNs + sFrameIntervalNs
                        + (sDriftAccumulator > 0 ? -sFrameIntervalNs / 8 : sFrameIntervalNs / 8);
                sDriftAccumulator = 0;
            }
        }
        sAdjustedFrameTimeNs = adjusted;
        sIsTimeBackward = (adjusted - sLastFrameTimeNs) < TIME_BACKWARD_THRESHOLD_NS;
        if (!sIsTimeBackward) {
            sLastFrameTimeNs = adjusted;
        }
    }

    public static long getAdjustedFrameTimeNanos(long fallback) {
        if (!sFeatureEnabled || Process.myTid() != sPid) {
            return fallback;
        }
        return sAdjustedFrameTimeNs != 0L ? sAdjustedFrameTimeNs : fallback;
    }

    public static long getLastFrameTimeNanos() {
        return sLastFrameTimeNs;
    }

    public static long getOriginalFrameTimeNanos() {
        return sOriginalFrameTimeNs;
    }

    public static void setVsyncIndexFull(boolean state) {
        sVsyncIndexFull = state;
    }

    public static boolean isVsyncIndexFull() {
        return sVsyncIndexFull;
    }

    public static void setInsertNum(int num) {
        sInsertNum = num < 0 ? 0 : num;
    }

    public static int getInsertNum() {
        return sInsertNum;
    }

    private static boolean hasAvailableBuffer() {
        int undequeued = getUndequeuedBufferCount();
        return (sInsertNum + 4) + undequeued > 0;
    }

    public static void setFlingVague(boolean isFling, int pageType) {
        sIsFlingVague = isFling;
        if (pageType > 100) {
            sWebViewFlutterFling = isFling;
        }
        if (!isFling && sIgnoreFling) {
            sIgnoreFling = false;
        }
    }

    public static void setFlingAccurate(boolean isFling, int pageType) {
        sIsFlingAccurate = isFling;
        if (pageType > 100) {
            sWebViewFlutterFling = isFling;
        }
        if (!isFling && sIgnoreFling) {
            sIgnoreFling = false;
        }
    }

    public static void setScrollChangedEnable(boolean enable) {
        sScrollChangedEnable = enable;
    }

    public static boolean isScrollChangedEnable() {
        return sScrollChangedEnable;
    }

    public static boolean isFling() {
        if (!sFeatureEnabled) return false;
        return sScrollChangedEnable ? sIsFlingVague : sIsFlingAccurate;
    }

    public static void updateOnVsyncInfo(long timestampNanos,
            DisplayEventReceiver.VsyncEventData vsyncEventData) {
        sVsyncTimestampNs = timestampNanos;
        if (vsyncEventData != null) {
            sVsyncPreferTimelineIndex = vsyncEventData.preferredFrameTimelineIndex;
        }
    }

    public static long getVsyncTimestampNanos() {
        return sVsyncTimestampNs;
    }

    public static int getVsyncPreferTimelineIndex() {
        return sVsyncPreferTimelineIndex;
    }

    public static boolean reachInsertCountThreshold(long nowNanos, long nextFrameTimeNanos) {
        long frameInterval = sFrameIntervalNs;
        if (frameInterval <= 0) {
            return true;
        }
        long originalFrame = sOriginalFrameTimeNs;
        long gapFromOriginal = nowNanos - originalFrame;
        long anchor;
        if (gapFromOriginal < frameInterval) {
            anchor = originalFrame;
        } else {
            anchor = nowNanos - (gapFromOriginal % frameInterval);
        }
        long diff = nextFrameTimeNanos - anchor + 500_000L;
        long count = diff / frameInterval;
        return count > sInsertNum;
    }

    public static boolean isActiveFling() {
        return sFeatureEnabled && mLastFlingFlg == FLING_START;
    }

    public static boolean isActiveScroll() {
        return sFeatureEnabled && (mLastFlingFlg == FLING_START || sMotionType == MOTION_SCROLL);
    }

    public static boolean shouldInsertFrame() {
        if (!sFeatureEnabled || !sFrameInsertEnabled || Process.myTid() != sPid) {
            return false;
        }
        if (mLastFlingFlg != FLING_START) {
            return false;
        }
        if (!isEnabledFlingScene()) {
            return false;
        }
        if (sVsyncIndexFull) {
            return false;
        }
        long frameInterval = sFrameIntervalNs;
        if (frameInterval > 0) {
            long nowNs = System.nanoTime();
            long intendedNext = sLastFrameTimeNs + frameInterval;
            if (reachInsertCountThreshold(nowNs, intendedNext)) {
                logger("frameInsert: insert count threshold reached");
                return false;
            }
        }
        int buffers = getUndequeuedBufferCount();
        if (buffers < 1) {
            logger("frameInsert: no buffer slots available");
            return false;
        }
        logger("frameInsert: eligible, buffers=" + buffers);
        return true;
    }
}
