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
package com.android.server.am;

import android.app.role.RoleManager;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.util.Slog;

import java.util.ArrayList;

public final class AxFreezeManager {

    private static final String TAG = "AxFreezeMgr";
    private static final int MSG_FREEZE = 1;
    private static final long REFREEZE_GAP_MS = 1500;

    private final HandlerThread mHandlerThread;
    private final Handler mHandler;
    private volatile boolean mFreezing = false;
    private volatile long mLastUnfreezeAt = 0;
    private int mDurationMs = 600;
    private ProcessList mProcList;
    private Context mContext;

    public AxFreezeManager() {
        mHandlerThread = new HandlerThread("AxFreezeThread", -2);
        mHandlerThread.start();
        mHandler = new FreezerHandler(mHandlerThread.getLooper());
        int tid = mHandlerThread.getThreadId();
        if (tid > 0) {
            try {
                Process.setThreadGroupAndCpuset(tid, Process.THREAD_GROUP_BACKGROUND);
            } catch (Exception e) {
                AxUtils.logger("AxFreezeThread cpuset pin failed: " + e);
            }
        }
    }

    public void setSystemReady(Context ctx, ProcessList procList) {
        mContext = ctx;
        mProcList = procList;
    }

    public void setDurationMs(int durationMs) {
        if (durationMs > 0) mDurationMs = durationMs;
    }

    public void freeze(String packageName) {
        if (packageName == null || mFreezing) {
            AxUtils.logger("AnimationFreeze: freezing not needed. ignoring!");
            return;
        }
        long sinceUnfreeze = SystemClock.uptimeMillis() - mLastUnfreezeAt;
        if (mLastUnfreezeAt > 0 && sinceUnfreeze < REFREEZE_GAP_MS) {
            AxUtils.logger("AnimationFreeze: hysteresis skip, sinceUnfreeze=" + sinceUnfreeze);
            return;
        }
        mFreezing = true;
        Message m = mHandler.obtainMessage(MSG_FREEZE, packageName);
        mHandler.sendMessage(m);
    }

    private void setFrozen(int pid, int uid, boolean frozen) {
        try {
            Process.setProcessFrozen(pid, uid, frozen);
        } catch (Exception e) {
            AxUtils.logger(e.toString());
        }
        AxUtils.logger("AnimationFreeze: frozen: uid=" + uid + " pid=" + pid + " frozen=" + frozen);
    }

    private void animationUnfreeze(ArrayList<ProcessRecord> list) {
        AxUtils.logger("AnimationFreeze: unfrozen processes start");
        for (int i = 0; i < list.size(); i++) {
            ProcessRecord r = list.get(i);
            setFrozen(r.mPid, r.getUid(), false);
        }
        AxUtils.logger("AnimationFreeze: unfrozen processes end");
    }

    private void animationFreeze(ArrayList<ProcessRecord> list) {
        AxUtils.logger("AnimationFreeze: frozen processes start");
        for (int i = 0; i < list.size(); i++) {
            ProcessRecord r = list.get(i);
            setFrozen(r.mPid, r.getUid(), true);
        }
        AxUtils.logger("AnimationFreeze: frozen processes end");
    }

    private void backgroundFreeze(String packageName) {
        AxUtils.logger("AnimationFreeze: get frozen app list and frozen start");
        ArrayList<ProcessRecord> freezeList = new ArrayList<>();
        if (mProcList == null) {
            Slog.e(TAG, "AnimationFreeze: system not ready");
            mFreezing = false;
            return;
        }
        ArrayList<ProcessRecord> lru =
                (ArrayList<ProcessRecord>) mProcList.ntGetLruProcesses().clone();
        try {
            boolean homeContains = packageName.isEmpty() ? false :
                    ((RoleManager) mContext.getSystemService(RoleManager.class))
                    .getRoleHolders("android.app.role.HOME").contains(packageName);

            for (int i = 0; i < lru.size(); i++) {
                ProcessRecord pr = lru.get(i);
                if (pr != null && !pr.getProcessName().equals(packageName)
                        && !pr.getProcessName().contains("webview")
                        && (!homeContains || !pr.getProcessName().equals(
                                "com.google.android.googlequicksearchbox:search"))) {
                    int curAdj = pr.getCurAdj();
                    if (pr.getUid() > 10000 && curAdj >= 250
                            && curAdj != 600 && curAdj != 700 && curAdj < 900) {
                        AxUtils.logger("AnimationFreeze: freeze " + pr.getProcessName());
                        freezeList.add(pr);
                    }
                }
            }

            if (freezeList.isEmpty()) {
                mFreezing = false;
                return;
            }

            mHandler.post(new FreezeRunnable(freezeList));
            AxUtils.logger("AnimationFreeze: size=" + freezeList.size());
            mHandler.postDelayed(new UnfreezeRunnable(freezeList), mDurationMs);
        } catch (Exception e) {
            AxUtils.logger("AnimationFreeze: get process failed");
            mFreezing = false;
        }
    }

    private final class FreezerHandler extends Handler {
        FreezerHandler(Looper looper) { super(looper); }
        @Override
        public void handleMessage(Message msg) {
            if (msg.what != MSG_FREEZE) return;
            backgroundFreeze(String.valueOf(msg.obj));
        }
    }

    private final class FreezeRunnable implements Runnable {
        final ArrayList<ProcessRecord> list;
        FreezeRunnable(ArrayList<ProcessRecord> l) { list = l; }
        @Override public void run() { animationFreeze(list); }
    }

    private final class UnfreezeRunnable implements Runnable {
        final ArrayList<ProcessRecord> list;
        UnfreezeRunnable(ArrayList<ProcessRecord> l) { list = l; }
        @Override public void run() {
            animationUnfreeze(list);
            mLastUnfreezeAt = SystemClock.uptimeMillis();
            mFreezing = false;
        }
    }
}
