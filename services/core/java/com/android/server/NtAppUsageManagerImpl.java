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

import android.app.AlarmManager;
import android.content.Context;
import android.content.pm.*;
import android.os.*;
import android.os.Process;
import android.util.*;
import android.util.Slog;
import com.android.server.am.ProcessList;
import com.android.server.am.ProcessRecord;
import com.android.server.utils.SimpleAppRecord;
import java.io.*;
import java.util.*;

public class NtAppUsageManagerImpl implements INtAppUsageManager {
    
    static final String TAG = "NtAppUsageManager";
    static final boolean DEBUG = SystemProperties.getBoolean("persist.sys.appusage.debug", false);
    static final String HIGH_USED_DIE_KEY = "high_used_die";
    static final String OPTI_HIGH_USED_KEY = "opti_high_used";
    
    public static final String APP_RECORD_FILE = "app_record.txt";
    public static final String APP_RECORD_BACKUP_FILE = "app_record_backup.txt";
    
    static final String FOREGROUND_TIME_KEY = "fg_time";
    static final String PACKAGE_KEY = "package";
    static final String APP_LAUNCH_EVENT = "App_Launch";
    static final String HIGH_USED_APP_DIE_EVENT = "High_Used_App_Die";
    static final String OS_APP_EVENT = "OS_App";
    
    static final int MSG_APP_DIED = 0;
    
    public static final String HIGH_USED_PACKAGES_KEY = "High Used Packages";
    public static final String GENERAL_USED_PACKAGES_KEY = "General Used Packages"; 
    public static final String LOW_USED_PACKAGES_KEY = "Low Used Packages";
    
    static final long SECOND_IN_MS = 1000;
    static final long RANKING_INTERVAL_MS = 1800000;
    static final long RECORD_WRITE_INTERVAL_MS = 1800000;
    static final long TIME_CHANGE_THRESHOLD_MS = 3600000;
    static final double SCORE_MULTIPLIER = 100.0d;
    
    static final int MAX_CLUSTERING_ITERATIONS = 20;
    static final int MAX_APP_COUNT_PER_BATCH = SystemProperties.getInt("persist.sys.appusage.maxcount", 30);
    
    static final long WARM_UP_DURATION_MS = DEBUG ? 60000L : 43200000L;
    static final long TIME_SLOT_DURATION_MS = DEBUG ? 180000L : 86400000L;
    static final int MAX_TIME_SLOTS = DEBUG ? 5 : 30;
    
    static boolean isScreenOff = false;
    static boolean isWritingRecord = false;
    static boolean hasWarmedUp = false;
    static String lastRunningPackage = null;
    static int maxTimeSlots = 0;
    static long lastRankingTime = 0;
    static long lastRecordWriteTime = 0;
    
    Context context;
    AlarmManager am;
    HandlerThread handlerThread;
    Handler handler;
    File dataDirectory;
    File recordFile;
    AtomicFile atomicRecordFile;
    UsageAlarmListener al;
    
    HashMap<String, UsageRecord> pkgUsgMap = new HashMap<>();
    HashMap<String, ArrayList<UsageRecord>> usageCategoryMap = new HashMap<>();
    
    ArrayList<UsageCluster> clusters = new ArrayList<>();
    ArrayList<ClusterAvg> clusterCenters = new ArrayList<>();
    
    private ArrayList<String> debugHighUsedPackages = new ArrayList<>();
    private ArrayList<String> debugGeneralUsedPackages = new ArrayList<>();
    private ArrayList<String> debugLowUsedPackages = new ArrayList<>();
    
    long warmUpTime = 0;
    String updatingPackageName = "";
    boolean isSystemReady = false;
    
    int highUsed = 3;
    int generalUsed = 1; 
    int lowUsed = 1;
    int totalClusters = highUsed + generalUsed + lowUsed;
    int maxHighUsedApps = 3;
    int maxGeneralUsedApps = 10;
    int maxLowUsedApps = 3;
    
    ArrayList<Integer> targetValues = new ArrayList<>();
    int highUsedAppDeathCount = 0;

    public static class UsageRecord {
        String pkg;
        long currentLaunchTime;
        long lastLaunchTime; 
        long totalForegroundTime;
        long totalLruTime;
        long lastCachedPss;
        long lastRemoveTaskTime;
        long lastLmkdKillTime;
        
        public double launchFreqScore;
        public double fgTimeScore; 
        public double lruScore;
        public int targetAdjustment;
        
        ArrayList<TimeSlotRecord> timeSlotRecords = new ArrayList<>();
        int currentTimeSlotIndex = 0;
        boolean canUpdateDuration = true;
        boolean isLaunchInProgress = false;
        int lmkdKillAdjustment = -1;
        
        public UsageRecord(String pkg, boolean initializeSlots, boolean skipCurrentSlot) {
            this.pkg = pkg;
            long currentTime = System.currentTimeMillis();
            this.totalLruTime = currentTime - (currentTime % TIME_SLOT_DURATION_MS);
            
            if (initializeSlots) {
                for (int i = 0; i < maxTimeSlots - 1; i++) {
                    timeSlotRecords.add(new TimeSlotRecord(pkg));
                }
            }
            
            if (!skipCurrentSlot) {
                TimeSlotRecord currentSlot = new TimeSlotRecord(pkg);
                currentSlot.startTime = totalLruTime;
                timeSlotRecords.add(currentSlot);
            }
        }

        public TimeSlotRecord getPreviousTimeSlot(long currentTime) {
            updateTimeSlots(currentTime);
            int previousIndex = timeSlotRecords.size() > 1 ? 
                               timeSlotRecords.size() - 2 : 
                               timeSlotRecords.size() - 1;
            return timeSlotRecords.get(previousIndex);
        }

        public void resetTotalTimes() {
            totalForegroundTime = 0L;
            currentLaunchTime = 0L;
        }
        
        public void setLastLmkdKillTime(long time) {
            this.lastLmkdKillTime = time;
            if (DEBUG) {
                Slog.d(TAG, pkg + " set lmkd kill time " + time);
            }
        }
        
        public void setTargetAdj(int adj) {
            this.lmkdKillAdjustment = adj;
        }
        
        public void onAppLaunched(long launchTime) {
            lastLaunchTime = currentLaunchTime;
            currentLaunchTime = launchTime;
            updateTimeSlots(launchTime);
            timeSlotRecords.get(currentTimeSlotIndex).startTime = launchTime;
            timeSlotRecords.get(currentTimeSlotIndex).lruTime = launchTime;
        }
        
        public void onAppStopped(long stopTime) {
            long duration = stopTime - currentLaunchTime;
            if (duration >= SECOND_IN_MS) {
                if (currentLaunchTime != 0) {
                    timeSlotRecords.get(currentTimeSlotIndex).addDuration(duration, pkg);
                }
                isLaunchInProgress = false;
                return;
            }
            
            Slog.d(TAG, "Duration is too short, ignore : " + duration + " in " + pkg);
            currentLaunchTime = lastLaunchTime;
            if (lastLaunchTime > timeSlotRecords.get(currentTimeSlotIndex).startTime) {
                timeSlotRecords.get(currentTimeSlotIndex).lruTime = lastLaunchTime;
            } else {
                timeSlotRecords.get(currentTimeSlotIndex).lruTime = 0L;
            }
            
            if (isLaunchInProgress) {
                timeSlotRecords.get(currentTimeSlotIndex).decrementLaunchCount();
                lastRunningPackage = "";
            }
            isLaunchInProgress = false;
        }
        
