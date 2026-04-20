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

import android.os.Handler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

public final class AxHintEngine {

    public static final long INVALID_HANDLE = 0L;

    private final Handler mHandler;
    private final AtomicLong mNextHandle = new AtomicLong(1L);

    private final HashMap<String, LongConsumer> mApply = new HashMap<>();
    private final HashMap<String, Long> mDefaults = new HashMap<>();
    private final HashMap<String, HashMap<Long, Long>> mHolders = new HashMap<>();
    private final HashMap<String, Long> mApplied = new HashMap<>();

    private final HashMap<Long, ArrayList<String>> mActive = new HashMap<>();
    private final HashMap<Long, Runnable> mTimers = new HashMap<>();
    private final HashMap<Long, Runnable> mCallbacks = new HashMap<>();

    private final Object mLock = new Object();

    public AxHintEngine(Handler handler) {
        mHandler = handler;
    }

    public void register(String name, long defaultValue, LongConsumer applyFn) {
        synchronized (mLock) {
            mApply.put(name, applyFn);
            mDefaults.put(name, defaultValue);
            mHolders.put(name, new HashMap<>());
            mApplied.put(name, defaultValue);
        }
    }

    public long acquire(Map<String, Long> res, long durationMs) {
        return acquire(res, durationMs, null);
    }

    public long acquire(Map<String, Long> res, long durationMs, Runnable onRelease) {
        if (res == null || res.isEmpty()) return INVALID_HANDLE;
        long handle = mNextHandle.getAndIncrement();
        ArrayList<String> names = new ArrayList<>(res.size());
        synchronized (mLock) {
            for (Map.Entry<String, Long> e : res.entrySet()) {
                String name = e.getKey();
                HashMap<Long, Long> h = mHolders.get(name);
                if (h == null) continue;
                h.put(handle, e.getValue());
                names.add(name);
                recomputeLocked(name);
            }
            if (names.isEmpty()) return INVALID_HANDLE;
            mActive.put(handle, names);
            if (onRelease != null) mCallbacks.put(handle, onRelease);
        }
        if (durationMs > 0) {
            Runnable r = () -> release(handle);
            synchronized (mLock) {
                mTimers.put(handle, r);
            }
            mHandler.postDelayed(r, durationMs);
        }
        return handle;
    }

    public long acquire(AxPerfConfig.HintProfile hint) {
        if (hint == null) return INVALID_HANDLE;
        return acquire(hint.res, hint.durationMs, null);
    }

    public long acquire(AxPerfConfig.HintProfile hint, Runnable onRelease) {
        if (hint == null) return INVALID_HANDLE;
        return acquire(hint.res, hint.durationMs, onRelease);
    }

    public long acquire(AxPerfConfig.HintProfile hint, long durationOverrideMs, Runnable onRelease) {
        if (hint == null) return INVALID_HANDLE;
        long d = durationOverrideMs > 0 ? durationOverrideMs : hint.durationMs;
        return acquire(hint.res, d, onRelease);
    }

    public void release(long handle) {
        if (handle == INVALID_HANDLE) return;
        ArrayList<String> names;
        Runnable timer;
        Runnable cb;
        synchronized (mLock) {
            names = mActive.remove(handle);
            if (names == null) return;
            timer = mTimers.remove(handle);
            cb = mCallbacks.remove(handle);
            for (String name : names) {
                HashMap<Long, Long> h = mHolders.get(name);
                if (h != null) h.remove(handle);
                recomputeLocked(name);
            }
        }
        if (timer != null) mHandler.removeCallbacks(timer);
        if (cb != null) mHandler.post(cb);
    }

    public boolean isActive(long handle) {
        synchronized (mLock) {
            return handle != INVALID_HANDLE && mActive.containsKey(handle);
        }
    }

    private void recomputeLocked(String name) {
        HashMap<Long, Long> holders = mHolders.get(name);
        Long def = mDefaults.get(name);
        if (holders == null || def == null) return;
        long target;
        if (holders.isEmpty()) {
            target = def;
        } else {
            long max = Long.MIN_VALUE;
            for (Long v : holders.values()) {
                if (v != null && v > max) max = v;
            }
            target = max;
        }
        Long prev = mApplied.get(name);
        if (prev != null && prev == target) return;
        mApplied.put(name, target);
        final long v = target;
        LongConsumer fn = mApply.get(name);
        if (fn != null) mHandler.post(() -> fn.accept(v));
    }
}
