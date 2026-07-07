/*
 * Copyright 2025-2026 AxionOS
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

import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_RESTRICTION_CHANGE;

import static com.android.server.am.CachedAppOptimizer.UNFREEZE_REASON_ACTIVITY;
import static com.android.server.am.CachedAppOptimizer.UNFREEZE_REASON_RESTRICTION_CHANGE;
import static com.android.server.am.ProcessList.CACHED_APP_MIN_ADJ;
import static com.android.server.am.ProcessList.SCHED_GROUP_TOP_APP;
import static com.android.server.am.ProcessList.SCHED_GROUP_TOP_APP_BOUND;
import static com.android.server.am.ProcessList.SERVICE_ADJ;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.IBinder;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.ArrayList;

public final class AppBackgroundManager {
    private static final String TAG = "AppBackgroundManager";

    public static final int FIRST_LAUNCH_FREEZE = 0;
    public static final int WARM_LAUNCH_FREEZE = 1;
    public static final int COLD_LAUNCH_FREEZE = 2;

    public static final int COMPLETE_LAUNCH_UNFREEZE = 0;
    public static final int INTERRUPT_LAUNCH_UNFREEZE = 1;
    public static final int DEPEND_LAUNCH_UNFREEZE = 5;

    private static final String PACKAGE_FREEZER_KEY = "axion_perf_package_freezer";
    private static final String RESTRICT_BACKGROUND_KEY = "axion_perf_restrict_bg_auto_start";
    private static final String AGGRESSIVE_POLICY_KEY = "axion_perf_aggressive_policy";
    private static final String FREEZER_LEVEL_KEY = "axion_perf_freezer_level";

    private static final int FREEZER_LEVEL_DISABLED = 0;
    private static final int FREEZER_LEVEL_PACKAGE = 2;

    private static final String PACKAGE_ANDROID = "android";
    private static final String PACKAGE_LAUNCHER = "com.android.launcher3";
    private static final String PACKAGE_SYSTEMUI = "com.android.systemui";

    private static AppBackgroundManager sInstance;

    private final ActivityManagerService mService;
    private final ContentResolver mResolver;
    private final Object mLock = new Object();
    private final ContentObserver mSettingsObserver;

    @GuardedBy("mLock")
    private final ArraySet<String> mFreezerPackages = new ArraySet<>();
    @GuardedBy("mLock")
    private final ArraySet<String> mRestrictedPackages = new ArraySet<>();
    @GuardedBy("mLock")
    private final ArraySet<String> mForegroundPackages = new ArraySet<>();
    @GuardedBy("mLock")
    private final ArraySet<String> mFrozenPackages = new ArraySet<>();
    @GuardedBy("mLock")
    private final ArraySet<String> mLaunchFrozenPackages = new ArraySet<>();
    @GuardedBy("mLock")
    private boolean mAggressivePolicy;
    @GuardedBy("mLock")
    private int mFreezerLevel = FREEZER_LEVEL_PACKAGE;

    private final BroadcastReceiver mPackageRemovedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) {
                return;
            }
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                return;
            }
            final Uri data = intent.getData();
            if (data == null) {
                return;
            }
            final String packageName = data.getSchemeSpecificPart();
            if (TextUtils.isEmpty(packageName)) {
                return;
            }
            int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
            if (userId == UserHandle.USER_NULL) {
                userId = mService.mUserController.getCurrentUserId();
            }
            removePackage(packageName, userId);
        }
    };

    AppBackgroundManager(ActivityManagerService service) {
        mService = service;
        mResolver = service.mContext.getContentResolver();
        mSettingsObserver = new ContentObserver(service.mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                reloadSettings();
            }
        };
        synchronized (AppBackgroundManager.class) {
            sInstance = this;
        }
    }

    public static AppBackgroundManager getInstance() {
        synchronized (AppBackgroundManager.class) {
            return sInstance;
        }
    }

    void systemReady() {
        registerSettingObserver(PACKAGE_FREEZER_KEY);
        registerSettingObserver(RESTRICT_BACKGROUND_KEY);
        registerSettingObserver(AGGRESSIVE_POLICY_KEY);
        registerSettingObserver(FREEZER_LEVEL_KEY);

        final IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        mService.mContext.registerReceiverAsUser(mPackageRemovedReceiver, UserHandle.ALL, filter,
                null, mService.mHandler);
        reloadSettings();
    }

    public boolean shouldPreventProcessStart(String processName, ApplicationInfo info,
            int intentFlags, HostingRecord hostingRecord, boolean allowWhileBooting,
            boolean isolated, boolean isSdkSandbox) {
        if (isolated || isSdkSandbox || allowWhileBooting) {
            return false;
        }
        if ((intentFlags & Intent.FLAG_FROM_BACKGROUND) == 0 && hostingRecord == null) {
            return false;
        }
        if (info == null || !mService.mProcessesReady || isExemptApplication(info)
                || isForegroundStart(hostingRecord)) {
            return false;
        }
        synchronized (mLock) {
            final String packageName = info.packageName;
            if (!mRestrictedPackages.contains(packageName)
                    || mForegroundPackages.contains(packageName)) {
                return false;
            }
        }
        Slog.i(TAG, "Blocked background start for " + processName + "/" + info.packageName);
        return true;
    }

    public void handleActivityStart(ApplicationInfo info) {
        if (info == null || isExemptApplication(info)) {
            return;
        }
        setPackageForeground(info.packageName, true);
        startUnfreeze(info.packageName, INTERRUPT_LAUNCH_UNFREEZE);
    }

    public void startFreeze(String packageName, int freezeReason) {
        if (TextUtils.isEmpty(packageName) || isBlockedPackage(packageName)) {
            return;
        }
        mService.mHandler.post(() -> {
            synchronized (mService) {
                synchronized (mService.mProcLock) {
                    startFreezeLSP(packageName);
                }
            }
        });
    }

    public void startUnfreeze(String packageName, int unfreezeReason) {
        if (TextUtils.isEmpty(packageName)) {
            return;
        }
        mService.mHandler.post(() -> {
            synchronized (mService) {
                synchronized (mService.mProcLock) {
                    startUnfreezeLSP(packageName, unfreezeReason);
                }
            }
        });
    }

    @GuardedBy({"mService", "mService.mProcLock"})
    public void startUnfreezeService(ProcessRecord app, int unfreezeReason) {
        if (app == null) {
            return;
        }
        final ArraySet<String> packages = getDependentFrozenPackagesLSP(app);
        for (int i = 0; i < packages.size(); i++) {
            unfreezePackageLSP(packages.valueAt(i), UNFREEZE_REASON_ACTIVITY);
        }
    }

    @GuardedBy({"mService", "mService.mProcLock"})
    public boolean checkNeedFreezeProcessLocked(ProcessRecord app) {
        return !getDependentFrozenPackagesLSP(app).isEmpty();
    }

    void onProcessSchedulingGroupChanged(ProcessRecord app, int oldSchedGroup, int curSchedGroup) {
        if (app == null || app.info == null || isExemptApplication(app.info)
                || !TextUtils.equals(app.processName, app.info.packageName)) {
            return;
        }
        final String packageName = app.info.packageName;
        if (isTopAppGroup(curSchedGroup)) {
            setPackageForeground(packageName, true);
            unfreezePackageLSP(packageName, UNFREEZE_REASON_ACTIVITY);
            return;
        }
        if (isTopAppGroup(oldSchedGroup) && !hasTopProcessLSP(packageName)) {
            setPackageForeground(packageName, false);
            freezePackageIfNeededLSP(packageName);
        }
    }

    void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("APP BACKGROUND MANAGER STATE");
            pw.println("  freezerLevel=" + mFreezerLevel);
            pw.println("  aggressivePolicy=" + mAggressivePolicy);
            pw.println("  freezerPackages=" + mFreezerPackages);
            pw.println("  restrictedPackages=" + mRestrictedPackages);
            pw.println("  foregroundPackages=" + mForegroundPackages);
            pw.println("  frozenPackages=" + mFrozenPackages);
            pw.println("  launchFrozenPackages=" + mLaunchFrozenPackages);
        }
    }

    private void registerSettingObserver(String key) {
        mResolver.registerContentObserver(Settings.Secure.getUriFor(key), false,
                mSettingsObserver, UserHandle.USER_ALL);
    }

    private void reloadSettings() {
        final int userId = mService.mUserController.getCurrentUserId();
        final ArraySet<String> freezerPackages = readPackageSet(PACKAGE_FREEZER_KEY, userId);
        final ArraySet<String> restrictedPackages = readPackageSet(RESTRICT_BACKGROUND_KEY, userId);
        final boolean aggressivePolicy = Settings.Secure.getIntForUser(mResolver,
                AGGRESSIVE_POLICY_KEY, 0, userId) != 0;
        final int freezerLevel = Settings.Secure.getIntForUser(mResolver, FREEZER_LEVEL_KEY,
                FREEZER_LEVEL_PACKAGE, userId);
        final ArraySet<String> packagesToUnfreeze = new ArraySet<>();

        synchronized (mLock) {
            for (int i = mFrozenPackages.size() - 1; i >= 0; i--) {
                final String packageName = mFrozenPackages.valueAt(i);
                if (freezerLevel == FREEZER_LEVEL_DISABLED
                        || !freezerPackages.contains(packageName)) {
                    packagesToUnfreeze.add(packageName);
                    mFrozenPackages.removeAt(i);
                    mLaunchFrozenPackages.remove(packageName);
                }
            }
            replaceSetLocked(mFreezerPackages, freezerPackages);
            replaceSetLocked(mRestrictedPackages, restrictedPackages);
            mAggressivePolicy = aggressivePolicy;
            mFreezerLevel = freezerLevel;
        }

        for (int i = 0; i < packagesToUnfreeze.size(); i++) {
            unfreezePackage(packagesToUnfreeze.valueAt(i), UNFREEZE_REASON_RESTRICTION_CHANGE);
        }
        scheduleOomUpdate();
    }

    private ArraySet<String> readPackageSet(String key, int userId) {
        final ArraySet<String> packages = new ArraySet<>();
        final String setting = Settings.Secure.getStringForUser(mResolver, key, userId);
        if (TextUtils.isEmpty(setting)) {
            return packages;
        }
        final String[] values = setting.split(",");
        for (int i = 0; i < values.length; i++) {
            final String packageName = values[i].trim();
            if (TextUtils.isEmpty(packageName) || isBlockedPackage(packageName)) {
                continue;
            }
            packages.add(packageName);
        }
        return packages;
    }

    @GuardedBy("mLock")
    private static void replaceSetLocked(ArraySet<String> target, ArraySet<String> source) {
        target.clear();
        target.addAll(source);
    }

    private void removePackage(String packageName, int userId) {
        final boolean unfreezePackage;
        synchronized (mLock) {
            mFreezerPackages.remove(packageName);
            mRestrictedPackages.remove(packageName);
            mForegroundPackages.remove(packageName);
            mLaunchFrozenPackages.remove(packageName);
            unfreezePackage = mFrozenPackages.remove(packageName);
        }
        removePackageFromSetting(PACKAGE_FREEZER_KEY, packageName, userId);
        removePackageFromSetting(RESTRICT_BACKGROUND_KEY, packageName, userId);
        if (unfreezePackage) {
            unfreezePackage(packageName, UNFREEZE_REASON_RESTRICTION_CHANGE);
        }
        scheduleOomUpdate();
    }

    private void removePackageFromSetting(String key, String packageName, int userId) {
        final ArraySet<String> packages = readPackageSet(key, userId);
        if (!packages.remove(packageName)) {
            return;
        }
        Settings.Secure.putStringForUser(mResolver, key, flattenPackages(packages), userId);
    }

    private static String flattenPackages(ArraySet<String> packages) {
        if (packages.isEmpty()) {
            return "";
        }
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < packages.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(packages.valueAt(i));
        }
        return builder.toString();
    }

    @GuardedBy({"mService", "mService.mProcLock"})
    private void startFreezeLSP(String targetPackageName) {
        final ArraySet<String> packages = new ArraySet<>();
        final boolean aggressivePolicy;
        synchronized (mLock) {
            if (mFreezerLevel == FREEZER_LEVEL_DISABLED) {
                return;
            }
            packages.addAll(mFreezerPackages);
            aggressivePolicy = mAggressivePolicy;
        }

        for (int i = 0; i < packages.size(); i++) {
            final String packageName = packages.valueAt(i);
            if (isTargetPackage(targetPackageName, packageName) || isBlockedPackage(packageName)) {
                continue;
            }
            if (freezePackageLSP(packageName, aggressivePolicy) > 0) {
                synchronized (mLock) {
                    mFrozenPackages.add(packageName);
                    mLaunchFrozenPackages.add(packageName);
                }
            }
        }
    }

    @GuardedBy({"mService", "mService.mProcLock"})
    private void startUnfreezeLSP(String packageName, int reason) {
        if (reason == COMPLETE_LAUNCH_UNFREEZE) {
            final ArraySet<String> packages = new ArraySet<>();
            synchronized (mLock) {
                packages.addAll(mLaunchFrozenPackages);
                mLaunchFrozenPackages.clear();
            }
            for (int i = 0; i < packages.size(); i++) {
                unfreezePackageLSP(packages.valueAt(i), UNFREEZE_REASON_ACTIVITY);
            }
            return;
        }

        unfreezePackageLSP(packageName, UNFREEZE_REASON_ACTIVITY);
        final ArraySet<String> packages = getPackagesForProcessLSP(packageName);
        for (int i = 0; i < packages.size(); i++) {
            unfreezePackageLSP(packages.valueAt(i), UNFREEZE_REASON_ACTIVITY);
        }
    }

    private int freezePackageLSP(String packageName, boolean aggressivePolicy) {
        final ArrayList<ProcessRecord> processes = getPackageProcessesLSP(packageName);
        int frozenCount = 0;
        for (int i = 0; i < processes.size(); i++) {
            if (freezeProcessLSP(processes.get(i), aggressivePolicy)) {
                frozenCount++;
            }
        }
        return frozenCount;
    }

    private void freezePackageIfNeededLSP(String packageName) {
        final boolean aggressivePolicy;
        synchronized (mLock) {
            if (mFreezerLevel == FREEZER_LEVEL_DISABLED
                    || !mFreezerPackages.contains(packageName)) {
                return;
            }
            aggressivePolicy = mAggressivePolicy;
        }

        if (freezePackageLSP(packageName, aggressivePolicy) > 0) {
            synchronized (mLock) {
                mFrozenPackages.add(packageName);
            }
        }
    }

    private boolean freezeProcessLSP(ProcessRecord app, boolean aggressivePolicy) {
        if (!mService.getCachedAppOptimizer().useFreezer() || !isFreezableLSP(app,
                aggressivePolicy)) {
            return false;
        }
        mService.getCachedAppOptimizer().forceFreezeAppAsyncLSP(app);
        return true;
    }

    private boolean isFreezableLSP(ProcessRecord app, boolean aggressivePolicy) {
        if (app == null || app.info == null || app.getPid() <= 0 || !app.isProcessRunning()
                || app.isKilled() || app.isKilledByAm() || app.isPersistent()
                || app.hasActiveInstrumentation() || app.shouldNotFreeze()
                || app.hasVisibleActivities() || app.isInterestingToUserLocked()
                || isTopAppGroup(app.getCurrentSchedulingGroup())
                || isExemptApplication(app.info)) {
            return false;
        }
        if (app.getCurAdj() >= CACHED_APP_MIN_ADJ) {
            return true;
        }
        return aggressivePolicy && app.getCurAdj() >= SERVICE_ADJ;
    }

    private void unfreezePackage(String packageName, int reason) {
        synchronized (mService) {
            synchronized (mService.mProcLock) {
                unfreezePackageLSP(packageName, reason);
            }
        }
    }

    private void unfreezePackageLSP(String packageName, int reason) {
        final ArrayList<ProcessRecord> processes = getPackageProcessesLSP(packageName);
        for (int i = 0; i < processes.size(); i++) {
            unfreezeProcessLSP(processes.get(i), reason);
        }
        synchronized (mLock) {
            mFrozenPackages.remove(packageName);
            mLaunchFrozenPackages.remove(packageName);
        }
    }

    private void unfreezeProcessLSP(ProcessRecord app, int reason) {
        if (!mService.getCachedAppOptimizer().useFreezer()
                || (!app.isFrozen() && !app.isPendingFreeze())) {
            return;
        }
        mService.getCachedAppOptimizer().unfreezeAppLSP(app, reason, true);
    }

    @GuardedBy({"mService", "mService.mProcLock"})
    private ArraySet<String> getDependentFrozenPackagesLSP(ProcessRecord clientApp) {
        final ArraySet<String> packages = new ArraySet<>();
        if (clientApp == null || clientApp.info == null) {
            return packages;
        }

        final ArraySet<String> frozenPackages = new ArraySet<>();
        synchronized (mLock) {
            frozenPackages.addAll(mFrozenPackages);
        }
        for (int i = 0; i < frozenPackages.size(); i++) {
            final String packageName = frozenPackages.valueAt(i);
            final ArrayList<ProcessRecord> processes = getPackageProcessesLSP(packageName);
            for (int j = 0; j < processes.size(); j++) {
                if (isBoundClientLSP(processes.get(j), clientApp)) {
                    packages.add(packageName);
                    break;
                }
            }
        }
        return packages;
    }

    @GuardedBy({"mService", "mService.mProcLock"})
    private ArraySet<String> getPackagesForProcessLSP(String processName) {
        final ArraySet<String> packages = new ArraySet<>();
        synchronized (mService.mPidsSelfLocked) {
            for (int i = 0, size = mService.mPidsSelfLocked.size(); i < size; i++) {
                final ProcessRecord app = mService.mPidsSelfLocked.valueAt(i);
                if (app != null && app.info != null && processName.equals(app.processName)) {
                    packages.add(app.info.packageName);
                }
            }
        }
        return packages;
    }

    @GuardedBy({"mService", "mService.mProcLock"})
    private static boolean isBoundClientLSP(ProcessRecord serviceApp, ProcessRecord clientApp) {
        final ProcessServiceRecord services = serviceApp.getServices();
        if (services == null || clientApp.info == null) {
            return false;
        }
        final String clientPackageName = clientApp.info.packageName;
        final String clientProcessName = clientApp.processName;
        for (int i = services.numberOfRunningServices() - 1; i >= 0; i--) {
            final ServiceRecord service = services.getRunningServiceAt(i);
            if (service == null) {
                continue;
            }
            final ArrayMap<IBinder, ArrayList<ConnectionRecord>> connections =
                    service.getConnections();
            for (int j = connections.size() - 1; j >= 0; j--) {
                final ArrayList<ConnectionRecord> records = connections.valueAt(j);
                for (int k = records.size() - 1; k >= 0; k--) {
                    final ConnectionRecord connection = records.get(k);
                    if (TextUtils.equals(clientPackageName, connection.clientPackageName)
                            || TextUtils.equals(clientProcessName, connection.clientProcessName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private ArrayList<ProcessRecord> getPackageProcessesLSP(String packageName) {
        final ArrayList<ProcessRecord> processes = new ArrayList<>();
        synchronized (mService.mPidsSelfLocked) {
            for (int i = 0, size = mService.mPidsSelfLocked.size(); i < size; i++) {
                final ProcessRecord app = mService.mPidsSelfLocked.valueAt(i);
                if (containsPackage(app, packageName)) {
                    processes.add(app);
                }
            }
        }
        return processes;
    }

    private boolean hasTopProcessLSP(String packageName) {
        synchronized (mService.mPidsSelfLocked) {
            for (int i = 0, size = mService.mPidsSelfLocked.size(); i < size; i++) {
                final ProcessRecord app = mService.mPidsSelfLocked.valueAt(i);
                if (containsPackage(app, packageName)
                        && isTopAppGroup(app.getCurrentSchedulingGroup())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void setPackageForeground(String packageName, boolean foreground) {
        synchronized (mLock) {
            if (foreground) {
                mForegroundPackages.add(packageName);
            } else {
                mForegroundPackages.remove(packageName);
            }
        }
    }

    private void scheduleOomUpdate() {
        mService.mHandler.post(() -> {
            synchronized (mService) {
                mService.updateOomAdjLocked(OOM_ADJ_REASON_RESTRICTION_CHANGE);
            }
        });
    }

    private static boolean isForegroundStart(HostingRecord hostingRecord) {
        return hostingRecord != null
                && (hostingRecord.isTopApp() || hostingRecord.isTypeActivity());
    }

    private static boolean containsPackage(ProcessRecord app, String packageName) {
        if (app == null || app.info == null) {
            return false;
        }
        if (packageName.equals(app.info.packageName)) {
            return true;
        }
        final String[] packages = app.getProcessPackageNames();
        for (int i = 0; i < packages.length; i++) {
            if (packageName.equals(packages[i])) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExemptApplication(ApplicationInfo info) {
        return info.isSystemApp()
                || info.isUpdatedSystemApp()
                || isBlockedPackage(info.packageName);
    }

    private static boolean isBlockedPackage(String packageName) {
        return PACKAGE_ANDROID.equals(packageName)
                || PACKAGE_LAUNCHER.equals(packageName)
                || PACKAGE_SYSTEMUI.equals(packageName);
    }

    private static boolean isTargetPackage(String targetName, String packageName) {
        return packageName.equals(targetName) || targetName.startsWith(packageName + ":");
    }

    private static boolean isTopAppGroup(int schedGroup) {
        return schedGroup == SCHED_GROUP_TOP_APP || schedGroup == SCHED_GROUP_TOP_APP_BOUND;
    }
}
