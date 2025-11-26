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
import android.os.Temperature;
import android.util.Slog;

import com.android.server.NtServiceInjector;

import java.util.*;

public class ProcessManager implements IProcessManager {

    private static final String TAG = "ProcessManager";

    private static final int MSG_SCHEDULE_NEXT = 0;
    private static final int MSG_PROCESS_PENDING = 1;

    private ActivityManagerService mActivityManagerService;
    private Context mContext;
    private HandlerThread mHandlerThread;
    private Handler mHandler;

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

    private volatile int mLastThermalStatus = Temperature.THROTTLING_NONE;

    private IThermalService mThermalService;
    private final IThermalEventListener mThermalListener = new IThermalEventListener.Stub() {
        @Override
        public void notifyThrottling(Temperature temperature) {
            int status = temperature.getStatus();
            int previousStatus = mLastThermalStatus;
            mLastThermalStatus = status;

            logger("ProcessManager: Thermal event: " + temperature + " (status=" + status + ")");

            if (status != previousStatus && status != Temperature.THROTTLING_NONE) {
                releaseMemory();
            }
        }
    };

    public ProcessManager() {
    }

    public void systemReady() {
        logger("ProcessManager: ProcessManager enabled");
        mActivityManagerService = NtServiceInjector.getAm();
        mContext = NtServiceInjector.getCtx();

        initHandlerThread();
        registerThermalCallback();
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

    public boolean isThermalHigh() {
        return mLastThermalStatus >= Temperature.THROTTLING_MODERATE;
    }

    private void releaseMemory() {
        logger("ProcessManager: Performing thermal mitigation: releasing memory");
        mActivityManagerService.releaseMemory(900, 20, false, false);
    }

    public void updateTopApp(String topProcessName) {
        mTopAppProcessName = topProcessName;
        logger("ProcessManager: Top app: " + mTopAppProcessName);
        processPendingStartsIfNeeded();
    }

    private String getTopAppProcessName() {
        ProcessRecord top = mActivityManagerService.getTopApp();
        return (top != null) ? top.processName : null;
    }

    private boolean isProcessVisible(ProcessRecord pRec) {
        if (pRec == null) {
            return false;
        }

        if (pRec.mProfile != null) {
            final int currentAdj = pRec.mProfile.getCurRawAdj();

            boolean isPerceptible = currentAdj <= ProcessList.PERCEPTIBLE_LOW_APP_ADJ
                    || currentAdj == ProcessList.VISIBLE_APP_ADJ
                    || currentAdj == ProcessList.HOME_APP_ADJ
                    || currentAdj == ProcessList.PREVIOUS_APP_ADJ;

            if (isPerceptible) {
                return true;
            }
        }

        if (pRec.hasActivities()) {
            return true;
        }

        ProcessStateRecord pState = pRec.mState;
        if (pState != null) {
            if (pState.hasTopUi() || pState.hasOverlayUi() || pState.isRunningRemoteAnimation()) {
                return true;
            }
        }

        return false;
    }

    public boolean checkDelayRestartService(ServiceRecord serviceRecord) {
        if (!mEnableDelayRestart || isWhitelisted(serviceRecord)) {
            return false;
        }

        if (!shouldDelayRestart(serviceRecord)) {
            return false;
        }

        if (mHandler != null) {
            mHandler.post(() -> enqueueDelayedRestart(serviceRecord));
        }

        return true;
    }

    private boolean shouldDelayRestart(ServiceRecord r) {
        ProcessRecord pRec = r.app;
        if (pRec == null || pRec.mProfile == null) {
            return false;
        }

        if (!AxUtils.isRestrictedNeedSelfControll(pRec)) {
            return false;
        }

        boolean shouldDelay = !isPersistent(r)
                && !r.isForeground
                && !isProcessVisible(pRec)
                && !isServiceCallFromTopApp(r);

        if (shouldDelay) {
            logger("ProcessManager: Delay " + r.processName);
        }

        return shouldDelay;
    }

    public long getDelayRestartDuration(ServiceRecord r) {
        ProcessRecord pRec = r.app;

        if (mEnableDelayRestart
                && !isPersistent(r)
                && !r.isForeground
                && !isProcessVisible(pRec)
                && !isServiceCallFromTopApp(r)) {
            return serviceHasBindings(r) ? mShortDelayRestartDuration : mLongDelayRestartDuration;
        }

        return mActivityManagerService.mConstants.SERVICE_RESTART_DURATION;
    }

    private void enqueueDelayedRestart(ServiceRecord sr) {
        synchronized (mPendingStartQueue) {
            if (!mPendingStartQueue.contains(sr)) {
                mPendingStartQueue.add(sr);
                logger("ProcessManager: Queued service for delayed restart: " + sr);
            }
        }
    }

    private void processPendingStartsIfNeeded() {
        if (mTopAppProcessName == null || mTopAppProcessName.isEmpty()) {
            return;
        }

        synchronized (mPendingStartQueue) {
            if (!mPendingStartQueue.isEmpty()) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_PROCESS_PENDING));
            }
        }
    }

    private void startDelayService() {
        synchronized (mPendingStartQueue) {
            if (mPendingStartQueue.isEmpty()) {
                return;
            }

            ServiceRecord sr = mPendingStartQueue.remove(0);
            long now = SystemClock.uptimeMillis();
            sr.nextRestartTime = now;

            logger("ProcessManager: startDelayService: " + sr.processName + ": " + sr.name);
            mActivityManagerService.mServices.performScheduleRestartLocked(sr, "Scheduling", "NtDelay", now);

            mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_SCHEDULE_NEXT), mDelayRestartDuration);
        }
    }

    private boolean isPersistent(ServiceRecord r) {
        return (r.serviceInfo.applicationInfo.flags & ApplicationInfo.FLAG_PERSISTENT) != 0;
    }

    private boolean isWhitelisted(ServiceRecord sr) {
        return sr != null && mWhitelistPackages.contains(sr.processName);
    }

    private boolean isServiceCallFromTopApp(ServiceRecord r) {
        String topProcess = getTopAppProcessName();
        if (topProcess == null) {
            return false;
        }

        String recentCaller = r.mRecentCallingPackage;
        boolean result = r.processName.contains(topProcess)
                || (recentCaller != null && recentCaller.contains(topProcess));

        logger("ProcessManager: isServiceCallFromTopApp? processName=" + r.processName
                + " recentCallingPkg=" + recentCaller + " => " + result);
        return result;
    }

    private boolean serviceHasBindings(ServiceRecord r) {
        logger("ProcessManager: ServiceRecord processName: " + r.processName
                + ", binds: " + r.bindings.size());
        return !r.bindings.isEmpty();
    }
}
