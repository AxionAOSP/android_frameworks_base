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
package com.android.server.am;

import android.content.Context;
import android.graphics.Point;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemProperties;
import android.util.Slog;

import com.android.server.am.CachedAppOptimizer;
import com.android.server.wm.WindowManagerService;
import com.android.server.NtServiceInjector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.android.server.OnlineConfigObserver;
import org.json.JSONArray;
import org.json.JSONObject;

public class AxMemoryManagerImpl implements IAxMemoryManager {

    private static final int MSG_TUNE_EXTRA_FREE = 0;
    private static final int MSG_BOOST_CAMERA_START_WARM = 1;
    private static final int MSG_BOOST_CAMERA_RESET_WARM = 2;
    private static final int MSG_RELEASE_MEMORY_SCREEN_ON = 3;
    private static final int MSG_LOAD_PROCESS_MEMORY = 4;
    private static final int MSG_CAMERA_MEMORY_RELEASE = 5;
    private static final int MSG_BOOST_CAMERA_COLD_RESET = 6;

    private static final String TAG = "AxMemoryManager";
    private static final boolean DEBUG = SystemProperties.getBoolean("persist.sys.nmm.debug", false);
    
    private volatile JSONObject mCurrentConfig = null;

    private static final List<String> DEFAULT_RELEASE_WHITELIST = List.of(
                "com.google.android.googlequicksearchbox:search", 
                "com.google.android.gms", 
                "com.android.chrome",
                "com.android.axion.widgets",
                "com.android.edge.bar"
        );

    private final List<String> mReleaseProcessWhiteList = new ArrayList<>(DEFAULT_RELEASE_WHITELIST);

    private Context mContext;
    private ActivityManagerService mService;
    private WindowManagerService mWindowService;
    private HandlerThread mHandlerThread;
    private Handler mHandler;

    private volatile boolean mIsBoostingCameraCold = false;
    private volatile boolean mIsBoostingCameraWarm = false;

    private long mBoostCameraDuration = 5000L;
    private int mKillProcessCount = 20;
    private int mKillProcessCountWarmStart = 5;
    private long mLastScreenOnTime = 0L;
    private long mReleaseMemoryDuration = 3600000L;
    private float mWeight = 10f;
    private int mReleaseMemoryKillCount = 5;
    private int mReleaseAdj = 900;

    private final ArrayList<String> mWhiteListForCameraStart = new ArrayList<>();
    private volatile boolean mSystemReady = false;

    public static final class ProcessInfo {
        final int pid;
        final int adj;
        final long rss;
        final String name;
        float score;

        ProcessInfo(int pid, int adj, long rss, String name) {
            this.pid = pid;
            this.adj = adj;
            this.rss = rss;
            this.name = name;
            this.score = 0f;
        }
    }

    private static final Comparator<ProcessInfo> BY_RSS = Comparator.comparingLong(pi -> pi.rss);
    private static final Comparator<ProcessInfo> BY_ADJ = Comparator.comparingInt(pi -> pi.adj);
    private static final Comparator<ProcessInfo> BY_SCORE = Comparator.comparingDouble(pi -> pi.score);

    class MemoryManagerHandler extends Handler {
        MemoryManagerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            if (message == null) return;