        public void onLaunchCountIncreased(String pkg) {
            timeSlotRecords.get(currentTimeSlotIndex).incrementLaunchCount();
            isLaunchInProgress = true;
            if (DEBUG) {
                Slog.d(TAG, "Increase Total Launch Time : " + pkg + ", times : " + 
                      timeSlotRecords.get(currentTimeSlotIndex).launchCount + ", index : " + currentTimeSlotIndex);
            }
        }
        
        public void resetUsageData(long newStartTime) {
            launchFreqScore = 0.0d;
            fgTimeScore = 0.0d;
            lruScore = 0.0d;
            totalLruTime = newStartTime - (newStartTime % TIME_SLOT_DURATION_MS);
            totalForegroundTime = 0L;
            currentLaunchTime = 0L;
            timeSlotRecords.clear();
            
            TimeSlotRecord newSlot = new TimeSlotRecord(pkg);
            newSlot.startTime = totalLruTime;
            timeSlotRecords.add(newSlot);
            currentTimeSlotIndex = 0;
            lastLaunchTime = 0L;
            canUpdateDuration = true;
        }
        
        public void clearScores() {
            launchFreqScore = 0.0d;
            fgTimeScore = 0.0d; 
            lruScore = 0.0d;
        }
        
        public void printDebugInfo() {
            Slog.d(TAG, pkg + " : X : " + launchFreqScore + ", Y : " + fgTimeScore + ", Z : " + lruScore);
        }
        
        public boolean updateTimeSlots(long currentTime) {
            long slotsToAdd = (currentTime - totalLruTime) / TIME_SLOT_DURATION_MS;
            
            int slotsToRemove = timeSlotRecords.size() - MAX_TIME_SLOTS;
            for (int i = 0; i < slotsToRemove; i++) {
                timeSlotRecords.remove(0);
            }
            
            if (slotsToAdd > 0) {
                if (DEBUG) {
                    Slog.d(TAG, "Package record in " + pkg + " need to add : " + slotsToAdd + 
                          ", max size is " + maxTimeSlots);
                }
                
                for (int i = 0; i < slotsToAdd; i++) {
                    TimeSlotRecord newSlot = new TimeSlotRecord(pkg);
                    newSlot.startTime = totalLruTime + (TIME_SLOT_DURATION_MS * (i + 1));
                    if (timeSlotRecords.size() >= MAX_TIME_SLOTS) {
                        timeSlotRecords.remove(0);
                    }
                    timeSlotRecords.add(newSlot);
                }
            }
            
            if (maxTimeSlots > timeSlotRecords.size()) {
                if (DEBUG) {
                    Slog.d(TAG, "Package record size is abnormal in " + pkg + 
                          " , size : " + timeSlotRecords.size() + ", max size is " + maxTimeSlots);
                }
                int slotsNeeded = maxTimeSlots - timeSlotRecords.size();
                for (int i = 0; i < slotsNeeded; i++) {
                    timeSlotRecords.add(i, new TimeSlotRecord(pkg));
                }
            }
            
            totalLruTime = timeSlotRecords.get(timeSlotRecords.size() - 1).startTime;
            currentTimeSlotIndex = timeSlotRecords.size() - 1;
            
            if (maxTimeSlots >= timeSlotRecords.size()) {
                return false;
            }
            
            maxTimeSlots = timeSlotRecords.size();
            return true;
        }
        
        public byte[] serializeToBytes() {
            StringBuilder sb = new StringBuilder();
            sb.append("PackageName: ").append(pkg).append("\n");
            sb.append("LastCachedPss: ").append(lastCachedPss).append("\n");
            
            for (TimeSlotRecord record : timeSlotRecords) {
                sb.append(record.serialize());
            }
            
            return sb.toString().getBytes();
        }
    }
    
    static final class TimeSlotRecord {
        String pkg;
        long startTime;
        long launchCount;
        long foregroundDuration;
        long totalUsageTime;
        long lruTime;
        double launchFreqScore = 0.0d;
        double fgTimeScore = 0.0d;
        
        public TimeSlotRecord(String pkg) {
            this.pkg = pkg;
        }
        
        public void incrementLaunchCount() {
            launchCount++;
        }
        
        public void decrementLaunchCount() {
            launchCount--;
        }
        
        public void addDuration(long duration, String pkg) {
            if (lruTime != 0 && duration > 0) {
                addForegroundTime(duration, pkg);
            } else if (DEBUG) {
                Slog.d(TAG, "Abnormal duration " + duration + ", launch time : " + lruTime + " for " + pkg);
            }
        }
        
        public void addForegroundTime(long duration, String pkg) {
            foregroundDuration += duration;
            if (DEBUG) {
                Slog.d(TAG, "increase duration : " + duration + " for " + pkg);
            }
        }
        
        public void setLaunchCount(long count) {
            this.launchCount = count;
        }
        
        public void clearScores() {
            launchFreqScore = 0.0d;
            fgTimeScore = 0.0d;
        }
        
        public String serialize() {
            return startTime + " " + launchCount + " " + totalUsageTime + " " + lruTime + " \n";
        }
    }
    
    static final class ClusterAvg {
        public double launchFreq; 
        public double fgTime;
        public double lruScore;
        
        public ClusterAvg() {
            launchFreq = 0.0d;
            fgTime = 0.0d;
            lruScore = 0.0d;
        }
        
        public ClusterAvg(double x, double y, double z) {
            launchFreq = x;
            fgTime = y;
            lruScore = z;
        }
        
        public void printDebugInfo() {
            Slog.d(TAG, "Point X : " + launchFreq + ", Y : " + fgTime + ", Z : " + lruScore);
        }
    }
    
    static class UsageCluster {
        public int clusterId;
        public ClusterAvg avg;
        public ArrayList<UsageRecord> apps = new ArrayList<>();
        public double distanceFromOrigin;
        
        public UsageCluster(int id) {
            this.clusterId = id;
        }
        
        public void setApps(ArrayList<UsageRecord> apps) {
            this.apps = apps;
        }
        
        public void setAvg(ClusterAvg avg) {
            this.avg = avg;
        }
        
        public void clearApps() {
            apps.clear();
        }
        
        public void addApp(UsageRecord app) {
            apps.add(app);
        }
        
        public void printDebugInfo() {
            Slog.d(TAG, "-----------------------------------------------------------------------");
            Slog.d(TAG, "Cluster " + clusterId);
            avg.printDebugInfo();
            Slog.d(TAG, "Distance from 0 : " + distanceFromOrigin);
            Slog.d(TAG, "All data : ");
            for (UsageRecord app : apps) {
                app.printDebugInfo();
            }
            Slog.d(TAG, "-----------------------------------------------------------------------");
        }
    }
    
    class UsageAlarmListener implements AlarmManager.OnAlarmListener {
        long dataStartTime;
        
