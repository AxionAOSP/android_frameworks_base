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

import android.app.WindowConfiguration;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.app.IAppLockManager;
import com.android.internal.app.IAppLockListener;
import com.android.internal.util.NTAppLockerHelper;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;

public class AppLockManagerService extends IAppLockManager.Stub implements IAppLockService {
    private static final String TAG = "AppLockManagerService";

    private static final String SETTING_LOCK_TYPE = "nothing_applocker_locktype";
    private static final String SETTING_PRIVATE_PASSWORD = "nothing_applocker_use_private_password";
    private static final String SETTING_LOCKED_APPS = "nt_locked_apps";
    
    private static final String APP_LOCKER_PKG = "com.android.applocker";
    private static final String APP_LOCKER_COMPONENT = "com.android.applocker.NTAppLockerActivity";

    private static final String EXTRA_LOCKED_COMPONENT = "LOCKED_COMPONENT";
    private static final String EXTRA_LOCKED_PACKAGE = "LOCKED_PACKAGE";
    private static final String EXTRA_LOCKED_UID = "LOCKED_UID";

    private static final int LOCK_TYPE_SCREEN_OFF = 0;
    private static final int LOCK_TYPE_APP_EXIT = 1;
    private static final int REQUEST_CODE_MASK = 0x0FFFFFFF;
    
    private final SharedPreferences mPreferences;
    private static final String PREF_HIDDEN_APPS = "hidden_apps_list";

    private static AppLockManagerService sInstance = null;

    private final Context mContext;
    private final ActivityTaskManagerService mAtmService;
    private final AppLockSettingsRepository mSettingsRepository;
    private final RemoteCallbackList<IAppLockListener> mListeners = new RemoteCallbackList<>();

    private final AppLockStateRepository mAppLockStateRepository;
    private Set<String> mLockedApps = new HashSet<>();

    private int mLockType = LOCK_TYPE_SCREEN_OFF;
    
    int mRequestCode = -1;
    private Intent mApplockerIntent = null;
    private ResolveInfo mAppLockerResolveInfo;
    private int mCurrentUserId = 0;

    private AppLockManagerService(Context context,
                                  ActivityTaskManagerService atmService) {
        mContext = context;
        mAtmService = atmService;
        
        mAppLockStateRepository = new AppLockStateRepository((unused) -> notifyAppLockerUpdated());

        mSettingsRepository = new AppLockSettingsRepository(mContext, atmService.mH,
            (lockedApps, lockType) -> {
                mLockedApps = lockedApps;
                mLockType = lockType;
            });
        
        mRequestCode = getAppLockerIntent().toString().hashCode() & REQUEST_CODE_MASK;
        File hiddenAppsPref = new File(
            new File(Environment.getDataSystemDeDirectory(UserHandle.USER_SYSTEM), "applock_prefs"),
            "hidden_apps.xml" 
        );
        Context deviceProtectedContext = mContext.createDeviceProtectedStorageContext();
        mPreferences = deviceProtectedContext.getSharedPreferences(hiddenAppsPref, Context.MODE_PRIVATE);

        Slog.d(TAG, "AppLockManagerService initialized");
    }

    public static void init(Context context,
                            ActivityTaskManagerService atmService) {
        if (sInstance == null) {
            sInstance = new AppLockManagerService(context, atmService);
            ServiceManager.addService("app_lock", sInstance);
        } else {
            Slog.w(TAG, "AppLockManagerService already initialized");
        }
    }

    public static AppLockManagerService get() {
        return sInstance;
    }

    @Override
    public boolean isAppLocked(ActivityRecord r) {
        if (!isValidAr(r) || r.isNoDisplay() || 
            r.isActivityTypeHomeOrRecents()) {
            return false;
        }
        int userId = UserHandle.getUserId(r.getUid());
        if (mAppLockerResolveInfo == null || mCurrentUserId != userId) {
            updateAppLockerResolveInfo(userId);
        }
        return getLockStatus(r.packageName, r.getUid()).locked;
    }

