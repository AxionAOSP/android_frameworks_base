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
import android.provider.Settings;
import android.text.TextUtils;

import com.android.server.AxExtServiceFactory;
import com.android.server.NtServiceInjector;
import com.android.server.pm.*;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class UxPerformance implements IUxPerformance {

    public static final int REQUEST_FAILED = -1;
    public static final int REQUEST_SUCCEEDED = 0;

    private static final String DEXOPT_TRACKER_KEY = "ux_dexopt_tracker";
    private static final long DEXOPT_EXPIRY_MS = 15L * 24 * 60 * 60 * 1000;
    private static final String[] OAT_DIRS = {"/oat/arm64/", "/oat/arm/"};
    private static final String[] FILE_SUFFIXES = {".art", ".odex", ".vdex"};

    private boolean enablePrefetch = true;
    private volatile boolean screenOff;
    private boolean systemReady = false;

    private IPackageManager packageManager;
    private PackageManagerService mPackageService;
    private DexOptHelper mDexOptHelper;

    private Handler prefetchHandler;
    private Handler pAppsHandler;

    private final Object dexoptLock = new Object();
    private final Object prefetchLock = new Object();

    private ExecutorService prefetchExecutor;

    public UxPerformance() {}

    public void systemReady() {
        if (!enablePrefetch) {
            logger("UxPerformance disabled: prefetch not supported");
            return;
        }

        mPackageService = NtServiceInjector.getPm();
        packageManager = AppGlobals.getPackageManager();
        mDexOptHelper = (mPackageService != null) ? mPackageService.getDexOptHelper() : null;

        logger("UxPerformance initialized (enablePrefetch=" + enablePrefetch + ")");

        prefetchHandler = createHandler("DexPrefetchHandlerThread");
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

    public int perfIOPrefetchStart(int pid, String packageName, String codePath) {
        if (!systemReady || !enablePrefetch || codePath == null) return REQUEST_FAILED;
        prefetchHandler.post(() -> dexPrefetch(codePath));
        return REQUEST_SUCCEEDED;
    }

    private void dexPrefetch(String codePath) {
        List<File> filesToLoad = new ArrayList<>();

        for (String dirSuffix : OAT_DIRS) {
            File dir = new File(codePath + dirSuffix);
            if (dir.exists()) {
                String baseName = codePath.startsWith("/data")
                        ? "base"
                        : codePath.substring(codePath.lastIndexOf('/') + 1);

                for (String suffix : FILE_SUFFIXES) {
                    File f = new File(dir, baseName + suffix);
                    if (f.exists()) filesToLoad.add(f);
                    logger("Scheduled dex prefetch for file: " + f.getAbsolutePath());
                }
                break;
            }
        }

        for (File f : filesToLoad) {
            prefetchExecutor.submit(() -> loadFileLocked(f));
        }
    }

    private void loadFileLocked(File file) {
        synchronized (prefetchLock) {
            try (FileChannel channel = new RandomAccessFile(file, "r").getChannel()) {
                MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
                buffer.load();
                logger("Dex prefetch completed: " + file.getAbsolutePath());
            } catch (IOException e) {
                logger("Dex prefetch failed for file: " + file.getAbsolutePath() + " - " + e);
            }
        }
    }

    public void setScreenState(boolean off) {
        if (!enablePrefetch || !systemReady) return;

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
        if (!enablePrefetch || !systemReady || packageManager == null || mDexOptHelper == null) return;

        try {
            if (packageManager.isStorageLow()) {
                logger("Skipping dexopt: low storage");
                return;
            }

            List<String> allPackages = getAllInstalledPackages();
            if (allPackages.isEmpty()) {
                logger("No installed packages found for dexopt");
                return;
            }

            allPackages = sortPackagesByUsageStats(allPackages);

            Map<String, Long> trackerSnapshot;
            synchronized (dexoptLock) {
                trackerSnapshot = getDexoptTracker();
            }

            int total = allPackages.size();
            int processed = 0;
            int skipped = 0;

            AxExtServiceFactory.getBoostAdjuster().boostInstall(true);
            logger("----- Dexopt started (" + total + " packages) -----");

            for (String pkg : allPackages) {
                if (!screenOff) {
                    logger("Dexopt cancelled mid-run (screen ON)");
                    synchronized (dexoptLock) {
                        saveDexoptTracker(trackerSnapshot);
                    }
                    break;
                }
                if (shouldSkipDexopt(pkg, trackerSnapshot)) {
                    skipped++;
                    continue;
                }

                int result = mDexOptHelper.doDexoptPackage(pkg);
                switch (result) {
                    case PackageDexOptimizer.DEX_OPT_PERFORMED:
                        trackerSnapshot.put(pkg, System.currentTimeMillis());
                        logger("Dex optimization performed for pkg=" + pkg);
                        break;

                    case PackageDexOptimizer.DEX_OPT_SKIPPED:
                        trackerSnapshot.put(pkg, System.currentTimeMillis());
                        skipped++;
                        logger("Dex optimization skipped for pkg=" + pkg);
                        break;

                    case PackageDexOptimizer.DEX_OPT_CANCELLED:
                        logger("Dex optimization cancelled for pkg=" + pkg);
                        break;

                    case PackageDexOptimizer.DEX_OPT_FAILED:
                        logger("Dex optimization FAILED for pkg=" + pkg);
                        break;

                    default:
                        logger("Unknown dexopt result (" + result + ") for pkg=" + pkg);
                        break;
                }

                processed++;
                
                int finalTotal = total - skipped;

                if (processed % 20 == 0 || processed == finalTotal)
                    logger("Dexopt progress: " + processed + "/" + finalTotal);
            }

            synchronized (dexoptLock) {
                saveDexoptTracker(trackerSnapshot);
            }

            logger("----- Dexopt finished (" + processed + "/" + (total - skipped) + " processed) -----");

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
        Map<String, Long> map = new HashMap<>();
        try {
            Context ctx = NtServiceInjector.getCtx();
            String json = Settings.Secure.getString(ctx.getContentResolver(), DEXOPT_TRACKER_KEY);
            if (json == null) return map;

            JSONObject obj = new JSONObject(json);
            for (Iterator<String> keys = obj.keys(); keys.hasNext(); ) {
                String key = keys.next();
                map.put(key, obj.getLong(key));
            }
        } catch (Exception ignored) {}
        return map;
    }

    private void saveDexoptTracker(Map<String, Long> map) {
        try {
            JSONObject obj = new JSONObject(map);
            Context ctx = NtServiceInjector.getCtx();
            Settings.Secure.putString(ctx.getContentResolver(), DEXOPT_TRACKER_KEY, obj.toString());
            logger("saving status. status=" + obj.toString());
        } catch (Exception ignored) {}
    }

    private void cleanupDexoptTracker() {
        Map<String, Long> trackerSnapshot;
        synchronized (dexoptLock) {
            trackerSnapshot = getDexoptTracker();
        }

        if (trackerSnapshot.isEmpty()) return;

        Set<String> installedSet = new HashSet<>(getAllInstalledPackages());
        int before = trackerSnapshot.size();
        trackerSnapshot.keySet().removeIf(pkg -> !installedSet.contains(pkg));

        if (trackerSnapshot.size() < before) {
            synchronized (dexoptLock) {
                saveDexoptTracker(trackerSnapshot);
            }
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
}
