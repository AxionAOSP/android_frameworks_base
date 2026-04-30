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

import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.StrictMode;
import android.os.SystemClock;
import android.provider.Settings;
import android.app.AxBoostFwk;
import android.util.Log;

import com.android.server.AxExtServiceFactory;
import com.android.server.LocalServices;
import com.android.server.NtServiceInjector;
import com.android.server.pm.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
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
    
    private static native int nativePosixFadvise(FileDescriptor fd, long offset, long len, int advice);

    private static final String LAST_FULL_RUN_KEY = "ux_dexopt_last_full_run";
    private static final long FULL_RUN_INTERVAL_MS = 30L * 24 * 60 * 60 * 1000; 

    private static final String[] OAT_DIRS = {"/oat/arm64/", "/oat/arm/"};
    private static final String[] FILE_SUFFIXES = {".art", ".odex", ".vdex"};

    private static final int MAX_GRAPH_APPS = 100;
    private static final int MAX_TARGETS_PER_APP = 20;
    private static final long DECAY_INTERVAL_MS = 24L * 60 * 60 * 1000;

    private final ConcurrentHashMap<File, WeakReference<MappedByteBuffer>> mappedDexBuffers = new ConcurrentHashMap<>();

    private volatile boolean screenOff;
    private volatile boolean isDexoptRunning = false;

    private boolean systemReady = false;

    private IPackageManager packageManager;

    private Handler dexPreloadHandler;
    private Handler pAppsHandler;
    private Handler mPredictorHandler;

    private final Object preloadLock = new Object();
    private final Object mPredictorLock = new Object();

    private ExecutorService prefetchExecutor;

    private final Map<String, Map<String, Integer>> mTransitionGraph = new LinkedHashMap<>();
    private String mLastAppPkg = null;
    private String mLastPredictedPkg = null;
    private long mLastDecayTime = 0;

    public UxPerformance() {}

    public void systemReady() {
        packageManager = AppGlobals.getPackageManager();

        dexPreloadHandler = createHandler("DexPrefetchHandlerThread");
        pAppsHandler = createHandler("PAppsSpeedHandlerThread");
        mPredictorHandler = createHandler("UxPredictorHandlerThread");

        prefetchExecutor = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors(), r -> {
                    Thread t = new Thread(r, "DexPrefetchWorker");
                    t.setDaemon(true);
                    return t;
                });

        mLastDecayTime = SystemClock.elapsedRealtime();
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
                ioPrefetch(codePath);
            } catch (Exception e) {
                logger(TAG + "dexPreload failed: " + e);
            }
        });
    }

    public void perfIOPrefetchStop() {
        mappedDexBuffers.clear();
    }

    public void uxEngineEvent(int opcode, int pid, String pkgName, int lat) {
        if (!systemReady || pkgName == null) return;

        mPredictorHandler.post(() -> {
            switch (opcode) {
                case AxBoostFwk.UXE_EVENT_BINDAPP:
                case AxBoostFwk.UXE_EVENT_DISPLAYED_ACT:
                case AxBoostFwk.UXE_EVENT_SUB_LAUNCH:
                    recordTransition(mLastAppPkg, pkgName);
                    mLastAppPkg = pkgName;
                    break;
                case AxBoostFwk.UXE_EVENT_KILL:
                    removeAppFromGraph(pkgName);
                    if (pkgName.equals(mLastAppPkg)) {
                        mLastAppPkg = null;
                    }
                    if (pkgName.equals(mLastPredictedPkg)) {
                        mLastPredictedPkg = null;
                    }
                    break;
                case AxBoostFwk.UXE_EVENT_PKG_UNINSTALL:
                    removeAppFromGraph(pkgName);
                    if (pkgName.equals(mLastAppPkg)) {
                        mLastAppPkg = null;
                    }
                    if (pkgName.equals(mLastPredictedPkg)) {
                        mLastPredictedPkg = null;
                    }
                    break;
                case AxBoostFwk.UXE_EVENT_PKG_INSTALL:
                case AxBoostFwk.UXE_EVENT_GAME:
                default:
                    break;
            }
        });
    }

    public String uxEngineTrigger() {
        if (!systemReady) return null;

        decayIfNeeded();

        String predicted = null;
        synchronized (mPredictorLock) {
            predicted = getPredictedApp();
        }

        if (predicted != null && !predicted.equals(mLastPredictedPkg)) {
            mLastPredictedPkg = predicted;
            startEmptyActivityForPkg(predicted);
        }

        return predicted;
    }

    private void recordTransition(String fromPkg, String toPkg) {
        if (fromPkg == null || toPkg == null || fromPkg.equals(toPkg)) return;

        synchronized (mPredictorLock) {
            Map<String, Integer> targets = mTransitionGraph.get(fromPkg);
            if (targets == null) {
                if (mTransitionGraph.size() >= MAX_GRAPH_APPS) {
                    Iterator<String> it = mTransitionGraph.keySet().iterator();
                    if (it.hasNext()) {
                        it.next();
                        it.remove();
                    }
                }
                targets = new LinkedHashMap<>();
                mTransitionGraph.put(fromPkg, targets);
            }

            if (targets.size() >= MAX_TARGETS_PER_APP && !targets.containsKey(toPkg)) {
                Iterator<Map.Entry<String, Integer>> it = targets.entrySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }

            targets.put(toPkg, targets.getOrDefault(toPkg, 0) + 1);
        }
    }

    private void removeAppFromGraph(String pkg) {
        if (pkg == null) return;

        synchronized (mPredictorLock) {
            mTransitionGraph.remove(pkg);
            for (Map<String, Integer> targets : mTransitionGraph.values()) {
                targets.remove(pkg);
            }
        }
    }

    private void decayIfNeeded() {
        long now = SystemClock.elapsedRealtime();
        if (now - mLastDecayTime < DECAY_INTERVAL_MS) return;
        mLastDecayTime = now;

        synchronized (mPredictorLock) {
            Iterator<Map.Entry<String, Map<String, Integer>>> srcIt = mTransitionGraph.entrySet().iterator();
            while (srcIt.hasNext()) {
                Map.Entry<String, Map<String, Integer>> srcEntry = srcIt.next();
                Map<String, Integer> targets = srcEntry.getValue();
                Iterator<Map.Entry<String, Integer>> tgtIt = targets.entrySet().iterator();
                while (tgtIt.hasNext()) {
                    Map.Entry<String, Integer> tgtEntry = tgtIt.next();
                    int newCount = tgtEntry.getValue() / 2;
                    if (newCount <= 0) {
                        tgtIt.remove();
                    } else {
                        tgtEntry.setValue(newCount);
                    }
                }
                if (targets.isEmpty()) {
                    srcIt.remove();
                }
            }
        }
    }

    private String getPredictedApp() {
        if (mLastAppPkg == null) return null;

        Map<String, Integer> targets = mTransitionGraph.get(mLastAppPkg);
        if (targets == null || targets.isEmpty()) return null;

        String bestPkg = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : targets.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                bestPkg = entry.getKey();
            }
        }
        return bestPkg;
    }

    private void startEmptyActivityForPkg(String pkg) {
        try {
            ActivityManagerInternal ami = LocalServices.getService(ActivityManagerInternal.class);
            if (ami == null) return;

            ArrayList<String> apps = new ArrayList<>();
            apps.add(pkg);
            Bundle b = new Bundle();
            b.putStringArrayList("start_empty_apps", apps);
            ami.startActivityAsUserEmpty(b);
        } catch (Exception e) {
            Log.w(TAG, "startEmptyActivity failed: " + e);
        }
    }

    private void fadviseFile(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            nativePosixFadvise(raf.getFD(), 0, file.length(), 0);
        } catch (Exception e) {
            logger(TAG + "fadvise failed: " + file.getAbsolutePath() + " - " + e);
        }
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

    private void ioPrefetch(String codePath) {
        List<File> filesToLoad = new ArrayList<>();

        File apkFile = new File(codePath + ".apk");
        if (apkFile.exists()) {
            filesToLoad.add(apkFile);
        }

        File libDir = new File(codePath, "lib/arm64");
        if (!libDir.exists()) {
            libDir = new File(codePath, "lib/arm");
        }
        if (libDir.exists()) {
            File[] soFiles = libDir.listFiles((dir, name) -> name.endsWith(".so"));
            if (soFiles != null) {
                filesToLoad.addAll(Arrays.asList(soFiles));
            }
        }

        for (File f : filesToLoad) {
            prefetchExecutor.submit(() -> preloadFile(f));
            prefetchExecutor.submit(() -> fadviseFile(f));
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