    @Override
    public boolean isAppLocked(String packageName) {
        if (packageName == null) return false;
        try {
            int userId = UserHandle.getUserId(android.os.Process.myUid());
            int uid = mContext.getPackageManager().getPackageUidAsUser(
                packageName, userId);
            if (mAppLockerResolveInfo == null || mCurrentUserId != userId) {
                updateAppLockerResolveInfo(userId);
            }
            return getLockStatus(packageName, uid).locked;
        } catch (Exception e) {
            Slog.w(TAG, "Package not found: " + packageName, e);
            return false;
        }
    }

    private AppLockStatus getLockStatus(String packageName, int uid) {
        int userId = UserHandle.getUserId(uid);
        if (!mAtmService.mWindowManager.isKeyguardSecure(userId)) {
            return new AppLockStatus(false, "NO_SECURE_KEYGUARD");
        }
        if (!mLockedApps.contains(packageName)) {
            return new AppLockStatus(false, "NOT_IN_LOCKED_LIST");
        }
        if (mAppLockStateRepository.isUnlocked(packageName)) {
            return new AppLockStatus(false, "ALREADY_UNLOCKED");
        }
        return new AppLockStatus(true, "LOCKED_POLICY");
    }

    @Override
    public void onAppFocusChanged(ActivityRecord r, Task task) {
        if (r == null || r.isActivityTypeHomeOrRecents()) {
            return;
        }
        lockTopApp(task, "newFocusTask");
    }

    @Override
    public void onWindowingModeChanged(Task task, int oldWindowingMode) {
        if (task == null || mLockType != LOCK_TYPE_APP_EXIT) {
            return;
        }

        int newWindowingMode = task.getWindowingMode();

        if (!WindowConfiguration.isFloating(oldWindowingMode) && 
            WindowConfiguration.isFloating(newWindowingMode) && task.isVisible()) {
            
            ActivityRecord topActivity = task.topRunningActivityLocked();
            if (isAppLocked(topActivity)) {
                mAppLockStateRepository.unlockApp(topActivity.packageName);
                return;
            }
        }

        if (!mAppLockStateRepository.isEmpty()) {
            boolean enteringMultiWindow = (!WindowConfiguration.inMultiWindowMode(oldWindowingMode) && 
                                         !WindowConfiguration.isFloating(oldWindowingMode)) &&
                                        (WindowConfiguration.inMultiWindowMode(newWindowingMode) || 
                                         WindowConfiguration.isFloating(newWindowingMode));
            
            if (enteringMultiWindow) {
                ActivityRecord topActivity = task.topRunningActivityLocked();
                if (task.isVisible()) {
                    if (task.getWindowingMode() == WindowConfiguration.WINDOWING_MODE_FULLSCREEN) {
                        if (topActivity != null) {
                            clearUnlockedApp(topActivity);
                        } else {
                            clearUnlockedApps();
                        }
                    }
                } else {
                    if (topActivity != null) {
                        mAppLockStateRepository.lockApp(topActivity.packageName);
                    }
                    ActivityRecord lastPausedActivity = task.mLastPausedActivity;
                    if (lastPausedActivity != null) {
                        mAppLockStateRepository.lockApp(lastPausedActivity.packageName);
                    }
                    ComponentName realActivity = task.realActivity;
                    if (realActivity != null) {
                        mAppLockStateRepository.lockApp(realActivity.getPackageName());
                    }
                }
            }
        }
    }

    @Override
    public void setKeyguardDoneLocked(boolean showing) {
        Slog.i(TAG, "Keyguard done: " + showing);
        try {
            if (showing) {
                mAppLockStateRepository.clearAll();
            }

            if (!showing) {
                DisplayContent displayContent = mAtmService.mWindowManager.getDefaultDisplayContentLocked();
                TaskDisplayArea taskDisplayArea = displayContent.getDefaultTaskDisplayArea();
                taskDisplayArea.forAllTasks(new Consumer<Task>() {
                    @Override
                    public void accept(Task task) {
                        handleTaskVisible(task);
                    }
                });
            }
        } catch (Exception e) {
        }
    }

    public void handleKeyguardDone(Task task) {
        if (task.isLeafTask() && task.shouldBeVisible(null) && isAppLocked(task.topRunningActivityLocked())) {
            lockTopApp(task, "setKeyguardDone");
        }
    }

