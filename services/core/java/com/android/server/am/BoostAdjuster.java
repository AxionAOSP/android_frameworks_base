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
import android.database.ContentObserver;
import android.net.Uri;
import android.os.*;
import android.os.Process;
import android.util.Slog;
import android.provider.Settings;

import com.android.server.NtServiceInjector;
import com.android.server.UiThread;

import java.util.ArrayList;
import java.util.HashMap;

public class BoostAdjuster implements IBoostAdjuster {

    private static final String TAG = "BoostAdjuster";

    public static final int THREAD_GROUP_NT_FOREGROUND = 10;
    public static final int THREAD_GROUP_RESTRICTED = Process.THREAD_GROUP_RESTRICTED;

    private final ActivityManagerService mAm;
    private final HandlerThread mHandlerThread;
    private final BoostHandler mHandler;
    private final UiHandler mUiHandler;
    private BoostConfig mConfig;
    private ConfigObserver mConfigObserver;

    private final HashMap<Integer, Boolean> sBoostedPids = new HashMap<>();
    private final HashMap<String, Boolean> sPerfMap = new HashMap<>();
    private boolean mPerfMode = false;
    private boolean mBoostingSf = false;
    private boolean mInputBoosting = false;
    private boolean mSystemReady = false;

    public static final ArrayList<String> sAppWhiteList = new ArrayList<>();
    public static final ArrayList<String> sAppPerfList = new ArrayList<>();
    public static final ArrayList<String> CAMERA_APPS = new ArrayList<>();

    static {
        sAppWhiteList.add("com.google.android.providers.media.module");
        sAppWhiteList.add("android.process.media");
        sAppWhiteList.add("android.os.cts");
        sAppPerfList.add("com.android.systemui");
        sAppPerfList.add("com.android.launcher3");
        CAMERA_APPS.add("com.google.android.GoogleCamera");
        CAMERA_APPS.add("org.lineageos.aperture");
        CAMERA_APPS.add("com.oplus.camera");
    }

    public BoostAdjuster() {
        mAm = NtServiceInjector.getAm();
        mHandlerThread = new HandlerThread("BoostAdjusterThread");
        mHandlerThread.start();
        mHandler = new BoostHandler(mHandlerThread.getLooper(), this);
        mUiHandler = new UiHandler(UiThread.getHandler().getLooper(), this);
    }
    
    public void systemReady() {
        mSystemReady = true;
        mConfig = new BoostConfig();
        mConfigObserver = new ConfigObserver(new Handler(), NtServiceInjector.getCtx());
    }

    public void write(String path, String value) {
        mHandler.sendMessage(mHandler.obtainMessage(BoostHandler.MSG_WRITE, new WriteParams(path, value)));
    }

    public void adjustCpusetCpus(String cgroup, long durationMillis) {
        mHandler.sendMessage(mHandler.obtainMessage(BoostHandler.MSG_ADJUST_CPUSET,
                new AdjustCpusetParams(cgroup, durationMillis)));
    }

    public void animationBoost(int pid, boolean enabled) {
        mHandler.sendMessage(mHandler.obtainMessage(BoostHandler.MSG_ANIMATION_BOOST, enabled ? 1 : 0, pid));
    }

    public void setThreadAffinity(int pid, int affinity) {
        mHandler.sendMessage(mHandler.obtainMessage(BoostHandler.MSG_SET_THREAD_AFFINITY, affinity, pid));
    }

    public void setPerformanceMode(boolean enabled, String reason) {
        synchronized (sPerfMap) {
            if (sPerfMap.containsKey(reason) && sPerfMap.get(reason) == enabled) return;
            mHandler.removeMessages(BoostHandler.MSG_SET_PERFORMANCE_MODE);
            sPerfMap.put(reason, enabled);
            boolean boost = sPerfMap.containsValue(true);
            mHandler.sendMessage(
                mHandler.obtainMessage(BoostHandler.MSG_SET_PERFORMANCE_MODE, boost ? 1 : 0, 0)
            );
        }
    }

    public void boostHint(String reason, long duration) {
        mHandler.sendMessage(mHandler.obtainMessage(BoostHandler.MSG_BOOST_HINT, (int) duration, 0));
    }

    public void inputBoost(long durationMillis) {
        mUiHandler.removeMessages(UiHandler.MSG_DISABLE_INPUT_BOOST);
        mUiHandler.sendMessage(mUiHandler.obtainMessage(UiHandler.MSG_INPUT_BOOST, (int) durationMillis, 0));
        mUiHandler.sendMessageDelayed(
                mUiHandler.obtainMessage(UiHandler.MSG_DISABLE_INPUT_BOOST),
                durationMillis
        );
    }

