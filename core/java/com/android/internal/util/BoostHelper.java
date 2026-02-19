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
    public static void boostGcThread(int pid, boolean boost) {
        try {
            ActivityManager.getService().boostGcThread(pid, boost);
        } catch (Exception e) {
            logException("boostGcThread", e);
        }
    }
    private static void logException(String method, Exception e) {
        if (DEBUG) {
            Log.w(TAG, method + " failed", e);
        }
    }
}