    public void handleTaskVisible(Task task) {
        if (task.isLeafTask() && task.isVisible()) {
            ActivityRecord topActivity = task.topRunningActivityLocked();
            if (isAppLocked(topActivity)) {
                mAppLockStateRepository.unlockApp(topActivity.packageName);
            }
        }
    }

    @Override
    public void removeTask(Task task, String reason) {
        if (task == null || !"remove-task".equals(reason) || 
            mLockType != LOCK_TYPE_APP_EXIT || mAppLockStateRepository.isEmpty()) {
            return;
        }
        if (WindowConfiguration.inMultiWindowMode(task.getWindowingMode()) || 
            WindowConfiguration.isFloating(task.getWindowingMode())) {
            ActivityRecord r = task.topRunningActivityLocked();
            if (r != null) {
                mAppLockStateRepository.lockApp(r.packageName);
            }
            ActivityRecord r2 = task.mLastPausedActivity;
            if (r2 != null) {
                mAppLockStateRepository.lockApp(r2.packageName);
            }
            ComponentName realActivity = task.realActivity;
            if (realActivity != null) {
                mAppLockStateRepository.lockApp(realActivity.getPackageName());
            }
        }
    }

    @Override
    public void clearUnlockedApp(ActivityRecord r) {
        if (!isValidAr(r) || mLockType == LOCK_TYPE_SCREEN_OFF) return;
        
        if (r.occludesParent() || r.isActivityTypeHomeOrRecents()) {
            if (r.isActivityTypeHomeOrRecents() && 
                r.mTransitionController.isTransientLaunch(r)) {
                return;
            }

            boolean wasUnlocked = mAppLockStateRepository.isUnlocked(r.packageName);
            
            clearUnlockedApps();
            
            if (wasUnlocked) {
                mAppLockStateRepository.unlockApp(r.packageName);
            } else {
                mAppLockStateRepository.lockApp(r.packageName);
            }

            if (WindowConfiguration.isFloating(r.getWindowingMode())) {
                processFullscreenTasks(mAtmService.mWindowManager.getDefaultDisplayContentLocked());
            }
        }
    }

    @Override
    public void registerListener(IAppLockListener listener) {
        if (listener != null) mListeners.register(listener);
    }

    @Override
    public void unregisterListener(IAppLockListener listener) {
        if (listener != null) mListeners.unregister(listener);
    }

