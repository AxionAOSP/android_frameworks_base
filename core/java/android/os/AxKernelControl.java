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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Objects;

/** @hide */
public final class AxKernelControl implements Parcelable {
    public static final int TYPE_CPU_MIN_FREQ = 1;
    public static final int TYPE_CPU_MAX_FREQ = 2;
    public static final int TYPE_GPU_MIN_FREQ = 3;
    public static final int TYPE_GPU_MAX_FREQ = 4;
    public static final int TYPE_CPU_GOVERNOR = 5;

    @IntDef(prefix = {"TYPE_"}, value = {
            TYPE_CPU_MIN_FREQ,
            TYPE_CPU_MAX_FREQ,
            TYPE_GPU_MIN_FREQ,
            TYPE_GPU_MAX_FREQ,
            TYPE_CPU_GOVERNOR,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ControlType {}

    private final String mId;
    private final String mGroup;
    @ControlType
    private final int mType;
    private final int mCurrentValue;
    private final int mDefaultValue;
    private final int[] mAvailableValues;
    private final String[] mValueLabels;

    public AxKernelControl(@NonNull String id, @NonNull String group, @ControlType int type,
            int currentValue, int defaultValue, @Nullable int[] availableValues) {
        this(id, group, type, currentValue, defaultValue, availableValues, null);
    }

    public AxKernelControl(@NonNull String id, @NonNull String group, @ControlType int type,
            int currentValue, int defaultValue, @Nullable int[] availableValues,
            @Nullable String[] valueLabels) {
        mId = Objects.requireNonNull(id);
        mGroup = Objects.requireNonNull(group);
        mType = type;
        mCurrentValue = currentValue;
        mDefaultValue = defaultValue;
        mAvailableValues = availableValues != null ? availableValues.clone() : new int[0];
        mValueLabels = valueLabels != null ? valueLabels.clone() : new String[0];
    }

    private AxKernelControl(@NonNull Parcel in) {
        mId = Objects.requireNonNull(in.readString8());
        mGroup = Objects.requireNonNull(in.readString8());
        mType = in.readInt();
        mCurrentValue = in.readInt();
        mDefaultValue = in.readInt();
        int[] values = in.createIntArray();
        mAvailableValues = values != null ? values : new int[0];
        String[] labels = in.createString8Array();
        mValueLabels = labels != null ? labels : new String[0];
    }

    @NonNull
    public String getId() {
        return mId;
    }

    @NonNull
    public String getGroup() {
        return mGroup;
    }

    @ControlType
    public int getType() {
        return mType;
    }

    public int getCurrentValue() {
        return mCurrentValue;
    }

    public int getDefaultValue() {
        return mDefaultValue;
    }

    @NonNull
    public int[] getAvailableValues() {
        return mAvailableValues.clone();
    }

    @NonNull
    public String[] getValueLabels() {
        return mValueLabels.clone();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mId);
        dest.writeString8(mGroup);
        dest.writeInt(mType);
        dest.writeInt(mCurrentValue);
        dest.writeInt(mDefaultValue);
        dest.writeIntArray(mAvailableValues);
        dest.writeString8Array(mValueLabels);
    }

    @Override
    public String toString() {
        return "AxKernelControl{id=" + mId
                + ", group=" + mGroup
                + ", type=" + mType
                + ", current=" + mCurrentValue
                + ", default=" + mDefaultValue
                + ", values=" + Arrays.toString(mAvailableValues)
                + ", labels=" + Arrays.toString(mValueLabels)
                + '}';
    }

    public static final @NonNull Creator<AxKernelControl> CREATOR =
            new Creator<AxKernelControl>() {
                @Override
                public AxKernelControl createFromParcel(Parcel in) {
                    return new AxKernelControl(in);
                }

                @Override
                public AxKernelControl[] newArray(int size) {
                    return new AxKernelControl[size];
                }
            };
}
