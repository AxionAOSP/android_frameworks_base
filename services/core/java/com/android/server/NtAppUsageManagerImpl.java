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
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.util.Slog;
import com.android.server.am.ProcessRecord;
import com.android.server.utils.SimpleAppRecord;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;

public class NtAppUsageManagerImpl implements INtAppUsageManager {

    private static final String TAG = "NtAppUsageManager";
    private static final String APP_RECORD_FILE = "app_usage_record.txt";
    private static final String APP_RECORD_BACKUP_FILE = "app_usage_record_backup.txt";

    private static boolean DEBUG = SystemProperties.getBoolean("persist.sys.appusage.debug", false);
    private static final long RECORD_WRITE_INTERVAL_MS = 1800000;

    private static final double HIGH_USAGE_PERCENTAGE = 0.67;
    private static final double GENERAL_USAGE_PERCENTAGE = 0.33;

    private Context mContext;
    private File mDataDirectory;
    private Handler mHandler;
    private HandlerThread mHandlerThread;
    private AtomicFile mAtomicRecordFile;

    private HashMap<String, AppRecord> mAppRecords = new HashMap<>();
    private boolean mSystemReady = false;
    private boolean mIsWritingRecord = false;
    private long mLastRecordWriteTime = 0L;
    private String mUpdatingPkgName = "";

    private ArrayList<String> mHighUsedApps = new ArrayList<>();
    private ArrayList<String> mGeneralUsedApps = new ArrayList<>();
    private ArrayList<String> mLowUsedApps = new ArrayList<>();
    private long mLastRankingTime = 0L;
    private static final long MIN_RANKING_INTERVAL_MS = 300000;

    public static class AppRecord {
        public String packageName;
        public long totalLaunchCount = 0;
        public long lastLaunchTime = 0;
        public long lastCachedPss = 0;
        public long removeTime = 0;
        public long lmkdKillTime = 0;
        public int targetAdj = -1;

        public long totalUsageDuration = 0; 
        public long lastSessionStartTime = 0;

        public AppRecord(String pkg) {
            packageName = pkg;
        }

        public void onAppLaunched() {
            totalLaunchCount++;
            lastLaunchTime = System.currentTimeMillis();
            lastSessionStartTime = lastLaunchTime;
        }

        public String serialize() {
            return packageName + "|" + totalLaunchCount + "|" + lastLaunchTime + "|" + lastCachedPss
                   + "|" + totalUsageDuration + "\n";
        }

        public static AppRecord deserialize(String line) {
            String[] parts = line.split("\\|");
            if (parts.length >= 5) {
                AppRecord record = new AppRecord(parts[0]);
                record.totalLaunchCount = Long.parseLong(parts[1]);
                record.lastLaunchTime = Long.parseLong(parts[2]);
                record.lastCachedPss = Long.parseLong(parts[3]);
                record.totalUsageDuration = Long.parseLong(parts[4]);
                return record;
            }
            return null;
        }

        public double getUsageScore() {
            long timeSinceLastLaunch = System.currentTimeMillis() - lastLaunchTime;
            long daysSinceLastLaunch = timeSinceLastLaunch / (24 * 60 * 60 * 1000);
            double recencyFactor = 1.0 / (1.0 + daysSinceLastLaunch * 0.1);

            double durationScore = totalUsageDuration / (60.0 * 60.0 * 1000.0); 
            return (totalLaunchCount + durationScore) * recencyFactor;
        }
    }

    private static class UsageScoreComparator implements Comparator<AppRecord> {
        @Override
        public int compare(AppRecord a1, AppRecord a2) {
            return Double.compare(a2.getUsageScore(), a1.getUsageScore());
        }
    }

    public NtAppUsageManagerImpl() {
        Slog.d(TAG, "Initializing NtAppUsageManager");
    }

    public void systemReady() {
        mContext = NtServiceInjector.getCtx();
        
        try {
            mDataDirectory = new File("/data/system/");
            mDataDirectory.mkdirs();
        } catch (Exception e) {
            Slog.e(TAG, "Failed to create data directory", e);
        }

        initHandlerThread();
        
        mHandler.post(() -> {
            loadRecords();
            updatePackageList();
        });
        
        mSystemReady = true;
        Slog.d(TAG, "System ready");
    }