    private void notifyAppLockerUpdated() {
        final int count = mListeners.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mListeners.getBroadcastItem(i).onAppLockerUpdated();
            } catch (Exception e) {
            }
        }
        mListeners.finishBroadcast();
    }

    public boolean isAppLockerActivity(ComponentName componentName) {
        return componentName != null &&
               APP_LOCKER_PKG.equals(componentName.getPackageName()) &&
               APP_LOCKER_COMPONENT.equals(componentName.getClassName());
    }

    private Intent getAppLockerIntent() {
        if (mApplockerIntent == null) {
            Intent intent = new Intent();
            intent.setClassName(APP_LOCKER_PKG, APP_LOCKER_COMPONENT);
            intent.putExtra("NT_APP_LOCKER", true);
            intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | 
                                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            mApplockerIntent = intent;
        }
        return mApplockerIntent;
    }

    @Override
    public boolean checkLockApp(ActivityRecord r, ActivityRecord r2) {
        if (r2 == null) {
            return false;
        }
        clearUnlockedApp(r2);
        if (!isAppLocked(r2)) {
            return false;
        }
        try {                                
            Intent intent = new Intent(getAppLockerIntent());
            intent.putExtra(EXTRA_LOCKED_UID, r2.getUid());
            intent.putExtra(EXTRA_LOCKED_PACKAGE, r2.packageName);
            intent.putExtra(EXTRA_LOCKED_COMPONENT, r2.intent.getComponent() != null ? r2.intent.getComponent().flattenToString() : "");

            if (r2.app == null) {
                mAtmService.getActivityStartController().obtainStarter(intent, "lockAppIfNeed->AppLocker")
                        .setCallingUid(0).setResultTo(r2.token).setRequestCode(mRequestCode)
                        .setActivityInfo(mAppLockerResolveInfo.activityInfo).setAllowBalExemptionForSystemProcess(true).execute();
            } else {
                try {
                    mAtmService.startActivityAsCaller(
                            r2.app.getThread(), r2.packageName, intent, "",
                            r2.token, r2.resultWho, mRequestCode, 0, null,
                            null, false, -10000);
                } catch (Exception e) {
                    return false;
                }
            }
            if (r != null && r.finishing) {
                r.setVisibility(false);
            }
            abortRemoteAnimation(r);
            r2.mRootWindowContainer.ensureActivitiesVisible();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void abortRemoteAnimation(ActivityRecord r) {
        if (r == null) {
            return;
        }
        try {
            if (r.getOptions() != null 
                && r.getOptions().getRemoteAnimationAdapter() != null) {
                r.getOptions().getRemoteAnimationAdapter().getRunner().onAnimationCancelled();
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to cancel remote animation for " + r, e);
        }
        r.abortAndClearOptionsAnimation();
    }

    @Override
    public boolean checkUnlockApp(ActivityRecord activityRecord, int i, Intent intent) {
        if (activityRecord.requestCode != mRequestCode) {
            return false;
        }
        if (intent == null) {
            return true;
        }
        try {
            int userId = UserHandle.getUserId(intent.getIntExtra(EXTRA_LOCKED_UID, 0));
            String packageName = intent.getStringExtra(EXTRA_LOCKED_PACKAGE);
            if (i != -1 || packageName == null) {
                return true;
            }
            mAppLockStateRepository.unlockApp(packageName);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void addLockedApp(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        synchronized (mLockedApps) {
            if (mLockedApps.add(packageName)) {
                updateLockedAppsList();
            }
        }
    }

    @Override
    public void removeLockedApp(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        synchronized (mLockedApps) {
            if (mLockedApps.remove(packageName)) {
                updateLockedAppsList();
            }
        }
    }

    private void updateLockedAppsList() {
        Settings.Secure.putStringForUser(
            mContext.getContentResolver(),
            SETTING_LOCKED_APPS,
            String.join(",", mLockedApps),
            UserHandle.USER_CURRENT
        );
    }

    @Override
    public boolean isPackageHidden(String packageName) {
        if (packageName == null) return false;
        String hiddenList = mPreferences.getString(PREF_HIDDEN_APPS, "");
        if (hiddenList == null) return false;
        for (String pkg : hiddenList.split(",")) {
            if (pkg.trim().equals(packageName)) return true;
        }
        return false;
    }

    @Override
    public void setPackageHidden(String packageName, boolean hidden) {
        if (packageName == null) return;
        String current = mPreferences.getString(PREF_HIDDEN_APPS, "");
        List<String> hiddenList = new ArrayList<>();
        if (current != null && !current.isEmpty()) {
            hiddenList.addAll(Arrays.asList(current.split(",")));
        }
        hiddenList.removeIf(pkg -> pkg.trim().equals(packageName));
        if (hidden) {
            hiddenList.add(packageName);
        }
        String updated = String.join(",", new LinkedHashSet<>(hiddenList));
        mPreferences.edit().putString(PREF_HIDDEN_APPS, updated).apply();
    }

    private void processFullscreenTasks(DisplayContent displayContent) {
        if (displayContent == null) {
            displayContent = mAtmService.mWindowManager.getDefaultDisplayContentLocked();
        }
        displayContent.getDefaultTaskDisplayArea().forAllTasks(new Consumer<Task>() {
            @Override
            public void accept(Task task) {
                processFullscreenTask(task);
            }
        });
    }

    private void processFullscreenTask(Task task) {
        if (task.isLeafTask() && task.getWindowingMode() == WindowConfiguration.WINDOWING_MODE_FULLSCREEN 
            && task.isVisible()) {
            ActivityRecord topActivity = task.topRunningActivityLocked();
            if (isAppLocked(topActivity)) {
                mAppLockStateRepository.unlockApp(topActivity.packageName);
            }
        }
    }

    public void clearUnlockedApps() {
        if (mLockType != LOCK_TYPE_APP_EXIT || mAppLockStateRepository.isEmpty()) {
            return;
        }
        mAppLockStateRepository.clearAll();
        processMultiWindowTasks(mAtmService.mWindowManager.getDefaultDisplayContentLocked());
    }

    private void processMultiWindowTasks(DisplayContent displayContent) {
        if (displayContent == null) {
            displayContent = mAtmService.mWindowManager.getDefaultDisplayContentLocked();
        }
        displayContent.getDefaultTaskDisplayArea().forAllTasks(new Consumer<Task>() {
            @Override
            public void accept(Task task) {
                processMultiWindowTask(task);
            }
        });
    }

    private void processMultiWindowTask(Task task) {
        if (task.isLeafTask()) {
            boolean isInMultiWindow = WindowConfiguration.inMultiWindowMode(task.getWindowingMode()) || 
                                    WindowConfiguration.isFloating(task.getWindowingMode());
            if (isInMultiWindow && task.isVisible()) {
                ActivityRecord topActivity = task.topRunningActivityLocked();
                if (isAppLocked(topActivity)) {
                    mAppLockStateRepository.unlockApp(topActivity.packageName);
                }
            }
        }
    }

    public void lockTopApp(Task task, String reason) {
        if (task == null) {
            return;
        }

        ActivityRecord topActivity = task.topRunningActivityLocked();
        boolean isLocked = isAppLocked(topActivity);

        if (isLocked) {
            Slog.i(TAG, "Blocking activity: " + topActivity + " reason=" + reason);
            
            Intent intent = new Intent(getAppLockerIntent());
            intent.putExtra(EXTRA_LOCKED_UID, topActivity.getUid());
            intent.putExtra(EXTRA_LOCKED_PACKAGE, topActivity.packageName);
            intent.putExtra(EXTRA_LOCKED_COMPONENT, 
                          topActivity.intent.getComponent() != null ? 
                          topActivity.intent.getComponent().flattenToString() : "");

            WindowProcessController processController = topActivity.app;
            if (processController == null) {
                mAtmService.getActivityStartController().obtainStarter(intent, reason)
                        .setCallingUid(0).setResultTo(topActivity.token).setRequestCode(mRequestCode)
                        .setActivityInfo(mAppLockerResolveInfo.activityInfo).setAllowBalExemptionForSystemProcess(true).execute();
            } else {
                mAtmService.startActivityAsCaller(
                        processController.getThread(), topActivity.packageName, intent, "",
                        topActivity.token, topActivity.resultWho, mRequestCode, 0, null,
                        null, false, -10000);
            }
            abortRemoteAnimation(topActivity);
        }
    }

    private boolean isValidAr(ActivityRecord r) {
        try {
            return r != null && r.packageName != null;
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> getLockedApps() {
        String lockedAppsStr = Settings.Secure.getStringForUser(
                mContext.getContentResolver(),
                SETTING_LOCKED_APPS,
                UserHandle.USER_CURRENT
        );
        if (lockedAppsStr == null || lockedAppsStr.isEmpty()) return Collections.emptyList();

        String[] locked = lockedAppsStr.split(",");
        List<String> list = new ArrayList<>();
        for (String pkg : locked) {
            if (!pkg.trim().isEmpty()) list.add(pkg.trim());
        }
        return list;
    }

    private void updateAppLockerResolveInfo(int userId) {
        List<ResolveInfo> activities = mAtmService.mContext.getPackageManager()
            .queryIntentActivitiesAsUser(getAppLockerIntent(), 65536, userId);

        if (activities != null) {
            for (ResolveInfo resolveInfo : activities) {
                if ((resolveInfo.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    mAppLockerResolveInfo = resolveInfo;
                    break;
                }
            }
        }

        int newUserId = 0;
        if (mAppLockerResolveInfo != null && 
            mAppLockerResolveInfo.activityInfo != null && 
            mAppLockerResolveInfo.activityInfo.applicationInfo != null) {
            newUserId = UserHandle.getUserId(mAppLockerResolveInfo.activityInfo.applicationInfo.uid);
        }

        if (mCurrentUserId != newUserId) {
            Slog.i(TAG, "User updated from " + mCurrentUserId + " to " + newUserId);
            mCurrentUserId = newUserId;
        }
    }

    private static class AppLockStatus {
        public final boolean locked;
        public final String reason;

        public AppLockStatus(boolean locked, String reason) {
            this.locked = locked;
            this.reason = reason;
        }
    }
}
