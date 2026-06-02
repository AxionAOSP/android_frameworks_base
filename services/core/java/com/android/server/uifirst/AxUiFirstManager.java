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
package com.android.server.uifirst;

import android.app.AxBoostFwk;
import android.content.pm.ApplicationInfo;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.FileUtils;
import android.os.Process;
import android.os.SystemClock;
import android.system.OsConstants;
import android.util.IntArray;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseLongArray;

import com.android.internal.os.BackgroundThread;
import com.android.server.LocalServices;
import com.android.server.AxExtServiceFactory;
import com.android.server.NtServiceInjector;
import com.android.server.am.AxBackgroundManager;
import com.android.server.am.AxBoostManager;
import com.android.server.am.AxPerfConfig;
import com.android.server.am.AxUtils;
import com.android.server.am.AxWorkloadDetector;
import com.android.server.cpu.CpuAvailabilityInfo;
import com.android.server.cpu.CpuAvailabilityMonitoringConfig;
import com.android.server.cpu.CpuMonitorInternal;
import static com.android.server.cpu.CpuAvailabilityMonitoringConfig.CPUSET_ALL;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;

public final class AxUiFirstManager implements IAxUiFirstManager {
    private static final String TAG = "AxUiFirstManager";
    private static final boolean DBG = false;

    private static final int RESET_UCLAMP_MIN = 0;
    private static final int RESET_UCLAMP_MAX = 1024;

    private static final int DEFAULT_RT_MIN = 512;
    private static final int DEFAULT_RT_MAX = 1024;
    private static final int DEFAULT_UI_MIN = 384;
    private static final int DEFAULT_UI_MAX = 1024;
    private static final int DEFAULT_RENDER_MIN = 512;
    private static final int DEFAULT_RENDER_MAX = 1024;
    private static final int DEFAULT_GL_MIN = 384;
    private static final int DEFAULT_GL_MAX = 900;
    private static final int DEFAULT_HWUI_TASK_MIN = 256;
    private static final int DEFAULT_HWUI_TASK_MAX = 800;
    private static final int DEFAULT_BINDER_POOL_MIN = 200;
    private static final int DEFAULT_BINDER_POOL_MAX = 700;
    private static final int ASYNC_BINDER_UX_MIN = 384;

    private static final int EARLY_WAKEUP_RENDER_MIN = 768;
    private static final int EARLY_WAKEUP_UI_MIN = 640;
    private static final int EARLY_WAKEUP_HWUI_MIN = 384;
    private static final int EARLY_WAKEUP_GL_MIN = 384;
    private static final int EARLY_WAKEUP_BINDER_MIN = 256;

    private static final int MEDIA_DAMPEN_MAX = 256;

    private static final long CACHE_EXPIRY_MS = 5000;
    private static final String PREFIX_HWUI_TASK = "hwuiTask";
    private static final String PREFIX_GL = "GLThread";
    private static final String PREFIX_BINDER = "binder:";
    private static final String PREFIX_DAV1D = "dav1d";
    private static final String PREFIX_MEDIA_CODEC = "MediaCodec";
    private static final String PREFIX_IMAGE_DECODER = "Image Decod";
    private static final String[] THREAD_PREFIXES = {
        PREFIX_HWUI_TASK,
        PREFIX_GL,
        PREFIX_BINDER,
        PREFIX_DAV1D,
        PREFIX_MEDIA_CODEC,
        PREFIX_IMAGE_DECODER
    };
    private static final int[] EMPTY_TIDS = new int[0];
    private static final SparseArray<ThreadCache> sThreadCaches = new SparseArray<>();
    private static final SparseLongArray sAppliedUclamp = new SparseLongArray();
    private static volatile int sUclampSupport = 0;
    private static volatile int sUseStune = -1;

    private final Object mTopAppLock = new Object();
    private final SparseArray<AppInfo> mProcesses = new SparseArray<>();
    private AppInfo mOnTopApp;
    private AppInfo mOnTopSystemUi;
    private AppInfo mOnTopLauncher;
    private AppInfo mInputMethod;
    private AppInfo mEffectiveTopApp;
    private boolean mSystemUiShowed;
    private boolean mInputMethodShowed;
    private String mRealInputMethodPackage;
    private int mAppliedTopUid = -1;
    private int mAppliedTopPid = -1;
    private int mAppliedTopRenderTid = -1;
    private int[] mAppliedTopHwuiTids = EMPTY_TIDS;
    private int[] mAppliedTopGlTids = EMPTY_TIDS;
    private int[] mAppliedTopBinderTids = EMPTY_TIDS;
    private int mTopAppChildScanSeq = 0;
    private boolean mEarlyWakeupActive = false;
    private int mEarlyWakeupPid = -1;
    private int mEarlyWakeupRenderTid = -1;
    private int[] mEarlyWakeupHwuiTids = EMPTY_TIDS;
    private int[] mEarlyWakeupGlTids = EMPTY_TIDS;
    private int[] mEarlyWakeupBinderTids = EMPTY_TIDS;

    private static final class AppInfo {
        String packageName;
        int uid;
        int pid;
        int renderTid;
        int[] hwuiTids;
        int[] glTids = EMPTY_TIDS;
        int[] binderTids = EMPTY_TIDS;
        int status;
        boolean remoteAnimation;

        AppInfo(String packageName, int uid, int pid, int renderTid, int[] hwuiTids) {
            this.packageName = packageName;
            this.uid = uid;
            this.pid = pid;
            this.renderTid = renderTid;
            this.hwuiTids = nonNullTids(hwuiTids);
        }

        void update(String packageName, int uid, int renderTid, int[] hwuiTids) {
            if (packageName != null) {
                this.packageName = packageName;
            }
            if (uid >= 0) {
                this.uid = uid;
            }
            if (renderTid > 0) {
                this.renderTid = renderTid;
            }
            if (hwuiTids != null && hwuiTids.length > 0) {
                this.hwuiTids = hwuiTids;
            }
        }
    }

    private static final class ThreadCache {
        final long timestamp;
        final Map<String, int[]> prefixToTids = new HashMap<>();

        ThreadCache() {
            timestamp = SystemClock.uptimeMillis();
        }
    }

    @Override
    public void setUxThreads(int uid, int pid, int[] tids, int role) {
        if (tids == null || tids.length == 0) return;
        int[] cfg = uclampForRole(role);
        String group = role == ROLE_RT ? "rt" : "top-app";
        for (int tid : tids) {
            applyUclampWithStuneGroup(tid, cfg[0], cfg[1], group);
        }
        if (DBG) Log.d(TAG, "setUxThreads pid=" + pid + " role=" + role + " count=" + tids.length);
    }