    public void updateLaunchTime(String packageName) {
        synchronized (this) {
            AppRecord record = mAppRecords.get(packageName);
            if (record != null) {
                record.onAppLaunched();
                if (DEBUG) {
                    Slog.d(TAG, packageName + " launched. Total count: " + record.totalLaunchCount);
                }
                mLastRankingTime = 0;
            }
        }
    }

    public void updateDuration(String packageName) {
        synchronized (this) {
            AppRecord record = mAppRecords.get(packageName);
            if (record != null && record.lastSessionStartTime > 0) {
                long duration = System.currentTimeMillis() - record.lastSessionStartTime;
                record.totalUsageDuration += duration;
                record.lastSessionStartTime = 0;

                if (DEBUG) {
                    Slog.d(TAG, "Updated usage duration for " + packageName + ": " + record.totalUsageDuration + " ms");
                }
                mLastRankingTime = 0;
            }
        }
    }

    public void setLastCachedPss(String packageName, long pss) {
        synchronized (this) {
            AppRecord record = mAppRecords.get(packageName);
            if (record != null && record.lastCachedPss < pss) {
                record.lastCachedPss = pss;
                if (DEBUG) {
                    Slog.d(TAG, "Set PSS for " + packageName + ": " + pss);
                }
            }
        }
    }

    public void setRemoveTaskTime(String packageName) {
        synchronized (this) {
            AppRecord record = mAppRecords.get(packageName);
            if (record != null) {
                record.removeTime = System.currentTimeMillis();
            }
        }
    }

    public void setTargetAdj(String packageName, int adj) {
        synchronized (this) {
            AppRecord record = mAppRecords.get(packageName);
            if (record != null) {
                record.targetAdj = adj;
            }
        }
    }

    public void appDied(ProcessRecord processRecord) {
        String packageName = processRecord.info.packageName;
        synchronized (this) {
            AppRecord record = mAppRecords.get(packageName);
            if (record != null) {
                record.lmkdKillTime = System.currentTimeMillis();
                if (DEBUG) {
                    Slog.d(TAG, packageName + " died with pid " + processRecord.mPid);
                }
            }
        }
    }

    public void addNewPackages(String packageName) {
        synchronized (this) {
            if (!mAppRecords.containsKey(packageName)) {
                mAppRecords.put(packageName, new AppRecord(packageName));
                if (DEBUG) {
                    Slog.d(TAG, "Added new package: " + packageName);
                }
            }
        }
    }

    public void removePackage(String packageName) {
        synchronized (this) {
            if (!TextUtils.equals(mUpdatingPkgName, packageName)) {
                mAppRecords.remove(packageName);
                if (DEBUG) {
                    Slog.d(TAG, "Removed package: " + packageName);
                }
            }
        }
    }

    public void setUpdatingPackage(String packageName) {
        mUpdatingPkgName = packageName;
    }

    public ArrayList<String> getHighUsedPackageList(boolean forceUpdate) {
        if (forceUpdate) {
            rankApps();
        } else {
            rankAppsIfNeeded();
        }
        synchronized (this) {
            return new ArrayList<>(mHighUsedApps);
        }
    }

    public ArrayList<String> getGeneralUsedPackageList(boolean forceUpdate) {
        if (forceUpdate) {
            rankApps();
        } else {
            rankAppsIfNeeded();
        }
        synchronized (this) {
            return new ArrayList<>(mGeneralUsedApps);
        }
    }

    public ArrayList<String> getLowUsedPackageList(boolean forceUpdate) {
        if (forceUpdate) {
            rankApps();
        } else {
            rankAppsIfNeeded();
        }
        synchronized (this) {
            return new ArrayList<>(mLowUsedApps);
        }
    }

    public boolean isHighUsedPackages(String packageName) {
        rankAppsIfNeeded();
        synchronized (this) {
            return mHighUsedApps.contains(packageName);
        }
    }

