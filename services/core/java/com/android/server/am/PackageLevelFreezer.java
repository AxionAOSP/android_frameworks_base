package com.android.server.am;

import android.os.Handler;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;

import com.android.server.AxExtServiceFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class PackageLevelFreezer {

    private static final String TAG = "AxBgMgr";

    private static final int FREEZE_PACKAGE_LEVEL = 3;
    private static final int UNFREEZE_PACKAGE_LEVEL = 4;

    private class PendingInfo {
        String mReason;
        int mRetryCount;

        PendingInfo(String reason) {
            mReason = reason;
            mRetryCount = 1;
        }
    }

    public static final int MAX_FREEZE_RETRIES = 2;

    private final Map<String, SparseArray<ProcessRecord>> mAppPids = new HashMap<>();
    private final Map<ProcessRecord, PendingInfo> mPendingFreezeMap = new ArrayMap<>();
    private final Object mPendingFreezeLock = new Object();
    private final Object mAppPidsLock = new Object();
    private final Handler mHandler;
    private boolean mUseDebug = false;

    public PackageLevelFreezer(Handler handler) {
        mHandler = handler;
    }

    public void setUseDebug(boolean debug) {
        mUseDebug = debug;
    }

    public void appendPendingList(ProcessRecord app, String reason) {
        if (app == null) return;
        synchronized (mPendingFreezeLock) {
            PendingInfo info = mPendingFreezeMap.get(app);
            if (info == null) {
                mPendingFreezeMap.put(app, new PendingInfo(reason));
            } else {
                if (mUseDebug) {
                    Slog.d(TAG, "Process " + app.processName + " already pending. Old reason: "
                            + info.mReason + ", New reason: " + reason);
                }
                info.mReason = reason;
            }
        }
    }

    public List<ProcessRecord> getPendingList() {
        synchronized (mPendingFreezeLock) {
            return new ArrayList<>(mPendingFreezeMap.keySet());
        }
    }

    public String getPendingFreezeReason(ProcessRecord app) {
        synchronized (mPendingFreezeLock) {
            PendingInfo info = mPendingFreezeMap.get(app);
            return (info != null) ? info.mReason : "unknown";
        }
    }

    public void incrementFreezeAttempt(ProcessRecord app) {
        synchronized (mPendingFreezeLock) {
            PendingInfo info = mPendingFreezeMap.get(app);
            if (info != null) {
                info.mRetryCount++;
            }
        }
    }

    public int getFreezeRetryCount(ProcessRecord app) {
        synchronized (mPendingFreezeLock) {
            PendingInfo info = mPendingFreezeMap.get(app);
            return (info != null) ? info.mRetryCount : 0;
        }
    }

    public boolean isFreezeRetryLimitReached(ProcessRecord app) {
        synchronized (mPendingFreezeLock) {
            PendingInfo info = mPendingFreezeMap.get(app);
            if (info == null) return false;
            return info.mRetryCount > MAX_FREEZE_RETRIES;
        }
    }

    public boolean removePendingProcess(ProcessRecord app) {
        if (app == null) return false;
        synchronized (mPendingFreezeLock) {
            return mPendingFreezeMap.remove(app) != null;
        }
    }

    public void removePendingProcessesByPackage(String packageName) {
        if (packageName == null) return;
        synchronized (mPendingFreezeLock) {
            Iterator<Map.Entry<ProcessRecord, PendingInfo>> iterator =
                    mPendingFreezeMap.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<ProcessRecord, PendingInfo> entry = iterator.next();
                ProcessRecord app = entry.getKey();
                if (app != null && app.info != null
                        && packageName.equals(app.info.packageName)) {
                    if (mUseDebug) {
                        PendingInfo info = entry.getValue();
                        Slog.d(TAG, "Removing process " + app.processName
                                + " (reason: " + info.mReason
                                + ", retries: " + info.mRetryCount + ")");
                    }
                    iterator.remove();
                }
            }
        }
    }

    public SparseArray<ProcessRecord> getAppPids(String packageName) {
        if (packageName == null) return null;
        synchronized (mAppPidsLock) {
            return mAppPids.get(packageName);
        }
    }

    public void appendAppPids(String packageName, ProcessRecord app) {
        if (packageName == null || app == null) return;
        synchronized (mAppPidsLock) {
            SparseArray<ProcessRecord> pids = mAppPids.get(packageName);
            if (pids == null) {
                pids = new SparseArray<>();
                mAppPids.put(packageName, pids);
            }
            pids.put(app.getPid(), app);
        }
    }

    public void addAppPids(String packageName, SparseArray<ProcessRecord> pids) {
        if (packageName == null || pids == null) return;
        synchronized (mAppPidsLock) {
            mAppPids.put(packageName, pids);
        }
    }

    public void removeAppPids(String packageName) {
        if (packageName == null) return;
        synchronized (mAppPidsLock) {
            mAppPids.remove(packageName);
        }
    }

    public boolean containsApp(String packageName) {
        if (packageName == null) return false;
        synchronized (mAppPidsLock) {
            return mAppPids.containsKey(packageName);
        }
    }

    public SparseArray<ProcessRecord> findRelatedPids(String packageName) {
        return AxExtServiceFactory.getAxFreezeManager().findPidsByPackageName(packageName);
    }

    public void freezePackageLevel(String packageName) {
        if (containsApp(packageName)) {
            if (mUseDebug) {
                Slog.d(TAG, "Skipping freeze request for " + packageName
                        + ": Already marked as frozen.");
            }
            return;
        }
        mHandler.sendMessage(mHandler.obtainMessage(
            FREEZE_PACKAGE_LEVEL, 0, 0, packageName));
        if (mUseDebug) {
            Slog.i(TAG, "Freeze request queued for package: " + packageName);
        }
    }

    public void unfreezePackageLevel(String packageName) {
        if (packageName == null) return;
        removePendingProcessesByPackage(packageName);
        if (!containsApp(packageName)) {
            if (mUseDebug) {
                Slog.d(TAG, "Skipping unfreeze request for " + packageName
                        + ": Not currently marked as frozen.");
            }
            return;
        }
        mHandler.sendMessage(mHandler.obtainMessage(
            UNFREEZE_PACKAGE_LEVEL, 0, 0, packageName));
        if (mUseDebug) {
            Slog.i(TAG, "Unfreeze request queued for package: " + packageName);
        }
    }

    public void unfreezeAllFrozenPackages() {
        List<String> packagesToUnfreeze;
        synchronized (mAppPidsLock) {
            if (mAppPids.isEmpty()) return;
            packagesToUnfreeze = new ArrayList<>(mAppPids.keySet());
        }
        for (String packageName : packagesToUnfreeze) {
            unfreezePackageLevel(packageName);
        }
    }

    public void dump(java.io.PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.println("PACKAGE LEVEL FREEZER:");
        String innerPrefix = prefix + "  ";
        String doubleInnerPrefix = prefix + "    ";
        synchronized (mAppPidsLock) {
            if (mAppPids.isEmpty()) {
                pw.print(innerPrefix);
                pw.println("No packages currently frozen");
            } else {
                pw.print(innerPrefix);
                pw.println(mAppPids.size() + " package(s) frozen:");
                for (Map.Entry<String, SparseArray<ProcessRecord>> entry : mAppPids.entrySet()) {
                    String pkg = entry.getKey();
                    SparseArray<ProcessRecord> processes = entry.getValue();
                    pw.print(innerPrefix);
                    pw.println("Package: " + pkg + " (" + processes.size() + " processes)");
                    for (int i = 0; i < processes.size(); i++) {
                        ProcessRecord app = processes.valueAt(i);
                        pw.print(doubleInnerPrefix);
                        pw.println("- PID: " + app.getPid() + ", Process: " + app.processName);
                    }
                }
            }
        }
        synchronized (mPendingFreezeLock) {
            if (mPendingFreezeMap.isEmpty()) {
                pw.print(innerPrefix);
                pw.println("No pending processes");
            } else {
                pw.print(innerPrefix);
                pw.println("Pending processes (" + mPendingFreezeMap.size() + "):");
                for (Map.Entry<ProcessRecord, PendingInfo> entry : mPendingFreezeMap.entrySet()) {
                    ProcessRecord app = entry.getKey();
                    PendingInfo info = entry.getValue();
                    pw.print(doubleInnerPrefix);
                    pw.print("- PID: "); pw.print(app.getPid());
                    pw.print(", Process: "); pw.print(app.processName);
                    pw.print(", Pkg: "); pw.print(app.info.packageName);
                    pw.print(", Reason: "); pw.print(info.mReason);
                    pw.print(", Retries: "); pw.println(info.mRetryCount);
                }
            }
        }
        pw.println();
    }
}
