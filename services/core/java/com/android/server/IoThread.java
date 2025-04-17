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

package com.android.server;

import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Trace;

import java.util.concurrent.Executor;

/**
 * Shared singleton I/O thread for the system.  This is a thread for non-background
 * service operations that can potential block briefly on network IO operations
 * (not waiting for data itself, but communicating with network daemons).
 */
public final class IoThread extends ServiceThread {
    private static volatile IoThread sInstance;
    private static volatile Handler sHandler;
    private static volatile HandlerExecutor sHandlerExecutor;

    private IoThread() {
        super("android.io", android.os.Process.THREAD_PRIORITY_DEFAULT, true /*allowIo*/);
    }

    @Override
    public void run() {
        // Make sure IoThread runs on little cluster
        android.os.Process.setThreadAffinity(android.os.Process.myTid(), 1);
        super.run();
    }

    private static void ensureThreadLocked() {
        if (sInstance == null) {
            IoThread thread = new IoThread();
            thread.start();
            thread.getLooper().setTraceTag(Trace.TRACE_TAG_SYSTEM_SERVER);
            sInstance = thread;
            sHandler = makeSharedHandler(thread.getLooper());
            sHandlerExecutor = new HandlerExecutor(sHandler);
        }
    }

    public static IoThread get() {
        if (sInstance == null) {
            synchronized (IoThread.class) {
                ensureThreadLocked();
            }
        }
        return sInstance;
    }

    public static Handler getHandler() {
        if (sHandler == null) {
            synchronized (IoThread.class) {
                ensureThreadLocked();
            }
        }
        return sHandler;
    }

    public static Executor getExecutor() {
        if (sHandlerExecutor == null) {
            synchronized (IoThread.class) {
                ensureThreadLocked();
            }
        }
        return sHandlerExecutor;
    }
}
