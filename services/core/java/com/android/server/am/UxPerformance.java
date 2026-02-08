/*
 * Copyright (C) 2025-2026 AxionOS Project
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
import android.os.StrictMode;
import android.provider.Settings;
import android.util.Log;

import com.android.server.AxExtServiceFactory;
import com.android.server.NtServiceInjector;
import com.android.server.pm.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.lang.ref.WeakReference;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UxPerformance implements IUxPerformance {

    private static final String TAG = "UxPerformance: ";

    private static final String LAST_FULL_RUN_KEY = "ux_dexopt_last_full_run";
    private static final long FULL_RUN_INTERVAL_MS = 15L * 24 * 60 * 60 * 1000; 

    private static final String[] OAT_DIRS = {"/oat/arm64/", "/oat/arm/"};
    private static final String[] FILE_SUFFIXES = {".art", ".odex", ".vdex"};

    private final ConcurrentHashMap<File, WeakReference<MappedByteBuffer>> mappedDexBuffers = new ConcurrentHashMap<>();

    private volatile boolean screenOff;
    private volatile boolean isDexoptRunning = false;

    private boolean systemReady = false;

    private IPackageManager packageManager;

    private Handler dexPreloadHandler;
    private Handler pAppsHandler;

    private final Object preloadLock = new Object();

    private ExecutorService prefetchExecutor;

    public UxPerformance() {}

    public void systemReady() {
        packageManager = AppGlobals.getPackageManager();

        dexPreloadHandler = createHandler("DexPrefetchHandlerThread");
        pAppsHandler = createHandler("PAppsSpeedHandlerThread");

        prefetchExecutor = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors(), r -> {
                    Thread t = new Thread(r, "DexPrefetchWorker");
                    t.setDaemon(true);
                    return t;
                });

        systemReady = true;
    }

    private Handler createHandler(String name) {
        HandlerThread thread = new HandlerThread(name);
        thread.start();
        return new Handler(thread.getLooper());
    }

    public void perfIOPrefetchStart(int pid, String packageName, String codePath) {
        if (!systemReady || codePath == null) return;

        dexPreloadHandler.post(() -> {
            try {
                dexPrefetch(codePath);
            } catch (Exception e) {
                logger(TAG + "dexPreload failed: " + e);
            }
        });
    }

    private void dexPrefetch(String codePath) {
        List<File> filesToLoad = new ArrayList<>();

        for (String dirSuffix : OAT_DIRS) {
            File dir = new File(codePath + dirSuffix);
            if (!dir.exists()) continue;

            String baseName = codePath.startsWith("/data")
                    ? "base"
                    : codePath.substring(codePath.lastIndexOf('/') + 1);

            for (String suffix : FILE_SUFFIXES) {
                File f = new File(dir, baseName + suffix);
                if (f.exists()) filesToLoad.add(f);
            }
            break;
        }

        for (File f : filesToLoad) {
            prefetchExecutor.submit(() -> preloadFile(f));
        }
    }

    private void preloadFile(File file) {
        try {
            WeakReference<MappedByteBuffer> ref = mappedDexBuffers.get(file);
            MappedByteBuffer buffer = ref != null ? ref.get() : null;

            if (buffer != null) {
                logger(TAG + "Dex already mapped in memory: " + file.getAbsolutePath());
                return;
            }

            try (FileChannel channel = new RandomAccessFile(file, "r").getChannel()) {
                buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
                buffer.load();
            }

            mappedDexBuffers.put(file, new WeakReference<>(buffer));

            logger(TAG + "Dex prefetch completed: " + file.getAbsolutePath());
        } catch (IOException e) {
            logger(TAG + "Dex prefetch failed: " + file.getAbsolutePath() + " - " + e);
        }
    }

    public void setScreenState(boolean off) {
        if (!systemReady) return;

        screenOff = off;
        pAppsHandler.removeCallbacksAndMessages(null);

        if (screenOff) {
            pAppsHandler.post(this::runPAppsOpt);
            logger("Started dexopt on screen OFF");
        } else {
            logger("Cancelled dexopt on screen ON");
        }
    }

    private void runPAppsOpt() {
        if (!systemReady || packageManager == null) return;

        if (isDexoptRunning) {
             logger("Skipping dexopt: already running");
             return;
        }

        isDexoptRunning = true;
        try {
            long lastFullRun = getLastFullRunTime();
            long timeSinceLastRun = System.currentTimeMillis() - lastFullRun;
            
            if (lastFullRun > 0 && timeSinceLastRun < FULL_RUN_INTERVAL_MS) {
                long daysRemaining = (FULL_RUN_INTERVAL_MS - timeSinceLastRun) / (24 * 60 * 60 * 1000);
                logger("Skipping dexopt: last full run was " + (timeSinceLastRun / (24 * 60 * 60 * 1000)) + 
                       " days ago. Next run in " + daysRemaining + " days");
                return;
            }

            if (packageManager.isStorageLow()) {
                logger("Skipping dexopt: low storage");
                return;
            }

            logger("Starting dexopt FULL RUN...");

            boolean compileSuccess = runCommand("package compile -m everything -f -a --full", "Dexopt");
            boolean bgDexoptSuccess = runCommand("package bg-dexopt-job", "Bg-dexopt-job");

            if (compileSuccess && bgDexoptSuccess) {
                saveLastFullRunTime(System.currentTimeMillis());
                Log.d(TAG, "----- Dexopt FULL RUN completed. Next run in 15 days -----");
            } else {
                isDexoptRunning = false;
                Log.e(TAG, "Dexopt FULL RUN failed. Compile: " + compileSuccess + ", Bg-Dexopt: " + bgDexoptSuccess);
            }

        } catch (Exception e) {
            isDexoptRunning = false;
            Log.e(TAG, "Dexopt exception: ", e);
        } finally {
            isDexoptRunning = false;
        }
    }

    private boolean runCommand(String cmdArgs, String logTag) {
        try {
            String[] args = ("/system/bin/cmd " + cmdArgs).split(" ");
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("Success") || line.contains("Job running")) {
                        Log.d(TAG, logTag + ": " + line);
                    } else {
                        Log.w(TAG, logTag + " output: " + line);
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return true;
            } else {
                Log.e(TAG, logTag + " failed with exit code: " + exitCode);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, logTag + " execution failed: ", e);
            return false;
        }
    }

    private long getLastFullRunTime() {
        try {
            Context ctx = NtServiceInjector.getCtx();
            String value = Settings.Secure.getString(ctx.getContentResolver(), LAST_FULL_RUN_KEY);
            return value != null ? Long.parseLong(value) : 0L;
        } catch (Exception e) {
            logger("getLastFullRunTime failed: " + e);
            return 0L;
        }
    }

    private void saveLastFullRunTime(long timestamp) {
        try {
            Context ctx = NtServiceInjector.getCtx();
            Settings.Secure.putString(ctx.getContentResolver(), LAST_FULL_RUN_KEY, String.valueOf(timestamp));
            logger("Saved last full run timestamp: " + timestamp);
        } catch (Exception e) {
            logger("saveLastFullRunTime failed: " + e);
        }
    }
}