    public SimpleAppRecord getHighUsedRecord(boolean forceUpdate, String packageName) {
        if (forceUpdate) {
            rankApps();
        } else {
            rankAppsIfNeeded();
        }
        
        synchronized (this) {
            if (!mHighUsedApps.contains(packageName)) {
                return null;
            }
            
            AppRecord record = mAppRecords.get(packageName);
            if (record == null) {
                return null;
            }
            
            SimpleAppRecord simpleRecord = new SimpleAppRecord();
            simpleRecord.mPackageName = record.packageName;
            simpleRecord.mLastCachedPss = record.lastCachedPss;
            simpleRecord.mLastRemoveTaskTime = record.removeTime;
            simpleRecord.mLastLmkdTimeTime = record.lmkdKillTime;
            simpleRecord.mCurTargetAdj = record.targetAdj;
            return simpleRecord;
        }
    }

    public ArrayList<SimpleAppRecord> getHighUsedRecords(boolean forceUpdate) {
        if (forceUpdate) {
            rankApps();
        } else {
            rankAppsIfNeeded();
        }
        
        synchronized (this) {
            ArrayList<SimpleAppRecord> result = new ArrayList<>();
            for (String packageName : mHighUsedApps) {
                AppRecord record = mAppRecords.get(packageName);
                if (record != null) {
                    SimpleAppRecord simpleRecord = new SimpleAppRecord();
                    simpleRecord.mPackageName = record.packageName;
                    simpleRecord.mLastCachedPss = record.lastCachedPss;
                    simpleRecord.mLastRemoveTaskTime = record.removeTime;
                    simpleRecord.mLastLmkdTimeTime = record.lmkdKillTime;
                    simpleRecord.mCurTargetAdj = record.targetAdj;
                    result.add(simpleRecord);
                }
            }
            return result;
        }
    }

    public void writeRecordsToDisk(boolean force) {
        long currentTime = System.currentTimeMillis();
        if (!force && (mIsWritingRecord || currentTime - mLastRecordWriteTime < RECORD_WRITE_INTERVAL_MS)) {
            return;
        }
        
        if (DEBUG) {
            Slog.d(TAG, "Writing records to disk");
        }
        
        mIsWritingRecord = true;
        new RecordWriterThread(currentTime).start();
    }

    public void setScreenState(boolean isOff) {
        writeRecordsToDisk(false);
    }

    public void cleanAllData(long newTime) {
        if (DEBUG) {
            Slog.d(TAG, "Cleaning all data");
        }
        
        synchronized (this) {
            mAppRecords.clear();
            mHighUsedApps.clear();
            mGeneralUsedApps.clear();
            mLowUsedApps.clear();
            mLastRankingTime = 0;
            mLastRecordWriteTime = 0;
            deleteRecordFiles();
        }
    }

    public void addInitialPkgs(ArrayList<String> packages) {
        synchronized (this) {
            for (String packageName : packages) {
                if (!mAppRecords.containsKey(packageName)) {
                    mAppRecords.put(packageName, new AppRecord(packageName));
                    if (DEBUG) {
                        Slog.d(TAG, "Added initial package: " + packageName);
                    }
                }
            }
        }
    }

    public void startCleanData(long currentTime) {
        cleanAllData(currentTime);
    }

    private void initHandlerThread() {
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
    }

