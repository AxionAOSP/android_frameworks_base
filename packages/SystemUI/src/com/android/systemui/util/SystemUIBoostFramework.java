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
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.os.IBoostFramework;

import java.util.Arrays;
import java.util.IntSummaryStatistics;

public class SystemUIBoostFramework {

    private static final String TAG = "SystemUIBoostFramework";
    
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
    public static int REQUEST_ANIMATION_BOOST_TYPE_FLING_NOTIFICATION_PANEL_VIEW = REQUEST_ANIMATION_BOOST_TYPE_BASE;
    public static int REQUEST_ANIMATION_BOOST_TYPE_TRACKING_NOTIFICATION_PANEL_VIEW = 1 << 1;
    public static int REQUEST_ANIMATION_BOOST_TYPE_SPEED_UP_NOTIFICATION_PANEL_VIEW_EXPAND = 1 << 2;
    public static int REQUEST_ANIMATION_BOOST_TYPE_UNLOCK = 1 << 3;
    public static int REQUEST_ANIMATION_BOOST_TYPE_LIGHT_REVEAL = 1 << 4;
    public static int REQUEST_ANIMATION_BOOST_TYPE_TRACKING_NOTIFICATION_STACK_SCROLL_LAYOUT = 1 << 5;
    public static int REQUEST_ANIMATION_BOOST_TYPE_SPEED_UP_QS_EXPANSION_ANIMATION = 1 << 6;
    public static int REQUEST_ANIMATION_BOOST_TYPE_SPEED_UP_QS_SB_ANIMATION = 1 << 7;

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
    
    private static IBoostFramework sService;
    
    private static SystemUIBoostFramework instance = null;

    static {
        CPUS_PARAMS_BIG_LIMIT = getCpuRange(CPUS_PARAMS_BIG_CORES);
    }

    private SystemUIBoostFramework() {}

    private static String getCpuRange(String cpuList) {
        IntSummaryStatistics stats = Arrays.stream(cpuList.split(","))
            .mapToInt(Integer::parseInt)
            .summaryStatistics();
        return stats.getMin() + "-" + stats.getMax();
    }

    public static synchronized SystemUIBoostFramework getInstance() {
        if (instance == null) {
            instance = new SystemUIBoostFramework();
        }
        return instance;
    }

    private static IBoostFramework getService() {
        if (sService == null) {
            IBinder binder = ServiceManager.getService("boost_framework");
            sService = IBoostFramework.Stub.asInterface(binder);
        }
        return sService;
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

    public void animationBoostOn(int type) {
        mAnimationBoostType |= type;
        if (mAnimationBoost != ANIMATION_BOOST_ON) {
            bindBigCore();
            mAnimationBoost = ANIMATION_BOOST_ON;
            executeSetAnimationBoost(ANIMATION_BOOST_ON);
        }
    }

    public void animationBoostOff(int type) {
        mAnimationBoostType &= ~type;
        if (mAnimationBoostType <= 0 && mAnimationBoost != ANIMATION_BOOST_OFF) {
            unbind();
            mAnimationBoost = ANIMATION_BOOST_OFF;
            executeSetAnimationBoost(ANIMATION_BOOST_OFF);
        }
    }

    private void executeSetAnimationBoost(long boost) {
        try {
            animationBoost(boost);
        } catch (Exception e) {
            Log.w(TAG, "executeSetAnimationBoost() Exception: ", e);
        }
    }

    private void executeSetThreadAffinity(int affinity) {
        try {
            setProcThreadAffinity(affinity);
        } catch (Exception e) {
            Log.w(TAG, "executeSetThreadAffinity() Exception: ", e);
        }
    }
    
    public static void setProcThreadAffinity(int affinity) {
        try {
            int tid = Process.myPid();
            IBoostFramework service = getService();
            if (service != null) {
                service.setProcThreadAffinity(tid, affinity);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to call setProcThreadAffinity", e);
        }
    }

    public static void animationBoost(long boost) {
        try {
            int tid = Process.myPid();
            IBoostFramework service = getService();
            if (service != null) {
                service.animationBoost(tid, boost);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to call animationBoost", e);
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
}
