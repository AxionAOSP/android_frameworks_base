/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.os;

import android.annotation.NonNull;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Trace;

import java.util.concurrent.Executor;

/**
 * Shared singleton background thread for each process.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class BackgroundThread extends HandlerThread {
    private static final long SLOW_DISPATCH_THRESHOLD_MS = 10_000;
    private static final long SLOW_DELIVERY_THRESHOLD_MS = 30_000;
    private static volatile BackgroundThread sInstance;
    private static volatile Handler sHandler;
    private static volatile HandlerExecutor sHandlerExecutor;

    private BackgroundThread() {
        super("android.bg", android.os.Process.THREAD_PRIORITY_BACKGROUND);
    }

    @Override
    public void run() {
        // Make sure BackgroundThread runs on little cluster
        android.os.Process.setThreadAffinity(android.os.Process.myTid(), 1);
        super.run();
    }

    private static void ensureThreadLocked() {
        if (sInstance == null) {
            BackgroundThread thread = new BackgroundThread();
            thread.start();
            final Looper looper = thread.getLooper();
            looper.setTraceTag(Trace.TRACE_TAG_SYSTEM_SERVER);
            looper.setSlowLogThresholdMs(
                    SLOW_DISPATCH_THRESHOLD_MS, SLOW_DELIVERY_THRESHOLD_MS);
            sInstance = thread;
            sHandler = new Handler(looper, /*callback=*/ null, /* async=*/ false,
                    /* shared=*/ true);
            sHandlerExecutor = new HandlerExecutor(sHandler);
        }
    }

    @NonNull
    public static BackgroundThread get() {
        if (sInstance == null) {
            synchronized (BackgroundThread.class) {
                ensureThreadLocked();
            }
        }
        return sInstance;
    }

    @NonNull
    public static Handler getHandler() {
        if (sHandler == null) {
            synchronized (BackgroundThread.class) {
                ensureThreadLocked();
            }
        }
        return sHandler;
    }

    @NonNull
    public static Executor getExecutor() {
        if (sHandlerExecutor == null) {
            synchronized (BackgroundThread.class) {
                ensureThreadLocked();
            }
        }
        return sHandlerExecutor;
    }
}
