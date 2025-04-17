/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server;

import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.Trace;

import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.Executor;

/**
 * Shared singleton thread for the system. This is a thread for handling
 * calls to and from the PermissionController and handling synchronization
 * between permissions and appops states.
 */
public final class PermissionThread extends ServiceThread {
    private static final long SLOW_DISPATCH_THRESHOLD_MS = 100;
    private static final long SLOW_DELIVERY_THRESHOLD_MS = 200;

    private static final Object sLock = new Object();

    private static volatile PermissionThread sInstance;
    private static volatile Handler sHandler;
    private static volatile HandlerExecutor sHandlerExecutor;

    private PermissionThread() {
        super("android.perm", android.os.Process.THREAD_PRIORITY_DEFAULT, /* allowIo= */ true);
    }

    @GuardedBy("sLock")
    private static void ensureThreadLocked() {
        if (sInstance != null) {
            return;
        }

        PermissionThread thread = new PermissionThread();
        thread.start();
        final Looper looper = thread.getLooper();
        looper.setTraceTag(Trace.TRACE_TAG_SYSTEM_SERVER);
        looper.setSlowLogThresholdMs(
                SLOW_DISPATCH_THRESHOLD_MS, SLOW_DELIVERY_THRESHOLD_MS);
        sInstance = thread;
        sHandler = new Handler(looper);
        sHandlerExecutor = new HandlerExecutor(sHandler);
    }

    /**
     * Obtain a singleton instance of the PermissionThread.
     */
    public static PermissionThread get() {
        if (sInstance == null) {
            synchronized (sLock) {
                ensureThreadLocked();
            }
        }
        return sInstance;
    }

    /**
     * Obtain a singleton instance of a handler executing in the PermissionThread.
     */
    public static Handler getHandler() {
        if (sHandler == null) {
            synchronized (sLock) {
                ensureThreadLocked();
                return sHandler;
            }
        }
        return sHandler;
    }


    /**
     * Obtain a singleton instance of an executor of the PermissionThread.
     */
    public static Executor getExecutor() {
        if (sHandlerExecutor == null) {
            synchronized (sLock) {
                ensureThreadLocked();
            }
        }
        return sHandlerExecutor;
    }
}
