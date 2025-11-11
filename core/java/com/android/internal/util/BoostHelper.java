package com.android.internal.util;

import android.app.ActivityManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

public class BoostHelper {

    private static final String TAG = "BoostHelper";
    private static final boolean DEBUG = false;

    public static void enablePerformanceMode(boolean enabled) {
        try {
            ActivityManager.getService().enablePerformanceMode(enabled);
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

    public static void adjustCpusetCpus(String group, String cpus, long duration) {
        try {
            ActivityManager.getService().adjustCpusetCpus(group, cpus, duration);
        } catch (Exception e) {
            logException("adjustCpusetCpus", e);
        }
    }

    public static void animationBoost(int pid, long duration) {
        try {
            ActivityManager.getService().animationBoost(pid, duration);
        } catch (Exception e) {
            logException("animationBoost", e);
        }
    }

    public static void setThreadAffinity(int tid, int affinity) {
        try {
            ActivityManager.getService().setThreadAffinity(tid, affinity);
        } catch (Exception e) {
            logException("setThreadAffinity", e);
        }
    }

    public static void inputBoost() {
        try {
            ActivityManager.getService().inputBoost();
        } catch (Exception e) {
            logException("inputBoost", e);
        }
    }

    private static void logException(String method, Exception e) {
        if (DEBUG) {
            Log.w(TAG, method + " failed", e);
        }
    }
    
    public static void boostThread(int tid) {
        try {
            ActivityManager.getService().boostThread(tid);
        } catch (Exception e) {
            logException("boostThread", e);
        }
    }
}