    public void onWakefulnessChanged(boolean awake) {
        mHandler.sendMessage(mHandler.obtainMessage(BoostHandler.MSG_ON_WAKEFULNESS_CHANGED, awake ? 1 : 0, 0));
    }

    private void adjustCpusetInternal(String cgroup, long durationMillis) {
        if (cgroup == null) return;
        adjustCpuset(cgroup, true);
        mHandler.postDelayed(() -> adjustCpuset(cgroup, false), durationMillis);
    }

    private void adjustCpuset(String cgroup, boolean boost) {
        String cpuset;
        switch (cgroup) {
            case "nt_foreground":
                cpuset = boost ? mConfig.getData().allCores() : BoostConfig.FG_LIMIT;
                break;
            case "background":
                cpuset = boost ? BoostConfig.BG_CPU : BoostConfig.BG_LIMIT;
                break;
            default:
                return;
        }
        mConfig.writeInternal(mConfig.cpuPath(cgroup), cpuset);
    }

    private void animationBoostInternal(int pid, boolean enabled) {
        ProcessRecord proc;
        synchronized (mAm.mPidsSelfLocked) {
            proc = mAm.mPidsSelfLocked.get(pid);
        }
        if (proc == null) return;
        final int renderTid = proc.getRenderThreadTid();
        final int prio = Process.getThreadPriority(pid);
        try {
            if (enabled) {
                final int policy =  Process.SCHED_RR | Process.SCHED_RESET_ON_FORK;
                Process.setThreadScheduler(pid, policy, 1);
                if (renderTid > 0) Process.setThreadScheduler(renderTid, policy, 1);
            } else {
                Process.setThreadScheduler(pid, 0, 0);
                Process.setThreadPriority(prio);
                Process.setThreadScheduler(renderTid, 0, 0);
            }
        } catch (Exception ignored) {}
        boostPid(pid, enabled);
        boostSF(enabled);
    }

    public void setThreadAffinityInternal(int pid, int affinity) {
        if (affinity == 0) {
            Process.setThreadGroupAndCpuset(pid, Process.THREAD_GROUP_TOP_APP);
        } else {
            Process.setThreadGroupAndCpuset(pid, Process.THREAD_GROUP_FOREGROUND);
        }
        Process.setThreadAffinity(pid, affinity);
    }

    private void setPerformanceModeInternal(boolean enabled) {
        if (!mSystemReady) return;
        if (!mConfig.getData().cpuBoost() && !mConfig.getData().inputBoost()) {
            if (mPerfMode) setPerfMode(false);
            return;
        }
        if (mPerfMode == enabled) return;
        setPerfMode(enabled);
    }

    private void setPerfMode(boolean enabled) {
        BoostConfig.BoostData data = mConfig.getData();
        String littleFreq = enabled ? data.freqBoost() : data.userMinLittle();
        String bigFreq = enabled && data.bigCoreBoost() ? data.freqBoostBig() : data.userMinBig();
        String primeFreq = enabled ? data.freqBoostPrime() : data.userMinPrime();
        mConfig.writeInternal(data.littleMin(), littleFreq);
        mConfig.writeInternal(data.bigMin(), bigFreq);
        if (data.hasPrime()) {
            mConfig.writeInternal(data.primeMin(), primeFreq);
        }
        mPerfMode = enabled;
    }

    private void inputBoostInternal(boolean enabled) {
        if (mInputBoosting == enabled) return;
        if (mConfig.getData().inputBoost()) {
            setPerformanceModeInternal(enabled);
        }
        adjustCpuset("background", enabled);
        adjustCpuset("nt_foreground", enabled);
        SystemProperties.set("dalvik.vm.dex2oat-threads", enabled ? "1" : "2");
        mInputBoosting = enabled;
    }

    private void boostSF(boolean enable) {
        if (!mConfig.getData().boostSf()) enable = false;
        if (mBoostingSf == enable) return;
        IBinder sfBinder = ServiceManager.getService("SurfaceFlinger");
        if (sfBinder != null) {
            Parcel data = Parcel.obtain();
            try {
                data.writeInterfaceToken("android.ui.ISurfaceComposer");
                data.writeInt(enable ? 1 : 0);
                sfBinder.transact(1048, data, null, 0);
            } catch (Exception e) {
                logger("boostSF transact failed: " + e);
            } finally {
                data.recycle();
            }
        }

        String val = enable ? String.valueOf(BoostConfig.SF_UC_MIN_BOOST) : "0";
        mConfig.writeInternal(mConfig.DISPLAY_UC_MIN, val);
        mConfig.writeInternal(mConfig.DISPLAY_UC_MAX, "100");
        mBoostingSf = enable;
    }