            switch (message.what) {
                case MSG_TUNE_EXTRA_FREE:
                    tuneExtraFreeInternal();
                    break;
                case MSG_BOOST_CAMERA_START_WARM:
                    SystemProperties.set("persist.sys.nmm.boost.camera", "1");
                    releaseMemory(mReleaseAdj, mKillProcessCountWarmStart, true, mWhiteListForCameraStart);
                    break;
                case MSG_BOOST_CAMERA_RESET_WARM:
                    mIsBoostingCameraWarm = false;
                    if (DEBUG) Slog.d(TAG, "mIsBoostingCameraWarm: " + mIsBoostingCameraWarm);
                    break;
                case MSG_RELEASE_MEMORY_SCREEN_ON:
                    if (DEBUG) Slog.d(TAG, "Start to kill process to release memory on screen on");
                    SystemProperties.set("persist.sys.nmm.boost.camera", "1");
                    releaseMemory(mReleaseAdj, mReleaseMemoryKillCount, false, mReleaseProcessWhiteList);
                    break;
                case MSG_LOAD_PROCESS_MEMORY:
                    String loadPkg = message.getData() != null
                            ? message.getData().getString("packageName", "")
                            : "";
                    if (!loadPkg.isEmpty()) startLoadProcessMemory(loadPkg);
                    break;
                case MSG_CAMERA_MEMORY_RELEASE:
                    SystemProperties.set("persist.sys.nmm.boost.camera", "1");
                    releaseMemory(mReleaseAdj, mKillProcessCount, true, mWhiteListForCameraStart);
                    break;
                case MSG_BOOST_CAMERA_COLD_RESET:
                    mIsBoostingCameraCold = false;
                    if (DEBUG) Slog.d(TAG, "mIsBoostingCameraCold: " + mIsBoostingCameraCold);
                    break;
                default:
                    if (DEBUG) Slog.d(TAG, "Unhandled msg: " + message.what);
            }
        }
    }

    public AxMemoryManagerImpl() {
        Slog.d(TAG, "init AxMemoryManagerImpl");
    }

    private void releaseMemory(int adjThreshold, int killLimit, boolean allowUiKill, List<String> whitelist) {
        if (killLimit <= 0) return;
        if (mService == null) return;

        try {
            final List<ProcessRecord> lruSnapshot;
            synchronized (mService.mProcLock) {
                List<?> raw = mService.mProcessList.getLruProcessesLOSP();
                lruSnapshot = new ArrayList<>(raw.size());
                for (Object o : raw) {
                    if (o instanceof ProcessRecord) lruSnapshot.add((ProcessRecord) o);
                }
            }

            ArrayList<ProcessInfo> candidates = new ArrayList<>(Math.min(lruSnapshot.size(), killLimit * 4 + 10));

            for (ProcessRecord pr : lruSnapshot) {
                if (pr == null) continue;
                final int adj = pr.getSetAdj();
                if (adj < adjThreshold) continue;
                if (!allowUiKill && pr.hasActivities()) {
                    if (DEBUG) Slog.d(TAG, "Don't kill process has ui: " + pr.processName);
                    continue;
                }
                if (whitelist != null && whitelist.contains(pr.processName)) {
                    if (DEBUG) Slog.d(TAG, "skip killing whiteListProcess:" + pr.processName);
                    continue;
                }
                long rss = 0L;
                try {
                    rss = pr.mProfile != null ? pr.mProfile.getLastRss() : 0L;
                } catch (Exception e) {
                    if (DEBUG) Slog.d(TAG, "failed to read rss for " + pr.processName, e);
                }
                candidates.add(new ProcessInfo(pr.getPid(), adj, rss, pr.processName));
            }

            if (candidates.isEmpty()) return;

            final float adjWeight = allowUiKill ? 1.0f : (mWeight / 10.0f);
            final float rssWeight = 1.0f - adjWeight;
            if (DEBUG) Slog.d(TAG, "adjWeight=" + adjWeight + ", rssWeight=" + rssWeight + ", candidates=" + candidates.size());

            if (rssWeight != 0.0f) {
                Collections.sort(candidates, BY_RSS);
                applyGroupOffsets(candidates, rssWeight, /*useRss=*/true);
            }

            if (adjWeight != 0.0f) {
                Collections.sort(candidates, BY_ADJ);
                applyGroupOffsets(candidates, adjWeight, /*useRss=*/false);
            }

            Collections.sort(candidates, BY_SCORE);

            int killed = 0;
            for (ProcessInfo pi : candidates) {
                try {
                    Process.killProcess(pi.pid);
                    killed++;
                    Slog.d(TAG, "kill proc " + pi.name + "[" + pi.pid + "]: adj:" + pi.adj + " rss:" + pi.rss
                            + " to release memory, now killed : " + killed + " processes");
                    if (killed >= killLimit) {
                        if (DEBUG) Slog.d(TAG, "Stop kill process , KillProcessCount " + killLimit);
                        break;
                    }
                } catch (Throwable t) {
                    if (DEBUG) Slog.d(TAG, "Failed to kill pid " + pi.pid + " (" + pi.name + ")", t);
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "releaseMemory exception", e);
        }
    }

    private void applyGroupOffsets(List<ProcessInfo> list, float weight, boolean useRss) {
        if (list == null || list.isEmpty()) return;

        int groupIndex = 0;
        long prevKey = useRss ? list.get(0).rss : list.get(0).adj;

        for (int i = 1; i < list.size(); i++) {
            long key = useRss ? list.get(i).rss : list.get(i).adj;
            if (key != prevKey) {
                groupIndex++;
                prevKey = key;
            }
            list.get(i).score += groupIndex * weight;
        }
    }

    private void startLoadProcessMemory(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            if (DEBUG) Slog.d(TAG, "Invalid packageName to load memory");
            return;
        }
        if (DEBUG) Slog.d(TAG, "Start find package uid of " + pkg);

        ProcessRecord prLocked = getProcessRecordLocked(pkg);
        if (prLocked == null) {
            if (DEBUG) Slog.d(TAG, "Couldn't find package uid of " + pkg);
            return;
        }
        if (DEBUG) Slog.d(TAG, "Start to load process memory of " + pkg);

        synchronized (mService.mProcLock) {
            mService.getCachedAppOptimizer().compactApp(
                    prLocked,
                    CachedAppOptimizer.CompactProfile.POPULATE,
                    CachedAppOptimizer.CompactSource.SHELL,
                    true
            );
        }
        if (DEBUG) Slog.d(TAG, "Load process memory of " + pkg + " successfully");
    }

    private void loadProcessMemoryInternal(String packageName) {
        if (mHandler == null) return;
        Bundle bundle = new Bundle();
        bundle.putString("packageName", packageName);
        Message msg = mHandler.obtainMessage(MSG_LOAD_PROCESS_MEMORY);
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        if (DEBUG) Slog.d(TAG, "Send msg to load memory: " + packageName);
    }

    private void loadBoostCamera() {
        final long memorySize = AxUtils.getPhysicalMemory();
        if (memorySize == AxUtils.MEM_12GB) {
            mKillProcessCount = 5;
            mKillProcessCountWarmStart = 5;
        } else {
            mKillProcessCount = 15;
            mKillProcessCountWarmStart = 5;
        }
        if (DEBUG) {
            Slog.d(TAG, "KillProcessCount : " + mKillProcessCount);
            Slog.d(TAG, "KillProcessCountWarmStart : " + mKillProcessCountWarmStart);
        }
    }

    private void tuneExtraFreeInternal() {
        if (mWindowService == null) return;
        Point displaySize = new Point();
        mWindowService.getBaseDisplaySize(0, displaySize);
        final int EXTRA_FREE_FACTOR = 6;
        final int bytesPerPixel = 4;
        int extraFreeKb = ((displaySize.x * displaySize.y) * bytesPerPixel * EXTRA_FREE_FACTOR) / 1024;
        SystemProperties.set("sys.sysctl.extra_free_kbytes", Integer.toString(extraFreeKb));
        if (DEBUG) {
            Slog.d(TAG, "new extra_free_kbytes: " + SystemProperties.getInt("sys.sysctl.extra_free_kbytes", 0));
        }
    }

    private void initThread() {
        HandlerThread handlerThread = new HandlerThread("NothingMemoryManager");
        mHandlerThread = handlerThread;
        handlerThread.start();
        mHandler = new MemoryManagerHandler(mHandlerThread.getLooper());
    }

    private ProcessRecord getProcessRecordLocked(String packageName) {
        Objects.requireNonNull(mService, "Service not initialized");
        synchronized (mService.mProcLock) {
            int currentUserId = mService.getCurrentUserId();
            if (DEBUG) Slog.d(TAG, "Current userId = " + currentUserId);
            int packageUid = mService.getPackageManagerInternal().getPackageUid(packageName, 0L, currentUserId);
            if (DEBUG) Slog.d(TAG, "Current packageUid = " + packageUid);
            return mService.getProcessRecordLocked(packageName, packageUid);
        }
    }

    private void loadReleaseMemoryConfig() {
        final long memorySize = AxUtils.getPhysicalMemory();
        mReleaseMemoryKillCount = (memorySize == AxUtils.MEM_12GB) ? 10 : 20;
        if (DEBUG) Slog.d(TAG, "KillProcessScreenOnCount : " + mReleaseMemoryKillCount);
    }

    public void boostCamera(boolean isColdStart) {
        if (isColdStart) {
            if (mIsBoostingCameraCold) {
                if (DEBUG) Slog.d(TAG, "now is boosting camera cold start, skipped this boost");
            } else {
                mIsBoostingCameraCold = true;
                if (mHandler != null) {
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_CAMERA_MEMORY_RELEASE));
                    mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_BOOST_CAMERA_COLD_RESET), mBoostCameraDuration);
                }
            }
        }

        if (mIsBoostingCameraWarm) {
            if (DEBUG) Slog.d(TAG, "now is boosting camera warm/hot start, skipped this boost");
        } else {
            mIsBoostingCameraWarm = true;
            if (mHandler != null) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_BOOST_CAMERA_START_WARM));
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_BOOST_CAMERA_RESET_WARM), mBoostCameraDuration);
            }
        }
    }

    public void loadProcessMemory(String pkg) {
        if (mSystemReady) loadProcessMemoryInternal(pkg);
    }

    public void releaseMemoryAtScreenOn() {
        if (!mSystemReady || mHandler == null) return;
        long current = System.currentTimeMillis();
        long last = mLastScreenOnTime;
        if (last == 0L || current - last > mReleaseMemoryDuration) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_RELEASE_MEMORY_SCREEN_ON));
            mLastScreenOnTime = current;
        } else if (DEBUG) {
            Slog.d(TAG, "Release memory skipped due to cooldown. last:" + last + " now:" + current);
        }
    }

    public void systemReady() {
        mService = NtServiceInjector.getAm();
        mWindowService = NtServiceInjector.getWm();
        mContext = NtServiceInjector.getCtx();
        initThread();
        loadReleaseMemoryConfig();
        loadBoostCamera();
        tuneExtraFree();
        
        OnlineConfigObserver.addConfigObserver(config -> {
            if (DEBUG) Slog.d(TAG, "Config update received");
            applyConfig(config);
        });
        
        mSystemReady = true;
    }

    public void tuneExtraFree() {
        if (mHandler != null) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_TUNE_EXTRA_FREE));
        } else if (DEBUG) {
            Slog.d(TAG, "Handler not initialized yet for tuneExtraFree");
        }
    }
    
    private void applyConfig(JSONObject config) {
        if (config == null) return;
        
        try {
            JSONObject nmm = config.optJSONObject("AxMemoryManager");
            if (nmm == null) {
                if (DEBUG) Slog.d(TAG, "No AxMemoryManager config found");
                return;
            }
            
            mBoostCameraDuration = nmm.optLong("boostCameraDuration", mBoostCameraDuration);
            mReleaseMemoryDuration = nmm.optLong("releaseMemoryDuration", mReleaseMemoryDuration);
            mWeight = (float) nmm.optDouble("weight", 10.0);
            
            mReleaseAdj = nmm.optInt("releaseAdj", mReleaseAdj);
            
            JSONArray whitelistArray = nmm.optJSONArray("releaseWhitelist");
            if (whitelistArray != null && whitelistArray.length() > 0) {
                mReleaseProcessWhiteList.clear();
                for (int i = 0; i < whitelistArray.length(); i++) {
                    String processName = whitelistArray.optString(i);
                    if (processName != null && !processName.isEmpty()) {
                        mReleaseProcessWhiteList.add(processName);
                    }
                }
                if (DEBUG) Slog.d(TAG, "Updated whitelist with " + mReleaseProcessWhiteList.size() + " entries");
            }
            
            String ramKey = AxUtils.getRamKey();
            
            JSONObject ramConfigs = nmm.optJSONObject("ramConfigs");
            if (ramConfigs != null && ramKey != null) {
                JSONObject ramConfig = ramConfigs.optJSONObject(ramKey);
                if (ramConfig != null) {
                    mKillProcessCount = ramConfig.optInt("killProcessCount", mKillProcessCount);
                    mKillProcessCountWarmStart = ramConfig.optInt("killProcessCountWarmStart", mKillProcessCountWarmStart);
                    mReleaseMemoryKillCount = ramConfig.optInt("releaseMemoryKillCount", mReleaseMemoryKillCount);
                    
                    if (ramConfig.has("releaseAdj")) {
                        mReleaseAdj = ramConfig.optInt("releaseAdj", mReleaseAdj);
                    }
                }
            }
            
            mKillProcessCount = nmm.optInt("killProcessCount", mKillProcessCount);
            mKillProcessCountWarmStart = nmm.optInt("killProcessCountWarmStart", mKillProcessCountWarmStart);
            mReleaseMemoryKillCount = nmm.optInt("releaseMemoryKillCount", mReleaseMemoryKillCount);
            
            if (DEBUG) {
                Slog.d(TAG, "Config applied - RAM:" + ramKey 
                    + " boostDuration:" + mBoostCameraDuration
                    + " killCount:" + mKillProcessCount
                    + " warmStart:" + mKillProcessCountWarmStart
                    + " releaseKill:" + mReleaseMemoryKillCount
                    + " releaseAdj:" + mReleaseAdj
                    + " weight:" + mWeight
                    + " whitelistSize:" + mReleaseProcessWhiteList.size());
            }
            
            mCurrentConfig = config;
        } catch (Exception e) {
            Slog.e(TAG, "Failed to apply config", e);
        }
    }
}
