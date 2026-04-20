package com.android.internal.util;

import android.app.ActivityManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

public class BoostHelper {

    private static final String TAG = "BoostHelper";
    private static final boolean DEBUG = false;

    public static void adjustCpusetCpus(String group, String cpus, long duration) {
        try {
            ActivityManager.getService().adjustCpusetCpus(group, cpus, duration);
        } catch (Exception e) {
            logException("adjustCpusetCpus", e);
        }
    }

    public static void inputBoost() {
        try {
            ActivityManager.getService().inputBoost();
        } catch (Exception e) {
            logException("inputBoost", e);
        }
    }

    public static void boostThread(int tid) {
        try {
            ActivityManager.getService().boostThread(tid);
        } catch (Exception e) {
            logException("boostThread", e);
        }
    }

    public static void launcherItemsLoadingBoost(long duration) {
        try {
            ActivityManager.getService().launcherItemsLoadingBoost(duration);
        } catch (Exception e) {
            logException("launcherItemsLoadingBoost", e);
        }
    }

    public static void systemThreadBoost(int tid, long duration) {
        try {
            ActivityManager.getService().systemThreadBoost(tid, duration);
        } catch (Exception e) {
            logException("systemThreadBoost", e);
        }
    }

    public static void flingBoost(boolean active) {
        try {
            ActivityManager.getService().flingBoost(active);
        } catch (Exception e) {
            logException("flingBoost", e);
        }
    }

    public static void compositionBoost(long durationMs) {
        try {
            ActivityManager.getService().compositionBoost(durationMs);
        } catch (Exception e) {
            logException("compositionBoost", e);
        }
    }

    public static void gpuBoost(boolean active) {
        try {
            ActivityManager.getService().gpuBoost(active);
        } catch (Exception e) {
            logException("gpuBoost", e);
        }
    }

    public static void shadeBoost(boolean active) {
        try {
            ActivityManager.getService().shadeBoost(active);
        } catch (Exception e) {
            logException("shadeBoost", e);
        }
    }

    public static final class RefreshRate {
        public static final int FLING_START = 0;
        public static final int FLING_UPDATE = 1;
        public static final int FLING_FINISH = 2;
        public static final int SCROLLER_INIT = 3;
        public static final int TOUCH_SCROLL_ENABLE = 4;
        public static final int FLING_FRICTION_UPDATE = 5;
        private RefreshRate() {}
    }

    public static final class Scroll {
        public static final int INPUT_EVENT = 0;
        public static final int PREFILING = 1;
        public static final int VERTICAL = 2;
        public static final int SCROLLER = 3;
        private Scroll() {}
    }

    public static final class Launch {
        public static final int LAUNCH_COLD = 1;
        public static final int LAUNCH_HOT = 2;
        public static final int ACTIVITY_SWITCH = 3;
        private Launch() {}
    }

    public static final class Frame {
        public static final int REAL_DRAW = 6;
        public static final int PREFETCHER = 4;
        public static final int OBTAIN_VIEW = 7;
        public static final int RENDER_INFO = 3;
        private Frame() {}
    }

    public static final class Ime {
        public static final int IME_SHOW = 1;
        public static final int IME_HIDE = 2;
        public static final int IME_INIT = 3;
        private Ime() {}
    }

    public static final class Consistency {
        public static final int NORMAL_MODE = 0;
        public static final int APP_LAUNCH_RESPONSE = 1;
        private Consistency() {}
    }

    public static final class Animation {
        public static final int END = 0;
        public static final int START = 1;
        private Animation() {}
    }

    private static volatile long sLastHotEventNs;
    private static final long FRAME_GATE_WINDOW_NS = 500_000_000L;

    private static void markHot() {
        sLastHotEventNs = System.nanoTime();
    }

    public static void onScrollEvent(int action) {
        markHot();
        try { ActivityManager.getService().onScrollEvent(action); }
        catch (Exception e) { logException("onScrollEvent", e); }
    }

    public static void onLaunch(int type) {
        markHot();
        try { ActivityManager.getService().onLaunch(type); }
        catch (Exception e) { logException("onLaunch", e); }
    }

    public static void onFrameStage(int stage, long frameId) {
        if (System.nanoTime() - sLastHotEventNs > FRAME_GATE_WINDOW_NS) return;
        try { ActivityManager.getService().onFrameStage(stage, frameId); }
        catch (Exception e) { logException("onFrameStage", e); }
    }

    public static void onRefreshRateEvent(int event) {
        markHot();
        try { ActivityManager.getService().onRefreshRateEvent(event); }
        catch (Exception e) { logException("onRefreshRateEvent", e); }
    }

    public static void onImeTransition(int action) {
        markHot();
        try { ActivityManager.getService().onImeTransition(action); }
        catch (Exception e) { logException("onImeTransition", e); }
    }

    public static void onConsistency(int mode) {
        if (mode == Consistency.APP_LAUNCH_RESPONSE) markHot();
        try { ActivityManager.getService().onConsistency(mode); }
        catch (Exception e) { logException("onConsistency", e); }
    }

    public static void onAnimation(int action) {
        markHot();
        try { ActivityManager.getService().onAnimation(action); }
        catch (Exception e) { logException("onAnimation", e); }
    }

    private static void logException(String method, Exception e) {
        if (DEBUG) {
            Log.w(TAG, method + " failed", e);
        }
    }
}