    @Override
    public void clearUxThreads(int uid, int pid, int role) {}

    @Override
    public void adjustTopApp(String packageName, int uid, int pid, int renderTid, int[] hwuiTids) {
        if (pid <= 0) {
            return;
        }
        synchronized (mTopAppLock) {
            AppInfo info = getOrCreateProcessLocked(packageName, uid, pid, renderTid, hwuiTids);
            mOnTopApp = info;
            updateEffectiveTopAppLocked();
        }
    }

    @Override
    public void adjustUxProcess(ApplicationInfo ai, String packageName, int status, int uid, int pid, int renderTid,
            int[] hwuiTids, boolean isRemoteAnimation) {
        if (pid <= 0) {
            return;
        }
        final AppInfo foregroundInfo;
        final AppInfo backgroundInfo;
        final boolean fixGameAffinity = status == STATUS_TOP && isGamePackage(ai, packageName);
        synchronized (mTopAppLock) {
            AppInfo oldInfo = mProcesses.get(pid);
            final int oldStatus = oldInfo != null ? oldInfo.status : 0;
            final int oldRenderTid = oldInfo != null ? oldInfo.renderTid : 0;
            final boolean oldRemoteAnimation = oldInfo != null && oldInfo.remoteAnimation;
            final boolean sameHwuiTids = oldInfo != null && sameTids(oldInfo.hwuiTids, hwuiTids);
            AppInfo info = getOrCreateProcessLocked(packageName, uid, pid, renderTid, hwuiTids);
            info.status = status;
            info.remoteAnimation = isRemoteAnimation;
            if ("com.android.systemui".equals(packageName)) {
                mOnTopSystemUi = status == STATUS_TOP ? info : null;
            } else if ("com.android.launcher3".equals(packageName)) {
                mOnTopLauncher = status == STATUS_TOP ? info : null;
            } else if (packageName != null && packageName.equals(mRealInputMethodPackage)) {
                mInputMethod = status == STATUS_TOP ? info : mInputMethod;
                if (status == STATUS_BACKGROUND && mInputMethod != null && mInputMethod.pid == pid) {
                    mInputMethod = null;
                }
            }
            if (fixGameAffinity) {
                fixGameThreadAffinity(packageName, pid);
            }
            if (status == STATUS_BACKGROUND && mOnTopApp != null && mOnTopApp.pid == pid) {
                mOnTopApp = null;
            }
            final boolean changed = oldInfo == null
                    || oldStatus != status
                    || oldRenderTid != info.renderTid
                    || oldRemoteAnimation != isRemoteAnimation
                    || !sameHwuiTids;
            foregroundInfo = changed && status == STATUS_FOREGROUND ? copyForApply(info) : null;
            backgroundInfo = changed && status == STATUS_BACKGROUND ? copyForApply(info) : null;
            updateEffectiveTopAppLocked();
        }
        if (foregroundInfo != null && !isAppliedTop(foregroundInfo.pid)) {
            applyForegroundRoles(foregroundInfo);
        }
        if (backgroundInfo != null && !isAppliedTop(backgroundInfo.pid)) {
            resetTopAppRoles(backgroundInfo.pid, backgroundInfo.renderTid, backgroundInfo.hwuiTids,
                    backgroundInfo.glTids, backgroundInfo.binderTids);
        }
    }

    @Override
    public void setTaskAsRemoteAnimationUx(int pid, int renderTid, int[] hwuiTids,
            String packageName, boolean isRemoteAnimation) {
        if (pid <= 0) {
            return;
        }
        synchronized (mTopAppLock) {
            AppInfo info = getOrCreateProcessLocked(packageName, -1, pid, renderTid, hwuiTids);
            info.remoteAnimation = isRemoteAnimation;
        }
        if (isRemoteAnimation) {
            applyForegroundRoles(new AppInfo(packageName, -1, pid, renderTid, hwuiTids));
        }
    }

    @Override
    public void applyTopAppRoles(int uid, int pid, int renderTid) {
        applyTopAppRoles(uid, pid, renderTid, EMPTY_TIDS);
    }

    @Override
    public void applyTopAppRoles(int uid, int pid, int renderTid, int[] hwuiTids) {
        final int oldPid;
        final int oldRenderTid;
        final int[] oldHwuiTids;
        final int[] oldGlTids;
        final int[] oldBinderTids;
        final int childScanSeq;
        synchronized (mTopAppLock) {
            if (uid == mAppliedTopUid
                    && pid == mAppliedTopPid
                    && renderTid == mAppliedTopRenderTid
                    && sameTids(mAppliedTopHwuiTids, hwuiTids)) {
                return;
            }
            childScanSeq = ++mTopAppChildScanSeq;
            oldPid = mAppliedTopPid;
            oldRenderTid = mAppliedTopRenderTid;
            oldHwuiTids = mAppliedTopHwuiTids;
            oldGlTids = mAppliedTopGlTids;
            oldBinderTids = mAppliedTopBinderTids;
            mAppliedTopUid = uid;
            mAppliedTopPid = pid;
            mAppliedTopRenderTid = renderTid;
            mAppliedTopHwuiTids = nonNullTids(hwuiTids);
            mAppliedTopGlTids = EMPTY_TIDS;
            mAppliedTopBinderTids = EMPTY_TIDS;
        }
        if (oldPid > 0 && oldPid != pid) {
            resetTopAppRoles(oldPid, oldRenderTid, oldHwuiTids, oldGlTids, oldBinderTids);
        } else if (oldPid == pid) {
            if (oldRenderTid > 0 && oldRenderTid != renderTid) {
                applyUclamp(oldRenderTid, RESET_UCLAMP_MIN, RESET_UCLAMP_MAX);
                moveToBoostGroup(oldRenderTid, "foreground");
            }
            if (!sameTids(oldHwuiTids, hwuiTids)) {
                for (int tid : oldHwuiTids) {
                    applyUclamp(tid, RESET_UCLAMP_MIN, RESET_UCLAMP_MAX);
                    moveToBoostGroup(tid, "foreground");
                }
            }
        }
        int[] uiCfg = uclampForRole(ROLE_UI);
        applyUclamp(pid, uiCfg[0], uiCfg[1]);
        moveToBoostGroup(pid, "top-app");
        if (renderTid > 0) {
            int[] rtCfg = uclampForRole(ROLE_RENDER);
            applyUclamp(renderTid, rtCfg[0], rtCfg[1]);
            moveToBoostGroup(renderTid, "top-app");
        }
        if (hwuiTids != null && hwuiTids.length > 0) {
            int[] hwCfg = uclampForRole(ROLE_HWUI_TASK);
            for (int tid : hwuiTids) {
                applyUclamp(tid, hwCfg[0], hwCfg[1]);
                moveToBoostGroup(tid, "top-app");
            }
        }
        BackgroundThread.getHandler()
                .post(() -> applyTopAppChildRoles(uid, pid, renderTid, childScanSeq));
    }