        UsageAlarmListener(long startTime) {
            this.dataStartTime = startTime;
            if (DEBUG) {
                Slog.d(TAG, "AppUsageAlarmListener DataStartTime: " + this.dataStartTime);
            }
        }
        
        @Override
        public void onAlarm() {
            collectCurrentUsageData();
            scheduleNextAlarm();
        }
        
        void collectCurrentUsageData() {
            long currentTime = System.currentTimeMillis();
            StringBuilder pkgs = new StringBuilder();
            StringBuilder usageTimes = new StringBuilder(); 
            Bundle reportBundle = new Bundle();
            
            int packageCount = 0;
            for (String pkg : pkgUsgMap.keySet()) {
                TimeSlotRecord previousSlot = pkgUsgMap.get(pkg).getPreviousTimeSlot(currentTime);
                long totalTime = previousSlot.foregroundDuration + previousSlot.totalUsageTime;
                
                if (totalTime > SECOND_IN_MS) {
                    if (DEBUG) {
                        Slog.d(TAG, previousSlot.pkg + " : " + totalTime);
                    }
                    pkgs.append(previousSlot.pkg).append("#");
                    usageTimes.append(totalTime / SECOND_IN_MS).append("#");
                    packageCount++;
                }
                
                if (packageCount >= MAX_APP_COUNT_PER_BATCH) {
                    submitUsageData(pkgs.toString(), usageTimes.toString());
                    pkgs.setLength(0);
                    usageTimes.setLength(0);
                    packageCount = 0;
                }
                
                if (previousSlot != null && previousSlot.pkg != null && previousSlot.launchCount > 0) {
                    reportBundle.putString(previousSlot.pkg, String.valueOf(previousSlot.launchCount));
                }
            }
            
            if (packageCount > 0) {
                submitUsageData(pkgs.toString(), usageTimes.toString());
                submitAppLaunchCount(reportBundle);
            }
            
            submitHighUsedAppDeaths();
        }
        
        void scheduleNextAlarm() {
            dataStartTime += TIME_SLOT_DURATION_MS;
            Slog.d(TAG, "schedule next alarm, DataStartTime: " + dataStartTime);
            scheduleAlarm(dataStartTime + TIME_SLOT_DURATION_MS);
        }
    }
    
    class KillHandler extends Handler {
        KillHandler(Looper looper) {
            super(looper);
        }
        
