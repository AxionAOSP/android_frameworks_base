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

import com.android.server.am.*;
import com.android.server.wm.WindowManagerService;
import com.android.server.INtAppUsageManager;

public class AxExtServiceFactory {
    private static final Object sLock = new Object();

    private static volatile INtMemoryManager sNtMemoryManager;
    private static volatile INtAppUsageManager sNtAppUsageManager;
    private static volatile IBoostAdjuster sBoostAdjuster;
    private static volatile IProcessManager sProcessManager;

    private AxExtServiceFactory() { }

    @SuppressWarnings("unchecked")
    public static <T> T getOrCreate(IAxExtServiceFactory.ExtType type) {
        Object instance;
        switch (type) {
            case NT_MEMORY_MANAGER:
                if (sNtMemoryManager == null) {
                    synchronized (sLock) {
                        if (sNtMemoryManager == null) {
                            sNtMemoryManager = new NtMemoryManagerImpl();
                        }
                    }
                }
                instance = sNtMemoryManager;
                break;

            case NT_APP_USAGE_MANAGER:
                if (sNtAppUsageManager == null) {
                    synchronized (sLock) {
                        if (sNtAppUsageManager == null) {
                            sNtAppUsageManager = new NtAppUsageManagerImpl();
                        }
                    }
                }
                instance = sNtAppUsageManager;
                break;

            case BOOST_ADJUSTER:
                if (sBoostAdjuster == null) {
                    synchronized (sLock) {
                        if (sBoostAdjuster == null) {
                            sBoostAdjuster = new BoostAdjuster();
                        }
                    }
                }
                instance = sBoostAdjuster;
                break;

            case PROCESS_MANAGER:
                if (sProcessManager == null) {
                    synchronized (sLock) {
                        if (sProcessManager == null) {
                            sProcessManager = new ProcessManager();
                        }
                    }
                }
                instance = sProcessManager;
                break;

            default:
                throw new IllegalArgumentException("Unknown ExtType: " + type);
        }

        return (T) type.getClazz().cast(instance);
    }

    public static void systemReady() {
        getProcessManager().systemReady();
        getBoostAdjuster().systemReady();
        getAppUsageManager().systemReady();
        getMemoryManager().systemReady();
    }

    public static INtMemoryManager getMemoryManager() {
        return getOrCreate(IAxExtServiceFactory.ExtType.NT_MEMORY_MANAGER);
    }

    public static INtAppUsageManager getAppUsageManager() {
        return getOrCreate(IAxExtServiceFactory.ExtType.NT_APP_USAGE_MANAGER);
    }

    public static IBoostAdjuster getBoostAdjuster() {
        return getOrCreate(IAxExtServiceFactory.ExtType.BOOST_ADJUSTER);
    }

    public static IProcessManager getProcessManager() {
        return getOrCreate(IAxExtServiceFactory.ExtType.PROCESS_MANAGER);
    }
}
