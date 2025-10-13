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
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.util.Slog;
import com.android.server.am.ProcessList;
import com.android.server.am.ProcessRecord;
import com.android.server.utils.SimpleAppRecord;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;

public class NtAppUsageManagerImpl implements INtAppUsageManager {

    private static final String TAG = "NtAppUsageManager";

    public static final String APP_RECORD_FILE = "app_record.txt";

    public static final String APP_RECORD_BACKUP_FILE = "app_record_backup.txt";

    private static boolean isScreenOff = false;

    private static boolean misWritingRecord = true;

    private static boolean mHasWarmedUp = false;

    private static String mLastRunningPackage = "";

    private static int mMaxTimeSlots = 0;

    private static long mLastRankingTime = 0;

    private static long mLastRecordWriteTime = 0L;

    private static boolean DEBUG = SystemProperties.getBoolean("persist.sys.appusage.debug", false);

    private static final long usageThreshold = 1000;

    private static final long RANKING_INTERVAL_MS = 1800000;

    private static final long RECORD_WRITE_INTERVAL_MS = 1800000;

    private static final long TIME_CHANGE_THRESHOLD_MS = 3600000;

    private static final double SCORE_MULTIPLIER = 100.0d;

    private static long WARM_UP_DURATION_MS = DEBUG ? 60000L : 43200000L;

    private static final long TIME_SLOT_DURATION_MS = DEBUG ? 180000L : 86400000L;

    private static final int MAX_TIME_SLOTS = DEBUG ? 5 : 30;

    public static final String HIGH_USED_PACKAGES_KEY = "High Used Packages";

    private static final int MAX_APP_COUNT_PER_BATCH = SystemProperties.getInt("persist.sys.appusage.maxcount", 30);

    public static final String GENERAL_USED_PACKAGES_KEY = "General Used Packages";

    public static final String LOW_USED_PACKAGES_KEY = "Low Used Packages";

    private Context mContext;

    private AppUsageAlarmListener mUsageAlarmListener;

    private File dataDirectory;

    private Handler mHandler;

    private AlarmManager mAlarmManager;

    private HandlerThread mHandlerThread;

    private AtomicFile mAtomicRecordFile;

    private long mWarmUpDuration = 0;

    private String mUpdatingPkgName = "";

    private HashMap<String, PackageRecord> mCurrentMaxDayRecord = new HashMap<>();

    private HashMap<String, ArrayList<PackageRecord>> mRecords = new HashMap<>();

    public boolean mSystemReady = false;

    public boolean mRecordLoaded = false;

    private int mHighUsed = 3;

    private int mGeneralUsed = 1;

    private int mLowUsed = 1;

    private int mTotalClusters = (mHighUsed + mGeneralUsed) + mLowUsed;

    private int mMaxHighUsedApps = 3;

    private int mMaxGeneralUsedApps = 10;

    private int mMaxLowUsedApps = 3;

    private ArrayList<Cluster> mClusters = new ArrayList<>();

    private ArrayList<Point> mPoints = new ArrayList<>();

    private ArrayList<Integer> mTargetValues = new ArrayList<>();

    private static final class DistanceComparator implements Comparator<PackageRecord> {
        @Override
        public int compare(PackageRecord pr, PackageRecord pr2) {
            return Double.valueOf(calculateDistance(pr2, new Point(0.0d, 0.0d, 0.0d))).compareTo(
                Double.valueOf(calculateDistance(pr, new Point(0.0d, 0.0d, 0.0d))));
        }
    }

    private static final class LruComparator implements Comparator<TimeSlotRecord> {
        @Override
        public int compare(TimeSlotRecord tr, TimeSlotRecord tr2) {
            return Long.valueOf(tr.lruTime).compareTo(Long.valueOf(tr2.lruTime));
        }
    }

    private static final class TrUsageTimeComparator implements Comparator<TimeSlotRecord> {
        @Override
        public int compare(TimeSlotRecord tr, TimeSlotRecord tr2) {
            return Long.valueOf(tr.usageTime).compareTo(Long.valueOf(tr2.usageTime));
        }
    }

    public static class PackageRecord {

        private long currentLaunchTime;

        private long launchTime;

        public double x;

        private long lastLaunchTime;

        public double y;

        private String packageName;

        public int distanceFromOrigin;

        private long totalLruTime;

        public double z;

        private ArrayList<TimeSlotRecord> mTimeRecords = new ArrayList<>();

        private int timeIndex = 0;

        public boolean canUpdateDuration = true;

        private boolean isLaunchInProgress = false;

        private long lastCachedPss = 0;

        private long removeTime = 0;

        private long lmkdKillTime = 0;

        private int adj = -1;

        public PackageRecord(String pkg, boolean initializeSlots, boolean skipCurrentSlot) {
            packageName = pkg;
            long jCurrentTimeMillis = System.currentTimeMillis();
            totalLruTime = jCurrentTimeMillis - (jCurrentTimeMillis % TIME_SLOT_DURATION_MS);
            if (initializeSlots) {
                for (int i = 0; i < mMaxTimeSlots - 1; i++) {
                    mTimeRecords.add(new TimeSlotRecord(packageName));
                }
            }
            if (skipCurrentSlot) {
                return;
            }
            TimeSlotRecord tr = new TimeSlotRecord(packageName);
            tr.startTime = totalLruTime;
            mTimeRecords.add(tr);
        }

        static void setTotalLruTime(PackageRecord pr, long j) {
            pr.totalLruTime = j;
        }

        static ArrayList getTimeSlotRecords(PackageRecord pr) {
            return pr.mTimeRecords;
        }

        static void setLaunchTime(PackageRecord pr, long j) {
            pr.launchTime = j;
        }

        static long getRemoveTime(PackageRecord pr) {
            return pr.removeTime;
        }

        static String getPackageName(PackageRecord pr) {
            return pr.packageName;
        }

        static long getLaunchTime(PackageRecord pr) {
            return pr.launchTime;
        }

        public int getTargetAdj() {
            return adj;
        }

        public void setLmkdKillTime(long j) {
            lmkdKillTime = j;
            if (DEBUG) {
                Slog.d(TAG, packageName + " set lmkd kill time " + j);
            }
        }

        public void serialize() {
            Slog.d(TAG, packageName + " : X : " + x + ", Y : " + y + ", Z : " + z);
        }

        public void setRemoveTime(long j) {
            removeTime = j;
        }

        public long getLastCachedPss() {
            return lastCachedPss;
        }

        public void setTargetAdj(int i) {
            adj = i;
        }

        void setCurrentLaunchTme(long j) {
            currentLaunchTime = j;
        }

        public void onAppStopped(long stopTime) {
            long j = launchTime;
            long duration = stopTime - j;
            TimeSlotRecord trxx = mTimeRecords.get(timeIndex);
            if (duration >= usageThreshold) {
                if (j != 0) {
                    trxx.addDuration(duration, packageName);
                }
                isLaunchInProgress = false;
                return;
            }
            Slog.d(TAG, "Duration is too short, ignore : " + duration + " in " + packageName);
            long j2 = lastLaunchTime;
            launchTime = j2;
            if (j2 > trxx.startTime) {
                trxx.lruTime = lastLaunchTime;
            } else {
                trxx.lruTime = 0L;
            }
            if (isLaunchInProgress) {
                trxx.decrementLaunchCount();
                mLastRunningPackage = "";
            }
            isLaunchInProgress = false;
        }

