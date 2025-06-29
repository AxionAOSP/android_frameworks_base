/*
 * Copyright (C) 2025 AxionAOSP Project
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
package com.android.systemui.util;

import android.app.ActivityManager;
import android.os.Process;
import android.os.SystemProperties;
import android.util.Log;

import java.util.Arrays;
import java.util.IntSummaryStatistics;

public class NTCpuBindController {

    private static final String TAG = "NTCpuBindController";
    
    private static final String CPUSET_PATH = "/dev/cpuset/";
    private static final String CAMERA_DAEMON_GROUP = CPUSET_PATH + "camera-daemon/cpus";
    private static final String TOP_APP_GROUP = CPUSET_PATH + "top-app/cpus";
    private static final String DEX2OAT_GROUP = CPUSET_PATH + "dex2oat/cpus";
    private static final String FG_GROUP = CPUSET_PATH + "foreground/cpus";
    private static final String FG_WINDOWN_GROUP = CPUSET_PATH + "foreground_window/cpus";
    private static final String RESTRICTED_GROUP = CPUSET_PATH + "restricted/cpus";
    private static final String SYS_BG_GROUP = CPUSET_PATH + "system-background/cpus";
    private static final String BG_GROUP = CPUSET_PATH + "background/cpus";

    private static final String CPUS_PARAMS_BG_LIMIT = SystemProperties.get("persist.sys.axion_cpu_limit_bg", "0-1");
    private static final String CPUS_PARAMS_UI_LIMIT = SystemProperties.get("persist.sys.axion_cpu_limit_ui", "0-4");
    private static final String CPUS_PARAMS_UI_UNLIMIT = SystemProperties.get("persist.sys.axion_cpu_unlimit_ui", "0-7");
    private static final String CPUS_PARAMS_FG_UNLIMIT = SystemProperties.get("persist.sys.axion_cpu_fg", "0-5");
    private static final String CPUS_PARAMS_BG_UNLIMIT = SystemProperties.get("persist.sys.axion_cpu_bg", "0-2");
    private static final String CPUS_PARAMS_BIG_CORES = SystemProperties.get("persist.sys.axion_cpu_big", "4,5,6,7");

    private static final String CPUS_PARAMS_BIG_LIMIT;

    public static int REQUEST_LIMIT_OTHER_PROCESS_CPU_WHEN_NOTIFICATION_EXPAND = 16;
    public static int REQUEST_LIMIT_OTHER_PROCESS_CPU_WHEN_PLAY_SCREEN_OFF_ANIMATION = 256;
    public static int REQUEST_LIMIT_OTHER_PROCESS_CPU_WHEN_UNLOCK = 1;

    public static int REQUEST_ANIMATION_BOOST_TYPE_BASE = 1;
    public static int REQUEST_ANIMATION_BOOST_TYPE_SPEED_UP_QS_EXPANSION_ANIMATION = 1 << 1;

    private static final int STATUS_BIND_BIG_CORE = 0;
    private static final int STATUS_BIND_SMALL_CORE = 1;
    private static final int STATUS_UNBIND = 2;

    private static final long ANIMATION_BOOST_ON = 0L;
    private static final long ANIMATION_BOOST_OFF = -1L;

    private int mAnimationBoostType = 0;
    private int mBindStatus = STATUS_UNBIND;
    private long mAnimationBoost = ANIMATION_BOOST_OFF;

    private int mLimitOtherProcessCpuReason = 0;
    private boolean mLimitForegroundAppCpu = false;
    private boolean mLimitOtherProcessCpu = false;
    
    private static NTCpuBindController instance = null;

    static {
        CPUS_PARAMS_BIG_LIMIT = getCpuRange(CPUS_PARAMS_BIG_CORES);
    }

    private NTCpuBindController() {}

    private static String getCpuRange(String cpuList) {
        IntSummaryStatistics stats = Arrays.stream(cpuList.split(","))
            .mapToInt(Integer::parseInt)
            .summaryStatistics();
        return stats.getMin() + "-" + stats.getMax();
    }

    public static synchronized NTCpuBindController INSTANCE() {
        if (instance == null) {
            instance = new NTCpuBindController();
        }
        return instance;
    }

    public void bindBigCore() {
        if (mBindStatus != STATUS_BIND_BIG_CORE) {
            mBindStatus = STATUS_BIND_BIG_CORE;
            executeSetThreadAffinity(STATUS_BIND_BIG_CORE);
        }
    }

    public void bindSmallCore() {
        if (mBindStatus != STATUS_BIND_SMALL_CORE) {
            mBindStatus = STATUS_BIND_SMALL_CORE;
            executeSetThreadAffinity(STATUS_BIND_SMALL_CORE);
        }
    }

    public void unbind() {
        if (mBindStatus != STATUS_UNBIND) {
            mBindStatus = STATUS_UNBIND;
            executeSetThreadAffinity(STATUS_UNBIND);
        }
    }
    
    public void animationBoost(int type, boolean enabled) {
        if (enabled) {
            animationBoostOn(type);
        } else {
            animationBoostOff(type);
        }
    }

    private void animationBoostOn(int type) {
        mAnimationBoostType |= type;
        if (mAnimationBoost != ANIMATION_BOOST_ON) {
            executePerformanceMode(true);
            bindBigCore();
            mAnimationBoost = ANIMATION_BOOST_ON;
            executeSetAnimationBoost(ANIMATION_BOOST_ON);
        }
    }

    private void animationBoostOff(int type) {
        mAnimationBoostType &= ~type;
        if (mAnimationBoostType <= 0 && mAnimationBoost != ANIMATION_BOOST_OFF) {
            unbind();
            mAnimationBoost = ANIMATION_BOOST_OFF;
            executeSetAnimationBoost(ANIMATION_BOOST_OFF);
            executePerformanceMode(false);
        }
    }

    private void executeSetAnimationBoost(long boost) {
        try {
            final boolean enabled = boost == ANIMATION_BOOST_ON;
            int pid = Process.myPid();
            ActivityManager.getService().animationBoost(pid, enabled);
        } catch (Exception e) {
            Log.e(TAG, "Failed to call animationBoost", e);
        }
    }

    private void executeSetThreadAffinity(int affinity) {
        try {
            int pid = Process.myPid();
            ActivityManager.getService().setThreadAffinity(pid, affinity);
        } catch (Exception e) {
            Log.e(TAG, "Failed to call setThreadAffinity", e);
        }
    }
    
    public void setLimitCpusForIdle(boolean limit) {
        if (limit) {
            // ui groups
            executeAdjustCpusetCpus(TOP_APP_GROUP, CPUS_PARAMS_UI_LIMIT);
            executeAdjustCpusetCpus(FG_WINDOWN_GROUP, CPUS_PARAMS_UI_LIMIT);
            executeAdjustCpusetCpus(FG_GROUP, CPUS_PARAMS_UI_LIMIT);
            
            // bg groups
            executeAdjustCpusetCpus(CAMERA_DAEMON_GROUP, CPUS_PARAMS_BG_LIMIT);
            executeAdjustCpusetCpus(DEX2OAT_GROUP, CPUS_PARAMS_BG_LIMIT);
            executeAdjustCpusetCpus(RESTRICTED_GROUP, CPUS_PARAMS_BG_LIMIT);
            executeAdjustCpusetCpus(SYS_BG_GROUP, CPUS_PARAMS_BG_LIMIT);
            executeAdjustCpusetCpus(BG_GROUP, CPUS_PARAMS_BG_LIMIT);
        } else {
            // ui groups
            executeAdjustCpusetCpus(TOP_APP_GROUP, CPUS_PARAMS_UI_UNLIMIT);
            executeAdjustCpusetCpus(CAMERA_DAEMON_GROUP, CPUS_PARAMS_UI_UNLIMIT);
            
            // fg groups
            executeAdjustCpusetCpus(FG_WINDOWN_GROUP, CPUS_PARAMS_FG_UNLIMIT);
            executeAdjustCpusetCpus(FG_GROUP, CPUS_PARAMS_FG_UNLIMIT);
            
            // bg groups
            executeAdjustCpusetCpus(DEX2OAT_GROUP, CPUS_PARAMS_BG_UNLIMIT);
            executeAdjustCpusetCpus(RESTRICTED_GROUP, CPUS_PARAMS_BG_UNLIMIT);
            executeAdjustCpusetCpus(SYS_BG_GROUP, CPUS_PARAMS_BG_UNLIMIT);
            executeAdjustCpusetCpus(BG_GROUP, CPUS_PARAMS_BG_UNLIMIT);
        }
    }

    public void setLimitForegroundAppCpu(boolean limitForegroundAppCpu) {
        if (limitForegroundAppCpu != mLimitForegroundAppCpu) {
            if (limitForegroundAppCpu) {
                executeAdjustCpusetCpus(TOP_APP_GROUP, CPUS_PARAMS_BIG_LIMIT);
            } else {
                executeAdjustCpusetCpus(TOP_APP_GROUP, CPUS_PARAMS_UI_UNLIMIT);
            }
            mLimitForegroundAppCpu = limitForegroundAppCpu;
        }
    }

    public void setLimitOtherProcessCpu(boolean limitOtherProcessCpu) {    
        if (limitOtherProcessCpu != mLimitOtherProcessCpu) {
            if (limitOtherProcessCpu) {
                executeAdjustCpusetCpus(CAMERA_DAEMON_GROUP, CPUS_PARAMS_BG_LIMIT);
                executeAdjustCpusetCpus(DEX2OAT_GROUP, CPUS_PARAMS_BG_LIMIT);
            } else {
                executeAdjustCpusetCpus(CAMERA_DAEMON_GROUP, CPUS_PARAMS_UI_UNLIMIT);
                executeAdjustCpusetCpus(DEX2OAT_GROUP, CPUS_PARAMS_BG_UNLIMIT);
            }
            mLimitOtherProcessCpu = limitOtherProcessCpu;
        }
    }

    public void requestLimitOtherProcessCPU(int type) {
        mLimitOtherProcessCpuReason = type | mLimitOtherProcessCpuReason;
        limitCameraHalCpu();
    }

    public void requestUnLimitOtherProcessCPU(int type) {
        mLimitOtherProcessCpuReason = (~type) & mLimitOtherProcessCpuReason;
        limitCameraHalCpu();
    }

    private void limitCameraHalCpu() {
        boolean limit = mLimitOtherProcessCpuReason > 0;
        setLimitOtherProcessCpu(limit);
    }

    public void setLimitOtherAppCpu(boolean on) {
        if (on) {
            requestLimitOtherProcessCPU(REQUEST_LIMIT_OTHER_PROCESS_CPU_WHEN_UNLOCK);
        } else {
            requestUnLimitOtherProcessCPU(REQUEST_LIMIT_OTHER_PROCESS_CPU_WHEN_UNLOCK);
        }
        setLimitForegroundAppCpu(on);
    }

    public void gameBoost(boolean boost) {
        if (boost) {
            executeAdjustCpusetCpus(TOP_APP_GROUP, CPUS_PARAMS_UI_UNLIMIT);
            executeAdjustCpusetCpus(DEX2OAT_GROUP, CPUS_PARAMS_BG_LIMIT);
            executeAdjustCpusetCpus(CAMERA_DAEMON_GROUP, CPUS_PARAMS_BG_LIMIT);
            executeAdjustCpusetCpus(FG_GROUP, CPUS_PARAMS_BG_LIMIT);
            executeAdjustCpusetCpus(FG_WINDOWN_GROUP, CPUS_PARAMS_BG_LIMIT);
            executeAdjustCpusetCpus(RESTRICTED_GROUP, CPUS_PARAMS_BG_LIMIT);
            executeAdjustCpusetCpus(BG_GROUP, CPUS_PARAMS_BG_LIMIT);
            executeAdjustCpusetCpus(SYS_BG_GROUP, CPUS_PARAMS_BG_LIMIT);
        } else {
            executeAdjustCpusetCpus(TOP_APP_GROUP, CPUS_PARAMS_UI_UNLIMIT);
            executeAdjustCpusetCpus(DEX2OAT_GROUP, CPUS_PARAMS_BG_UNLIMIT);
            executeAdjustCpusetCpus(CAMERA_DAEMON_GROUP, CPUS_PARAMS_UI_UNLIMIT);
            executeAdjustCpusetCpus(FG_GROUP, CPUS_PARAMS_FG_UNLIMIT);
            executeAdjustCpusetCpus(FG_WINDOWN_GROUP, CPUS_PARAMS_FG_UNLIMIT);
            executeAdjustCpusetCpus(RESTRICTED_GROUP, CPUS_PARAMS_BG_UNLIMIT);
            executeAdjustCpusetCpus(BG_GROUP, CPUS_PARAMS_BG_UNLIMIT);
            executeAdjustCpusetCpus(SYS_BG_GROUP, CPUS_PARAMS_BG_UNLIMIT);
        }
    }

    private void executeAdjustCpusetCpus(String path, String cpus) {
        try {
            ActivityManager.getService().executeAdjustCpusetCpus(path, cpus);
        } catch (Exception e) {
        }
    }
    
    private void executePerformanceMode(boolean enabled) {
        try {
            ActivityManager.getService().setPerformanceMode(enabled);
        } catch (Exception e) {
        }
    }
}