    @Override
    public void onHwuiTaskThreads(int uid, int pid, int[] hwuiTids) {
        try {
            final int[] tids = nonNullTids(hwuiTids);
            if (tids.length == 0) {
                return;
            }
            synchronized (mTopAppLock) {
                AppInfo info = mProcesses.get(pid);
                if (info != null && uid == info.uid) {
                    info.hwuiTids = tids;
                }
                if (uid != mAppliedTopUid || pid != mAppliedTopPid) {
                    return;
                }
                mAppliedTopHwuiTids = tids;
                if (mEarlyWakeupActive && pid == mEarlyWakeupPid) {
                    mEarlyWakeupHwuiTids = tids;
                }
            }
            int[] hwCfg = uclampForRole(ROLE_HWUI_TASK);
            for (int tid : tids) {
                applyUclampWithStuneGroup(tid, hwCfg[0], hwCfg[1], "top-app");
            }
        } catch (Exception e) {
        }
    }

    @Override
    public void addApplicationHwuiTaskThread(String packageName, int uid, int pid, int tid) {
        if (pid <= 0 || tid <= 0) {
            return;
        }
        final boolean applyTop;
        final boolean applyForeground;
        synchronized (mTopAppLock) {
            AppInfo info = getOrCreateProcessLocked(packageName, uid, pid, 0, null);
            info.hwuiTids = appendTid(info.hwuiTids, tid);
            if (pid == mAppliedTopPid) {
                mAppliedTopHwuiTids = appendTid(mAppliedTopHwuiTids, tid);
                if (mEarlyWakeupActive && pid == mEarlyWakeupPid) {
                    mEarlyWakeupHwuiTids = appendTid(mEarlyWakeupHwuiTids, tid);
                }
            }
            applyTop = pid == mAppliedTopPid;
            applyForeground = info.status == STATUS_FOREGROUND;
        }
        if (applyTop || applyForeground) {
            int[] hwCfg = uclampForRole(ROLE_HWUI_TASK);
            applyUclampWithStuneGroup(tid, hwCfg[0], hwCfg[1], applyTop ? "top-app" : "foreground");
        }
    }

    @Override
    public void addApplicationGlThread(String packageName, int uid, int pid, int tid) {
        if (pid <= 0 || tid <= 0) {
            return;
        }
        final boolean applyTop;
        final boolean applyForeground;
        synchronized (mTopAppLock) {
            AppInfo info = getOrCreateProcessLocked(packageName, uid, pid, 0, null);
            info.glTids = appendTid(info.glTids, tid);
            if (pid == mAppliedTopPid) {
                mAppliedTopGlTids = appendTid(mAppliedTopGlTids, tid);
                if (mEarlyWakeupActive && pid == mEarlyWakeupPid) {
                    mEarlyWakeupGlTids = appendTid(mEarlyWakeupGlTids, tid);
                }
            }
            applyTop = pid == mAppliedTopPid;
            applyForeground = info.status == STATUS_FOREGROUND;
        }
        if (applyTop || applyForeground) {
            int[] glCfg = uclampForRole(ROLE_GL);
            applyUclampWithStuneGroup(tid, glCfg[0], glCfg[1], applyTop ? "top-app" : "foreground");
        }
    }

    @Override
    public void removeApplicationGlThread(String packageName, int pid, int tid) {
        if (pid <= 0 || tid <= 0) {
            return;
        }
        synchronized (mTopAppLock) {
            AppInfo info = mProcesses.get(pid);
            if (info != null) {
                info.glTids = removeTid(info.glTids, tid);
            }
            if (pid == mAppliedTopPid) {
                mAppliedTopGlTids = removeTid(mAppliedTopGlTids, tid);
            }
            if (pid == mEarlyWakeupPid) {
                mEarlyWakeupGlTids = removeTid(mEarlyWakeupGlTids, tid);
            }
        }
        applyUclamp(tid, RESET_UCLAMP_MIN, RESET_UCLAMP_MAX);
        moveToBoostGroup(tid, "foreground");
    }

    @Override
    public int getApplicationGlThreadValue(String packageName) {
        synchronized (mTopAppLock) {
            for (int i = 0; i < mProcesses.size(); i++) {
                AppInfo info = mProcesses.valueAt(i);
                if (packageName != null && packageName.equals(info.packageName)
                        && info.glTids.length > 0) {
                    return info.status;
                }
            }
        }
        return 0;
    }

    @Override
    public void setApplicationKeyThreads(String packageName, int uid, int op, int pid, int[] tids) {
        if (tids == null) {
            return;
        }
        for (int tid : tids) {
            if (op > 0) {
                addApplicationGlThread(packageName, uid, pid, tid);
            } else {
                removeApplicationGlThread(packageName, pid, tid);
            }
        }
    }

    @Override
    public void onRenderThreadTid(String packageName, int uid, int pid, int renderTid) {
        try {
            if (pid <= 0 || renderTid <= 0) {
                return;
            }
            final boolean applyRenderRole;
            synchronized (mTopAppLock) {
                AppInfo info = getOrCreateProcessLocked(packageName, uid, pid, renderTid, null);
                info.renderTid = renderTid;
                applyRenderRole = uid == mAppliedTopUid && pid == mAppliedTopPid;
                updateEffectiveTopAppLocked();
            }
            if (applyRenderRole) {
                int[] rtCfg = uclampForRole(ROLE_RENDER);
                applyUclampWithStuneGroup(renderTid, rtCfg[0], rtCfg[1], "top-app");
            }
        } catch (Exception e) {
        }
    }

    @Override
    public void setRenderThreadTid(String packageName, int uid, int pid, int renderTid) {
        onRenderThreadTid(packageName, uid, pid, renderTid);
    }

