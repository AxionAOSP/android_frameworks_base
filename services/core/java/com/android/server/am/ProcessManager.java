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
    private final Set<String> mWhitelistPackages = new HashSet<>(Arrays.asList(
            "com.android.axion.widgets",
            "com.android.edge.bar"
    ));

    private String mTopAppProcessName = "";

    private static final long MEMORY_RELEASE_INTERVAL_MS = 1 * 60 * 1000L;
    private long mLastReleaseTime = 0;
    
    private volatile int mLastThermalStatus = Temperature.THROTTLING_NONE;

    private IThermalService mThermalService;
    private final IThermalEventListener mThermalListener = new IThermalEventListener.Stub() {
        @Override
        public void notifyThrottling(Temperature temperature) {
            int status = temperature.getStatus();
            mLastThermalStatus = status;
            logger("ProcessManager: Thermal event: " + temperature + " (status=" + status + ")");
            if (status != Temperature.THROTTLING_NONE) {
                releaseMemory();
            } else {
                logger("ProcessManager: Throttling ended, no mitigation");
            }
        }
    };

    private static final int MSG_SCHEDULE_NEXT = 0;
    private static final int MSG_PROCESS_PENDING = 1;

    public ProcessManager() {
    }

    public boolean isThermalHigh() {
        return mLastThermalStatus >= Temperature.THROTTLING_MODERATE;
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
            logger("ProcessManager: IThermalService not available");
            return;
        }

        try {
            mThermalService.registerThermalEventListener(mThermalListener);
            logger("ProcessManager: Thermal listener registered");
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
        logger("ProcessManager: Performing thermal mitigation: releasing memory");
        mActivityManagerService.releaseMemory(900, 20, false, false);
    }

    public void systemReady() {
        logger("ProcessManager: ProcessManager enabled");
        mActivityManagerService = NtServiceInjector.getAm();
        mContext = NtServiceInjector.getCtx();

        initHandlerThread();
        registerThermalCallback();
    }

    public void updateTopApp(String topProcessName) {
        mTopAppProcessName = topProcessName;
        logger("ProcessManager: Top app: " + mTopAppProcessName);
        processPendingStartsIfNeeded();
    }

    public boolean checkDelayRestartService(ServiceRecord serviceRecord) {
        if (!mEnableDelayRestart || isWhitelisted(serviceRecord)) {
            return false;
        }

        boolean shouldDelay = shouldDelayRestart(serviceRecord);
        if (!shouldDelay) {
            return false;
        }

        if (mHandler != null) {
            mHandler.post(() -> handleDelayRestartService(serviceRecord));
        }

        return true;
    }

    private boolean shouldDelayRestart(ServiceRecord r) {
        boolean isPersistent = (r.serviceInfo.applicationInfo.flags
                & ApplicationInfo.FLAG_PERSISTENT) != 0;

        ProcessRecord pRec = r.app;
        boolean isVisible = false;
        if (pRec != null && pRec.mProfile != null) {
            isVisible = (pRec.mProfile.getCurRawAdj() == ProcessList.VISIBLE_APP_ADJ) 
                || pRec.hasActivities();
        }

        boolean result = !(isPersistent ||
                 r.isForeground ||
                 isVisible ||
                 isServiceCallFromTopApp(r));
        
        if (result) logger("ProcessManager: Delay " + r.processName);
        return result;
    }

    private void handleDelayRestartService(ServiceRecord sr) {
        synchronized (mPendingStartQueue) {
            if (!mPendingStartQueue.contains(sr)) {
                mPendingStartQueue.add(sr);
                logger("ProcessManager: Queued service for delayed restart: " + sr);
            }
        }
    }

    public long getDelayRestartDuration(ServiceRecord r) {
        boolean isPersistent = (r.serviceInfo.applicationInfo.flags
                & ApplicationInfo.FLAG_PERSISTENT) != 0;

        ProcessRecord pRec = r.app;
        boolean isVisible = false;
        if (pRec != null && pRec.mProfile != null) {
            isVisible = (pRec.mProfile.getCurRawAdj() == ProcessList.VISIBLE_APP_ADJ) 
                || pRec.hasActivities();
        }

        if (mEnableDelayRestart 
                && !isPersistent 
                && !r.isForeground 
                && !isVisible
                && !isServiceCallFromTopApp(r)) {
            if (serviceHasBindings(r)) {
                return mShortDelayRestartDuration;
            } else {
                return mLongDelayRestartDuration;
            }
        }

        return mActivityManagerService.mConstants.SERVICE_RESTART_DURATION;
    }

    private boolean isServiceCallFromTopApp(ServiceRecord r) {
        String topProcess = null;
        ProcessRecord top = mActivityManagerService.getTopApp();
        if (top != null) topProcess = top.processName;

        if (topProcess == null) return false;
        String recent = r.mRecentCallingPackage;
        boolean result = false;
        if (r.processName.contains(topProcess) ||
                (recent != null && recent.contains(topProcess))) {
            result = true;
        }
        logger("ProcessManager: isServiceCallFromTopApp? processName=" + r.processName
                + " recentCallingPkg=" + r.mRecentCallingPackage + " => " + result);
        return result;
    }

    private boolean serviceHasBindings(ServiceRecord r) {
        logger("ProcessManager: ServiceRecord processName: " + r.processName + ", binds : " + r.bindings.size());
        return r.bindings.size() > 0;
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

    private boolean isWhitelisted(ServiceRecord sr) {
        return sr != null && mWhitelistPackages.contains(sr.processName);
    }

    private void startDelayService() {
        synchronized (mPendingStartQueue) {
            if (mPendingStartQueue.size() > 0) {
                ServiceRecord sr = mPendingStartQueue.get(0);
                long now = SystemClock.uptimeMillis();
                sr.nextRestartTime = now;
                logger("ProcessManager: startDelayService: " + sr.processName + ": " + sr.name);
                mActivityManagerService.mServices.performScheduleRestartLocked(sr, "Scheduling", "NtDelay", now);
                mPendingStartQueue.remove(0);
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_SCHEDULE_NEXT), mDelayRestartDuration);
            }
        }
    }
}