    private void boostPid(int pid, boolean enable) {
        BoostConfig.BoostData data = mConfig.getData();
        if (sBoostedPids.containsKey(pid) && sBoostedPids.get(pid) == enable) return;
        String boostCores = data.bigCores() + (data.hasPrime() ? ("," + data.primeCores()) : "");
        String boostVal = enable ? "100" : "0";
        mConfig.writeInternal(mConfig.RESTRICTED_UC_MIN, boostVal);
        mConfig.writeInternal(mConfig.RESTRICTED_UC_MAX, "100");
        mConfig.writeInternal(mConfig.CPU_RESTRICTED, enable ? boostCores : data.allCores());
        mConfig.writeInternal(enable ? mConfig.RESTRICTED_PROCS : mConfig.ROOT_PROCS, String.valueOf(pid));
        sBoostedPids.put(pid, enable);
    }

    private void restrictBackground(boolean limit) {
        mConfig.writeInternal(mConfig.CPU_BG, limit ? BoostConfig.BG_LIMIT : BoostConfig.FG_LIMIT);
        mConfig.writeInternal(mConfig.CPU_NT_FG, limit ? BoostConfig.BG_LIMIT : BoostConfig.BG_CPU);
    }

    private void onWakefulnessChangedInternal(boolean awake) {
        restrictBackground(!awake);
    }

    private static boolean needsControl(ProcessRecord app, boolean verifyGroup, int oldScheduleGroup) {
        if (verifyGroup && oldScheduleGroup == ProcessList.SCHED_GROUP_TOP_APP && app.hasActivities()) {
            logger("previous schedule group is top, not need limit!");
            return false;
        }
        if (app.uid % 100000 < 10000 || isInPerfList(app.processName) || isInWhiteList(app.processName)) {
            logger("system app not need limit!");
            return false;
        }
        if (app.getHostingRecord() == null || app.getHostingRecord().isTopApp()) {
            return false;
        }
        logger("process : " + app.processName + " is not top!");
        return true;
    }

    public static boolean isForegroundNeedSelfControll(int oldScheduleGroup, ProcessRecord app) {
        return needsControl(app, true, oldScheduleGroup);
    }

    public static boolean isRestrictedNeedSelfControll(ProcessRecord app) {
        return needsControl(app, false, -1);
    }

    public static boolean isInWhiteList(String processName) {
        return processName != null && sAppWhiteList.contains(processName);
    }

    public static boolean isInPerfList(String processName) {
        return processName != null && (sAppPerfList.contains(processName) || isCamera(processName));
    }

    public static boolean isCamera(String processName) {
        return processName != null && CAMERA_APPS.contains(processName);
    }

    public static void boostCamera(boolean boost) {
        SystemProperties.set(BoostConfig.SCALING_GOV, boost ? BoostConfig.PERF_GOV : BoostConfig.DEFAULT_GOV);
    }

    public static boolean isBoosted() {
        return BoostConfig.PERF_GOV.equals(BoostConfig.scalingGov());
    }

    private static class BoostHandler extends Handler {
        static final int MSG_WRITE = 1;
        static final int MSG_ADJUST_CPUSET = 2;
        static final int MSG_DISABLE_BOOST_HINT = 3;
        static final int MSG_ANIMATION_BOOST = 4;
        static final int MSG_SET_THREAD_AFFINITY = 5;
        static final int MSG_SET_PERFORMANCE_MODE = 6;
        static final int MSG_BOOST_HINT = 7;
        static final int MSG_ON_WAKEFULNESS_CHANGED = 8;

        private final BoostAdjuster mAdjuster;