    private AppInfo getOrCreateProcessLocked(String packageName, int uid, int pid, int renderTid,
            int[] hwuiTids) {
        AppInfo info = mProcesses.get(pid);
        if (info == null) {
            info = new AppInfo(packageName, uid, pid, renderTid, hwuiTids);
            mProcesses.put(pid, info);
            return info;
        }
        info.update(packageName, uid, renderTid, hwuiTids);
        return info;
    }

    private void updateEffectiveTopAppLocked() {
        AppInfo selected = mSystemUiShowed && mOnTopSystemUi != null ? mOnTopSystemUi : null;
        if (selected == null && mInputMethodShowed && mInputMethod != null) {
            selected = mInputMethod;
        }
        if (selected == null) {
            selected = mOnTopApp;
        }
        if (selected == null) {
            selected = mOnTopLauncher;
        }
        if (selected == null || selected.pid <= 0) {
            return;
        }
        if (mEffectiveTopApp != null
                && mEffectiveTopApp.pid == selected.pid
                && mEffectiveTopApp.renderTid == selected.renderTid
                && sameTids(mEffectiveTopApp.hwuiTids, selected.hwuiTids)) {
            return;
        }
        mEffectiveTopApp = copyForApply(selected);
        applyTopAppRoles(selected.uid, selected.pid, selected.renderTid, selected.hwuiTids);
    }

    private boolean isAppliedTop(int pid) {
        synchronized (mTopAppLock) {
            return pid == mAppliedTopPid;
        }
    }

    private static AppInfo copyForApply(AppInfo info) {
        AppInfo copy = new AppInfo(info.packageName, info.uid, info.pid, info.renderTid,
                info.hwuiTids);
        copy.glTids = info.glTids;
        copy.binderTids = info.binderTids;
        copy.status = info.status;
        copy.remoteAnimation = info.remoteAnimation;
        return copy;
    }

    private static void applyForegroundRoles(AppInfo info) {
        int[] uiCfg = uclampForRole(ROLE_UI);
        applyUclampWithStuneGroup(info.pid, uiCfg[0], uiCfg[1], "foreground");
        if (info.renderTid > 0) {
            int[] rtCfg = uclampForRole(ROLE_RENDER);
            applyUclampWithStuneGroup(info.renderTid, rtCfg[0], rtCfg[1], "foreground");
        }
        int[] hwCfg = uclampForRole(ROLE_HWUI_TASK);
        for (int tid : info.hwuiTids) {
            applyUclampWithStuneGroup(tid, hwCfg[0], hwCfg[1], "foreground");
        }
        int[] glCfg = uclampForRole(ROLE_GL);
        for (int tid : info.glTids) {
            applyUclampWithStuneGroup(tid, glCfg[0], glCfg[1], "foreground");
        }
    }

    private void applyTopAppChildRoles(int uid, int pid, int renderTid, int childScanSeq) {
        synchronized (mTopAppLock) {
            if (childScanSeq != mTopAppChildScanSeq
                    || uid != mAppliedTopUid
                    || pid != mAppliedTopPid
                    || renderTid != mAppliedTopRenderTid) {
                return;
            }
        }
        int[] hwuiTids;
        int[] glTids;
        int[] binderTids;
        synchronized (mTopAppLock) {
            hwuiTids = mAppliedTopHwuiTids;
            AppInfo info = mProcesses.get(pid);
            glTids = info != null ? info.glTids : EMPTY_TIDS;
            binderTids = info != null ? info.binderTids : EMPTY_TIDS;
        }
        synchronized (mTopAppLock) {
            if (childScanSeq != mTopAppChildScanSeq
                    || uid != mAppliedTopUid
                    || pid != mAppliedTopPid
                    || renderTid != mAppliedTopRenderTid) {
                return;
            }
            mAppliedTopHwuiTids = hwuiTids;
            mAppliedTopGlTids = glTids;
            mAppliedTopBinderTids = binderTids;
            AppInfo info = mProcesses.get(pid);
            if (info != null && uid == info.uid) {
                info.hwuiTids = hwuiTids;
                info.glTids = glTids;
                info.binderTids = binderTids;
            }
            if (mEarlyWakeupActive && pid == mEarlyWakeupPid) {
                mEarlyWakeupHwuiTids = hwuiTids;
                mEarlyWakeupGlTids = glTids;
                mEarlyWakeupBinderTids = binderTids;
            }
        }
        if (hwuiTids.length > 0) {
            int[] hwCfg = uclampForRole(ROLE_HWUI_TASK);
            for (int tid : hwuiTids) {
                applyUclamp(tid, hwCfg[0], hwCfg[1]);
                moveToBoostGroup(tid, "top-app");
            }
        }
        if (glTids.length > 0) {
            int[] glCfg = uclampForRole(ROLE_GL);
            for (int tid : glTids) {
                applyUclamp(tid, glCfg[0], glCfg[1]);
                moveToBoostGroup(tid, "top-app");
            }
        }
        if (binderTids.length > 0) {
            int[] bpCfg = uclampForRole(ROLE_BINDER_POOL);
            for (int tid : binderTids) {
                applyUclamp(tid, bpCfg[0], bpCfg[1]);
                moveToBoostGroup(tid, "top-app");
            }
        }
        if (DBG)
            Log.d(
                    TAG,
                    "applyTopAppRoles pid="
                            + pid
                            + " hwui="
                            + hwuiTids.length
                            + " gl="
                            + glTids.length
                            + " binder="
                            + binderTids.length);
    }

    @Override
    public void clearTopAppRoles(int uid, int pid) {
        final int renderTid;
        final int[] hwuiTids;
        final int[] glTids;
        final int[] binderTids;
        synchronized (mTopAppLock) {
            if (pid != mAppliedTopPid) {
                return;
            }
            renderTid = mAppliedTopRenderTid;
            hwuiTids = mAppliedTopHwuiTids;
            glTids = mAppliedTopGlTids;
            binderTids = mAppliedTopBinderTids;
            mAppliedTopUid = -1;
            mAppliedTopPid = -1;
            mAppliedTopRenderTid = -1;
            mAppliedTopHwuiTids = EMPTY_TIDS;
            mAppliedTopGlTids = EMPTY_TIDS;
            mAppliedTopBinderTids = EMPTY_TIDS;
            if (mEffectiveTopApp != null && mEffectiveTopApp.pid == pid) {
                mEffectiveTopApp = null;
            }
            mTopAppChildScanSeq++;
        }
        resetTopAppRoles(pid, renderTid, hwuiTids, glTids, binderTids);
    }

