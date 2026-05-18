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
package com.android.server.am;

import static android.os.Process.THREAD_PRIORITY_TOP_APP_BOOST;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.util.SparseArray;

import com.android.server.AxExtServiceFactory;
import com.android.server.NtServiceInjector;
import com.android.server.ServiceThread;
import com.android.server.uifirst.AxUiFirstManager;
import com.android.server.am.psc.ProcessRecordInternal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AxBackgroundManager {

    public enum AppPolicy {
        NORMAL(0), AGGRESSIVE(1);
        public final int value;
        AppPolicy(int v) { value = v; }
        public static AppPolicy fromValue(int v) {
            for (AppPolicy p : values()) if (p.value == v) return p;
            return NORMAL;
        }
    }

    private static final String TAG = "AxBackgroundManager";

    private static final int FREEZE_PACKAGE_LEVEL = 3;
    private static final int UNFREEZE_PACKAGE_LEVEL = 4;
    private static final int DEFAULT_PROC_WEIGHT = -1;
    private static final int LOW_PROC_WEIGHT = 0;

    private volatile Set<String> mRestrictBgPackages = new HashSet<>();
    private volatile Set<String> mAutoStartAllowedPackages = new HashSet<>();
    private volatile Set<String> mKeepalivePackages = new HashSet<>();
    private volatile Set<String> mFreezePackages = new HashSet<>();
    private volatile AppPolicy mAppPolicy = AppPolicy.NORMAL;
    private volatile int mFreezerLevel = 2;

    private final Handler mHandler;
    private final Object mAutoStartLock = new Object();
    private PackageLevelFreezer mPackageFreezerManager;
    private boolean mSystemReady = false;
    
    private volatile int sCpuLoadTier = 0;

    private AxFreezeManager getFreezeMgr() {
        return AxExtServiceFactory.getAxFreezeManager();
    }

    private final BroadcastReceiver mPackageRemovedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) return;

            final String packageName = intent.getData() != null
                    ? intent.getData().getSchemeSpecificPart() : null;
            if (packageName == null) {
                Slog.w(TAG, "Received package removed intent with null package name");
                return;
            }

            final boolean isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
            if (isReplacing) {
                return;
            }

            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
            handlePackageUninstalled(packageName, userId);
        }
    };

    private void handlePackageUninstalled(String packageName, int userId) {
        if (usePackageLevelFreezer()) {
            mPackageFreezerManager.removeAppPids(packageName);
            mPackageFreezerManager.removePendingProcessesByPackage(packageName);
        }

        mRestrictBgPackages.remove(packageName);
        synchronized (mAutoStartLock) {
            mAutoStartAllowedPackages.remove(packageName);
        }
        mKeepalivePackages.remove(packageName);
        mFreezePackages.remove(packageName);
        getFreezeMgr().setFreezePackages(mFreezePackages);

        cleanupUninstalledAppResources(packageName);
    }

    private void cleanupUninstalledAppResources(String packageName) {
        try {
            for (String key : new String[]{
                    "axion_perf_package_freezer",
                    "axion_perf_keepalive",
                    "axion_perf_restrict_bg_auto_start",
                    "axion_perf_aggressive_policy",
            }) {
                String val = Settings.Secure.getString(
                        NtServiceInjector.getCtx().getContentResolver(), key);
                if (val == null || val.isEmpty()) continue;
                String[] pkgs = val.split(",");
                StringBuilder sb = new StringBuilder();
                for (String p : pkgs) {
                    String t = p.trim();
                    if (!t.isEmpty() && !t.equals(packageName)) {
                        if (sb.length() > 0) sb.append(',');
                        sb.append(t);
                    }
                }
                Settings.Secure.putString(NtServiceInjector.getCtx().getContentResolver(),
                        key, sb.toString());
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to cleanup settings for " + packageName, e);
        }
    }

    public AxBackgroundManager() {
        ServiceThread freezerThread = createAndStartFreezeThread();
        mPackageFreezerManager = new PackageLevelFreezer(
                new Handler(freezerThread.getLooper()));

        mHandler = new Handler(freezerThread.getLooper(), msg -> {
            switch (msg.what) {
                case FREEZE_PACKAGE_LEVEL: {
                    final List<ProcessRecord> pList = mPackageFreezerManager.getPendingList();
                    for (int i = 0; i < pList.size(); i++) {
                        ProcessRecord pr = pList.get(i);
                        if (pr == null || pr.getPid() <= 0) {
                            mPackageFreezerManager.removePendingProcess(pr);
                            continue;
                        }
                        int rc = getFreezeMgr().freezeProcess(pr);
                        switch (rc) {
                            case 0: // FREEZE_SUCCESS
                                mPackageFreezerManager.removePendingProcess(pr);
                                mPackageFreezerManager.appendAppPids(pr.info.packageName, pr);
                                break;
                            case -2: // BINDER_FREEZE_FAILED
                            case -4: // FOREGROUND_SERVICE_ACTIVE
                                if (mPackageFreezerManager.isFreezeRetryLimitReached(pr)) {
                                    Slog.w(TAG, "Give up freezing " + pr.processName
                                            + " after " + PackageLevelFreezer.MAX_FREEZE_RETRIES
                                            + " retries.");
                                    mPackageFreezerManager.removePendingProcess(pr);
                                } else {
                                    mPackageFreezerManager.incrementFreezeAttempt(pr);
                                }
                                break;
                            default:
                                mPackageFreezerManager.removePendingProcess(pr);
                                break;
                        }
                    }

                    String pkgName = (String) msg.obj;
                    final SparseArray<ProcessRecord> pids =
                            mPackageFreezerManager.findRelatedPids(pkgName);

                    if (pids == null || pids.size() == 0) break;

                    List<Integer> toRemove = new ArrayList<>();

                    for (int i = 0; i < pids.size(); i++) {
                        int pid = pids.keyAt(i);
                        ProcessRecord pr = pids.valueAt(i);
                        if (pr == null || pr.getPid() <= 0) {
                            toRemove.add(pid);
                            continue;
                        }
                        int rc = getFreezeMgr().freezeProcess(pr);
                        switch (rc) {
                            case 0: // FREEZE_SUCCESS
                                break;
                            case -2: // BINDER_FREEZE_FAILED
                                Slog.w(TAG, "Binder freeze failed, add to pending list: "
                                        + pr.processName);
                                mPackageFreezerManager.appendPendingList(pr,
                                        "Binder Transaction Pending");
                                toRemove.add(pid);
                                break;
                            case -4: // FOREGROUND_SERVICE_ACTIVE
                                Slog.w(TAG, "Foreground service active, add to pending list: "
                                        + pr.processName);
                                mPackageFreezerManager.appendPendingList(pr,
                                        "Foreground Service Active");
                                toRemove.add(pid);
                                break;
                            default:
                                Slog.e(TAG, "Freeze failed for process: " + pr.processName);
                                toRemove.add(pid);
                                break;
                        }
                    }

                    for (int i = toRemove.size() - 1; i >= 0; i--) {
                        pids.remove(toRemove.get(i));
                    }

                    if (pids.size() > 0) {
                        mPackageFreezerManager.addAppPids(pkgName, pids);
                    }
                } break;

                case UNFREEZE_PACKAGE_LEVEL: {
                    String pkgName = (String) msg.obj;
                    final SparseArray<ProcessRecord> pids =
                            mPackageFreezerManager.getAppPids(pkgName);

                    if (pids == null) {
                        mPackageFreezerManager.removeAppPids(pkgName);
                        break;
                    }

                    for (int i = 0; i < pids.size(); i++) {
                        ProcessRecord pr = pids.valueAt(i);
                        getFreezeMgr().unFreezeProcess(pr);
                    }

                    mPackageFreezerManager.removeAppPids(pkgName);
                } break;

                default:
                    return true;
            }
            return true;
        });

        mPackageFreezerManager.setUseDebug(false);
    }

    private static ServiceThread createAndStartFreezeThread() {
        final ServiceThread freezerManagerThread = new ServiceThread(
                "FreezerManagerThread", THREAD_PRIORITY_TOP_APP_BOOST, true);
        freezerManagerThread.start();
        return freezerManagerThread;
    }

    public void systemReady() {
        registerSettingsObserver();

        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addDataScheme("package");
        NtServiceInjector.getCtx().registerReceiverAsUser(mPackageRemovedReceiver,
                UserHandle.ALL, packageFilter, null, null);

        mSystemReady = true;
    }

    private void registerSettingsObserver() {
        ContentResolver cr = NtServiceInjector.getCtx().getContentResolver();
        ContentObserver observer = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                loadSettings();
            }
        };
        for (String key : new String[]{
                "axion_perf_package_freezer",
                "axion_perf_keepalive",
                "axion_perf_restrict_bg_auto_start",
                "axion_perf_aggressive_policy",
                "axion_perf_freezer_level",
        }) {
            cr.registerContentObserver(Settings.Secure.getUriFor(key), false, observer);
        }
        loadSettings();
    }

    private void loadSettings() {
        ContentResolver cr = NtServiceInjector.getCtx().getContentResolver();
        if (cr == null) return;

        mRestrictBgPackages = parsePackageList(
                Settings.Secure.getString(cr, "axion_perf_restrict_bg_auto_start"));
        synchronized (mAutoStartLock) {
            mAutoStartAllowedPackages.retainAll(mRestrictBgPackages);
        }

        Set<String> freezePkgs = parsePackageList(
                Settings.Secure.getString(cr, "axion_perf_package_freezer"));
        mFreezePackages = freezePkgs;
        getFreezeMgr().setFreezePackages(freezePkgs);

        Set<String> keepalivePkgs = parsePackageList(
                Settings.Secure.getString(cr, "axion_perf_keepalive"));
        final boolean wasKeepaliveEnabled = useAppKeepaliveManager();
        final boolean isKeepaliveEnabled = !keepalivePkgs.isEmpty();
        if (!wasKeepaliveEnabled && isKeepaliveEnabled) {
            ProcessList.updateLmkLazyKillFlag(true);
        }
        mKeepalivePackages = keepalivePkgs;
        if (wasKeepaliveEnabled && !isKeepaliveEnabled) {
            ProcessList.updateLmkLazyKillFlag(false);
        }

        mAppPolicy = readAppPolicy(cr);
        getFreezeMgr().setAppPolicy(
                AxFreezeManager.AppPolicy.fromValue(mAppPolicy.value));

        mFreezerLevel = Settings.Secure.getInt(cr, "axion_perf_freezer_level", 2);
        getFreezeMgr().setFreezerLevel(mFreezerLevel);
    }

    private Set<String> parsePackageList(String raw) {
        Set<String> packages = new HashSet<>();
        if (raw != null && !raw.isEmpty()) {
            for (String s : raw.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) packages.add(t);
            }
        }
        return packages;
    }

    private AppPolicy readAppPolicy(ContentResolver cr) {
        try {
            String raw = Settings.Secure.getString(cr, "axion_perf_aggressive_policy");
            return AppPolicy.fromValue(raw != null ? Integer.parseInt(raw.trim()) : 0);
        } catch (NumberFormatException e) {
            Slog.w(TAG, "Invalid app policy setting, falling back to NORMAL", e);
            return AppPolicy.NORMAL;
        }
    }

    public boolean usePackageLevelFreezer() {
        return mFreezerLevel >= 2;
    }

    public boolean useFreezerManager() {
        return true;
    }

    public boolean useAppKeepaliveManager() {
        return !mKeepalivePackages.isEmpty();
    }

    public boolean useUiBoost() {
        return sCpuLoadTier > 0;
    }

    public AxUiFirstManager.UiRtLevel getUiRtLevel() {
        return AxUiFirstManager.UiRtLevel.fromValue(sCpuLoadTier);
    }

    public void onCpuLoadTierChanged(int tier) {
        sCpuLoadTier = tier;
        getFreezeMgr().setIsCpuLoadHigh(useUiBoost());
    }

    public boolean isProcessKeepAlive(ProcessRecord app) {
        return mKeepalivePackages.contains(app.info.packageName);
    }

    public boolean isProcessKeepAlive(ProcessRecordInternal app) {
        String pkg = app.getPackageName();
        return pkg != null && mKeepalivePackages.contains(pkg);
    }

    public ArrayList<Integer> getProcsKeepaliveWeight(ArrayList<ProcessRecordInternal> procs) {
        ArrayList<Integer> weights = new ArrayList<>(procs.size());
        for (int i = 0; i < procs.size(); i++) {
            weights.add(getProcKeepaliveWeight(procs.get(i)));
        }
        return weights;
    }

    public int getProcKeepaliveWeight(ProcessRecordInternal app) {
        return isProcessKeepAlive(app) ? LOW_PROC_WEIGHT : DEFAULT_PROC_WEIGHT;
    }

    public boolean shouldPreventProcessStart(String processName, ApplicationInfo info) {
        if (mRestrictBgPackages == null || mRestrictBgPackages.isEmpty()) return false;
        if (info == null) return false;
        if (info.isSystemApp() || info.isUpdatedSystemApp()) return false;
        if (!mRestrictBgPackages.contains(info.packageName)) return false;
        synchronized (mAutoStartLock) {
            return !mAutoStartAllowedPackages.contains(info.packageName);
        }
    }

    public boolean isPackageExemptFromAutoStart(String packageName) {
        return mRestrictBgPackages == null || !mRestrictBgPackages.contains(packageName);
    }

    public boolean isPackageExemptFromFreeze(String packageName) {
        return getFreezeMgr().isPackageExemptFromFreeze(packageName);
    }

    public boolean checkNeedFreezeProcessLocked(ProcessRecordInternal app) {
        return getFreezeMgr().checkNeedFreezeProcessLocked(app);
    }

    public void startFreeze(String packageName, int freezeReason) {
        getFreezeMgr().startFreeze(packageName, freezeReason);
    }

    public void startUnfreeze(String packageName, int unfreezeReason) {
        getFreezeMgr().startUnfreeze(packageName, unfreezeReason);
    }

    public void startUnfreezeService(ProcessRecordInternal app, int unfreezeReason) {
        getFreezeMgr().startUnfreezeService(app, unfreezeReason);
    }

    public void addPidLocked(ProcessRecordInternal app) {
        getFreezeMgr().addPidLocked(app);
    }

    public void removePidLocked(int pid, ProcessRecordInternal app) {
        getFreezeMgr().removePidLocked(pid, app);
    }

    private void freezePackageLevel(String packageName) {
        mPackageFreezerManager.freezePackageLevel(packageName);
    }

    private void unfreezePackageLevel(String packageName) {
        mPackageFreezerManager.unfreezePackageLevel(packageName);
    }

    private void unfreezeAllFrozenPackages() {
        mPackageFreezerManager.unfreezeAllFrozenPackages();
    }

    private void setPackageAutoStartAllowed(String packageName) {
        if (packageName != null && mRestrictBgPackages.contains(packageName)) {
            synchronized (mAutoStartLock) {
                mAutoStartAllowedPackages.add(packageName);
            }
        }
    }

    private void setPackageAutoStartBlocked(String packageName) {
        if (packageName != null) {
            synchronized (mAutoStartLock) {
                mAutoStartAllowedPackages.remove(packageName);
            }
        }
    }

    public void handleActivityStart(ApplicationInfo info) {
        if (!mSystemReady) return;
        if (info == null) return;

        activateAppForForeground(info.packageName);
    }

    public void handleSchedGroupTransition(ProcessRecord app) {
        if (!mSystemReady) return;

        if (!app.processName.equals(app.info.packageName)) {
            return;
        }

        final int curSchedGroup = app.getCurrentSchedulingGroup();
        final String packageName = app.info.packageName;

        switch (curSchedGroup) {
            case ProcessList.SCHED_GROUP_TOP_APP:
            case ProcessList.SCHED_GROUP_TOP_APP_BOUND:
                activateAppForForeground(packageName);
                break;
            default:
                deactivateAppForBackground(packageName);
                break;
        }
    }

    private void activateAppForForeground(String packageName) {
        if (usePackageLevelFreezer()) {
            unfreezePackageLevel(packageName);
        }
        if (!mRestrictBgPackages.isEmpty()) {
            setPackageAutoStartAllowed(packageName);
        }
    }

    private void deactivateAppForBackground(String packageName) {
        if (usePackageLevelFreezer()) {
            if (isPackageExemptFromFreeze(packageName)) {
                Slog.d(TAG, "(Disallow freeze) exempt package: " + packageName);
            } else {
                freezePackageLevel(packageName);
            }
        }
        if (!mRestrictBgPackages.isEmpty()) {
            if (isPackageExemptFromAutoStart(packageName)) {
                Slog.d(TAG, "(Allow auto-start) exempt package: " + packageName);
            } else {
                setPackageAutoStartBlocked(packageName);
            }
        }
    }
}
