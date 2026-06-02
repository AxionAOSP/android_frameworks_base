/*
 * Copyright (C) 2026 AxionOS
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

import android.os.SystemClock;
import android.util.SparseArray;

import java.io.PrintWriter;

final class PulseEngineEnergyLedger {
    private static final int MAX_UID_STATES = 512;

    private final Object mLock = new Object();
    private final SparseArray<UidEnergy> mUidEnergy = new SparseArray<>();

    private UidEnergy mLastSample;

    void onUidBatteryUsageSample(int uid, double backgroundMah, double cachedMah,
            double backgroundPercent, double cachedPercent, long now) {
        synchronized (mLock) {
            UidEnergy energy = mUidEnergy.get(uid);
            if (energy == null) {
                energy = new UidEnergy(uid);
                mUidEnergy.put(uid, energy);
            }
            energy.mBackgroundMah = Math.max(0.0d, backgroundMah);
            energy.mCachedMah = Math.max(0.0d, cachedMah);
            energy.mBackgroundPercent = Math.max(0.0d, backgroundPercent);
            energy.mCachedPercent = Math.max(0.0d, cachedPercent);
            energy.mUpdatedElapsedMillis = now;
            mLastSample = energy;
            pruneLocked(now);
        }
    }

    int getUidEnergyScore(PulseEngineConfig config, int uid) {
        final long elapsedNow = SystemClock.elapsedRealtime();
        synchronized (mLock) {
            final UidEnergy energy = mUidEnergy.get(uid);
            if (energy == null || elapsedNow < energy.mUpdatedElapsedMillis
                    || elapsedNow - energy.mUpdatedElapsedMillis > config.mEnergySampleMaxAgeMs) {
                return 0;
            }
            return energy.score(config);
        }
    }

    void dump(PrintWriter pw) {
        pw.println("  energyLedger:");
        synchronized (mLock) {
            pw.println("    trackedUidCount=" + mUidEnergy.size());
            if (mLastSample != null) {
                mLastSample.dump(pw);
            }
        }
    }

    private void pruneLocked(long now) {
        if (mUidEnergy.size() <= MAX_UID_STATES) {
            return;
        }
        int oldestIndex = -1;
        long oldest = Long.MAX_VALUE;
        for (int i = 0; i < mUidEnergy.size(); i++) {
            final UidEnergy energy = mUidEnergy.valueAt(i);
            if (energy.mUpdatedElapsedMillis < oldest) {
                oldest = energy.mUpdatedElapsedMillis;
                oldestIndex = i;
            }
        }
        if (oldestIndex >= 0 && now >= oldest) {
            mUidEnergy.removeAt(oldestIndex);
        }
    }

    private static final class UidEnergy {
        final int mUid;
        double mBackgroundMah;
        double mCachedMah;
        double mBackgroundPercent;
        double mCachedPercent;
        long mUpdatedElapsedMillis;

        UidEnergy(int uid) {
            mUid = uid;
        }

        int score(PulseEngineConfig config) {
            final double percent = mBackgroundPercent + mCachedPercent;
            final int milliMah = (int) Math.round((mBackgroundMah + mCachedMah) * 1000.0d);
            int score = 0;
            if (percent >= config.mEnergyHighBackgroundPercent) {
                score += Math.min(45, (int) (percent * 6.0d));
            }
            if (milliMah >= config.mEnergyHighBackgroundMahMilli) {
                score += Math.min(45, milliMah * 20 / config.mEnergyHighBackgroundMahMilli);
            }
            return Math.min(score, 90);
        }

        void dump(PrintWriter pw) {
            pw.println("    lastUid=" + mUid);
            pw.println("    lastBackgroundMah=" + mBackgroundMah);
            pw.println("    lastCachedMah=" + mCachedMah);
            pw.println("    lastBackgroundPercent=" + mBackgroundPercent);
            pw.println("    lastCachedPercent=" + mCachedPercent);
            pw.println("    lastUpdatedElapsedMillis=" + mUpdatedElapsedMillis);
        }
    }
}