    @Override
    public void onPanelRevealed(int items) {
        synchronized (mTopAppLock) {
            mSystemUiShowed = true;
            updateEffectiveTopAppLocked();
        }
    }

    @Override
    public void onPanelHidden() {
        synchronized (mTopAppLock) {
            mSystemUiShowed = false;
            updateEffectiveTopAppLocked();
        }
    }

    @Override
    public void notifyUiSwitched(String uiInfo, int status) {
        if (uiInfo == null) {
            return;
        }
        synchronized (mTopAppLock) {
            final boolean shown = status > 0;
            if ("com.android.systemui".equals(uiInfo)) {
                mSystemUiShowed = shown;
            } else if (uiInfo.startsWith("inputmethod:")) {
                mInputMethodShowed = shown;
                mRealInputMethodPackage = uiInfo.substring("inputmethod:".length());
            }
            updateEffectiveTopAppLocked();
        }
    }

    @Override
    public void onAppStatusChanged(int status, String packageName, String activityName, int uid) {
        if (status == STATUS_RESUME_ACTIVITY) {
            notifyUiSwitched(packageName, 1);
        } else if (status == STATUS_PAUSE_ACTIVITY) {
            notifyUiSwitched(packageName, 0);
        }
    }

    @Override
    public void handleProcessStart(
            String packageName, int uid, int pid, boolean isolated, String processName) {
        try {
            if (pid <= 0) {
                return;
            }
            synchronized (mTopAppLock) {
                getOrCreateProcessLocked(packageName, uid, pid, 0, null);
            }
        } catch (Exception e) {
        }
    }

    @Override
    public void handleProcessStop(String packageName, int uid, int pid) {
        onProcessDied(pid);
    }

    @Override
    public void onProcessDied(int pid) {
        final AppInfo info;
        synchronized (mTopAppLock) {
            info = mProcesses.get(pid);
            mProcesses.remove(pid);
            if (mOnTopApp != null && mOnTopApp.pid == pid) {
                mOnTopApp = null;
            }
            if (mOnTopSystemUi != null && mOnTopSystemUi.pid == pid) {
                mOnTopSystemUi = null;
            }
            if (mOnTopLauncher != null && mOnTopLauncher.pid == pid) {
                mOnTopLauncher = null;
            }
            if (mInputMethod != null && mInputMethod.pid == pid) {
                mInputMethod = null;
            }
            if (mEffectiveTopApp != null && mEffectiveTopApp.pid == pid) {
                mEffectiveTopApp = null;
            }
            if (mEarlyWakeupPid == pid) {
                mEarlyWakeupActive = false;
                mEarlyWakeupPid = -1;
                mEarlyWakeupRenderTid = -1;
                mEarlyWakeupHwuiTids = EMPTY_TIDS;
                mEarlyWakeupGlTids = EMPTY_TIDS;
                mEarlyWakeupBinderTids = EMPTY_TIDS;
            }
        }
        if (info != null) {
            resetTopAppRoles(pid, info.renderTid, info.hwuiTids, info.glTids, info.binderTids);
        }
        synchronized (sThreadCaches) {
            sThreadCaches.remove(pid);
        }
    }

    @Override
    public void setBinderThreadUxFlag(int pid, int flag) {
        final int targetPid;
        final int[] binderTids;
        final boolean targetIsTop;
        synchronized (mTopAppLock) {
            targetPid = pid > 0 ? pid : mAppliedTopPid;
            targetIsTop = targetPid == mAppliedTopPid;
            binderTids = targetIsTop ? mAppliedTopBinderTids : EMPTY_TIDS;
        }
        if (targetPid <= 0) {
            return;
        }
        final int[] tids = binderTids.length > 0 || pid <= 0
                ? binderTids : discoverThreadsByPrefix(targetPid, PREFIX_BINDER);
        if (tids.length == 0) {
            return;
        }
        if (flag > 0) {
            for (int tid : tids) {
                applyUclampWithStuneGroup(tid, ASYNC_BINDER_UX_MIN,
                        DEFAULT_BINDER_POOL_MAX, targetIsTop ? "top-app" : "foreground");
            }
        } else {
            int[] cfg = uclampForRole(ROLE_BINDER_POOL);
            for (int tid : tids) {
                applyUclampWithStuneGroup(tid, cfg[0], cfg[1],
                        targetIsTop ? "top-app" : "foreground");
            }
        }
    }

    private static void applyUclampWithStuneGroup(int tid, int min, int max, String group) {
        applyUclamp(tid, min, max);
        moveToBoostGroup(tid, group);
    }

    private static void moveToBoostGroup(int tid, String group) {
        if (tid <= 0) return;
        AxUtils.writeUncached("/dev/cpuctl/" + group + "/tasks", String.valueOf(tid));
        moveToStuneGroup(tid, group);
    }

    private static void moveToStuneGroup(int tid, String group) {
        if (tid <= 0 || !useStuneFallback()) return;
        File taskFile = new File("/dev/stune/" + group + "/tasks");
        if (taskFile.exists()) {
            AxUtils.writeUncached(taskFile.getPath(), String.valueOf(tid));
        }
    }

    private static void resetTopAppRoles(
            int pid, int renderTid, int[] hwuiTids, int[] glTids, int[] binderTids) {
        applyUclamp(pid, RESET_UCLAMP_MIN, RESET_UCLAMP_MAX);
        moveToBoostGroup(pid, "foreground");
        if (renderTid > 0) {
            applyUclamp(renderTid, RESET_UCLAMP_MIN, RESET_UCLAMP_MAX);
            moveToBoostGroup(renderTid, "foreground");
        }
        for (int tid : hwuiTids) {
            applyUclamp(tid, RESET_UCLAMP_MIN, RESET_UCLAMP_MAX);
            moveToBoostGroup(tid, "foreground");
        }
        for (int tid : glTids) {
            applyUclamp(tid, RESET_UCLAMP_MIN, RESET_UCLAMP_MAX);
            moveToBoostGroup(tid, "foreground");
        }
        for (int tid : binderTids) {
            applyUclamp(tid, RESET_UCLAMP_MIN, RESET_UCLAMP_MAX);
            moveToBoostGroup(tid, "foreground");
        }
    }