        @Override
        public void handleMessage(Message message) {
            if (message.what != MSG_APP_DIED) {
                return;
            }
            
            try {
                Bundle data = message.getData();
                if (DEBUG) {
                    Slog.d(TAG, "msg MSG_APP_DIED");
                    for (String key : data.keySet()) {
                        Slog.d(TAG, key + " = \"" + data.get(key) + "\"");
                    }
                }
                String pkg = data.getString("pkg_name");
                int pid = data.getInt("pid");
                if (DEBUG || isLmkdKill(pkg, pid)) {
                    long currentTime = System.currentTimeMillis();
                    if (pkgUsgMap.containsKey(pkg)) {
                        pkgUsgMap.get(pkg).setLastLmkdKillTime(currentTime);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    
    class RecordWriterThread extends Thread {
        final long writeTime;
        
        RecordWriterThread(String threadName, long time) {
            super(threadName);
            this.writeTime = time;
        }
        
        @Override
        public void run() {
            lastRecordWriteTime = writeTime;
            File recordFile = new File(dataDirectory, APP_RECORD_FILE);
            FileOutputStream outputStream = null;
            
            try {
                if (recordFile.exists()) {
                    File backupFile = new File(dataDirectory, APP_RECORD_BACKUP_FILE);
                    if (backupFile.exists()) {
                        backupFile.delete();
                    }
                    recordFile.renameTo(backupFile);
                }
                atomicRecordFile = new AtomicFile(new File(dataDirectory, APP_RECORD_FILE));
                outputStream = atomicRecordFile.startWrite();
                String warmUpFlag = "HasWarmUp : " + hasWarmedUp + "\n";
                String warmUpTimeStr = "WarmUpTime : " + warmUpTime + "\n";
                outputStream.write(warmUpFlag.getBytes());
                outputStream.write(warmUpTimeStr.getBytes());
                for (String pkg : pkgUsgMap.keySet()) {
                    UsageRecord record = pkgUsgMap.get(pkg);
                    for (TimeSlotRecord slot : record.timeSlotRecords) {
                        slot.totalUsageTime = slot.foregroundDuration + slot.totalUsageTime;
                    }
                    outputStream.write(record.serializeToBytes());
                }
                outputStream.flush();
                atomicRecordFile.finishWrite(outputStream);
                if (DEBUG) {
                    Slog.d(TAG, "Finishing writting old record");
                }
            } catch (Exception e) {
                Slog.w(TAG, "Error writing process statistics", e);
                if (atomicRecordFile != null) {
                    atomicRecordFile.failWrite(outputStream);
                }
            } finally {
                isWritingRecord = false;
            }
        }
    }

    static final class LaunchFrequencyComparator implements Comparator<UsageRecord> {
        @Override
        public int compare(UsageRecord a, UsageRecord b) {
            return Double.valueOf(calculateDistance(b, new ClusterAvg(0.0d, 0.0d, 0.0d)))
                   .compareTo(Double.valueOf(calculateDistance(a, new ClusterAvg(0.0d, 0.0d, 0.0d))));
        }
    }
    
    static final class LruTimeComparator implements Comparator<TimeSlotRecord> {
        @Override
        public int compare(TimeSlotRecord a, TimeSlotRecord b) {
            return Long.valueOf(a.lruTime).compareTo(Long.valueOf(b.lruTime));
        }
    }
    
    static final class UsageTimeComparator implements Comparator<TimeSlotRecord> {
        @Override  
        public int compare(TimeSlotRecord a, TimeSlotRecord b) {
            return Long.valueOf(a.totalUsageTime).compareTo(Long.valueOf(b.totalUsageTime));
        }
    }
    
    static final class LaunchCountComparator implements Comparator<TimeSlotRecord> {
        @Override
        public int compare(TimeSlotRecord a, TimeSlotRecord b) {
            return Long.valueOf(a.launchCount).compareTo(Long.valueOf(b.launchCount));
        }
    }
    
    static final class CachedPssComparator implements Comparator<UsageRecord> {
        @Override
        public int compare(UsageRecord a, UsageRecord b) {
            return Long.valueOf(a.lastCachedPss).compareTo(Long.valueOf(b.lastCachedPss));
        }
    }
    
    static final class TotalScoreComparator implements Comparator<UsageRecord> {
        @Override
        public int compare(UsageRecord a, UsageRecord b) {
            double aTotal = a.fgTimeScore + a.launchFreqScore + a.lruScore;
            double bTotal = b.fgTimeScore + b.launchFreqScore + b.lruScore;
            return Double.valueOf(bTotal).compareTo(Double.valueOf(aTotal));
        }
    }
    
    static final class DistanceComparator implements Comparator<UsageCluster> {
        @Override
        public int compare(UsageCluster a, UsageCluster b) {
            return Double.valueOf(a.distanceFromOrigin).compareTo(Double.valueOf(b.distanceFromOrigin));
        }
    }
    
    public void systemReady() {        
        if (DEBUG) {
            Slog.d(TAG, "Initial from system ready");
        }
        
        this.context = NtServiceInjector.getCtx();
        this.isSystemReady = true;
        this.am = (AlarmManager) context.getSystemService("alarm");
        
        long currentTime = System.currentTimeMillis();
        long slotBoundary = (currentTime - (currentTime % TIME_SLOT_DURATION_MS)) + TIME_SLOT_DURATION_MS;
        al = new UsageAlarmListener(slotBoundary);
        scheduleAlarm(slotBoundary + TIME_SLOT_DURATION_MS);
        
        try {
            dataDirectory = new File("/data/system/");
            dataDirectory.mkdirs();
        } catch (Exception e) {
            e.printStackTrace();
        }

        createHandlerThread();
        handler.post(() -> {
            init(context);
        });
        
        Slog.d(TAG, "systemReady: starting service: " +
                "dirExists=" + dataDirectory.exists() +
                ", dirEmpty=" + (dataDirectory.exists() && dataDirectory.isDirectory() && dataDirectory.listFiles() != null && dataDirectory.listFiles().length == 0) +
                ", time=" + currentTime +
                ", slotBoundary=" + slotBoundary);
    }

    public ArrayList<String> getHighUsedPackageList(boolean needUpdate) {
        if (!isWarmedUp()) {
            return new ArrayList<>();
        }
        return extractPackageNames(categorizeApps(needUpdate).get(HIGH_USED_PACKAGES_KEY));
    }
    
    public ArrayList<String> getGeneralUsedPackageList(boolean needUpdate) {
        if (!isWarmedUp()) {
            return new ArrayList<>();
        }
        return extractPackageNames(categorizeApps(needUpdate).get(GENERAL_USED_PACKAGES_KEY));
    }
    
    public ArrayList<String> getLowUsedPackageList(boolean needUpdate) {
        if (!isWarmedUp()) {
            return new ArrayList<>();
        }
        return extractPackageNames(categorizeApps(needUpdate).get(LOW_USED_PACKAGES_KEY));
    }
    
    public ArrayList<SimpleAppRecord> getHighUsedRecords(boolean needUpdate) {
        if (!isWarmedUp()) {
            return new ArrayList<>();
        }
        ArrayList<UsageRecord> highUsedApps = categorizeApps(needUpdate).get(HIGH_USED_PACKAGES_KEY);
        ArrayList<SimpleAppRecord> records = new ArrayList<>();
        if (highUsedApps != null) {
            for (UsageRecord app : highUsedApps) {
                SimpleAppRecord record = new SimpleAppRecord();
                record.mPackageName = app.pkg;
                record.mLastCachedPss = app.lastCachedPss;
                record.mLastRemoveTaskTime = app.lastRemoveTaskTime;
                record.mLastLmkdTimeTime = app.lastLmkdKillTime;
                record.mCurTargetAdj = app.lmkdKillAdjustment;
                records.add(record);
            }
        }
        return records;
    }

    public SimpleAppRecord geedHighUsedRecord(boolean needUpdate, String pkg) {
        if (!isWarmedUp()) {
            return null;
        }
        ArrayList<UsageRecord> highUsedApps = categorizeApps(needUpdate).get(HIGH_USED_PACKAGES_KEY);
        if (highUsedApps != null) {
            for (UsageRecord app : highUsedApps) {
                if (app.pkg.equals(pkg)) {
                    SimpleAppRecord record = new SimpleAppRecord();
                    record.mPackageName = app.pkg;
                    record.mLastCachedPss = app.lastCachedPss;
                    record.mLastRemoveTaskTime = app.lastRemoveTaskTime;
                    record.mLastLmkdTimeTime = app.lastLmkdKillTime;
                    record.mCurTargetAdj = app.lmkdKillAdjustment;
                    return record;
                }
            }
        }
        return null;
    }

    public SimpleAppRecord getHighUsedRecord(boolean needUpdate, String pkg) {
        if (!isWarmedUp()) {
            return null;
        }
        for (UsageRecord app : categorizeApps(needUpdate).get(HIGH_USED_PACKAGES_KEY)) {
            if (app.pkg.equals(pkg)) {
                SimpleAppRecord record = new SimpleAppRecord();
                record.mPackageName = app.pkg;
                record.mLastCachedPss = app.lastCachedPss;
                record.mLastRemoveTaskTime = app.lastRemoveTaskTime;
                record.mLastLmkdTimeTime = app.lastLmkdKillTime;
                record.mCurTargetAdj = app.lmkdKillAdjustment;
                return record;
            }
        }
        return null;
    }
    
    public boolean isHighUsedPackages(String pkg) {
        ArrayList<String> highUsedPackages;
        if (isWarmedUp() && (highUsedPackages = getHighUsedPackageList(false)) != null) {
            return highUsedPackages.contains(pkg);
        }
        return false;
    }
    
    public void updateLaunchTime(String pkg) {
        if (isScreenOff) {
            if (DEBUG) {
                Slog.d(TAG, "Screen is off, skip updateLaunchTime : " + pkg);
            }
            return;
        }
        
        synchronized (this) {
            if (DEBUG) {
                Slog.d(TAG, "Update Total Launch Times :" + pkg);
            }
            UsageRecord record = pkgUsgMap.get(pkg);
            if (record != null) {
                record.onAppLaunched(System.currentTimeMillis());
                record.canUpdateDuration = false;
                
                if (!lastRunningPackage.equals(pkg)) {
                    record.onLaunchCountIncreased(pkg);
                }
                lastRunningPackage = pkg;
            } else {
                lastRunningPackage = pkg;
                if (DEBUG) {
                    Slog.d(TAG, "sLastRunningPackage (null) : " + lastRunningPackage);
                }
            }
        }
    }
    
    public void updateDuration(String pkg) {
        synchronized (this) {
            UsageRecord record = pkgUsgMap.get(pkg);
            if (record == null || !isScreenOff || !record.canUpdateDuration) {
                if (record == null) {
                    return;
                }
                record.onAppStopped(System.currentTimeMillis());
                record.canUpdateDuration = true;
                return;
            }
            if (DEBUG) {
                Slog.d(TAG, "Screen is off, skip updateDuration : " + pkg + " " + record.canUpdateDuration);
            }
        }
    }
    
    public void appDied(ProcessRecord processRecord) {
        if (isHighUsedPackages(processRecord.info.packageName)) {
            highUsedAppDeathCount++;
            Slog.d(TAG, processRecord.info.packageName + " is died with pid " + processRecord.mPid);
            Message message = handler.obtainMessage(MSG_APP_DIED);
            Bundle data = new Bundle();
            data.putString("pkg_name", processRecord.info.packageName);
            data.putInt("pid", processRecord.mPid);
            message.setData(data);
            handler.sendMessage(message);
        }
    }
    
    public void addNewPackages(String pkg) {
        synchronized (this) {
            if (pkgUsgMap.containsKey(pkg)) {
                pkgUsgMap.remove(pkg);
            }
            if (DEBUG) {
                Slog.d(TAG, "Adding package : " + pkg);
            }
            pkgUsgMap.put(pkg, new UsageRecord(pkg, true, false));
        }
    }
    
    public void removePackage(String pkg) {
        synchronized (this) {
            if (!updatingPackageName.equals(pkg)) {
                if (DEBUG) {
                    Slog.d(TAG, "Removing package : " + pkg);
                }
                pkgUsgMap.remove(pkg);
            } else if (DEBUG) {
                Slog.d(TAG, "Updating package : " + pkg);
            }
        }
    }
    
    public void setUpdatingPackage(String pkg) {
        updatingPackageName = pkg;
    }
    
    public void setLastCachedPss(String pkg, long pss) {
        synchronized (this) {
            UsageRecord record = pkgUsgMap.get(pkg);
            if (record != null) {
                if (record.lastCachedPss < pss) {
                    record.lastCachedPss = pss;
                }
                if (DEBUG) {
                    Slog.d(TAG, "setLastCachedPss: " + pkg + " " + pss);
                }
            }
        }
    }
    
    public void setRemoveTaskTime(String pkg) {
        synchronized (this) {
            UsageRecord record = pkgUsgMap.get(pkg);
            if (record != null) {
                record.lastRemoveTaskTime = System.currentTimeMillis();
                if (DEBUG) {
                    Slog.d(TAG, "setRemoveTaskTime: " + pkg + " " + record.lastRemoveTaskTime);
                }
            }
        }
    }
    
    public void setTargetAdj(String pkg, int targetAdj) {
        try {
            synchronized (this) {
                for (String p : pkgUsgMap.keySet()) {
                    UsageRecord record = pkgUsgMap.get(p);
                    if (record.pkg.equals(pkg)) {
                        record.setTargetAdj(targetAdj);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public void setScreenState(boolean isScreenOff) {
        this.isScreenOff = isScreenOff;
        writeRecordsToDisk(false);
    }
    
    public void cleanAllData(long newTime) {
        long currentTime = System.currentTimeMillis();
        if (DEBUG) {
            Slog.d(TAG, "Old time : " + currentTime + ", new time : " + newTime);
        }
        long timeDiff = newTime - currentTime;
        if (Math.abs(timeDiff) >= TIME_CHANGE_THRESHOLD_MS) {
            if (DEBUG) {
                Slog.d(TAG, "Clean all data due to time is changed");
            }
            resetAllData(newTime);
        } else {
            Slog.d(TAG, "Normal time diff : " + timeDiff);
        }
    }
    
    public void writeRecordsToDisk(boolean force) {
        long currentTime = System.currentTimeMillis();
        if (!force && (isWritingRecord || currentTime - lastRecordWriteTime < RECORD_WRITE_INTERVAL_MS)) {
            Slog.d(TAG, "No need to write old record");
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, "Start to write old record");
        }
        isWritingRecord = true;
        new RecordWriterThread("OpRestartProcessManager", currentTime).start();
    }

    boolean isWarmedUp() {
        if (!hasWarmedUp && System.currentTimeMillis() - warmUpTime < WARM_UP_DURATION_MS) {
            return false;
        }
        hasWarmedUp = true;
        return true;
    }
    
    boolean isLmkdKill(String pkg, int pid) {
        int iIntValue = ProcessList.checkLmkdKillOptiProc(pid).intValue();
        Slog.d(TAG, "isLmkdKill " + pkg + ", pid= " + pid + ", found= " + iIntValue);
        return iIntValue != -1;
    }

    void initClusters() {
        clusterCenters.clear();
        clusters.clear();
        for (int i = 0; i < totalClusters; i++) {
            clusterCenters.add(new ClusterAvg());
            UsageCluster cluster = new UsageCluster(i);
            cluster.setAvg(clusterCenters.get(i));
            clusters.add(cluster);
        }
    }
    
    void initPackages(Context context) {
        ArrayList<String> pks = new ArrayList<>();
        LauncherApps launcherApps = (LauncherApps) context.getSystemService(LauncherApps.class);
        for (LauncherActivityInfo info : launcherApps.getActivityList(null, Process.myUserHandle())) {
            pks.add(info.getComponentName().getPackageName());
            if (DEBUG) {
                Slog.d(TAG, "Initial from system ready : " + info.getComponentName().getPackageName());
            }
        }
        initializePackageList(pks);
    }
    
    void initializePackageList(ArrayList<String> packages) {
        synchronized (this) {
            for (String pkg : packages) {
                if (!pkgUsgMap.containsKey(pkg)) {
                    pkgUsgMap.put(pkg, new UsageRecord(pkg, false, false));
                }
                if (DEBUG) {
                    Slog.d(TAG, "initialAllPackage : " + pkg);
                }
            }
        }
        isWritingRecord = false;
    }
    
    void createHandlerThread() {
        HandlerThread handlerThread = new HandlerThread(TAG);
        this.handlerThread = handlerThread;
        handlerThread.start();
        handler = new KillHandler(handlerThread.getLooper());
    }
    
    void scheduleAlarm(long alarmTime) {
        Slog.d(TAG, "schedule alarm, alarmTime: " + alarmTime);
        if (DEBUG) {
            am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarmTime, "AppUsageAlarmListener", al, null);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, alarmTime, "AppUsageAlarmListener", al, null);
        }
    }
    
    void loadRecords() {
        File primaryFile = new File(dataDirectory, APP_RECORD_FILE);
        File backupFile = new File(dataDirectory, APP_RECORD_BACKUP_FILE);
        
        File fileToRead = null;
        if (primaryFile.exists() && primaryFile.canRead()) {
            fileToRead = primaryFile;
        } else if (backupFile.exists() && backupFile.canRead()) {
            fileToRead = backupFile;
        }
        
        if (fileToRead != null) {
            Slog.d(TAG, "Use " + fileToRead.getAbsolutePath());
            synchronized (this) {
                try {
                    parseRecordFile(fileToRead);
                } catch (Throwable e) {
                    resetAllData(System.currentTimeMillis());
                }
            }
        }
    }
    
    void parseRecordFile(File file) throws Throwable {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String currentPackage = "";
            String line;
            if (DEBUG) {
                Slog.d(TAG, "Read old record");
            }
            while ((line = reader.readLine()) != null) {
                if (DEBUG) {
                    Slog.d(TAG, line);
                }
                if (line.startsWith("HasWarmUp :")) {
                    hasWarmedUp = Boolean.parseBoolean(line.substring(line.indexOf(":") + 1).trim());
                    Slog.d(TAG, "HasWarmUp : " + hasWarmedUp);
                } else if (line.startsWith("WarmUpTime :")) {
                    warmUpTime = Long.parseLong(line.substring(line.indexOf(":") + 1).trim());
                    Slog.d(TAG, "StartWarmUpTime : " + warmUpTime);
                } else if (line.startsWith("PackageName:")) {
                    currentPackage = line.substring(line.indexOf(":") + 1).trim();
                    pkgUsgMap.put(currentPackage, new UsageRecord(currentPackage, false, true));
                } else if (line.startsWith("LastCachedPss:")) {
                    long pss = Long.parseLong(line.substring(line.indexOf(":") + 1).trim());
                    pkgUsgMap.get(currentPackage).lastCachedPss = pss;
                    Slog.d(TAG, currentPackage + " last cached pss : " + pss);
                } else {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 4) {
                        TimeSlotRecord record = new TimeSlotRecord(currentPackage);
                        record.startTime = Long.parseLong(parts[0]);
                        record.launchCount = Long.parseLong(parts[1]);
                        record.totalUsageTime = Long.parseLong(parts[2]);
                        record.lruTime = Long.parseLong(parts[3]);
                        
                        pkgUsgMap.get(currentPackage).timeSlotRecords.add(record);
                        pkgUsgMap.get(currentPackage).totalLruTime = record.startTime;
                        
                        if (record.lruTime != 0) {
                            pkgUsgMap.get(currentPackage).currentLaunchTime = record.lruTime;
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            resetAllData(System.currentTimeMillis());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    
    void resetAllData(long newStartTime) {
        if (DEBUG) {
            Slog.d(TAG, "Start to clean data");
        }
        synchronized (this) {
            for (String pkg : pkgUsgMap.keySet()) {
                pkgUsgMap.get(pkg).resetUsageData(newStartTime);
            }
            isWritingRecord = true;
            lastRunningPackage = "";
            hasWarmedUp = false;
            warmUpTime = newStartTime;
            maxTimeSlots = 0;
            lastRankingTime = 0L;
            lastRecordWriteTime = 0L;
            usageCategoryMap.clear();
            
            try {
                File primaryFile = new File(dataDirectory, APP_RECORD_FILE);
                if (primaryFile.exists()) {
                    primaryFile.delete();
                }
                File backupFile = new File(dataDirectory, APP_RECORD_BACKUP_FILE);
                if (backupFile.exists()) {
                    backupFile.delete();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (DEBUG) {
            Slog.d(TAG, "Finish cleaning data");
        }
        isWritingRecord = false;
    }
    
    ArrayList<String> extractPackageNames(ArrayList<UsageRecord> records) {
        ArrayList<String> pkgs = new ArrayList<>();
        if (records == null) {
            return pkgs;
        }
        
        for (UsageRecord record : records) {
            pkgs.add(record.pkg);
        }
        return pkgs;
    }
    
    HashMap<String, ArrayList<UsageRecord>> categorizeApps(boolean needUpdate) {
        long currentTime = System.currentTimeMillis();
        
        if (needUpdate) {
            performAppRanking();
            lastRankingTime = System.currentTimeMillis();
        } else {
            if (!isSystemReady) {
                handleDebugOperations();
                return usageCategoryMap;
            }
            if (currentTime - lastRankingTime <= RANKING_INTERVAL_MS) {
                handleDebugOperations();
                return usageCategoryMap;
            }
            Slog.d(TAG, "Need to rank due to duration is over : " + RANKING_INTERVAL_MS);
            performAppRanking();
            lastRankingTime = System.currentTimeMillis();
        }
        
        synchronized (this) {
            Collections.sort(clusters, new DistanceComparator());
            ArrayList<UsageRecord> highUsedApps = new ArrayList<>();
            ArrayList<UsageRecord> generalUsedApps = new ArrayList<>();
            ArrayList<UsageRecord> lowUsedApps = new ArrayList<>();
            usageCategoryMap.clear();
            
            for (int i = 0; i < lowUsed && i < clusters.size(); i++) {
                lowUsedApps.addAll(clusters.get(i).apps);
            }
            for (int i = lowUsed; i < lowUsed + generalUsed && i < clusters.size(); i++) {
                generalUsedApps.addAll(clusters.get(i).apps);
            }
            for (int i = lowUsed + generalUsed; i < totalClusters && i < clusters.size(); i++) {
                highUsedApps.addAll(clusters.get(i).apps);
            }
            
            balanceCategorySizes(highUsedApps, generalUsedApps, lowUsedApps);
            Collections.sort(highUsedApps, new LaunchFrequencyComparator());
            Collections.sort(generalUsedApps, new LaunchFrequencyComparator());
            Collections.sort(lowUsedApps, new LaunchFrequencyComparator());
            usageCategoryMap.put(HIGH_USED_PACKAGES_KEY, highUsedApps);
            usageCategoryMap.put(GENERAL_USED_PACKAGES_KEY, generalUsedApps);
            usageCategoryMap.put(LOW_USED_PACKAGES_KEY, lowUsedApps);
            if (DEBUG) {
                Slog.d(TAG, "KEY_HIGH_USED_PACKAGES : " + highUsedApps.size());
                Slog.d(TAG, "KEY_GENERAL_USED_PACKAGES : " + generalUsedApps.size());
                Slog.d(TAG, "KEY_LOW_USED_PACKAGES : " + lowUsedApps.size());
            }
        }
        
        handleDebugOperations();
        return usageCategoryMap;
    }
    
    void performAppRanking() {
        try {
            synchronized (this) {
                long currentTime = System.currentTimeMillis();
                if (DEBUG) {
                    Slog.d(TAG, "Start to rank all packages");
                }
                boolean timeSlotChanged = false;
                for (String pkg : pkgUsgMap.keySet()) {
                    UsageRecord record = pkgUsgMap.get(pkg);
                    record.clearScores();
                    if (record.updateTimeSlots(currentTime)) {
                        timeSlotChanged = true;
                    }
                }
                if (timeSlotChanged) {
                    Slog.d(TAG, "CurrentMaxDayRecord has changed, update all packages again");
                    for (String pkg : pkgUsgMap.keySet()) {
                        pkgUsgMap.get(pkg).updateTimeSlots(currentTime);
                    }
                }
                calculateTimeSlotScores();
                calculateLruScores(currentTime);
                calculateFinalScores();
                kMean();
                if (DEBUG) {
                    Slog.d(TAG, "Finish all packages");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            resetAllData(System.currentTimeMillis());
        }
    }
    
    void calculateTimeSlotScores() {
        for (int slotIndex = 0; slotIndex < maxTimeSlots; slotIndex++) {
            ArrayList<TimeSlotRecord> slotRecords = new ArrayList<>();
            for (String pkg : pkgUsgMap.keySet()) {
                UsageRecord record = pkgUsgMap.get(pkg);
                if (slotIndex < record.timeSlotRecords.size()) {
                    TimeSlotRecord slotRecord = record.timeSlotRecords.get(slotIndex);
                    if (slotRecord.lruTime != 0) {
                        slotRecord.totalUsageTime = slotRecord.foregroundDuration + slotRecord.totalUsageTime;
                        slotRecords.add(slotRecord);
                    }
                } else if (DEBUG) {
                    Slog.d(TAG, "Package " + record.pkg + " size is abnrmal " + 
                          record.timeSlotRecords.size() + ", max size is " + maxTimeSlots);
                }
            }
            if (slotRecords.size() > 0) {
                calculateSlotScores(slotRecords);
            }
        }
    }
    
    void calculateSlotScores(ArrayList<TimeSlotRecord> records) {
        for (TimeSlotRecord record : records) {
            record.clearScores();
        }
        Collections.sort(records, new UsageTimeComparator());
        long maxUsageTime = records.get(records.size() - 1).totalUsageTime;
        if (DEBUG) {
            Slog.d(TAG, "Max day duration : " + maxUsageTime);
        }
        for (TimeSlotRecord record : records) {
            if (record.totalUsageTime != 0) {
                record.fgTimeScore = (record.totalUsageTime / (double)maxUsageTime) * SCORE_MULTIPLIER;
                if (DEBUG) {
                    Slog.d(TAG, record.pkg + " got score " + record.fgTimeScore + 
                          " in DayDuration for duration : " + record.totalUsageTime);
                }
            }
        }
        Collections.sort(records, new LaunchCountComparator());
        long maxLaunchCount = records.get(records.size() - 1).launchCount;
        if (DEBUG) {
            Slog.d(TAG, "Max launch times : " + maxLaunchCount);
        }
        for (TimeSlotRecord record : records) {
            if (record.launchCount != 0) {
                record.launchFreqScore = (record.launchCount / (double)maxLaunchCount) * SCORE_MULTIPLIER;
                if (DEBUG) {
                    Slog.d(TAG, record.pkg + " got score " + record.launchFreqScore + 
                          " in DayLaunchTimes for launch times : " + record.launchCount);
                }
            }
        }
    }
    
    void calculateLruScores(long currentTime) {
        ArrayList<TimeSlotRecord> allRecords = new ArrayList<>();
        for (String pkg : pkgUsgMap.keySet()) {
            UsageRecord record = pkgUsgMap.get(pkg);
            for (int i = maxTimeSlots - 1; i >= 0; i--) {
                if (i < record.timeSlotRecords.size() && record.timeSlotRecords.get(i).lruTime != 0) {
                    allRecords.add(record.timeSlotRecords.get(i));
                    break;
                }
            }
        }
        if (allRecords.isEmpty()) {
            return;
        }
        Collections.sort(allRecords, new LruTimeComparator());
        TimeSlotRecord leastRecentlyUsed = allRecords.get(allRecords.size() - 1);
        long maxLruTime = leastRecentlyUsed.lruTime;
        long startTime = pkgUsgMap.get(leastRecentlyUsed.pkg).timeSlotRecords.get(0).startTime;
        long timeDiff = maxLruTime - startTime;
        
        if (DEBUG) {
            Slog.d(TAG, "leastUsedTime " + maxLruTime + ", startTime " + startTime + "time diff: " + timeDiff);
        }
        
        for (TimeSlotRecord record : allRecords) {
            long recordTimeDiff = record.lruTime - startTime;
            if (recordTimeDiff >= 0) {
                UsageRecord appRecord = pkgUsgMap.get(record.pkg);
                appRecord.lruScore = (maxTimeSlots * SCORE_MULTIPLIER) * (recordTimeDiff / (double)timeDiff);
                if (DEBUG) {
                    Slog.d(TAG, record.pkg + " got score " + appRecord.lruScore + " LRU for LRU diff : " + recordTimeDiff);
                }
            }
        }
    }
    
    void calculateFinalScores() {
        for (String pkg : pkgUsgMap.keySet()) {
            UsageRecord record = pkgUsgMap.get(pkg);
            for (int i = 0; i < maxTimeSlots && i < record.timeSlotRecords.size(); i++) {
                TimeSlotRecord slotRecord = record.timeSlotRecords.get(i);
                double timeWeight = i + 1;
                record.launchFreqScore += slotRecord.launchFreqScore * timeWeight;
                record.fgTimeScore += slotRecord.fgTimeScore * timeWeight;
            }
        }
    }
    
    void kMean() {
        initClusterCenters();
        boolean converged = false;
        int iteration = 0;
        while (!converged && iteration < MAX_CLUSTERING_ITERATIONS) {
            clearClusterAssignments();
            ArrayList<ClusterAvg> previousCenters = copyClusterCenters();
            assignAppsToNearestClusters();
            updateClusterCenters();
            iteration++;
            double totalMovement = calculateCentroidMovement(previousCenters, clusterCenters);
            if (DEBUG) {
                for (UsageCluster cluster : clusters) {
                    cluster.avg.printDebugInfo();
                }
                Slog.d(TAG, "Iteration: " + iteration);
                Slog.d(TAG, "Centroid distances: " + totalMovement);
            }
            if (totalMovement == 0.0d) {
                converged = true;
            }
        }
        for (UsageCluster cluster : clusters) {
            cluster.distanceFromOrigin = calculateDistance(cluster.avg, new ClusterAvg(0.0d, 0.0d, 0.0d));
        }
        
        if (DEBUG) {
            for (int i = 0; i < totalClusters; i++) {
                clusters.get(i).printDebugInfo();
            }
        }
    }
    
    void initClusterCenters() {
        ArrayList<Double> launchScores = new ArrayList<>();
        ArrayList<Double> foregroundScores = new ArrayList<>();
        ArrayList<Double> lruScores = new ArrayList<>();
        
        for (String pkg : pkgUsgMap.keySet()) {
            UsageRecord record = pkgUsgMap.get(pkg);
            launchScores.add(record.launchFreqScore);
            foregroundScores.add(record.fgTimeScore);
            lruScores.add(record.lruScore);
        }
        
        Collections.sort(launchScores);
        Collections.sort(foregroundScores);
        Collections.sort(lruScores);
        
        double minLaunch = launchScores.get(0);
        double minForeground = foregroundScores.get(0);
        double minLru = lruScores.get(0);
        
        double maxLaunch = launchScores.get(launchScores.size() - 1);
        double maxForeground = foregroundScores.get(foregroundScores.size() - 1);
        double maxLru = lruScores.get(lruScores.size() - 1);
        
        double launchRange = maxLaunch - minLaunch;
        double foregroundRange = maxForeground - minForeground;
        double lruRange = maxLru - minLru;
        
        if (DEBUG) {
            Slog.d(TAG, "maxScoreX : " + maxLaunch + ", maxScoreY : " + maxForeground + ", maxScoreZ : " + maxLru);
            Slog.d(TAG, "minScoreX : " + minLaunch + ", minScoreY : " + minForeground + ", minScoreZ : " + minLru);
        }
        
        for (int i = 0; i < totalClusters; i++) {
            double ratio = (double) i / (totalClusters - 1);
            clusterCenters.get(i).launchFreq = minLaunch + (ratio * launchRange);
            clusterCenters.get(i).fgTime = minForeground + (ratio * foregroundRange);
            clusterCenters.get(i).lruScore = minLru + (ratio * lruRange);
            if (DEBUG) {
                clusterCenters.get(i).printDebugInfo();
            }
        }
    }
    
    void clearClusterAssignments() {
        for (UsageCluster cluster : clusters) {
            cluster.clearApps();
        }
    }
    
    ArrayList<ClusterAvg> copyClusterCenters() {
        ArrayList<ClusterAvg> copy = new ArrayList<>();
        for (ClusterAvg avg : clusterCenters) {
            copy.add(new ClusterAvg(avg.launchFreq, avg.fgTime, avg.lruScore));
        }
        return copy;
    }
    
    void assignAppsToNearestClusters() {
        for (String pkg : pkgUsgMap.keySet()) {
            UsageRecord record = pkgUsgMap.get(pkg);
            double minDistance = Double.MAX_VALUE;
            int nearestClusterIndex = 0;
            for (int i = 0; i < totalClusters; i++) {
                double distance = calculateDistance(record, clusterCenters.get(i));
                if (distance < minDistance) {
                    nearestClusterIndex = i;
                    minDistance = distance;
                }
            }
            record.targetAdjustment = nearestClusterIndex;
            clusters.get(nearestClusterIndex).addApp(record);
            if (DEBUG) {
                record.printDebugInfo();
            }
        }
    }
    
    void updateClusterCenters() {
        for (UsageCluster cluster : clusters) {
            ArrayList<UsageRecord> apps = cluster.apps;
            int numApps = apps.size();
            if (numApps == 0) {
                continue;
            }
            ClusterAvg avg = cluster.avg;
            double totalLaunch = 0.0d;
            double totalForeground = 0.0d;
            double totalLru = 0.0d;
            for (UsageRecord app : apps) {
                totalLaunch += app.launchFreqScore;
                totalForeground += app.fgTimeScore;
                totalLru += app.lruScore;
            }
            avg.launchFreq = totalLaunch / numApps;
            avg.fgTime = totalForeground / numApps;
            avg.lruScore = totalLru / numApps;
        }
    }
    
    double calculateCentroidMovement(ArrayList<ClusterAvg> oldAvg, ArrayList<ClusterAvg> newAvg) {
        double totalMovement = 0.0d;
        for (int i = 0; i < oldAvg.size(); i++) {
            totalMovement += calculateDistance(oldAvg.get(i), newAvg.get(i));
        }
        return totalMovement;
    }
    
    static double calculateDistance(UsageRecord app, ClusterAvg avg) {
        return calculateDistance(app.launchFreqScore, app.fgTimeScore, app.lruScore,
                               avg.launchFreq, avg.fgTime, avg.lruScore);
    }
    
    static double calculateDistance(ClusterAvg avg1, ClusterAvg avg2) {
        return calculateDistance(avg1.launchFreq, avg1.fgTime, avg1.lruScore,
                               avg2.launchFreq, avg2.fgTime, avg2.lruScore);
    }
    
    static double calculateDistance(double x1, double y1, double z1, 
                                          double x2, double y2, double z2) {
        return Math.sqrt(Math.pow(x1 - x2, 2.0d) + Math.pow(y1 - y2, 2.0d) + Math.pow(z1 - z2, 2.0d));
    }
    
    void balanceCategorySizes(ArrayList<UsageRecord> highUsed, 
                                    ArrayList<UsageRecord> generalUsed, 
                                    ArrayList<UsageRecord> lowUsed) {
        int excessHighUsed = highUsed.size() - maxHighUsedApps;
        if (excessHighUsed > 0) {
            Collections.sort(highUsed, new LaunchFrequencyComparator());
            for (int i = 0; i < excessHighUsed; i++) {
                int lastIndex = highUsed.size() - 1;
                if (lastIndex >= 0) {
                    UsageRecord app = highUsed.get(lastIndex);
                    if (DEBUG) {
                        Slog.d(TAG, "Choose " + app.pkg + " from High to General");
                    }
                    highUsed.remove(app);
                    generalUsed.add(app);
                }
            }
        }
        int missingHighUsed = maxHighUsedApps - highUsed.size();
        if (missingHighUsed > 0) {
            Collections.sort(generalUsed, new LaunchFrequencyComparator());
            for (int i = 0; i < missingHighUsed && !generalUsed.isEmpty(); i++) {
                UsageRecord app = generalUsed.get(0);
                if (DEBUG) {
                    Slog.d(TAG, "Choose " + app.pkg + " from General to High");
                }
                generalUsed.remove(app);
                highUsed.add(app);
            }
        }
    }
    
    void handleDebugOperations() {
        if (DEBUG) {
            synchronized (this) {
                try {
                    ArrayList<UsageRecord> highUsedApps = usageCategoryMap.get(HIGH_USED_PACKAGES_KEY);
                    ArrayList<UsageRecord> generalUsedApps = usageCategoryMap.get(GENERAL_USED_PACKAGES_KEY);
                    ArrayList<UsageRecord> lowUsedApps = usageCategoryMap.get(LOW_USED_PACKAGES_KEY);
                    
                    if (highUsedApps == null) {
                        highUsedApps = new ArrayList<>();
                        usageCategoryMap.put(HIGH_USED_PACKAGES_KEY, highUsedApps);
                    }
                    if (generalUsedApps == null) {
                        generalUsedApps = new ArrayList<>();
                        usageCategoryMap.put(GENERAL_USED_PACKAGES_KEY, generalUsedApps);
                    }
                    if (lowUsedApps == null) {
                        lowUsedApps = new ArrayList<>();
                        usageCategoryMap.put(LOW_USED_PACKAGES_KEY, lowUsedApps);
                    }
                    
                    for (String pkg : debugHighUsedPackages) {
                        movePackageBetweenCategories(pkg, generalUsedApps, highUsedApps);
                        movePackageBetweenCategories(pkg, lowUsedApps, highUsedApps);
                    }
                    
                    for (String pkg : debugGeneralUsedPackages) {
                        movePackageBetweenCategories(pkg, highUsedApps, generalUsedApps);
                        movePackageBetweenCategories(pkg, lowUsedApps, generalUsedApps);
                    }
                    
                    for (String pkg : debugLowUsedPackages) {
                        movePackageBetweenCategories(pkg, highUsedApps, lowUsedApps);
                        movePackageBetweenCategories(pkg, generalUsedApps, lowUsedApps);
                    }
                } catch (Throwable th) {
                    throw th;
                }
            }
        }
    }
    
    void movePackageBetweenCategories(String pkg, ArrayList<UsageRecord> fromList, ArrayList<UsageRecord> toList) {
        Iterator<UsageRecord> iterator = fromList.iterator();
        while (iterator.hasNext()) {
            UsageRecord record = iterator.next();
            if (record.pkg.equals(pkg)) {
                iterator.remove();
                toList.add(record);
                break;
            }
        }
    }
    
    public void submitUsageData(String pkgs, String usageTimes) {
        Bundle data = new Bundle();
        data.putString(PACKAGE_KEY, pkgs);
        data.putString(FOREGROUND_TIME_KEY, usageTimes);
        if (DEBUG) {
            Slog.d(TAG, "submitAppUsage");
            Slog.d(TAG, "pkgNameList: " + pkgs);
            Slog.d(TAG, "timeList: " + usageTimes);
        }
    }
    
    public void submitAppLaunchCount(Bundle bundle) {
        if (DEBUG) {
            Slog.d(TAG, "submitAppLaunchCount");
            Slog.d(TAG, "pkgNameLaunchTotalTime: " + bundle.toString());
        }
    }
    
    void submitHighUsedAppDeaths() {
        Bundle data = new Bundle();
        data.putInt(HIGH_USED_DIE_KEY, highUsedAppDeathCount);
        boolean isHighUsedOptEnabled = true; 
        data.putBoolean(OPTI_HIGH_USED_KEY, isHighUsedOptEnabled);
        if (DEBUG) {
            Slog.d(TAG, "submitHighUsedDie: " + highUsedAppDeathCount + ", " + isHighUsedOptEnabled);
        }
        highUsedAppDeathCount = 0;
    }
    
    public void init(Context context) {
        initClusters();
        loadRecords();
        initPackages(context);
        if (warmUpTime == 0) {
            warmUpTime = System.currentTimeMillis();
        }
        targetValues.add(801);
        targetValues.add(601);
        targetValues.add(401);
    }
}