        public void resetUsageData(long j) {
            x = 0.0d;
            y = 0.0d;
            z = 0.0d;
            totalLruTime = j - (j % TIME_SLOT_DURATION_MS);
            currentLaunchTime = 0L;
            mTimeRecords.clear();
            TimeSlotRecord tr = new TimeSlotRecord(packageName);
            tr.startTime = totalLruTime;
            mTimeRecords.add(tr);
            timeIndex = 0;
            launchTime = 0L;
            lastLaunchTime = 0L;
            canUpdateDuration = true;
        }

        public void resetTotalTimes() {
            currentLaunchTime = 0L;
        }

        public boolean updateTimeSlots(long j) {
            long slotsToAdd = (j - totalLruTime) / TIME_SLOT_DURATION_MS;
            int size = mTimeRecords.size() - MAX_TIME_SLOTS;
            for (int i = 0; i < size; i++) {
                mTimeRecords.remove(0);
            }
            if (slotsToAdd > 0) {
                Slog.d(TAG, "Package record in " + packageName + "  need to add : " + slotsToAdd + ", max size is " + mMaxTimeSlots);
                int i2 = 0;
                while (i2 < slotsToAdd) {
                    TimeSlotRecord tr = new TimeSlotRecord(packageName);
                    i2++;
                    tr.startTime = totalLruTime + (TIME_SLOT_DURATION_MS * i2);
                    if (mTimeRecords.size() >= MAX_TIME_SLOTS) {
                        mTimeRecords.remove(0);
                    }
                    mTimeRecords.add(tr);
                }
            }
            if (mMaxTimeSlots > mTimeRecords.size()) {
                Slog.d(TAG, "Package record size is abnormal in " + packageName + " , size : " + mTimeRecords.size() + ", max size is " + mMaxTimeSlots);
                int max = mMaxTimeSlots - mTimeRecords.size();
                for (int i3 = 0; i3 < max; i3++) {
                    mTimeRecords.add(i3, new TimeSlotRecord(packageName));
                }
            }
            ArrayList<TimeSlotRecord> arrayList = mTimeRecords;
            totalLruTime = arrayList.get(arrayList.size() - 1).startTime;
            timeIndex = mTimeRecords.size() - 1;
            if (mMaxTimeSlots >= mTimeRecords.size()) {
                return false;
            }
            mMaxTimeSlots = mTimeRecords.size();
            return true;
        }

        public void onAppLaunched(long j) {
            lastLaunchTime = launchTime;
            launchTime = j;
            updateTimeSlots(j);
            mTimeRecords.get(timeIndex).lruTime = j;
        }

        public void setLastCachedPss(long j) {
            lastCachedPss = j;
        }

        public void setDistanceFromOrigin(int i) {
            distanceFromOrigin = i;
        }

        public long getRemovedTime() {
            return removeTime;
        }

        public long getLmkdKillTime() {
            if (DEBUG) {
                Slog.d(TAG, packageName + " get lmkd kill time " + lmkdKillTime);
            }
            return lmkdKillTime;
        }

        public byte[] serializeBytes() {
            StringBuilder sb = new StringBuilder();
            sb.append("PackageName: ");
            sb.append(packageName + "\n");
            sb.append("LastCachedPss: ");
            sb.append(lastCachedPss);
            sb.append("\n");
            ArrayList<TimeSlotRecord> arrayList = mTimeRecords;
            int size = arrayList.size();
            int i = 0;
            while (i < size) {
                TimeSlotRecord tr = arrayList.get(i);
                i++;
                sb.append(tr.serialize());
            }
            return sb.toString().getBytes();
        }

        public String getPkg() {
            return packageName;
        }

        public void onLaunchCountIncreased(String str) {
            mTimeRecords.get(timeIndex).incrementLaunchCount();
            isLaunchInProgress = true;
            if (DEBUG) {
                Slog.d(TAG, "Increase Total Launch Time : " + str + ", times : " + TimeSlotRecord.getLaunchCount(mTimeRecords.get(timeIndex)) + ", index : " + timeIndex);
            }
        }

        public void reset() {
            x = 0.0d;
            y = 0.0d;
            z = 0.0d;
        }
    }