    @Override
    public void dampenMediaForInputBurst(int pid, boolean active) {
        if (pid <= 0) return;
        int[] dav1dTids = discoverThreadsByPrefix(pid, PREFIX_DAV1D);
        int[] mcTids = discoverThreadsByPrefix(pid, PREFIX_MEDIA_CODEC);
        int[] imgTids = discoverThreadsByPrefix(pid, PREFIX_IMAGE_DECODER);
        if (active) {
            for (int tid : dav1dTids) applyUclamp(tid, RESET_UCLAMP_MIN, MEDIA_DAMPEN_MAX);
            for (int tid : mcTids) applyUclamp(tid, RESET_UCLAMP_MIN, MEDIA_DAMPEN_MAX);
            for (int tid : imgTids) applyUclamp(tid, RESET_UCLAMP_MIN, MEDIA_DAMPEN_MAX);
        } else {
            for (int tid : dav1dTids) applyUclamp(tid, RESET_UCLAMP_MIN, RESET_UCLAMP_MAX);
            for (int tid : mcTids) applyUclamp(tid, RESET_UCLAMP_MIN, RESET_UCLAMP_MAX);
            for (int tid : imgTids) applyUclamp(tid, RESET_UCLAMP_MIN, RESET_UCLAMP_MAX);
        }
    }

    @Override
    public void boostTopAppForEarlyWakeup(int pid, int renderTid, boolean active) {
        if (pid <= 0) return;
        if (active) {
            final int oldPid;
            final int oldRenderTid;
            final int[] oldHwuiTids;
            final int[] oldGlTids;
            final int[] oldBinderTids;
            final int[] hwuiTids;
            final int[] glTids;
            final int[] binderTids;
            synchronized (mTopAppLock) {
                if (mEarlyWakeupActive
                        && pid == mEarlyWakeupPid
                        && renderTid == mEarlyWakeupRenderTid) {
                    return;
                }
                hwuiTids = pid == mAppliedTopPid ? mAppliedTopHwuiTids : EMPTY_TIDS;
                glTids = pid == mAppliedTopPid ? mAppliedTopGlTids : EMPTY_TIDS;
                binderTids = pid == mAppliedTopPid ? mAppliedTopBinderTids : EMPTY_TIDS;
                oldPid = mEarlyWakeupActive ? mEarlyWakeupPid : -1;
                oldRenderTid = mEarlyWakeupActive ? mEarlyWakeupRenderTid : -1;
                oldHwuiTids = mEarlyWakeupActive ? mEarlyWakeupHwuiTids : EMPTY_TIDS;
                oldGlTids = mEarlyWakeupActive ? mEarlyWakeupGlTids : EMPTY_TIDS;
                oldBinderTids = mEarlyWakeupActive ? mEarlyWakeupBinderTids : EMPTY_TIDS;
                mEarlyWakeupActive = true;
                mEarlyWakeupPid = pid;
                mEarlyWakeupRenderTid = renderTid;
                mEarlyWakeupHwuiTids = hwuiTids;
                mEarlyWakeupGlTids = glTids;
                mEarlyWakeupBinderTids = binderTids;
            }
            if (oldPid > 0) {
                restoreEarlyWakeupBoost(
                        oldPid, oldRenderTid, oldHwuiTids, oldGlTids, oldBinderTids);
            }
            applyUclampWithStuneGroup(pid, EARLY_WAKEUP_UI_MIN, DEFAULT_UI_MAX, "top-app");
            if (renderTid > 0) {
                applyUclampWithStuneGroup(renderTid, EARLY_WAKEUP_RENDER_MIN,
                        DEFAULT_RENDER_MAX, "top-app");
            }
            for (int tid : hwuiTids) {
                applyUclampWithStuneGroup(tid, EARLY_WAKEUP_HWUI_MIN,
                        DEFAULT_HWUI_TASK_MAX, "top-app");
            }
            for (int tid : glTids) {
                applyUclampWithStuneGroup(tid, EARLY_WAKEUP_GL_MIN, DEFAULT_GL_MAX, "top-app");
            }
            for (int tid : binderTids) {
                applyUclampWithStuneGroup(tid, EARLY_WAKEUP_BINDER_MIN,
                        DEFAULT_BINDER_POOL_MAX, "top-app");
            }
        } else {
            final int appliedRenderTid;
            final int[] hwuiTids;
            final int[] glTids;
            final int[] binderTids;
            synchronized (mTopAppLock) {
                if (!mEarlyWakeupActive || pid != mEarlyWakeupPid) {
                    return;
                }
                appliedRenderTid = mEarlyWakeupRenderTid;
                hwuiTids = mEarlyWakeupHwuiTids;
                glTids = mEarlyWakeupGlTids;
                binderTids = mEarlyWakeupBinderTids;
                mEarlyWakeupActive = false;
                mEarlyWakeupPid = -1;
                mEarlyWakeupRenderTid = -1;
                mEarlyWakeupHwuiTids = EMPTY_TIDS;
                mEarlyWakeupGlTids = EMPTY_TIDS;
                mEarlyWakeupBinderTids = EMPTY_TIDS;
            }
            restoreEarlyWakeupBoost(pid, appliedRenderTid, hwuiTids, glTids, binderTids);
        }
    }

    private static void restoreEarlyWakeupBoost(
            int pid, int renderTid, int[] hwuiTids, int[] glTids, int[] binderTids) {
        int[] uiCfg = uclampForRole(ROLE_UI);
        applyUclampWithStuneGroup(pid, uiCfg[0], uiCfg[1], "top-app");
        if (renderTid > 0) {
            int[] rtCfg = uclampForRole(ROLE_RENDER);
            applyUclampWithStuneGroup(renderTid, rtCfg[0], rtCfg[1], "top-app");
        }
        int[] hwCfg = uclampForRole(ROLE_HWUI_TASK);
        for (int tid : hwuiTids) {
            applyUclampWithStuneGroup(tid, hwCfg[0], hwCfg[1], "top-app");
        }
        int[] glCfg = uclampForRole(ROLE_GL);
        for (int tid : glTids) {
            applyUclampWithStuneGroup(tid, glCfg[0], glCfg[1], "top-app");
        }
        int[] binderCfg = uclampForRole(ROLE_BINDER_POOL);
        for (int tid : binderTids) {
            applyUclampWithStuneGroup(tid, binderCfg[0], binderCfg[1], "top-app");
        }
    }

