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
package com.android.server.thermal;

import android.content.Context;
import android.os.Bundle;
import android.util.IndentingPrintWriter;
import android.util.LocalLog;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;

import dalvik.annotation.optimization.NeverCompile;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class AxAdvancedThermalMitigationDispatcher {
    private static final boolean DBG = true;
    private static final String TAG = "AxAdvancedThermalMitigationDispatcher";

    private final Context mContext;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private Bundle mCachedExtra;

    @GuardedBy("mLock")
    private final List<AxAdvancedThermalMitigationInfo> mCachedConfigs = new ArrayList<>();

    @GuardedBy("mLock")
    private final List<ListenerRecord> mRecords = new ArrayList<>();

    private final LocalLog mLocalLog = new LocalLog(200);

    private static class ListenerRecord {
        IAxAdvancedThermalMitigationListener listener;
        Set<String> units;
        String callingPackage;

        @Override
        public String toString() {
            return "{callingPackage=" + this.callingPackage + " unitList=" + this.units + "}";
        }
    }

    public AxAdvancedThermalMitigationDispatcher(Context context) {
        this.mContext = context;
        Log.d(TAG, "AxAdvancedThermalMitigationDispatcher: create");
    }

    public void listenWithUnitList(
            IAxAdvancedThermalMitigationListener listener, String[] units, String packageName) {
        synchronized (this.mLock) {
            if (units != null && units.length != 0) {
                String msg =
                        "listenWithType: units: "
                                + Arrays.toString(units)
                                + " package: "
                                + packageName
                                + " listener: "
                                + listener
                                + " cached: "
                                + this.mCachedConfigs;
                this.mLocalLog.log(msg);
                Log.d(TAG, msg);
                final HashSet<String> unitSet = new HashSet<>(Arrays.asList(units));
                ListenerRecord record = new ListenerRecord();
                record.listener = listener;
                record.callingPackage = packageName;
                record.units = unitSet;
                this.mRecords.add(record);
                if (this.mCachedConfigs.isEmpty()) {
                    return;
                }
                List<AxAdvancedThermalMitigationInfo> cached =
                        this.mCachedConfigs.stream()
                                .filter(info -> unitSet.contains(info.getUnit()))
                                .collect(Collectors.toList());
                Log.d(
                        TAG,
                        "listenWithModuleType: units "
                                + unitSet
                                + " listener: "
                                + listener
                                + " cached infos: "
                                + cached);
                if (!cached.isEmpty()) {
                    try {
                        listener.onThermalStatus(cached, this.mCachedExtra);
                    } catch (RuntimeException e) {
                        Slog.e(TAG, "listenWithModuleType listener failed to call", e);
                    }
                }
                return;
            }
            String msg = "unregister listener: " + listener + " from pkg: " + packageName;
            Iterator<ListenerRecord> it = this.mRecords.iterator();
            while (it.hasNext()) {
                ListenerRecord record = it.next();
                if (record.listener == listener) {
                    msg = msg + " Record: " + record;
                    it.remove();
                    break;
                }
            }
            this.mLocalLog.log(msg);
            Log.d(TAG, msg);
        }
    }

    public void notifyThermalStatusUpdate(
            List<AxAdvancedThermalMitigationInfo> list, Bundle bundle) {
        Log.d(TAG, "notifyThermalStatusUpdate: " + list + " " + bundle);
        synchronized (this.mLock) {
            if (list == null) {
                resetForAllLocked();
            } else {
                notifyListenersLocked(list, bundle);
            }
        }
    }

    public void retrieveCachedConfig(List<AxAdvancedThermalMitigationInfo> list, Bundle bundle) {
        synchronized (this.mLock) {
            String msg =
                    "retrieveCachedConfig++ "
                            + this.mCachedConfigs
                            + " extra: "
                            + this.mCachedExtra
                            + " in infos: "
                            + list
                            + " in extras: "
                            + bundle;
            this.mLocalLog.log(msg);
            Log.d(TAG, msg);
            if (this.mCachedExtra != null) {
                bundle.putAll(this.mCachedExtra);
            }
            list.addAll(this.mCachedConfigs);
            Log.d(TAG, "retrieveCachedConfig--");
        }
    }

    private void notifyListenersLocked(List<AxAdvancedThermalMitigationInfo> list, Bundle bundle) {
        for (ListenerRecord record : this.mRecords) {
            final Set<String> cookie = record.units;
            List<AxAdvancedThermalMitigationInfo> filtered =
                    list.stream()
                            .filter(info -> cookie.contains(info.getUnit()))
                            .collect(Collectors.toList());
            if (filtered.isEmpty()) continue;
            String msg = "notifyListenersLocked: callback with: " + filtered + " Record: " + record;
            this.mLocalLog.log(msg);
            Log.d(TAG, msg);
            try {
                record.listener.onThermalStatus(filtered, bundle);
            } catch (RuntimeException e) {
                Slog.e(TAG, "callback failed to call", e);
            }
        }
        final Set<String> updatedUnits =
                list.stream()
                        .map(AxAdvancedThermalMitigationInfo::getUnit)
                        .collect(Collectors.toSet());
        this.mCachedConfigs.removeIf(info -> updatedUnits.contains(info.getUnit()));
        this.mCachedConfigs.addAll(
                list.stream()
                        .filter(AxAdvancedThermalMitigationDispatcher::isValidStatus)
                        .collect(Collectors.toList()));
        Log.d(TAG, "notifyListenersLocked: mCachedConfigs update " + this.mCachedConfigs);
        this.mCachedExtra = bundle;
    }

    private void resetForAllLocked() {
        Log.d(TAG, "resetForAllLocked");
        if (this.mCachedExtra != null) this.mCachedExtra.clear();
        if (this.mCachedConfigs.isEmpty()) {
            Log.d(TAG, "resetForAllLocked no cached config, return");
            return;
        }
        List<AxAdvancedThermalMitigationInfo> filtered =
                this.mCachedConfigs.stream()
                        .filter(AxAdvancedThermalMitigationDispatcher::isValidStatus)
                        .collect(Collectors.toList());
        this.mCachedConfigs.clear();
        if (filtered.isEmpty()) {
            Log.d(TAG, "resetForAllLocked empty info after filtering, return");
            return;
        }
        for (ListenerRecord record : this.mRecords) {
            final Set<String> cookie = record.units;
            if (filtered.stream().noneMatch(info -> cookie.contains(info.getUnit()))) {
                continue;
            }
            try {
                Log.d(TAG, "resetForAllLocked: callback with empty list");
                record.listener.onThermalStatus(this.mCachedConfigs, this.mCachedExtra);
            } catch (RuntimeException e) {
                Slog.e(TAG, "resetForAllLocked callback failed to call", e);
            }
        }
    }

    public static boolean isValidStatus(AxAdvancedThermalMitigationInfo info) {
        return info.getStatus() != -1;
    }

    @NeverCompile
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, ipw)) {
            synchronized (this.mLock) {
                ipw.println("mCachedConfig: " + this.mCachedConfigs);
                ipw.println("mCachedExtra: " + this.mCachedExtra);
                ipw.println("mRecords:");
                ipw.increaseIndent();
                ipw.println(this.mRecords);
                ipw.decreaseIndent();
                this.mLocalLog.dump(ipw);
            }
        }
    }
}