    class AppUsageHandler extends Handler {
        AppUsageHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            if (message.what != 0) {
                return;
            }
            try {
                Bundle data = message.getData();
                Slog.d(TAG, "msg MSG_APP_DIED");
                for (String str : data.keySet()) {
                    Slog.d(TAG, str + " = \"" + data.get(str) + "\"");
                }
                String string = data.getString("pkg_name");
                int i = data.getInt("pid");
                if (DEBUG || isLmkdKill(string, i)) {
                    long jCurrentTimeMillis = System.currentTimeMillis();
                    if (mCurrentMaxDayRecord.containsKey(string)) {
                        ((PackageRecord) mCurrentMaxDayRecord.get(string)).setLmkdKillTime(jCurrentTimeMillis);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static final class CachedPssComparator implements Comparator<PackageRecord> {
        @Override
        public int compare(PackageRecord pr, PackageRecord pr2) {
            return Long.valueOf(pr.getLastCachedPss()).compareTo(Long.valueOf(pr2.lastCachedPss));
        }
    }

    private static final class TimeSlotRecord {

        private long launchCount;

        public long startTime;

        private long totalUsageTime;

        private String pkgName;

        private long usageTime;

        public long lruTime;

        private long usageDuration;

        private double launchFreqScore = 0.0d;

        private double fgTimeScore = 0.0d;

        public TimeSlotRecord(String pkg) {
            pkgName = pkg;
        }

        static void setUsageTime(TimeSlotRecord tr, long j) {
            tr.usageTime = j;
        }

        static long getUsageTime(TimeSlotRecord tr) {
            return tr.usageTime;
        }

        static void setFgTimeScore(TimeSlotRecord tr, double d) {
            tr.fgTimeScore = d;
        }

        static long getLaunchCount(TimeSlotRecord tr) {
            return tr.launchCount;
        }

        static void setLaunchFreqScore(TimeSlotRecord tr, double d) {
            tr.launchFreqScore = d;
        }

        static long getTotalUsageTime(TimeSlotRecord tr) {
            return tr.totalUsageTime;
        }

        static long getForegroundDuration(TimeSlotRecord tr) {
            return tr.usageDuration;
        }

        static double getFgTimeScore(TimeSlotRecord tr) {
            return tr.fgTimeScore;
        }

        static void setLaunchCount(TimeSlotRecord tr, long j) {
            tr.launchCount = j;
        }

        static double getLaunchFreqScore(TimeSlotRecord tr) {
            return tr.launchFreqScore;
        }

        static String getPackageName(TimeSlotRecord tr) {
            return tr.pkgName;
        }

        public String serialize() {
            return new String(startTime + " " + launchCount + " " + usageTime + " " + lruTime + " \n");
        }

        public void addUsageDuration(long j) {
            usageDuration += j;
        }

        public void addForegroundTime(long duration, String pkg) {
            if (DEBUG) {
                Slog.d(TAG, "increase duration : " + duration + " for " + pkg);
            }
            usageDuration += duration;
        }

        public void decrementLaunchCount() {
            launchCount--;
        }

        public void clearScores() {
            launchFreqScore = 0.0d;
            fgTimeScore = 0.0d;
        }

        public void incrementLaunchCount() {
            launchCount++;
        }

        public void addDuration(long duration, String pkg) {
            if (lruTime != 0 && duration > 0) {
                addForegroundTime(duration, pkg);
                return;
            }
            if (DEBUG) {
                Slog.d(TAG, "Abnormal duration " + duration + ", launch time : " + lruTime + " for " + pkg);
            }
        }
    }

    private static final class ClusterDistanceComparator implements Comparator<Cluster> {
        @Override
        public int compare(Cluster cluster, Cluster cluster2) {
            return Double.valueOf(cluster.distance).compareTo(Double.valueOf(cluster2.distance));
        }
    }

    class RecordWriterThread extends Thread {
        final long time;

        RecordWriterThread(String str, long j) {
            super(str);
            time = j;
        }

        @Override
        public void run() {
            mLastRecordWriteTime = time;
            File file = new File(dataDirectory, APP_RECORD_FILE);
            FileOutputStream outputStr = null;
            try {
                try {
                    if (file.exists()) {
                        File file2 = new File(dataDirectory, APP_RECORD_BACKUP_FILE);
                        if (file2.exists()) {
                            file2.delete();
                        }
                        file.renameTo(file2);
                    }
                    mAtomicRecordFile = new AtomicFile(new File(dataDirectory, APP_RECORD_FILE));
                    outputStr = mAtomicRecordFile.startWrite();
                    String str = "HasWarmUp : " + mHasWarmedUp + "\n";
                    String str2 = "WarmUpTime : " + mWarmUpDuration + "\n";
                    outputStr.write(str.getBytes());
                    outputStr.write(str2.getBytes());
                    Iterator it = mCurrentMaxDayRecord.keySet().iterator();
                    while (it.hasNext()) {
                        PackageRecord pr = (PackageRecord) mCurrentMaxDayRecord.get((String) it.next());
                        ArrayList timeSlotRecords = PackageRecord.getTimeSlotRecords(pr);
                        int size = timeSlotRecords.size();
                        int i = 0;
                        while (i < size) {
                            Object obj = timeSlotRecords.get(i);
                            i++;
                            TimeSlotRecord tr = (TimeSlotRecord) obj;
                            TimeSlotRecord.setUsageTime(tr, TimeSlotRecord.getForegroundDuration(tr) + TimeSlotRecord.getTotalUsageTime(tr));
                        }
                        outputStr.write(pr.serializeBytes());
                    }
                    outputStr.flush();
                    mAtomicRecordFile.finishWrite(outputStr);
                    if (DEBUG) {
                        Slog.d(TAG, "Finishing writting old record");
                    }
                    misWritingRecord = false;
                } catch (Exception e) {
                    Slog.w(TAG, "Error writing process statistics", e);
                    mAtomicRecordFile.failWrite(outputStr);
                    misWritingRecord = false;
                }
            } catch (Throwable th) {
                misWritingRecord = false;
                throw th;
            }
        }
    }

    private static final class TimeSlotComparator implements Comparator<TimeSlotRecord> {
        @Override
        public int compare(TimeSlotRecord tr, TimeSlotRecord tr2) {
            return Long.valueOf(TimeSlotRecord.getLaunchCount(tr)).compareTo(Long.valueOf(TimeSlotRecord.getLaunchCount(tr2)));
        }
    }

    private static final class PackageRecordComparator implements Comparator<PackageRecord> {
        @Override
        public int compare(PackageRecord pr, PackageRecord pr2) {
            return Double.valueOf(pr2.y + pr2.x + pr2.z).compareTo(Double.valueOf(pr.y + pr.x + pr.z));
        }
    }

    private static class Cluster {

        public Point point;

        public int cluster;

        public ArrayList<PackageRecord> records = new ArrayList<>();

        public double distance;

        public Cluster(int i) {
            cluster = i;
        }

        public void setRecords(ArrayList<PackageRecord> arrayList) {
            records = arrayList;
        }

        public void setPoint(Point point) {
            point = point;
        }

        public void clearRecords() {
            records.clear();
        }

        public void serialize() {
            Slog.d(TAG, "-----------------------------------------------------------------------");
            Slog.d(TAG, "Cluster " + cluster);
            if (point != null) point.dump();
            Slog.d(TAG, "Distance from 0 : " + distance);
            Slog.d(TAG, "All data : ");
            ArrayList<PackageRecord> arrayList = records;
            int size = arrayList.size();
            int i = 0;
            while (i < size) {
                PackageRecord pr = arrayList.get(i);
                i++;
                pr.serialize();
            }
            Slog.d(TAG, "-----------------------------------------------------------------------");
        }

        public void addPackage(PackageRecord pr) {
            records.add(pr);
        }

        public ArrayList<PackageRecord> getRecords() {
            return records;
        }

        public int getCluster() {
            return cluster;
        }

        public Point getPoint() {
            return point;
        }
    }

    private class AppUsageAlarmListener implements AlarmManager.OnAlarmListener {

        private long mDataStartTime;

        AppUsageAlarmListener(long time) {
            mDataStartTime = time;
            if (DEBUG) {
                Slog.d(TAG, "AppUsageAlarmListener DataStartTime: " + mDataStartTime);
            }
        }

        private void scheduleNextAlarm() {
            mDataStartTime += TIME_SLOT_DURATION_MS;
            Slog.d(TAG, "schedule next alarm, DataStartTime: " + mDataStartTime);
            scheduleAlarm(mDataStartTime + TIME_SLOT_DURATION_MS);
        }

        @Override
        public void onAlarm() {
            scheduleNextAlarm();
        }
    }

    public NtAppUsageManagerImpl() {
        Slog.d(TAG, "init NtAppUsageManager");
    }

    private boolean isLmkdKill(String pkg, int pid) {
        int iIntValue = ProcessList.checkLmkdKillOptiProc(pid).intValue();
        Slog.d(TAG, "isLmkdKill " + pkg + ", pid= " + pid + ", found= " + iIntValue);
        return iIntValue != -1;
    }

    private void rankAllPackages() {
        int i;
        ArrayList arrayList = new ArrayList();
        ArrayList arrayList2 = new ArrayList();
        ArrayList arrayList3 = new ArrayList();
        try {
            synchronized (this) {
                try {
                    long jCurrentTimeMillis = System.currentTimeMillis();
                    if (DEBUG) {
                        Slog.d(TAG, "Start to rank all packages");
                    }
                    Iterator<String> it = mCurrentMaxDayRecord.keySet().iterator();
                    boolean z = false;
                    while (true) {
                        i = 1;
                        if (!it.hasNext()) {
                            break;
                        }
                        PackageRecord pr = mCurrentMaxDayRecord.get(it.next());
                        pr.reset();
                        if (pr.updateTimeSlots(jCurrentTimeMillis)) {
                            z = true;
                        }
                    }
                    if (z) {
                        Slog.d(TAG, "CurrentMaxDayRecord has changed, update all packages again");
                        Iterator<String> it2 = mCurrentMaxDayRecord.keySet().iterator();
                        while (it2.hasNext()) {
                            mCurrentMaxDayRecord.get(it2.next()).updateTimeSlots(jCurrentTimeMillis);
                        }
                    }
                    ArrayList<TimeSlotRecord> arrayList4 = new ArrayList<>();
                    for (int i2 = 0; i2 < mMaxTimeSlots; i2++) {
                        arrayList4.clear();
                        Iterator<String> it3 = mCurrentMaxDayRecord.keySet().iterator();
                        while (it3.hasNext()) {
                            PackageRecord pr2 = mCurrentMaxDayRecord.get(it3.next());
                            if (i2 < PackageRecord.getTimeSlotRecords(pr2).size()) {
                                TimeSlotRecord tr = (TimeSlotRecord) PackageRecord.getTimeSlotRecords(pr2).get(i2);
                                if (tr.lruTime != 0) {
                                    TimeSlotRecord.setUsageTime(tr, TimeSlotRecord.getForegroundDuration(tr) + TimeSlotRecord.getTotalUsageTime(tr));
                                    arrayList4.add(tr);
                                }
                            } else {
                                Slog.d(TAG, "Package " + PackageRecord.getPackageName(pr2) + " size is abnrmal " + PackageRecord.getTimeSlotRecords(pr2).size() + ", max size is " + mMaxTimeSlots);
                            }
                        }
                        if (arrayList4.size() != 0) {
                            calculateSlotScores(arrayList4);
                        }
                    }
                    arrayList4.clear();
                    Iterator<String> it4 = mCurrentMaxDayRecord.keySet().iterator();
                    int i3 = 0;
                    while (it4.hasNext()) {
                        PackageRecord pr2 = mCurrentMaxDayRecord.get(it4.next());
                        for (int i4 = mMaxTimeSlots - 1; i4 >= 0; i4--) {
                            TimeSlotRecord trx = (TimeSlotRecord) PackageRecord.getTimeSlotRecords(pr2).get(i4);
                            if (trx.lruTime != 0) {
                                arrayList4.add(trx);
                                if (i4 > i3) {
                                    i3 = i4;
                                }
                            }
                        }
                    }
                    calculateScores(arrayList4, i3 + 1);
                    Iterator<String> it5 = mCurrentMaxDayRecord.keySet().iterator();
                    while (it5.hasNext()) {
                        PackageRecord packageRecord3 = mCurrentMaxDayRecord.get(it5.next());
                        int i5 = 0;
                        while (i5 < mMaxTimeSlots) {
                            TimeSlotRecord tr2 = (TimeSlotRecord) PackageRecord.getTimeSlotRecords(packageRecord3).get(i5);
                            i5++;
                            int i6 = i;
                            double d = i5;
                            packageRecord3.x += TimeSlotRecord.getLaunchFreqScore(tr2) * d;
                            packageRecord3.y += TimeSlotRecord.getFgTimeScore(tr2) * d;
                            i = i6;
                        }
                        arrayList.add(Double.valueOf(packageRecord3.x));
                        arrayList2.add(Double.valueOf(packageRecord3.y));
                        arrayList3.add(Double.valueOf(packageRecord3.z));
                        i = i;
                    }
                    int i7 = i;
                    Collections.sort(arrayList);
                    Collections.sort(arrayList2);
                    Collections.sort(arrayList3);
                    double minScoreX = ((Double) arrayList.get(0)).doubleValue();
                    double minScoreY = ((Double) arrayList2.get(0)).doubleValue();
                    double minScoreZ = ((Double) arrayList3.get(0)).doubleValue();
                    double maxScoreY = ((Double) arrayList.get(arrayList.size() - i7)).doubleValue();
                    double maxScoreY2 = ((Double) arrayList2.get(arrayList2.size() - i7)).doubleValue();
                    double maxScoreZ = ((Double) arrayList3.get(arrayList3.size() - i7)).doubleValue();
                    double d2 = maxScoreY - minScoreX;
                    double d3 = maxScoreY2 - minScoreY;
                    double d4 = maxScoreZ - minScoreZ;
                    if (DEBUG) {
                        Slog.d(TAG, "maxScoreX : " + maxScoreY + ", maxScoreY : " + maxScoreY2 + ", maxScoreZ : " + maxScoreZ);
                        Slog.d(TAG, "minScoreX : " + minScoreX + ", minScoreY : " + minScoreY + ", minScoreZ : " + minScoreZ);
                    }
                    for (int i8 = 0; i8 < mTotalClusters; i8++) {
                        double d5 = i8;
                        mPoints.get(i8).x = ((d5 * d2) / (mTotalClusters - 1)) + minScoreX;
                        mPoints.get(i8).y = ((d5 * d3) / (mTotalClusters - 1)) + minScoreY;
                        mPoints.get(i8).z = ((d5 * d4) / (mTotalClusters - 1)) + minScoreZ;
                        if (DEBUG) {
                            mPoints.get(i8).dump();
                        }
                    }
                    computePkgsDistances();
                    if (DEBUG) {
                        Slog.d(TAG, "Finish all packages");
                    }
                } finally {
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            startCleanData(System.currentTimeMillis());
        }
    }

    private void resetClusters() {
        ArrayList<Cluster> arrayList = mClusters;
        int size = arrayList.size();
        int i = 0;
        while (i < size) {
            Cluster cluster = arrayList.get(i);
            i++;
            cluster.clearRecords();
        }
    }

    private void calculateSlotScores(ArrayList<TimeSlotRecord> arrayList) {
        double d;
        double d2;
        int size = arrayList.size();
        int i = 0;
        int i2 = 0;
        while (i2 < size) {
            TimeSlotRecord tr = arrayList.get(i2);
            i2++;
            tr.clearScores();
        }
        Collections.sort(arrayList, new TrUsageTimeComparator());
        long maxDayDuration = TimeSlotRecord.getUsageTime(arrayList.get(arrayList.size() - 1));
        if (DEBUG) {
            Slog.d(TAG, "Max day duration : " + maxDayDuration);
        }
        int size2 = arrayList.size();
        int i3 = 0;
        while (true) {
            d = SCORE_MULTIPLIER;
            if (i3 >= size2) {
                break;
            }
            TimeSlotRecord tr2 = arrayList.get(i3);
            i3++;
            TimeSlotRecord tr3 = tr2;
            if (TimeSlotRecord.getUsageTime(tr3) != 0) {
                TimeSlotRecord.setFgTimeScore(tr3, (TimeSlotRecord.getUsageTime(tr3) / maxDayDuration) * SCORE_MULTIPLIER);
                if (DEBUG) {
                    Slog.d(TAG, TimeSlotRecord.getPackageName(tr3) + " got score " + TimeSlotRecord.getFgTimeScore(tr3) + " in DayDuration for duration : " + TimeSlotRecord.getUsageTime(tr3));
                }
            }
        }
        Collections.sort(arrayList, new TimeSlotComparator());
        long maxLaunchTimes = TimeSlotRecord.getLaunchCount(arrayList.get(arrayList.size() - 1));
        if (DEBUG) {
            Slog.d(TAG, "Max launch times : " + maxLaunchTimes);
        }
        int size3 = arrayList.size();
        while (i < size3) {
            TimeSlotRecord tr4 = arrayList.get(i);
            i++;
            TimeSlotRecord tr5 = tr4;
            if (TimeSlotRecord.getLaunchCount(tr5) != 0) {
                d2 = d;
                TimeSlotRecord.setLaunchFreqScore(tr5, (TimeSlotRecord.getLaunchCount(tr5) / maxLaunchTimes) * d2);
                if (DEBUG) {
                    Slog.d(TAG, TimeSlotRecord.getPackageName(tr5) + " got score " + TimeSlotRecord.getLaunchFreqScore(tr5) + " in DayLaunchTimes for launch times : " + TimeSlotRecord.getLaunchCount(tr5));
                }
            } else {
                d2 = d;
            }
            d = d2;
        }
    }

    private static double calculateDistance(double d, double d2, double d3, double d4, double d5, double d6) {
        return Math.sqrt(Math.pow(d - d4, 2.0d) + Math.pow(d2 - d5, 2.0d) + Math.pow(d3 - d6, 2.0d));
    }

    private void calculateScores(ArrayList<TimeSlotRecord> arrayList, int i) {
        ArrayList<TimeSlotRecord> arrayList2 = arrayList;
        if (arrayList2.isEmpty()) {
            return;
        }
        int size = arrayList2.size();
        double d = SCORE_MULTIPLIER;
        int i2 = 0;
        if (size == 1) {
            mCurrentMaxDayRecord.get(TimeSlotRecord.getPackageName(arrayList2.get(0))).z = i * SCORE_MULTIPLIER;
            return;
        }
        Collections.sort(arrayList2, new LruComparator());
        TimeSlotRecord tr = arrayList2.get(arrayList2.size() - 1);
        long lruTime = tr.lruTime;
        TimeSlotRecord trx = (TimeSlotRecord) PackageRecord.getTimeSlotRecords(mCurrentMaxDayRecord.get(TimeSlotRecord.getPackageName(tr))).get(0);
        long start = trx.startTime;
        long j = lruTime - start;
        if (DEBUG) {
            Slog.d(TAG, "leastUsedTime " + lruTime + ", startTime " + start + "time diff: " + j);
        }
        int size2 = arrayList2.size();
        while (i2 < size2) {
            TimeSlotRecord tr2 = arrayList2.get(i2);
            i2++;
            TimeSlotRecord tr3 = tr2;
            long lruTime2 = tr3.lruTime - start;
            if (lruTime2 >= 0) {
                double d2 = d;
                PackageRecord pr = mCurrentMaxDayRecord.get(TimeSlotRecord.getPackageName(tr3));
                pr.z = (lruTime2 / j) * i * d2;
                if (DEBUG) {
                    Slog.d(TAG, TimeSlotRecord.getPackageName(tr3) + " got score " + pr.z + " LRU for LRU diff : " + lruTime2);
                }
                arrayList2 = arrayList;
                d = d2;
            }
        }
    }

    private void parseRecordFile(File file) {
        BufferedReader bufferedReader = null;
        try {
            try {
                BufferedReader bufferedReader2 = new BufferedReader(new FileReader(file));
                try {
                    String strTrim = "";
                    if (DEBUG) {
                        Slog.d(TAG, "Read old record");
                    }
                    while (true) {
                        String line = bufferedReader2.readLine();
                        if (line == null) {
                            bufferedReader2.close();
                            return;
                        }
                        if (DEBUG) {
                            Slog.d(TAG, line);
                        }
                        if (line.startsWith("HasWarmUp :")) {
                            mHasWarmedUp = Boolean.parseBoolean(line.substring(line.indexOf(":") + 1).trim());
                            Slog.d(TAG, "HasWarmUp : " + mHasWarmedUp);
                        } else if (line.startsWith("WarmUpTime :")) {
                            mWarmUpDuration = Long.parseLong(line.substring(line.indexOf(":") + 1).trim());
                            Slog.d(TAG, "StartWarmUpTime : " + mWarmUpDuration);
                        } else if (line.startsWith("PackageName:")) {
                            strTrim = line.substring(line.indexOf(":") + 1).trim();
                            mCurrentMaxDayRecord.put(strTrim, new PackageRecord(strTrim, false, true));
                        } else if (line.startsWith("LastCachedPss:")) {
                            long j = Long.parseLong(line.substring(line.indexOf(":") + 1).trim());
                            mCurrentMaxDayRecord.get(strTrim).setLastCachedPss(j);
                            Slog.d(TAG, strTrim + " last cached pss : " + j);
                        } else {
                            String[] strArrSplit = line.split("\\s+");
                            TimeSlotRecord tr = new TimeSlotRecord(strTrim);
                            tr.startTime = Long.parseLong(strArrSplit[0]);
                            tr.launchCount = Long.parseLong(strArrSplit[1]);
                            tr.totalUsageTime = Long.parseLong(strArrSplit[2]);
                            tr.lruTime = Long.parseLong(strArrSplit[3]);
                            PackageRecord.getTimeSlotRecords(mCurrentMaxDayRecord.get(strTrim)).add(tr);
                            PackageRecord.setTotalLruTime(mCurrentMaxDayRecord.get(strTrim), tr.startTime);
                            if (tr.lruTime != 0) {
                                PackageRecord.setLaunchTime(mCurrentMaxDayRecord.get(strTrim), tr.lruTime);
                            }
                        }
                    }
                } catch (Exception e) {
                    e = e;
                    bufferedReader = bufferedReader2;
                    e.printStackTrace();
                    startCleanData(System.currentTimeMillis());
                    if (bufferedReader != null) {
                        bufferedReader.close();
                    }
                } catch (Throwable th) {
                    th = th;
                    bufferedReader = bufferedReader2;
                    if (bufferedReader != null) {
                        try {
                            bufferedReader.close();
                        } catch (IOException e2) {
                            e2.printStackTrace();
                        }
                    }
                    throw th;
                }
            } catch (Throwable th2) {
            }
        } catch (Exception e3) {
        }
    }

    static double calculateDistance(PackageRecord pr, Point point) {
        return calculateDistance(pr, point);
    }

    private static double calculateDistance(Point point, Point point2) {
        return calculateDistance(point.x, point.y, point.z, point2.x, point2.y, point2.z);
    }

    private void loadRecords() {
        File file = new File(dataDirectory, APP_RECORD_FILE);
        if (!file.exists() || !file.canRead()) {
            file = new File(dataDirectory, APP_RECORD_BACKUP_FILE);
            if (!file.exists() || !file.canRead()) {
                file = null;
            }
        }
        if (file != null) {
            Slog.d(TAG, "Use " + file.getAbsolutePath());
            synchronized (this) {
                parseRecordFile(file);
            }
        }
        mRecordLoaded = true;
    }

    private void updatePackageList() {
        ArrayList<String> arrayList = new ArrayList<>();
        for (LauncherActivityInfo launcherActivityInfo : ((LauncherApps) mContext.getSystemService(LauncherApps.class)).getActivityList(null, Process.myUserHandle())) {
            arrayList.add(launcherActivityInfo.getComponentName().getPackageName());
            if (DEBUG) {
                Slog.d(TAG, "Initial from system ready : " + launcherActivityInfo.getComponentName().getPackageName());
            }
        }
        addInitialPkgs(arrayList);
    }

    private void scheduleAlarm(long j) {
        Slog.d(TAG, "schedule alarm, alarmTime: " + j);
        if (DEBUG) {
            mAlarmManager.setExact(0, j, "AppUsageAlarmListener", mUsageAlarmListener, null);
        } else {
            mAlarmManager.set(1, j, "AppUsageAlarmListener", mUsageAlarmListener, null);
        }
    }

    private ArrayList<Point> clusterizePoints() {
        ArrayList<Point> arrayList = new ArrayList<>();
        ArrayList<Cluster> arrayList2 = mClusters;
        int size = arrayList2.size();
        int i = 0;
        while (i < size) {
            Cluster cluster = arrayList2.get(i);
            i++;
            Point point = cluster.getPoint();
            arrayList.add(new Point(point.x, point.y, point.z));
        }
        return arrayList;
    }

    private HashMap<String, ArrayList<PackageRecord>> rankRecords(boolean needUpdate) {
        int i;
        int i2;
        int i3;
        long jCurrentTimeMillis = System.currentTimeMillis();
        if (needUpdate) {
            rankAllPackages();
            mLastRankingTime = System.currentTimeMillis();
        } else {
            if (!mRecordLoaded || !mSystemReady) {
                return mRecords;
            }
            if (jCurrentTimeMillis - mLastRankingTime <= 1800000) {
                return mRecords;
            }
            Slog.d(TAG, "Need to rank due to duration is over : 1800000");
            rankAllPackages();
            mLastRankingTime = System.currentTimeMillis();
        }
        synchronized (this) {
            ArrayList<PackageRecord> arrayList = new ArrayList<>();
            ArrayList<PackageRecord> arrayList2 = new ArrayList<>();
            ArrayList<PackageRecord> arrayList3 = new ArrayList<>();
            mRecords.clear();
            Collections.sort(mClusters, new ClusterDistanceComparator());
            int i4 = 0;
            while (true) {
                i = mLowUsed;
                if (i4 >= i) {
                    break;
                }
                arrayList3.addAll(mClusters.get(i4).records);
                i4++;
            }
            while (true) {
                i2 = mLowUsed;
                i3 = mGeneralUsed;
                if (i >= i2 + i3) {
                    break;
                }
                arrayList2.addAll(mClusters.get(i).records);
                i++;
            }
            for (int i5 = i2 + i3; i5 < mTotalClusters; i5++) {
                arrayList.addAll(mClusters.get(i5).records);
            }
            if (DEBUG) {
                for (int i6 = 0; i6 < mTotalClusters; i6++) {
                    mClusters.get(i6).serialize();
                }
            }
            int size = arrayList.size() - mMaxGeneralUsedApps;
            if (size > 0) {
                Collections.sort(arrayList, new DistanceComparator());
                for (int i7 = 0; i7 < size; i7++) {
                    int size2 = arrayList.size();
                    if (size2 > 0) {
                        PackageRecord pr = arrayList.get(size2 - 1);
                        if (DEBUG) {
                            Slog.d(TAG, "Choose " + PackageRecord.getPackageName(pr) + " from High to General");
                        }
                        arrayList.remove(pr);
                        arrayList2.add(pr);
                    }
                }
            }
            int size3 = mMaxHighUsedApps - arrayList.size();
            if (size3 > 0) {
                Collections.sort(arrayList2, new DistanceComparator());
                for (int i8 = 0; i8 < size3; i8++) {
                    if (arrayList2.size() > 0) {
                        PackageRecord pr2 = arrayList2.get(0);
                        if (DEBUG) {
                            Slog.d(TAG, "Choose " + PackageRecord.getPackageName(pr2) + " from General to High");
                        }
                        arrayList2.remove(pr2);
                        arrayList.add(pr2);
                    }
                }
            }
            Collections.sort(arrayList, new DistanceComparator());
            Collections.sort(arrayList2, new DistanceComparator());
            Collections.sort(arrayList3, new DistanceComparator());
            mRecords.put(HIGH_USED_PACKAGES_KEY, arrayList);
            if (DEBUG) {
                Slog.d(TAG, "KEY_HIGH_USED_PACKAGES : " + arrayList.size());
            }
            mRecords.put(GENERAL_USED_PACKAGES_KEY, arrayList2);
            if (DEBUG) {
                Slog.d(TAG, "KEY_GENERAL_USED_PACKAGES : " + arrayList2.size());
            }
            mRecords.put(LOW_USED_PACKAGES_KEY, arrayList3);
            if (DEBUG) {
                Slog.d(TAG, "KEY_LOW_USED_PACKAGES : " + arrayList3.size());
            }
        }
        return mRecords;
    }

    private void addPackageToCluster() {
        Iterator<String> it = mCurrentMaxDayRecord.keySet().iterator();
        int i = 0;
        while (it.hasNext()) {
            PackageRecord pr = mCurrentMaxDayRecord.get(it.next());
            double d = Double.MAX_VALUE;
            for (int i2 = 0; i2 < mTotalClusters; i2++) {
                double dCalculateDistance = calculateDistance(pr, mClusters.get(i2).getPoint());
                if (dCalculateDistance < d) {
                    i = i2;
                    d = dCalculateDistance;
                }
            }
            pr.setDistanceFromOrigin(i);
            mClusters.get(i).addPackage(pr);
            if (DEBUG) {
                pr.serialize();
            }
        }
    }

    private boolean isWarmUpComplete() {
        if (!mSystemReady || !mHasWarmedUp && System.currentTimeMillis() - mWarmUpDuration < WARM_UP_DURATION_MS) {
            return false;
        }
        mHasWarmedUp = true;
        return true;
    }

    private void updateClusterPoints() {
        ArrayList<Cluster> arrayList = mClusters;
        int size = arrayList.size();
        int i = 0;
        while (i < size) {
            Cluster cluster = arrayList.get(i);
            int i2 = i + 1;
            Cluster cluster2 = cluster;
            ArrayList<PackageRecord> records = cluster2.getRecords();
            int size2 = records.size();
            int size3 = records.size();
            double d = 0.0d;
            double d2 = 0.0d;
            double d3 = 0.0d;
            int i3 = 0;
            while (i3 < size3) {
                PackageRecord pr = records.get(i3);
                i3++;
                PackageRecord pr2 = pr;
                d += pr2.x;
                d2 += pr2.y;
                d3 += pr2.z;
                i2 = i2;
            }
            int i4 = i2;
            Point point = cluster2.getPoint();
            if (size2 > 0) {
                double d4 = size2;
                point.x = d / d4;
                point.y = d2 / d4;
                point.z = d3 / d4;
            }
            i = i4;
        }
    }

    private void recreateClusters() {
        mPoints.clear();
        mClusters.clear();
        for (int i = 0; i < mTotalClusters; i++) {
            mPoints.add(new Point());
            Cluster cluster = new Cluster(i);
            cluster.setPoint(mPoints.get(i));
            mClusters.add(cluster);
        }
    }

    private void computePkgsDistances() {
        int i = 0;
        boolean z = false;
        int i2 = 0;
        while (!z && 20 > i2) {
            resetClusters();
            ArrayList<Point> arrayListClusterizePoints = clusterizePoints();
            addPackageToCluster();
            updateClusterPoints();
            i2++;
            ArrayList<Point> arrayListClusterizePoints2 = clusterizePoints();
            double dCalculateDistance = 0.0d;
            for (int i3 = 0; i3 < arrayListClusterizePoints.size(); i3++) {
                dCalculateDistance += calculateDistance(arrayListClusterizePoints.get(i3), arrayListClusterizePoints2.get(i3));
            }
            if (DEBUG) {
                ArrayList<Cluster> arrayList = mClusters;
                int size = arrayList.size();
                int i4 = 0;
                while (i4 < size) {
                    Cluster cluster = arrayList.get(i4);
                    i4++;
                    cluster.point.dump();
                }
                Slog.d(TAG, "Iteration: " + i2);
                Slog.d(TAG, "Centroid distances: " + dCalculateDistance);
            }
            if (dCalculateDistance == 0.0d) {
                z = true;
            }
        }
        ArrayList<Cluster> arrayList2 = mClusters;
        int size2 = arrayList2.size();
        while (i < size2) {
            Cluster cluster2 = arrayList2.get(i);
            i++;
            Cluster cluster3 = cluster2;
            cluster3.distance = calculateDistance(cluster3.getPoint(), new Point(0.0d, 0.0d, 0.0d));
        }
    }

    private ArrayList<String> getRecords(ArrayList<PackageRecord> arrayList) {
        ArrayList<String> arrayList2 = new ArrayList<>();
        if (arrayList != null) {
            int size = arrayList.size();
            int i = 0;
            while (i < size) {
                PackageRecord pr = arrayList.get(i);
                i++;
                arrayList2.add(PackageRecord.getPackageName(pr));
            }
        }
        return arrayList2;
    }

    private void initHandlerThread() {
        HandlerThread handlerThread = new HandlerThread(TAG);
        mHandlerThread = handlerThread;
        handlerThread.start();
        mHandler = new AppUsageHandler(mHandlerThread.getLooper());
    }

    public void addNewPackages(String str) {
        synchronized (this) {
            if (mCurrentMaxDayRecord.containsKey(str)) {
                mCurrentMaxDayRecord.remove(str);
            }
            if (DEBUG) {
                Slog.d(TAG, "Adding package : " + str);
            }
            mCurrentMaxDayRecord.put(str, new PackageRecord(str, true, false));
        }
    }

    public void appDied(ProcessRecord processRecord) {
        if (isHighUsedPackages(processRecord.info.packageName)) {
            Slog.d(TAG, processRecord.info.packageName + " is died with pid " + processRecord.mPid);
            Message messageObtainMessage = mHandler.obtainMessage(0);
            Bundle bundle = new Bundle();
            bundle.putString("pkg_name", processRecord.info.packageName);
            bundle.putInt("pid", processRecord.mPid);
            messageObtainMessage.setData(bundle);
            mHandler.sendMessage(messageObtainMessage);
        }
    }

    public void cleanAllData(long j) {
        long jCurrentTimeMillis = System.currentTimeMillis();
        if (DEBUG) {
            Slog.d(TAG, "Old time : " + jCurrentTimeMillis + ", new time : " + j);
        }
        long j2 = j - jCurrentTimeMillis;
        if (Math.abs(j2) >= TIME_CHANGE_THRESHOLD_MS) {
            if (DEBUG) {
                Slog.d(TAG, "Clean all data due to time is changed");
            }
            startCleanData(j);
        } else {
            Slog.d(TAG, "Normal time diff : " + j2);
        }
    }

    public SimpleAppRecord geedHighUsedRecord(boolean z, String str) {
        if (!isWarmUpComplete()) {
            return null;
        }
        ArrayList<PackageRecord> arrayList = rankRecords(z).get(HIGH_USED_PACKAGES_KEY);
        if (arrayList == null) return null;
        int size = arrayList.size();
        int i = 0;
        while (i < size) {
            PackageRecord pr = arrayList.get(i);
            i++;
            PackageRecord pr2 = pr;
            if (TextUtils.equals(PackageRecord.getPackageName(pr2), str)) {
                SimpleAppRecord simpleAppRecord = new SimpleAppRecord();
                simpleAppRecord.mPackageName = pr2.getPkg();
                simpleAppRecord.mLastCachedPss = pr2.getLastCachedPss();
                simpleAppRecord.mLastRemoveTaskTime = pr2.getRemovedTime();
                simpleAppRecord.mLastLmkdTimeTime = pr2.getLmkdKillTime();
                simpleAppRecord.mCurTargetAdj = pr2.getTargetAdj();
                return simpleAppRecord;
            }
        }
        return null;
    }

    public ArrayList<String> getGeneralUsedPackageList(boolean z) {
        return !isWarmUpComplete() ? new ArrayList<>() : getRecords(rankRecords(z).get(GENERAL_USED_PACKAGES_KEY));
    }

    public ArrayList<String> getHighUsedPackageList(boolean z) {
        return !isWarmUpComplete() ? new ArrayList<>() : getRecords(rankRecords(z).get(HIGH_USED_PACKAGES_KEY));
    }

    public ArrayList<SimpleAppRecord> getHighUsedRecords(boolean z) {
        if (!isWarmUpComplete()) {
            return new ArrayList<>();
        }
        ArrayList<PackageRecord> arrayList = rankRecords(z).get(HIGH_USED_PACKAGES_KEY);
        ArrayList<SimpleAppRecord> arrayList2 = new ArrayList<>();
        if (arrayList != null) {
            int size = arrayList.size();
            int i = 0;
            while (i < size) {
                PackageRecord pr = arrayList.get(i);
                i++;
                PackageRecord pr2 = pr;
                SimpleAppRecord simpleAppRecord = new SimpleAppRecord();
                simpleAppRecord.mPackageName = pr2.getPkg();
                simpleAppRecord.mLastCachedPss = pr2.getLastCachedPss();
                simpleAppRecord.mLastRemoveTaskTime = pr2.getRemovedTime();
                simpleAppRecord.mLastLmkdTimeTime = pr2.getLmkdKillTime();
                simpleAppRecord.mCurTargetAdj = pr2.getTargetAdj();
                arrayList2.add(simpleAppRecord);
            }
        }
        return arrayList2;
    }

    public ArrayList<String> getLowUsedPackageList(boolean z) {
        return !isWarmUpComplete() ? new ArrayList<>() : getRecords(rankRecords(z).get(LOW_USED_PACKAGES_KEY));
    }

    public boolean isHighUsedPackages(String str) {
        ArrayList<String> highUsedPackageList;
        if (isWarmUpComplete() && (highUsedPackageList = getHighUsedPackageList(false)) != null) {
            return highUsedPackageList.contains(str);
        }
        return false;
    }

    public void startCleanData(long j) {
        if (DEBUG) {
            Slog.d(TAG, "Start to clean data");
        }
        synchronized (this) {
            Iterator<String> it = mCurrentMaxDayRecord.keySet().iterator();
            while (it.hasNext()) {
                mCurrentMaxDayRecord.get(it.next()).resetUsageData(j);
            }
            misWritingRecord = true;
            mLastRunningPackage = "";
            mHasWarmedUp = false;
            mWarmUpDuration = j;
            mMaxTimeSlots = 0;
            mLastRankingTime = 0L;
            mLastRecordWriteTime = 0L;
            mRecords.clear();
            try {
                File file = new File(dataDirectory, APP_RECORD_FILE);
                if (file.exists()) {
                    file.delete();
                }
                File file2 = new File(dataDirectory, APP_RECORD_BACKUP_FILE);
                if (file2.exists()) {
                    file2.delete();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (DEBUG) {
            Slog.d(TAG, "Finish cleaning data");
        }
        misWritingRecord = false;
    }

    public void initDependencies() {
        recreateClusters();
        loadRecords();
        updatePackageList();
        if (mWarmUpDuration == 0) {
            mWarmUpDuration = System.currentTimeMillis();
        }
        mTargetValues.add(801);
        mTargetValues.add(601);
        mTargetValues.add(401);
    }

    public void writeRecordsToDisk(boolean force) {
        long jCurrentTimeMillis = System.currentTimeMillis();
        if (!force && (misWritingRecord || jCurrentTimeMillis - mLastRecordWriteTime < 1800000)) {
            Slog.d(TAG, "No need to write old record");
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, "Start to write old record");
        }
        misWritingRecord = true;
        new RecordWriterThread("OpRestartProcessManager", jCurrentTimeMillis).start();
    }

    public void addInitialPkgs(ArrayList<String> arrayList) {
        synchronized (this) {
            int size = arrayList.size();
            int i = 0;
            while (i < size) {
                String str = arrayList.get(i);
                i++;
                String initialAllPackage = str;
                if (!mCurrentMaxDayRecord.containsKey(initialAllPackage)) {
                    mCurrentMaxDayRecord.put(initialAllPackage, new PackageRecord(initialAllPackage, false, false));
                }
                if (DEBUG) {
                    Slog.d(TAG, "initialAllPackage : " + initialAllPackage);
                }
            }
        }
        misWritingRecord = false;
    }

    public void removePackage(String str) {
        synchronized (this) {
            if (!TextUtils.equals(mUpdatingPkgName, str)) {
                if (DEBUG) {
                    Slog.d(TAG, "Removing package : " + str);
                }
                mCurrentMaxDayRecord.remove(str);
            } else if (DEBUG) {
                Slog.d(TAG, "Updating package : " + str);
            }
        }
    }

    public void setLastCachedPss(String str, long j) {
        synchronized (this) {
            PackageRecord pr = mCurrentMaxDayRecord.get(str);
            if (pr != null) {
                if (pr.getLastCachedPss() < j) {
                    pr.setLastCachedPss(j);
                }
                if (DEBUG) {
                    Slog.d(TAG, "setLastCachedPss: " + str + " " + j);
                }
            }
        }
    }

    public void setRemoveTaskTime(String str) {
        synchronized (this) {
            PackageRecord pr = mCurrentMaxDayRecord.get(str);
            if (pr != null) {
                pr.setRemoveTime(System.currentTimeMillis());
                if (DEBUG) {
                    Slog.d(TAG, "setRemoveTaskTime: " + str + " " + PackageRecord.getRemoveTime(pr));
                }
            }
        }
    }

    public void setScreenState(boolean screenOff) {
        isScreenOff = screenOff;
        writeRecordsToDisk(false);
    }

    public void setTargetAdj(String pkg, int adj) {
        try {
            synchronized (this) {
                try {
                    Iterator<String> it = mCurrentMaxDayRecord.keySet().iterator();
                    while (it.hasNext()) {
                        PackageRecord pr = mCurrentMaxDayRecord.get(it.next());
                        if (TextUtils.equals(pr.getPkg(), pkg)) {
                            pr.setTargetAdj(adj);
                        }
                    }
                } finally {
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setUpdatingPackage(String str) {
        mUpdatingPkgName = str;
    }

    public void systemReady() {
        if (DEBUG) {
            Slog.d(TAG, "Initial from system ready");
        }
        mContext = NtServiceInjector.getCtx();
        mAlarmManager = (AlarmManager) mContext.getSystemService("alarm");
        long now = System.currentTimeMillis();
        long j = TIME_SLOT_DURATION_MS;
        long j2 = (now - (now % j)) + j;
        mUsageAlarmListener = new AppUsageAlarmListener(j2);
        scheduleAlarm(j2 + j);
        try {
            File file = new File("/data/system/");
            dataDirectory = file;
            file.mkdirs();
        } catch (Exception e) {
            e.printStackTrace();
        }
        initHandlerThread();
        mHandler.post(() -> {
            initDependencies();
        });
        mSystemReady = true;
    }

    public void updateDuration(String str) {
        synchronized (this) {
            PackageRecord pr = mCurrentMaxDayRecord.get(str);
            if (pr == null || !isScreenOff || !pr.canUpdateDuration) {
                if (pr == null) {
                    return;
                }
                pr.onAppStopped(System.currentTimeMillis());
                pr.canUpdateDuration = true;
                return;
            }
            if (DEBUG) {
                Slog.d(TAG, "Screen is off, skip updateDuration : " + str + " " + pr.canUpdateDuration);
            }
        }
    }

    public void updateLaunchTime(String str) {
        if (isScreenOff) {
            if (DEBUG) {
                Slog.d(TAG, "Screen is off, skip updateLaunchTime : " + str);
            }
            return;
        }
        synchronized (this) {
            if (DEBUG) {
                Slog.d(TAG, "Update Total Launch Times :" + str);
            }
            PackageRecord pr = mCurrentMaxDayRecord.get(str);
            if (pr != null) {
                pr.onAppLaunched(System.currentTimeMillis());
                pr.canUpdateDuration = false;
                if (!TextUtils.equals(mLastRunningPackage, str)) {
                    pr.onLaunchCountIncreased(str);
                }
                mLastRunningPackage = str;
                return;
            }
            mLastRunningPackage = str;
            if (DEBUG) {
                Slog.d(TAG, "sLastRunningPackage (null) : " + mLastRunningPackage);
            }
        }
    }

    private static final class Point {
        public double y;
        public double z;
        public double x;

        public Point() {
            x = 0.0d;
            y = 0.0d;
            z = 0.0d;
        }

        public void dump() {
            Slog.d(TAG, "Point X : " + x + ", Y : " + y + ", Z : " + z);
        }

        public Point(double d, double d2, double d3) {
            x = d;
            y = d2;
            z = d3;
        }
    }
}