    @Override
    public void setBinderUx(int uid, int pid, boolean enable) {
        int[] tids = discoverThreadsByPrefix(pid, PREFIX_BINDER);
        if (tids.length == 0) return;
        if (enable) {
            int[] cfg = uclampForRole(ROLE_BINDER_POOL);
            for (int tid : tids) {
                applyUclampWithStuneGroup(tid, cfg[0], cfg[1], "top-app");
            }
        } else {
            for (int tid : tids) {
                applyUclampWithStuneGroup(tid, RESET_UCLAMP_MIN, RESET_UCLAMP_MAX, "foreground");
            }
        }
        if (DBG)
            Log.d(TAG, "setBinderUx pid=" + pid + " enable=" + enable + " count=" + tids.length);
    }

    @Override
    public void setImeRelevant(int uid, int pid, boolean enable) {
        if (enable) {
            int[] uiCfg = uclampForRole(ROLE_UI);
            applyUclampWithStuneGroup(pid, uiCfg[0], uiCfg[1], "foreground");
            int[] binderTids = discoverThreadsByPrefix(pid, PREFIX_BINDER);
            int[] bpCfg = uclampForRole(ROLE_BINDER_POOL);
            for (int tid : binderTids) {
                applyUclampWithStuneGroup(tid, bpCfg[0], bpCfg[1], "foreground");
            }
            if (DBG)
                Log.d(TAG, "setImeRelevant pid=" + pid + " enabled binder=" + binderTids.length);
        } else {
            applyUclampWithStuneGroup(pid, RESET_UCLAMP_MIN, RESET_UCLAMP_MAX, "foreground");
            for (int tid : discoverThreadsByPrefix(pid, PREFIX_BINDER)) {
                applyUclampWithStuneGroup(tid, RESET_UCLAMP_MIN, RESET_UCLAMP_MAX, "foreground");
            }
        }
    }

    public static int[] discoverThreadsByPrefix(int pid, String namePrefix) {
        synchronized (sThreadCaches) {
            long now = SystemClock.uptimeMillis();
            ThreadCache cache = sThreadCaches.get(pid);
            if (cache != null && (now - cache.timestamp) < CACHE_EXPIRY_MS) {
                int[] cached = cache.prefixToTids.get(namePrefix);
                if (cached != null) return cached;
            } else {
                cache = new ThreadCache();
                sThreadCaches.put(pid, cache);
                if (sThreadCaches.size() > 10) sThreadCaches.removeAt(0);
            }

            final String taskPath = "/proc/" + pid + "/task";
            final String[] tasks = new File(taskPath).list();
            if (tasks == null) return EMPTY_TIDS;

            final IntArray[] scanResult = new IntArray[THREAD_PREFIXES.length];
            final byte[] commBuffer = new byte[64];
            for (String taskName : tasks) {
                final int tid;
                try {
                    tid = Integer.parseInt(taskName);
                } catch (NumberFormatException e) {
                    continue;
                }
                final int nameLen = readThreadName(taskPath + '/' + taskName + "/comm",
                        commBuffer);
                if (nameLen <= 0) continue;
                for (int i = 0; i < THREAD_PREFIXES.length; i++) {
                    if (startsWith(commBuffer, nameLen, THREAD_PREFIXES[i])) {
                        if (scanResult[i] == null) {
                            scanResult[i] = new IntArray();
                        }
                        scanResult[i].add(tid);
                    }
                }
            }

            for (int i = 0; i < THREAD_PREFIXES.length; i++) {
                final IntArray tids = scanResult[i];
                cache.prefixToTids.put(THREAD_PREFIXES[i],
                        tids != null ? tids.toArray() : EMPTY_TIDS);
            }

            int[] result = cache.prefixToTids.get(namePrefix);
            return result != null ? result : EMPTY_TIDS;
        }
    }

