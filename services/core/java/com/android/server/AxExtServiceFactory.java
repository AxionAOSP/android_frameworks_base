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
package com.android.server;

import android.content.Context;

import com.android.server.am.*;
import com.android.server.pm.*;
import com.android.server.spoof.AxSpoofManager;
import com.android.server.spoof.IAxSpoofManager;
import com.android.server.thermal.AxAdvancedThermalMitigationService;
import com.android.server.thermal.IAxAdvancedThermalMitigationService;
import com.android.server.uifirst.AxUiFirstManager;
import com.android.server.uifirst.IAxUiFirstManager;
import com.android.server.wm.AxSandboxService;
import com.android.server.wm.GameSpaceService;
import com.android.server.wm.WindowManagerService;

import java.util.concurrent.ConcurrentHashMap;

public class AxExtServiceFactory {
    private static AxExtServiceFactory sInstance = null;

    private static final int MAX_SERVICE_COUNT = 8;
    private static final ConcurrentHashMap<IAxExtServiceFactory.ExtType, Object> sCache =
            new ConcurrentHashMap<>(MAX_SERVICE_COUNT);

    private AxExtServiceFactory(Context context) {
        NtServiceInjector.get().setCtx(context);
    }

    public static synchronized AxExtServiceFactory init(Context context) {
        if (sInstance == null) {
            sInstance = new AxExtServiceFactory(context);
        }
        return sInstance;
    }

    public static AxExtServiceFactory get() {
        if (sInstance == null) {
            throw new IllegalStateException("AxExtServiceFactory not initialized");
        }
        return sInstance;
    }

    public static void injectActivityManagerService(ActivityManagerService ams) {
        NtServiceInjector.get().setActivityManagerService(ams);
    }

    public static void injectWindowManagerService(WindowManagerService wms) {
        NtServiceInjector.get().setWindowManagerService(wms);
    }

    public static void injectPackageManagerservice(PackageManagerService pm) {
        NtServiceInjector.get().setPackageManagerService(pm);
    }

    @SuppressWarnings("unchecked")
    public static <T> T getOrCreate(IAxExtServiceFactory.ExtType type) {
        Object obj = sCache.get(type);
        if (obj == null) {
            obj = create(type);
            sCache.put(type, obj);
        }
        return (T) obj;
    }

    private static Object create(IAxExtServiceFactory.ExtType type) {
        switch (type) {
            case AX_BURST_ENGINE:
                return new AxBurstEngine();
            case AX_MEMORY_MANAGER:
                return new AxMemoryManagerImpl();
            case UX_PERFORMANCE:
                return new UxPerformance();
            case PC_MODE_SERVICE:
                return new AxPcModeService();
            case AX_SPOOF_MANAGER:
                return new AxSpoofManager();
            case AX_UI_FIRST_MANAGER:
                return new AxUiFirstManager();
            case AX_BACKGROUND_MANAGER:
                return new AxBackgroundManager();
            case AX_FREEZE_MANAGER:
                return new AxFreezeManager();
            case AX_ADVANCED_THERMAL_MITIGATION:
                return new AxAdvancedThermalMitigationService();
            default:
                throw new IllegalArgumentException("Unknown ExtType: " + type);
        }
    }

    public static void systemReady() {
        GameSpaceService.systemReady();
        AxSandboxService.systemReady();
        getAxPcModeService().systemReady();
        getAxBackgroundManager().systemReady();
        getAxFreezeManager().systemReady();
    }

    public static void onLateSystemReady() {
        OnlineConfigObserver.systemReady();
        getAxBurstEngine().systemReady();
        getMemoryManager().systemReady();
        getUxPerformance().systemReady();
        getSpoofManager().systemReady();
        getAdvancedThermalMitigationService().systemReady();
    }

    public static IAxBurstEngine getAxBurstEngine() {
        return getOrCreate(IAxExtServiceFactory.ExtType.AX_BURST_ENGINE);
    }

    public static IAxMemoryManager getMemoryManager() {
        return getOrCreate(IAxExtServiceFactory.ExtType.AX_MEMORY_MANAGER);
    }

    public static IUxPerformance getUxPerformance() {
        return getOrCreate(IAxExtServiceFactory.ExtType.UX_PERFORMANCE);
    }

    public static IAxPcModeService getAxPcModeService() {
        return getOrCreate(IAxExtServiceFactory.ExtType.PC_MODE_SERVICE);
    }

    public static IAxSpoofManager getSpoofManager() {
        return getOrCreate(IAxExtServiceFactory.ExtType.AX_SPOOF_MANAGER);
    }

    public static IAxUiFirstManager getUiFirstManager() {
        return getOrCreate(IAxExtServiceFactory.ExtType.AX_UI_FIRST_MANAGER);
    }

    public static IAxAdvancedThermalMitigationService getAdvancedThermalMitigationService() {
        return getOrCreate(IAxExtServiceFactory.ExtType.AX_ADVANCED_THERMAL_MITIGATION);
    }

    public static AxBackgroundManager getAxBackgroundManager() {
        return getOrCreate(IAxExtServiceFactory.ExtType.AX_BACKGROUND_MANAGER);
    }
    
    public static AxFreezeManager getAxFreezeManager() {
        return getOrCreate(IAxExtServiceFactory.ExtType.AX_FREEZE_MANAGER);
    }
}