    private void rankAppsIfNeeded() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - mLastRankingTime > MIN_RANKING_INTERVAL_MS) {
            rankApps();
        }
    }

    private void rankApps() {
        synchronized (this) {
            ArrayList<AppRecord> allRecords = new ArrayList<>(mAppRecords.values());

            allRecords.removeIf(record -> record.totalLaunchCount == 0 && record.totalUsageDuration == 0);

            if (allRecords.isEmpty()) {
                mHighUsedApps.clear();
                mGeneralUsedApps.clear();
                mLowUsedApps.clear();
                return;
            }

            Collections.sort(allRecords, new UsageScoreComparator());

            double maxScore = allRecords.get(0).getUsageScore();

            mHighUsedApps.clear();
            mGeneralUsedApps.clear();
            mLowUsedApps.clear();

            for (AppRecord record : allRecords) {
                double usageRatio = record.getUsageScore() / maxScore;

                if (usageRatio >= HIGH_USAGE_PERCENTAGE) {
                    mHighUsedApps.add(record.packageName);
                } else if (usageRatio >= GENERAL_USAGE_PERCENTAGE) {
                    mGeneralUsedApps.add(record.packageName);
                } else {
                    mLowUsedApps.add(record.packageName);
                }
            }

            mLastRankingTime = System.currentTimeMillis();

            if (DEBUG) {
                Slog.d(TAG, "Ranked apps (max score: " + maxScore + ") - High: " + mHighUsedApps.size() +
                       ", General: " + mGeneralUsedApps.size() +
                       ", Low: " + mLowUsedApps.size());
            }
        }
    }

    private void loadRecords() {
        File file = new File(mDataDirectory, APP_RECORD_FILE);
        if (!file.exists() || !file.canRead()) {
            file = new File(mDataDirectory, APP_RECORD_BACKUP_FILE);
            if (!file.exists() || !file.canRead()) {
                if (DEBUG) {
                    Slog.d(TAG, "No existing records found");
                }
                return;
            }
        }
        
        if (DEBUG) {
            Slog.d(TAG, "Loading records from: " + file.getAbsolutePath());
        }
        
        synchronized (this) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    AppRecord record = AppRecord.deserialize(line.trim());
                    if (record != null) {
                        mAppRecords.put(record.packageName, record);
                    }
                }
                if (DEBUG) {
                    Slog.d(TAG, "Loaded " + mAppRecords.size() + " app records");
                }
            } catch (IOException e) {
                Slog.e(TAG, "Failed to load records", e);
            }
        }
    }

    private void updatePackageList() {
        LauncherApps launcherApps = (LauncherApps) mContext.getSystemService(LauncherApps.class);
        ArrayList<String> packages = new ArrayList<>();
        
        for (LauncherActivityInfo info : launcherApps.getActivityList(null, Process.myUserHandle())) {
            packages.add(info.getComponentName().getPackageName());
        }
        
        addInitialPkgs(packages);
    }

    private void deleteRecordFiles() {
        try {
            File recordFile = new File(mDataDirectory, APP_RECORD_FILE);
            if (recordFile.exists()) {
                recordFile.delete();
            }
            File backupFile = new File(mDataDirectory, APP_RECORD_BACKUP_FILE);
            if (backupFile.exists()) {
                backupFile.delete();
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to delete record files", e);
        }
    }

    private class RecordWriterThread extends Thread {
        private final long time;

        RecordWriterThread(long time) {
            super("AppUsageRecordWriter");
            this.time = time;
        }

        @Override
        public void run() {
            mLastRecordWriteTime = time;
            File recordFile = new File(mDataDirectory, APP_RECORD_FILE);
            FileOutputStream outputStream = null;
            try {
                if (recordFile.exists()) {
                    File backupFile = new File(mDataDirectory, APP_RECORD_BACKUP_FILE);
                    if (backupFile.exists()) {
                        backupFile.delete();
                    }
                    recordFile.renameTo(backupFile);
                }

                mAtomicRecordFile = new AtomicFile(new File(mDataDirectory, APP_RECORD_FILE));
                outputStream = mAtomicRecordFile.startWrite();
            
                synchronized (NtAppUsageManagerImpl.this) {
                    for (AppRecord record : mAppRecords.values()) {
                        outputStream.write(record.serialize().getBytes(StandardCharsets.UTF_8));
                    }
                }

                outputStream.flush();
                mAtomicRecordFile.finishWrite(outputStream);

                if (DEBUG) {
                    Slog.d(TAG, "Successfully wrote records to disk");
                }
            } catch (Exception e) {
                Slog.e(TAG, "Error in record writer", e);
            } finally {
                mIsWritingRecord = false;
            }
        }
    }
}
