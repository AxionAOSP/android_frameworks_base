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

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.android.server.AxExtServiceFactory;
import com.android.server.LocalServices;
import com.android.server.NtServiceInjector;
import com.android.server.am.IAxBurstEngine;
import com.android.server.pm.DexOptHelper;

import java.util.List;

public class AxAdvancedThermalMitigationService implements IAxAdvancedThermalMitigationService {
    private static final String TAG = "AxAdvancedThermalMitigationService";

    private final AxAdvancedThermalMitigationDispatcher mDispatcher;
    private final AxAdvancedThermalMitigationProducer mProducer;
    private int mLastBgKillerStatus = -1;
    private int mLastDexoptStatus = -1;

    public AxAdvancedThermalMitigationService() {
        Context context = NtServiceInjector.getCtx();
        mDispatcher = new AxAdvancedThermalMitigationDispatcher(context);
        mProducer = new AxAdvancedThermalMitigationProducer(context, mDispatcher);
    }

    @Override
    public void systemReady() {
        registerBurstEngineSubscriber();
        registerBgKillerSubscriber();
        registerDexoptSubscriber();
        mProducer.start();
    }

    private void registerBurstEngineSubscriber() {
        String[] units =
                new String[] {
                    AxAdvancedThermalMitigationConfig.UNIT_CPU,
                    AxAdvancedThermalMitigationConfig.UNIT_GPU
                };
        IAxAdvancedThermalMitigationListener listener =
                new IAxAdvancedThermalMitigationListener() {
                    @Override
                    public void onThermalStatus(
                            List<AxAdvancedThermalMitigationInfo> infos, Bundle bundle) {
                        int cpuCap = -1;
                        int gpuCap = -1;
                        for (AxAdvancedThermalMitigationInfo info : infos) {
                            if (AxAdvancedThermalMitigationConfig.UNIT_CPU.equals(info.getUnit())) {
                                cpuCap = info.getStatus();
                            } else if (AxAdvancedThermalMitigationConfig.UNIT_GPU.equals(
                                    info.getUnit())) {
                                gpuCap = info.getStatus();
                            }
                        }
                        int level = (bundle != null) ? bundle.getInt("thermalLevel", 0) : 0;
                        IAxBurstEngine engine = AxExtServiceFactory.getAxBurstEngine();
                        if (engine != null) {
                            engine.setThermalState(level, cpuCap, gpuCap);
                        }
                    }
                };
        mDispatcher.listenWithUnitList(listener, units, "system_server");
    }

    private void registerBgKillerSubscriber() {
        String[] units = new String[] {AxAdvancedThermalMitigationConfig.UNIT_BG_KILLER};
        IAxAdvancedThermalMitigationListener listener =
                new IAxAdvancedThermalMitigationListener() {
                    @Override
                    public void onThermalStatus(
                            List<AxAdvancedThermalMitigationInfo> infos, Bundle bundle) {
                        int status = -1;
                        for (AxAdvancedThermalMitigationInfo info : infos) {
                            if (AxAdvancedThermalMitigationConfig.UNIT_BG_KILLER.equals(
                                    info.getUnit())) {
                                status = info.getStatus();
                                break;
                            }
                        }
                        if (status == mLastBgKillerStatus) return;
                        mLastBgKillerStatus = status;
                        if (status < 1) return;
                        int maxProcState = bgKillerStatusToMaxProcState(status);
                        ActivityManagerInternal ami =
                                LocalServices.getService(ActivityManagerInternal.class);
                        if (ami != null) {
                            Log.i(
                                    TAG,
                                    "bg_killer status=" + status + " maxProcState=" + maxProcState);
                            try {
                                ami.killAllBackgroundProcessesExcept(-1, maxProcState);
                            } catch (Exception e) {
                                Log.e(TAG, "killAllBackgroundProcessesExcept failed", e);
                            }
                        }
                    }
                };
        mDispatcher.listenWithUnitList(listener, units, "system_server");
    }

    private void registerDexoptSubscriber() {
        String[] units = new String[] {AxAdvancedThermalMitigationConfig.UNIT_DEXOPT};
        IAxAdvancedThermalMitigationListener listener =
                new IAxAdvancedThermalMitigationListener() {
                    @Override
                    public void onThermalStatus(
                            List<AxAdvancedThermalMitigationInfo> infos, Bundle bundle) {
                        int status = -1;
                        for (AxAdvancedThermalMitigationInfo info : infos) {
                            if (AxAdvancedThermalMitigationConfig.UNIT_DEXOPT.equals(
                                    info.getUnit())) {
                                status = info.getStatus();
                                break;
                            }
                        }
                        if (status == mLastDexoptStatus) return;
                        mLastDexoptStatus = status;
                        if (status >= 1) {
                            try {
                                if (DexOptHelper.artManagerLocalIsInitialized()) {
                                    DexOptHelper.getArtManagerLocal().cancelBackgroundDexoptJob();
                                    Log.i(
                                            TAG,
                                            "dexopt status="
                                                    + status
                                                    + " background dexopt cancelled");
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "cancelBackgroundDexoptJob failed", e);
                            }
                        }
                    }
                };
        mDispatcher.listenWithUnitList(listener, units, "system_server");
    }

    private static int bgKillerStatusToMaxProcState(int status) {
        switch (status) {
            case 1:
                return ActivityManager.PROCESS_STATE_LAST_ACTIVITY;
            case 2:
                return ActivityManager.PROCESS_STATE_SERVICE;
            case 3:
                return ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND;
            default:
                return ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
        }
    }

    @Override
    public AxAdvancedThermalMitigationDispatcher getDispatcher() {
        return mDispatcher;
    }

    @Override
    public AxAdvancedThermalMitigationProducer getProducer() {
        return mProducer;
    }
}
