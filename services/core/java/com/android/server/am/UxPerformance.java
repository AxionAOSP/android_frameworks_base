/*
 * Copyright (C) 2025 AxionOS Project
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

import static com.android.server.am.AxUtils.logger;

import android.app.AppGlobals;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemProperties;

import com.android.server.AxExtServiceFactory;
import com.android.server.NtServiceInjector;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;

public class UxPerformance implements IUxPerformance {

    public static final int REQUEST_FAILED = -1;
    public static final int REQUEST_SUCCEEDED = 0;

    private boolean enablePrefetch = false;

    private Context context;
    private IPackageManager packageManager;

    private HandlerThread prefetchHandlerThread;
    private Handler prefetchHandler;

    private HandlerThread pAppsHandlerThread;
    private Handler pAppsHandler;
    
    private boolean screenOff;

    public UxPerformance() {
    }

    public void systemReady() {
        this.enablePrefetch = AxUtils.isPreferredAppsSupported();
        this.context = enablePrefetch ? NtServiceInjector.getCtx() : null;
        this.packageManager = enablePrefetch ? AppGlobals.getPackageManager() : null;

        logger("UxPerformance created: enablePrefetch=" + enablePrefetch);

        if (!enablePrefetch) {
            logger("UxPerformance disabled: preferred apps not supported");
            return;
        }

        prefetchHandlerThread = new HandlerThread("DexPrefetchHandlerThread");
        prefetchHandlerThread.start();
        prefetchHandler = new Handler(prefetchHandlerThread.getLooper());

        pAppsHandlerThread = new HandlerThread("PAppsSpeedHandlerThread");
        pAppsHandlerThread.start();
        pAppsHandler = new Handler(pAppsHandlerThread.getLooper());
    }

    public int perfIOPrefetchStart(int pid, String packageName, String codePath) {
        if (!enablePrefetch) {
            logger("Prefetch disabled");
            return REQUEST_FAILED;
        }

        prefetchHandler.post(() -> dexPrefetch(codePath));
        return REQUEST_SUCCEEDED;
    }

    private void dexPrefetch(String codePath) {
        if (codePath == null) {
            logger("DexPrefetch: codePath is null, aborting");
            return;
        }

        if (codePath.startsWith("/data")) {
            logger("Prefetching data package");
            if (new File(codePath + "/oat/arm64/").exists()) {
                loadFiles(codePath + "/oat/arm64/", "base");
            } else if (new File(codePath + "/oat/arm/").exists()) {
                loadFiles(codePath + "/oat/arm/", "base");
            }
        } else {
            logger("Prefetching system/vendor package");
            String[] parts = codePath.split("/");
            String pkgName = parts[parts.length - 1];

            if (new File(codePath + "/oat/arm64/").exists()) {
                loadFiles(codePath + "/oat/arm64/", pkgName);
            } else if (new File(codePath + "/oat/arm/").exists()) {
                loadFiles(codePath + "/oat/arm/", pkgName);
            }
        }
    }

    private void loadFiles(String dir, String baseName) {
        String[] suffixes = {".art", ".odex", ".vdex"};
        logger("Loading files from: " + dir);

        for (String suffix : suffixes) {
            File file = new File(dir + baseName + suffix);
            if (!file.exists()) continue;

            try (FileChannel channel = new RandomAccessFile(file, "r").getChannel()) {
                MappedByteBuffer buffer =
                        channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());

                logger("Loading file: " + file.getAbsolutePath());
                buffer.load();
            } catch (FileNotFoundException e) {
                logger("File not found: " + file.getAbsolutePath());
            } catch (IOException e) {
                logger("I/O error: " + file.getAbsolutePath());
            }
        }
    }

    private Set<String> getPreferredApps() {
        Set<String> pkgSet = new LinkedHashSet<>();

        List<String> highUsedPkgsList = AxExtServiceFactory.getAppUsageManager()
                .getHighUsedPackageList(false);
        List<String> generalUsedPkgsList = AxExtServiceFactory.getAppUsageManager()
                .getGeneralUsedPackageList(false);

        if (highUsedPkgsList != null) pkgSet.addAll(highUsedPkgsList);
        if (generalUsedPkgsList != null) pkgSet.addAll(generalUsedPkgsList);

        return pkgSet;
    }

    public void setScreenState(boolean off) {
        if (!enablePrefetch) return;
        
        screenOff = off;

        if (screenOff) {
            pAppsHandler.post(this::runPAppsOpt);
            logger("started PAppsSpeed opt after entering idle mode");
        } else {
            pAppsHandler.removeCallbacksAndMessages(null);
            AxExtServiceFactory.getBoostAdjuster().boostInstall(false);
            logger("stopped PAppsSpeed opt due to screen event");
        }
    }

    private void runPAppsOpt() {
        if (!enablePrefetch || packageManager == null) return;

        try {
            boolean useJitProfiles =
                    SystemProperties.getBoolean("dalvik.vm.usejitprofiles", false);
            String defaultFilter = SystemProperties.get("pm.dexopt.bg-dexopt");
            boolean lowStorage = packageManager.isStorageLow();

            if ("speed".equals(defaultFilter) || lowStorage) {
                logger("Skipping PApps opt (filter=" + defaultFilter
                        + ", lowStorage=" + lowStorage + ")");
                return;
            }

            Set<String> pkgSet = getPreferredApps();
            if (pkgSet.isEmpty()) {
                logger("No preferred apps to optimize");
                return;
            }
            
            AxExtServiceFactory.getBoostAdjuster().boostInstall(true);

            logger("Running PAppsSpeed opt, total packages=" + pkgSet.size());

            for (String pkg : pkgSet) {
                if (!screenOff) {
                    logger("PAppsSpeed opt pasued");
                    break;
                }
                boolean success = packageManager.performDexOptMode(
                        pkg, useJitProfiles, "speed", true, true, null);
                logger("Optimized package: " + pkg + " -> " + success);
            }

        } catch (Exception e) {
            logger("PAppsSpeed exception: " + e);
        }
    }
}
