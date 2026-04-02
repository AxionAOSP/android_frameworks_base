/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.tuner;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.DropDownPreference;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import lineageos.providers.LineageSettings;

public class BatteryPreference extends DropDownPreference {

    private static final String HIDDEN = "0";
    private static final String INSIDE = "1";
    private static final String NEXT_TO = "2";

    private boolean mHasSetValue;

    public BatteryPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEntryValues(new CharSequence[] {HIDDEN, INSIDE, NEXT_TO});
    }

    @Override
    public void onAttached() {
        super.onAttached();
        if (!mHasSetValue) {
            mHasSetValue = true;
            int current = LineageSettings.System.getInt(
                    getContext().getContentResolver(),
                    LineageSettings.System.STATUS_BAR_SHOW_BATTERY_PERCENT, 0);
            setValue(String.valueOf(current));
        }
    }

    @Override
    protected boolean persistString(String value) {
        int intValue = Integer.parseInt(value);
        MetricsLogger.action(getContext(), MetricsEvent.TUNER_BATTERY_PERCENTAGE, intValue > 0);
        LineageSettings.System.putInt(getContext().getContentResolver(),
                LineageSettings.System.STATUS_BAR_SHOW_BATTERY_PERCENT, intValue);
        return true;
    }
}