        BoostHandler(Looper looper, BoostAdjuster adjuster) {
            super(looper);
            mAdjuster = adjuster;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_WRITE:
                    WriteParams wp = (WriteParams) msg.obj;
                    mAdjuster.mConfig.writeInternal(wp.path, wp.value);
                    break;
                case MSG_ADJUST_CPUSET:
                    AdjustCpusetParams cp = (AdjustCpusetParams) msg.obj;
                    mAdjuster.adjustCpusetInternal(cp.cgroup, cp.durationMillis);
                    break;
                case MSG_DISABLE_BOOST_HINT:
                    mAdjuster.setPerformanceModeInternal(false);
                    break;
                case MSG_ANIMATION_BOOST:
                    mAdjuster.animationBoostInternal(msg.arg2, msg.arg1 == 1);
                    break;
                case MSG_SET_THREAD_AFFINITY:
                    mAdjuster.setThreadAffinityInternal(msg.arg2, msg.arg1);
                    break;
                case MSG_SET_PERFORMANCE_MODE:
                    mAdjuster.setPerformanceModeInternal(msg.arg1 == 1);
                    break;
                case MSG_BOOST_HINT:
                    mAdjuster.setPerformanceModeInternal(true);
                    sendEmptyMessageDelayed(MSG_DISABLE_BOOST_HINT, msg.arg1);
                    break;
                case MSG_ON_WAKEFULNESS_CHANGED:
                    mAdjuster.onWakefulnessChangedInternal(msg.arg1 == 1);
                    break;
                default:
                    logger("Unknown message: " + msg.what);
            }
        }
    }

    private static class UiHandler extends Handler {
        static final int MSG_INPUT_BOOST = 1;
        static final int MSG_DISABLE_INPUT_BOOST = 2;

        private final BoostAdjuster mAdjuster;

        UiHandler(Looper looper, BoostAdjuster adjuster) {
            super(looper);
            mAdjuster = adjuster;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_INPUT_BOOST:
                    mAdjuster.inputBoostInternal(true);
                    break;
                case MSG_DISABLE_INPUT_BOOST:
                    mAdjuster.inputBoostInternal(false);
                    break;
                default:
                    logger("Unknown UI message: " + msg.what);
            }
        }
    }

    private static class WriteParams {
        final String path;
        final String value;
        WriteParams(String path, String value) { this.path = path; this.value = value; }
    }

    private static class AdjustCpusetParams {
        final String cgroup;
        final long durationMillis;
        AdjustCpusetParams(String cgroup, long durationMillis) { this.cgroup = cgroup; this.durationMillis = durationMillis; }
    }

    private final class ConfigObserver extends ContentObserver {
        private final Context mContext;

        ConfigObserver(Handler handler, Context context) {
            super(handler);
            mContext = context;
            register();
            onChange(true);
        }

        void register() {
            registerKey("axion_cpu_boost");
            registerKey("axion_big_core_boost");
            registerKey("axion_prime_core_boost");
            registerKey("axion_sf_boost");
            registerKey("axion_touch_boost");
            registerKey("axion_min_freq_boost");
            registerKey("axion_min_freq_big_boost");
            registerKey("axion_min_freq_prime_boost");
            registerKey("axion_min_freq");
            registerKey("axion_min_freq_big");
            registerKey("axion_min_freq_prime");
            registerKey("axion_max_freq");
            registerKey("axion_max_freq_big");
            registerKey("axion_max_freq_prime");
        }

        private void registerKey(String key) {
            Uri uri = Settings.Secure.getUriFor(key);
            if (uri != null) {
                mContext.getContentResolver().registerContentObserver(uri, false, this);
            }
        }

        @Override
        public void onChange(boolean selfChange) {
            boolean cpuBoost = intSetting("axion_cpu_boost", 1) == 1;
            boolean bigCoreBoost = intSetting("axion_big_core_boost", 0) == 1;
            boolean primeCoreBoost = intSetting("axion_prime_core_boost", 0) == 1;
            boolean sfBoost = intSetting("axion_sf_boost", 1) == 1;
            boolean inputBoost = intSetting("axion_touch_boost", 0) == 1;
            
            int minFreqBoostLittle = intSetting("axion_min_freq_boost", 1000000);
            int minFreqBoostBig = intSetting("axion_min_freq_big_boost", 1000000);
            int minFreqBoostPrime = intSetting("axion_min_freq_prime_boost", 1000000);

            int minFreqLittle = intSetting("axion_min_freq", 0);
            int minFreqBig = intSetting("axion_min_freq_big", 0);
            int minFreqPrime = intSetting("axion_min_freq_prime", 0);

            int maxFreqLittle = intSetting("axion_max_freq", 999999);
            int maxFreqBig = intSetting("axion_max_freq_big", 999999);
            int maxFreqPrime = intSetting("axion_max_freq_prime", 999999);

            mConfig.updateSettings(cpuBoost, bigCoreBoost, primeCoreBoost, sfBoost, inputBoost,
                                   minFreqBoostLittle, minFreqBoostBig, minFreqBoostPrime,
                                   minFreqLittle, minFreqBig, minFreqPrime,
                                   maxFreqLittle, maxFreqBig, maxFreqPrime);

            applyConfig();
        }
        
        private int intSetting(String key, int def) {
            return Settings.Secure.getIntForUser(mContext.getContentResolver(), key, def, UserHandle.USER_CURRENT);
        }
        
        void applyConfig() {
            BoostConfig.BoostData data = mConfig.getData();
            mConfig.writeInternal(data.littleMin(), data.userMinLittle());
            mConfig.writeInternal(data.bigMin(), data.userMinBig());
            if (data.hasPrime()) {
                mConfig.writeInternal(data.primeMin(), data.userMinPrime());
            }
            mConfig.writeInternal(data.littleMax(), data.userMaxLittle());
            mConfig.writeInternal(data.bigMax(), data.userMaxBig());
            if (data.hasPrime()) {
                mConfig.writeInternal(data.primeMax(), data.userMaxPrime());
            }
        }
    }

    private static void logger(String msg) {
        if (SystemProperties.getBoolean("persist.sys.ax_boost_debug", false)) Slog.d(TAG, msg);
    }
}
