package com.android.internal.util;

import android.app.ActivityManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

public class BoostHelper {

    private static final String TAG = "BoostHelper";
    private static final boolean DEBUG = false;

    public static void setPerformanceMode(boolean enabled, String reason) {
        try {
            ActivityManager.getService().setPerformanceMode(enabled, reason);
        } catch (Exception e) {
            logException("setPerformanceMode", e);
        }
    }

    public static void boostHint(String reason, long duration) {
        try {
            ActivityManager.getService().boostHint(reason, duration);
        } catch (Exception e) {
            logException("setPerformanceMode", e);
        }
    }

    public static void executeAdjustCpusetCpus(String path, String cpus) {
        try {
            ActivityManager.getService().executeAdjustCpusetCpus(path, cpus);
        } catch (Exception e) {
            logException("executeAdjustCpusetCpus", e);
        }
    }

    public static void adjustCpusetCpus(String cgroup, long durationMillis) {
        try {
            ActivityManager.getService().adjustCpusetCpus(cgroup, durationMillis);
        } catch (Exception e) {
            logException("adjustCpusetCpus", e);
        }
    }

    public static void animationBoost(int pid, boolean enabled) {
        try {
            ActivityManager.getService().animationBoost(pid, enabled);
        } catch (Exception e) {
            logException("animationBoost", e);
        }
    }

    public static void setThreadAffinity(int pid, int affinity) {
        try {
            ActivityManager.getService().setThreadAffinity(pid, affinity);
        } catch (Exception e) {
            logException("setThreadAffinity", e);
        }
    }

    public static void inputBoost(long durationMillis) {
        try {
            ActivityManager.getService().inputBoost(durationMillis);
        } catch (Exception e) {
            logException("inputBoost", e);
        }
    }

    private static void logException(String method, Exception e) {
        if (DEBUG) {
            Log.w(TAG, method + " failed", e);
        }
    }
}
