/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package android.os;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** @hide */
public final class AxKernelMetrics implements Parcelable {
    public static final float USAGE_UNAVAILABLE = -1.0f;
    public static final long FREQUENCY_UNAVAILABLE_HZ = -1L;
    public static final long CPU_TIME_UNAVAILABLE_TICKS = -1L;

    private final long mTimestampElapsedRealtimeMillis;
    private final float mCpuUsagePercent;
    private final long mTotalCpuActiveTimeTicks;
    private final long mTotalCpuTimeTicks;
    private final List<CpuCluster> mCpuClusters;
    private final Gpu mGpu;

    public AxKernelMetrics(
            long timestampElapsedRealtimeMillis,
            float cpuUsagePercent,
            long totalCpuActiveTimeTicks,
            long totalCpuTimeTicks,
            @NonNull List<CpuCluster> cpuClusters,
            @Nullable Gpu gpu) {
        mTimestampElapsedRealtimeMillis = timestampElapsedRealtimeMillis;
        mCpuUsagePercent = cpuUsagePercent;
        mTotalCpuActiveTimeTicks = totalCpuActiveTimeTicks;
        mTotalCpuTimeTicks = totalCpuTimeTicks;
        mCpuClusters =
                Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(cpuClusters)));
        mGpu = gpu;
    }

    private AxKernelMetrics(@NonNull Parcel in) {
        mTimestampElapsedRealtimeMillis = in.readLong();
        mCpuUsagePercent = in.readFloat();
        mTotalCpuActiveTimeTicks = in.readLong();
        mTotalCpuTimeTicks = in.readLong();
        ArrayList<CpuCluster> cpuClusters = in.createTypedArrayList(CpuCluster.CREATOR);
        mCpuClusters =
                cpuClusters != null
                        ? Collections.unmodifiableList(cpuClusters)
                        : Collections.emptyList();
        mGpu = in.readTypedObject(Gpu.CREATOR);
    }

    public long getTimestampElapsedRealtimeMillis() {
        return mTimestampElapsedRealtimeMillis;
    }

    public float getCpuUsagePercent() {
        return mCpuUsagePercent;
    }

    public long getTotalCpuActiveTimeTicks() {
        return mTotalCpuActiveTimeTicks;
    }

    public long getTotalCpuTimeTicks() {
        return mTotalCpuTimeTicks;
    }

    @NonNull
    public List<CpuCluster> getCpuClusters() {
        return mCpuClusters;
    }

    @Nullable
    public Gpu getGpu() {
        return mGpu;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mTimestampElapsedRealtimeMillis);
        dest.writeFloat(mCpuUsagePercent);
        dest.writeLong(mTotalCpuActiveTimeTicks);
        dest.writeLong(mTotalCpuTimeTicks);
        dest.writeTypedList(mCpuClusters, flags);
        dest.writeTypedObject(mGpu, flags);
    }

    public static final @NonNull Creator<AxKernelMetrics> CREATOR =
            new Creator<AxKernelMetrics>() {
                @Override
                public AxKernelMetrics createFromParcel(Parcel in) {
                    return new AxKernelMetrics(in);
                }

                @Override
                public AxKernelMetrics[] newArray(int size) {
                    return new AxKernelMetrics[size];
                }
            };

    public static final class CpuCluster implements Parcelable {
        private final String mId;
        private final String mGroup;
        private final int[] mCpuIds;
        private final long mActiveTimeTicks;
        private final long mTotalTimeTicks;
        private final long mCurrentFrequencyHz;
        private final long mMinFrequencyHz;
        private final long mMaxFrequencyHz;

        public CpuCluster(
                @NonNull String id,
                @NonNull String group,
                @NonNull int[] cpuIds,
                long activeTimeTicks,
                long totalTimeTicks,
                long currentFrequencyHz,
                long minFrequencyHz,
                long maxFrequencyHz) {
            mId = Objects.requireNonNull(id);
            mGroup = Objects.requireNonNull(group);
            mCpuIds = Objects.requireNonNull(cpuIds).clone();
            mActiveTimeTicks = activeTimeTicks;
            mTotalTimeTicks = totalTimeTicks;
            mCurrentFrequencyHz = currentFrequencyHz;
            mMinFrequencyHz = minFrequencyHz;
            mMaxFrequencyHz = maxFrequencyHz;
        }

        private CpuCluster(@NonNull Parcel in) {
            mId = Objects.requireNonNull(in.readString8());
            mGroup = Objects.requireNonNull(in.readString8());
            int[] cpuIds = in.createIntArray();
            mCpuIds = cpuIds != null ? cpuIds : new int[0];
            mActiveTimeTicks = in.readLong();
            mTotalTimeTicks = in.readLong();
            mCurrentFrequencyHz = in.readLong();
            mMinFrequencyHz = in.readLong();
            mMaxFrequencyHz = in.readLong();
        }

        @NonNull
        public String getId() {
            return mId;
        }

        @NonNull
        public String getGroup() {
            return mGroup;
        }

        @NonNull
        public int[] getCpuIds() {
            return mCpuIds.clone();
        }

        public long getActiveTimeTicks() {
            return mActiveTimeTicks;
        }

        public long getTotalTimeTicks() {
            return mTotalTimeTicks;
        }

        public long getCurrentFrequencyHz() {
            return mCurrentFrequencyHz;
        }

        public long getMinFrequencyHz() {
            return mMinFrequencyHz;
        }

        public long getMaxFrequencyHz() {
            return mMaxFrequencyHz;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeString8(mId);
            dest.writeString8(mGroup);
            dest.writeIntArray(mCpuIds);
            dest.writeLong(mActiveTimeTicks);
            dest.writeLong(mTotalTimeTicks);
            dest.writeLong(mCurrentFrequencyHz);
            dest.writeLong(mMinFrequencyHz);
            dest.writeLong(mMaxFrequencyHz);
        }

        public static final @NonNull Creator<CpuCluster> CREATOR =
                new Creator<CpuCluster>() {
                    @Override
                    public CpuCluster createFromParcel(Parcel in) {
                        return new CpuCluster(in);
                    }

                    @Override
                    public CpuCluster[] newArray(int size) {
                        return new CpuCluster[size];
                    }
                };
    }

    public static final class Gpu implements Parcelable {
        private final float mUsagePercent;
        private final long mCurrentFrequencyHz;
        private final long mMinFrequencyHz;
        private final long mMaxFrequencyHz;

        public Gpu(
                float usagePercent,
                long currentFrequencyHz,
                long minFrequencyHz,
                long maxFrequencyHz) {
            mUsagePercent = usagePercent;
            mCurrentFrequencyHz = currentFrequencyHz;
            mMinFrequencyHz = minFrequencyHz;
            mMaxFrequencyHz = maxFrequencyHz;
        }

        private Gpu(@NonNull Parcel in) {
            mUsagePercent = in.readFloat();
            mCurrentFrequencyHz = in.readLong();
            mMinFrequencyHz = in.readLong();
            mMaxFrequencyHz = in.readLong();
        }

        public float getUsagePercent() {
            return mUsagePercent;
        }

        public long getCurrentFrequencyHz() {
            return mCurrentFrequencyHz;
        }

        public long getMinFrequencyHz() {
            return mMinFrequencyHz;
        }

        public long getMaxFrequencyHz() {
            return mMaxFrequencyHz;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeFloat(mUsagePercent);
            dest.writeLong(mCurrentFrequencyHz);
            dest.writeLong(mMinFrequencyHz);
            dest.writeLong(mMaxFrequencyHz);
        }

        public static final @NonNull Creator<Gpu> CREATOR =
                new Creator<Gpu>() {
                    @Override
                    public Gpu createFromParcel(Parcel in) {
                        return new Gpu(in);
                    }

                    @Override
                    public Gpu[] newArray(int size) {
                        return new Gpu[size];
                    }
                };
    }
}
