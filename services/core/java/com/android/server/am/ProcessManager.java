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

import static com.android.server.am.AxUtils.*;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.Message;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Temperature;
import android.util.Slog;

import com.android.server.NtServiceInjector;

import java.util.*;

public class ProcessManager implements IProcessManager {

    private static final String TAG = "ProcessManager";

    private ActivityManagerService mActivityManagerService;
    private Context mContext;
    private HandlerThread mHandlerThread;
    private Handler mHandler;

    private boolean mEnableBgKiller = true;
    private boolean mEnableDelayRestart = true;
    private long mShortDelayRestartDuration = 5000;
    private long mLongDelayRestartDuration = 30000;
    private long mDelayRestartDuration = 3000; 

    private final ArrayList<ServiceRecord> mPendingStartQueue = new ArrayList<>();

    private String mTopAppProcessName = "";

    private static final long MEMORY_RELEASE_INTERVAL_MS = 1 * 60 * 1000L;
    private long mLastReleaseTime = 0;

    private IThermalService mThermalService;
    private final IThermalEventListener mThermalListener = new IThermalEventListener.Stub() {
        @Override
        public void notifyThrottling(Temperature temperature) {
            int status = temperature.getStatus();
            logger("Thermal event: " + temperature + " (status=" + status + ")");
            if (status != Temperature.THROTTLING_NONE) {
                releaseMemory();
            } else {
                logger("Throttling ended, no mitigation");
            }
        }
    };

    private static final int MSG_SCHEDULE_NEXT = 0;
    private static final int MSG_PROCESS_PENDING = 1;

    public ProcessManager() {
    }

    private void initHandlerThread() {
        mHandlerThread = new HandlerThread("ProcessManager");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_SCHEDULE_NEXT:
                        processPendingStartsIfNeeded();
                        break;
                    case MSG_PROCESS_PENDING:
                        startDelayService();
                        break;
                }
            }
        };
    }

    private void registerThermalCallback() {
        mThermalService = IThermalService.Stub.asInterface(
                ServiceManager.getService(Context.THERMAL_SERVICE));

        if (mThermalService == null) {
            logger("IThermalService not available");
            return;
        }

        try {
            mThermalService.registerThermalEventListener(mThermalListener);
            logger("Thermal listener registered");
        } catch (Exception e) {
            Slog.e(TAG, "Failed to register thermal listener", e);
        }
    }

    private void releaseMemory() {
        long now = System.currentTimeMillis();
        if (now - mLastReleaseTime < MEMORY_RELEASE_INTERVAL_MS) {
            return;
        }
        mLastReleaseTime = now;
        logger("Performing thermal mitigation: releasing memory");
        mActivityManagerService.releaseMemory(900, 20, false, false);
    }

    public void systemReady() {
        logger("ProcessManager enabled");
        mActivityManagerService = NtServiceInjector.getAm();
        mContext = NtServiceInjector.getCtx();

        initHandlerThread();
        registerThermalCallback();
    }

    public void updateTopApp(String topProcessName) {
        mTopAppProcessName = topProcessName;
        logger("Top app: " + mTopAppProcessName);
        processPendingStartsIfNeeded();
    }

    public boolean checkDelayRestartService(ServiceRecord serviceRecord) {
        synchronized (mPendingStartQueue) {
            if (!mEnableDelayRestart) {
                return false;
            }

            boolean isPersistent = (serviceRecord.serviceInfo.applicationInfo.flags
                    & ApplicationInfo.FLAG_PERSISTENT) != 0;

            ProcessRecord pRec = serviceRecord.app;
            boolean isVisible = false;
            if (pRec != null && pRec.mProfile != null) {
                isVisible = (pRec.mProfile.getCurRawAdj() == ProcessList.VISIBLE_APP_ADJ);
            }

            if (isPersistent || serviceRecord.isForeground || isVisible 
                    || isServiceCallFromTopApp(serviceRecord)) {
                return false;
            }

            mPendingStartQueue.add(serviceRecord);
            logger("Queued service for delayed restart: " + serviceRecord);
            return true;
        }
    }

    public long getDelayRestartDuration(ServiceRecord serviceRecord) {
        boolean isPersistent = (serviceRecord.serviceInfo.applicationInfo.flags
                & ApplicationInfo.FLAG_PERSISTENT) != 0;

        ProcessRecord pRec = serviceRecord.app;
        boolean isVisible = (pRec != null && pRec.mProfile != null
                && pRec.mProfile.getCurRawAdj() == ProcessList.VISIBLE_APP_ADJ);

        if (mEnableDelayRestart 
                && !isPersistent 
                && !serviceRecord.isForeground 
                && !isVisible
                && !isServiceCallFromTopApp(serviceRecord)) {
            if (serviceHasBindings(serviceRecord)) {
                return mShortDelayRestartDuration;
            } else {
                return mLongDelayRestartDuration;
            }
        }

        return mActivityManagerService.mConstants.SERVICE_RESTART_DURATION;
    }

    private boolean isServiceCallFromTopApp(ServiceRecord serviceRecord) {
        String topProcess = null;
        ProcessRecord top = mActivityManagerService.getTopApp();
        if (top != null) topProcess = top.processName;

        if (topProcess == null) return false;
        String recent = serviceRecord.mRecentCallingPackage;
        boolean result = false;
        if (serviceRecord.processName.contains(topProcess) ||
                (recent != null && recent.contains(topProcess))) {
            result = true;
        }
        logger("isServiceCallFromTopApp? processName=" + serviceRecord.processName
                + " recentCallingPkg=" + serviceRecord.mRecentCallingPackage + " => " + result);
        return result;
    }

    private boolean serviceHasBindings(ServiceRecord serviceRecord) {
        logger("ServiceRecord processName: " + serviceRecord.processName + ", binds : " + serviceRecord.bindings.size());
        return serviceRecord.bindings.size() > 0;
    }

    private void processPendingStartsIfNeeded() {
        int i = 0;
        if ((mTopAppProcessName == null || mTopAppProcessName.length() == 0)) {
            return;
        }
        synchronized (mPendingStartQueue) {
            if (mPendingStartQueue.size() > 0) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_PROCESS_PENDING));
            }
        }
    }

    private void startDelayService() {
        synchronized (mPendingStartQueue) {
            if (mPendingStartQueue.size() > 0) {
                ServiceRecord sr = mPendingStartQueue.get(0);
                long now = SystemClock.uptimeMillis();
                sr.nextRestartTime = now;
                logger("startDelayService: " + sr.processName + ": " + sr.name);
                mActivityManagerService.mServices.performScheduleRestartLocked(sr, "Scheduling", "NtDelay", now);
                mPendingStartQueue.remove(0);
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_SCHEDULE_NEXT), mDelayRestartDuration);
            }
        }
    }
}
