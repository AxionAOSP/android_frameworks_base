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
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.StrictMode;
import android.provider.Settings;
import android.text.TextUtils;

import com.android.server.AxExtServiceFactory;
import com.android.server.NtServiceInjector;
import com.android.server.pm.*;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
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

    private static final String DEXOPT_TRACKER_KEY = "ux_dexopt_tracker";
    private static final long DEXOPT_EXPIRY_MS = 15L * 24 * 60 * 60 * 1000;

    private static final String LAST_FULL_RUN_KEY = "ux_dexopt_last_full_run";
    private static final long FULL_RUN_INTERVAL_MS = 15L * 24 * 60 * 60 * 1000; 

    private static final String[] OAT_DIRS = {"/oat/arm64/", "/oat/arm/"};
    private static final String[] FILE_SUFFIXES = {".art", ".odex", ".vdex"};

    private final ConcurrentHashMap<File, WeakReference<MappedByteBuffer>> mappedDexBuffers = new ConcurrentHashMap<>();

    private volatile boolean screenOff;
    private boolean systemReady = false;

    private IPackageManager packageManager;
    private PackageManagerService mPackageService;
    private DexOptHelper mDexOptHelper;

    private Handler dexPreloadHandler;
    private Handler pAppsHandler;

    private final Object dexoptLock = new Object();
    private final Object preloadLock = new Object();

    private ExecutorService prefetchExecutor;

    public UxPerformance() {}

    public void systemReady() {
        mPackageService = NtServiceInjector.getPm();
        packageManager = AppGlobals.getPackageManager();
        mDexOptHelper = (mPackageService != null) ? mPackageService.getDexOptHelper() : null;

        dexPreloadHandler = createHandler("DexPrefetchHandlerThread");
        pAppsHandler = createHandler("PAppsSpeedHandlerThread");

        prefetchExecutor = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors(), r -> {
                    Thread t = new Thread(r, "DexPrefetchWorker");
                    t.setDaemon(true);
                    return t;
                });

        systemReady = true;

        pAppsHandler.post(this::cleanupDexoptTracker);
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
        if (!systemReady || packageManager == null || mDexOptHelper == null) return;

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

            List<String> allPackages = getAllInstalledPackages();
            if (allPackages.isEmpty()) {
                logger("No installed packages found for dexopt");
                return;
            }

            if (AxExtServiceFactory.getProcessManager().isThermalHigh()) {
                logger("Skipping dexopt: high device temperature");
                return;
            }

            allPackages = sortPackagesByUsageStats(allPackages);

            Map<String, Long> trackerSnapshot = getDexoptTracker();

            int total = allPackages.size();
            int processed = 0;
            int skipped = 0;
            boolean fullRunCompleted = true;

            AxExtServiceFactory.getBoostAdjuster().boostInstall(true);
            logger("----- Dexopt started (" + total + " packages) -----");

            for (String pkg : allPackages) {

                if (AxExtServiceFactory.getProcessManager().isThermalHigh()) {
                    logger("Dexopt aborted: device overheating");
                    fullRunCompleted = false;
                    break;
                }

                if (!screenOff) {
                    logger("Dexopt cancelled mid-run (screen ON)");
                    fullRunCompleted = false;
                    break;
                }

                if (shouldSkipDexopt(pkg, trackerSnapshot)) {
                    skipped++;
                    continue;
                }

                int result = mDexOptHelper.doDexoptPackage(pkg);

                trackerSnapshot.put(pkg, System.currentTimeMillis());

                switch (result) {
                    case PackageDexOptimizer.DEX_OPT_PERFORMED:
                        logger("Dex optimization performed for pkg=" + pkg);
                        break;

                    case PackageDexOptimizer.DEX_OPT_SKIPPED:
                        logger("Dex optimization skipped for pkg=" + pkg);
                        skipped++;
                        break;

                    case PackageDexOptimizer.DEX_OPT_CANCELLED:
                        logger("Dex optimization cancelled for pkg=" + pkg);
                        skipped++;
                        break;

                    case PackageDexOptimizer.DEX_OPT_FAILED:
                        logger("Dex optimization FAILED for pkg=" + pkg);
                        skipped++;
                        break;

                    default:
                        logger("Unknown dexopt result (" + result + ") for pkg=" + pkg);
                        skipped++;
                        break;
                }

                processed++;
                int finalTotal = total - skipped;

                if (processed % 20 == 0 || processed == finalTotal)
                    logger("Dexopt progress: " + processed + "/" + finalTotal);
            }

            saveDexoptTracker(trackerSnapshot);
            
            if (fullRunCompleted) {
                saveLastFullRunTime(System.currentTimeMillis());
                logger("----- Dexopt FULL RUN completed (" + processed + "/" + (total - skipped) + 
                       " processed). Next run in 15 days -----");
            } else {
                logger("----- Dexopt incomplete run (" + processed + "/" + (total - skipped) + 
                       " processed). Will retry on next screen off -----");
            }

        } catch (Exception e) {
            logger("Dexopt exception: " + e);
        } finally {
            AxExtServiceFactory.getBoostAdjuster().boostInstall(false);
        }
    }

    private boolean shouldSkipDexopt(String pkg, Map<String, Long> tracker) {
        Long last = tracker.get(pkg);
        return last != null && (System.currentTimeMillis() - last < DEXOPT_EXPIRY_MS);
    }

    private List<String> sortPackagesByUsageStats(List<String> pkgs) {
        if (pkgs == null || pkgs.isEmpty()) return pkgs;

        try {
            Context context = NtServiceInjector.getCtx();
            UsageStatsManager usm =
                    (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return pkgs;

            long now = System.currentTimeMillis();
            long interval = 3L * 24 * 60 * 60 * 1000;

            List<UsageStats> stats =
                    usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - interval, now);
            if (stats == null || stats.isEmpty()) return pkgs;

            Map<String, Long> usageMap = new HashMap<>();
            for (UsageStats stat : stats) {
                long time = stat.getTotalTimeInForeground();
                if (time > 0) usageMap.put(stat.getPackageName(), time);
            }

            pkgs.sort((a, b) -> Long.compare(
                    usageMap.getOrDefault(b, 0L),
                    usageMap.getOrDefault(a, 0L))
            );

            logger("Packages prioritized by usage stats (" + usageMap.size() + " entries)");
        } catch (Exception e) {
            logger("sortPackagesByUsageStats failed: " + e);
        }

        return pkgs;
    }

    private Map<String, Long> getDexoptTracker() {
        return withDexoptLock(() -> {
            Map<String, Long> map = new HashMap<>();
            try {
                Context ctx = NtServiceInjector.getCtx();
                String json = Settings.Secure.getString(ctx.getContentResolver(), DEXOPT_TRACKER_KEY);
                if (json == null) return map;

                JSONObject obj = new JSONObject(json);
                for (Iterator<String> keys = obj.keys(); keys.hasNext();) {
                    String key = keys.next();
                    map.put(key, obj.getLong(key));
                }
            } catch (Exception ignored) {}
            return map;
        });
    }

    private void saveDexoptTracker(Map<String, Long> map) {
        withDexoptLock(() -> {
            try {
                JSONObject obj = new JSONObject(map);
                Context ctx = NtServiceInjector.getCtx();
                Settings.Secure.putString(ctx.getContentResolver(), DEXOPT_TRACKER_KEY, obj.toString());
                logger("saving status. status=" + obj);
            } catch (Exception ignored) {}
        });
    }

    private void cleanupDexoptTracker() {
        Map<String, Long> trackerSnapshot = getDexoptTracker();
        if (trackerSnapshot.isEmpty()) return;

        Set<String> installedSet = new HashSet<>(getAllInstalledPackages());
        int before = trackerSnapshot.size();
        trackerSnapshot.keySet().removeIf(pkg -> !installedSet.contains(pkg));

        if (trackerSnapshot.size() < before) {
            saveDexoptTracker(trackerSnapshot);
            logger("Cleaned dexopt tracker: removed " + (before - trackerSnapshot.size()) + " stale entries");
        }
    }

    private List<String> getAllInstalledPackages() {
        List<String> result = new ArrayList<>();
        try {
            List<PackageInfo> infos =
                    packageManager.getInstalledPackages(0, 0).getList();
            for (PackageInfo pi : infos) {
                if (pi != null && !TextUtils.isEmpty(pi.packageName)) {
                    result.add(pi.packageName);
                }
            }
        } catch (Exception e) {
            logger("getAllInstalledPackages failed: " + e);
        }
        return result;
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

    private void withDexoptLock(Runnable action) {
        synchronized (dexoptLock) {
            action.run();
        }
    }

    private <T> T withDexoptLock(java.util.function.Supplier<T> action) {
        synchronized (dexoptLock) {
            return action.get();
        }
    }
}