    private static int readThreadName(String commPath, byte[] buffer) {
        try (FileInputStream in = new FileInputStream(commPath)) {
            int len = in.read(buffer, 0, buffer.length);
            if (len <= 0) return 0;
            if (buffer[len - 1] == '\n') len--;
            return len;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static boolean startsWith(byte[] name, int nameLen, String prefix) {
        final int prefixLen = prefix.length();
        if (nameLen < prefixLen) return false;
        for (int i = 0; i < prefixLen; i++) {
            if (name[i] != (byte) prefix.charAt(i)) return false;
        }
        return true;
    }

    private static int[] nonNullTids(int[] tids) {
        return tids != null ? tids : EMPTY_TIDS;
    }

    private static boolean sameTids(int[] first, int[] second) {
        first = nonNullTids(first);
        second = nonNullTids(second);
        if (first.length != second.length) {
            return false;
        }
        for (int i = 0; i < first.length; i++) {
            if (first[i] != second[i]) {
                return false;
            }
        }
        return true;
    }

    private static int[] appendTid(int[] tids, int tid) {
        tids = nonNullTids(tids);
        for (int existing : tids) {
            if (existing == tid) {
                return tids;
            }
        }
        int[] result = new int[tids.length + 1];
        System.arraycopy(tids, 0, result, 0, tids.length);
        result[tids.length] = tid;
        return result;
    }

    private static int[] removeTid(int[] tids, int tid) {
        tids = nonNullTids(tids);
        int index = -1;
        for (int i = 0; i < tids.length; i++) {
            if (tids[i] == tid) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return tids;
        }
        if (tids.length == 1) {
            return EMPTY_TIDS;
        }
        int[] result = new int[tids.length - 1];
        System.arraycopy(tids, 0, result, 0, index);
        System.arraycopy(tids, index + 1, result, index, tids.length - index - 1);
        return result;
    }

    private static void fixGameThreadAffinity(String pkgName, int pid) {
        if (pkgName == null || pid <= 0) return;
        final File taskDir = new File("/proc/" + pid + "/task");
        final File[] subdirs = taskDir.listFiles();
        if (subdirs == null) return;
        for (File d : subdirs) {
            final int tid;
            try {
                tid = Integer.parseInt(d.getName());
            } catch (NumberFormatException e) {
                continue;
            }
            final File commFile = new File(d, "comm");
            if (!commFile.canRead()) continue;
            try {
                final String name = FileUtils.readTextFile(commFile, 64, null).trim();
                switch (name) {
                    case "UnityMain":
                    case "UnityGfxDeviceW":
                        Process.setThreadAffinity(tid, Process.AFFINITY_ALL);
                        break;
                    case "Thread":
                        Process.setThreadAffinity(tid, Process.AFFINITY_LITTLE);
                        break;
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean isGamePackage(ApplicationInfo ai, String pkgName) {
        if (pkgName == null || pkgName.startsWith("android.")
                || pkgName.startsWith("com.android.")) {
            return false;
        }
        return AxExtServiceFactory.getAxBurstEngine().perfGetFeedback(ai, pkgName) == AxBoostFwk.WORKLOAD_GAME;
    }

    private static void applyUclamp(int tid, int min, int max) {
        if (tid <= 0) return;
        final long packed = (((long) min) << 32) | (max & 0xffffffffL);
        synchronized (sAppliedUclamp) {
            if (sAppliedUclamp.get(tid, Long.MIN_VALUE) == packed) {
                return;
            }
        }
        if (sUclampSupport >= 0) {
            try {
                int result = Process.setThreadUtilClamp(tid, min, max);
                if (result == 0) {
                    sUclampSupport = 1;
                    synchronized (sAppliedUclamp) {
                        sAppliedUclamp.put(tid, packed);
                    }
                    return;
                }
                if (isUclampUnsupported(result)) {
                    sUclampSupport = -1;
                } else {
                    synchronized (sAppliedUclamp) {
                        sAppliedUclamp.delete(tid);
                    }
                    if (DBG) Log.w(TAG, "setThreadUtilClamp failed tid=" + tid + " rc=" + result);
                    return;
                }
            } catch (Exception e) {
                synchronized (sAppliedUclamp) {
                    sAppliedUclamp.delete(tid);
                }
                if (DBG) Log.w(TAG, "setThreadUtilClamp failed tid=" + tid, e);
                return;
            }
        }
        if (useStuneFallback()) {
            synchronized (sAppliedUclamp) {
                sAppliedUclamp.put(tid, packed);
            }
            return;
        }
        synchronized (sAppliedUclamp) {
            sAppliedUclamp.delete(tid);
        }
    }

    private static boolean isUclampUnsupported(int result) {
        return result == -OsConstants.ENOSYS
                || result == -OsConstants.EOPNOTSUPP
                || result == -OsConstants.EINVAL;
    }

    private static boolean useStuneFallback() {
        int cached = sUseStune;
        if (cached >= 0) return cached == 1;
        boolean use = !new File("/dev/cpuctl/top-app/cpu.uclamp.min").exists()
                && new File("/dev/stune/top-app/tasks").exists();
        sUseStune = use ? 1 : 0;
        return use;
    }

    private static int[] uclampForRole(int role) {
        String name = roleName(role);
        if (name != null) {
            int[] xml = AxPerfConfig.getUxThreadUclamp(name);
            if (xml != null) return xml;
        }
        switch (role) {
            case ROLE_RT:
                return new int[] {DEFAULT_RT_MIN, DEFAULT_RT_MAX};
            case ROLE_UI:
                return new int[] {DEFAULT_UI_MIN, DEFAULT_UI_MAX};
            case ROLE_RENDER:
                return new int[] {DEFAULT_RENDER_MIN, DEFAULT_RENDER_MAX};
            case ROLE_GL:
                return new int[] {DEFAULT_GL_MIN, DEFAULT_GL_MAX};
            case ROLE_HWUI_TASK:
                return new int[] {DEFAULT_HWUI_TASK_MIN, DEFAULT_HWUI_TASK_MAX};
            case ROLE_BINDER_POOL:
                return new int[] {DEFAULT_BINDER_POOL_MIN, DEFAULT_BINDER_POOL_MAX};
            default:
                return new int[] {RESET_UCLAMP_MIN, RESET_UCLAMP_MAX};
        }
    }

    private static String roleName(int role) {
        switch (role) {
            case ROLE_RT:
                return "rt";
            case ROLE_UI:
                return "ui";
            case ROLE_RENDER:
                return "render";
            case ROLE_GL:
                return "gl";
            case ROLE_HWUI_TASK:
                return "hwui_task";
            case ROLE_BINDER_POOL:
                return "binder_pool";
            default:
                return null;
        }
    }

    public enum UiRtLevel {
        NONE(0), RR(1), FIFO(2);
        public final int value;
        UiRtLevel(int v) { value = v; }
        public static UiRtLevel fromValue(int v) {
            for (UiRtLevel l : values()) if (l.value == v) return l;
            return NONE;
        }
    }

    private volatile int mCpuLoadTier = 0;
    private final Object mCpuHighLoadLock = new Object();

    public boolean useUiBoost() {
        return mCpuLoadTier > 0;
    }

    public UiRtLevel getUiRtLevel() {
        return UiRtLevel.fromValue(mCpuLoadTier);
    }

    private void setCpuLoadTier(int tier) {
        synchronized (mCpuHighLoadLock) {
            mCpuLoadTier = tier;
        }
        AxBoostManager.setUiBoostActive(tier > 0);
        AxExtServiceFactory.getAxBackgroundManager().onCpuLoadTierChanged(tier);
    }

    public class CpuLoadMonitor {
        private CpuMonitorInternal mCpuMonitorService = null;
        private int mCpuAvalabilityPercentThreshold = 60;
        private int mCpuSet = CPUSET_ALL;
        public class CpuAvailabilityCallback implements CpuMonitorInternal.CpuAvailabilityCallback {
            @Override
            public void onAvailabilityChanged(CpuAvailabilityInfo info) {
                int currentCpuAvalabilityPercent = info.latestAvgAvailabilityPercent;
                int tier;
                if (currentCpuAvalabilityPercent >= 70) {
                    tier = 0;
                } else if (currentCpuAvalabilityPercent >= 40) {
                    tier = 1;
                } else {
                    tier = 2;
                }
                setCpuLoadTier(tier);
            }

            @Override
            public void onMonitoringIntervalChanged(long intervalMilliseconds){
                Slog.d(TAG, "CPU load monitor interval convert to "+ intervalMilliseconds);
            }
        }

        public void startCpuLoadMonitorOnce() {
            if (mCpuMonitorService != null) {
                return;
            }
            CpuAvailabilityCallback callback = new CpuAvailabilityCallback();
            CpuAvailabilityMonitoringConfig config =
                new CpuAvailabilityMonitoringConfig.Builder(mCpuSet).addThreshold(
                        mCpuAvalabilityPercentThreshold).build();
            mCpuMonitorService = LocalServices.getService(CpuMonitorInternal.class);
            if (mCpuMonitorService != null) {
                mCpuMonitorService.addCpuAvailabilityCallback(
                            null, config, callback);
                Slog.d(TAG, "CPU load monitor started");
            }
        }
    }
}
