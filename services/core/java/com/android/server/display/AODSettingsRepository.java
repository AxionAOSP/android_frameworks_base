/*
 * Copyright (C) 2025 AxionOS Project
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
package com.android.server.display;

import android.content.*;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Pair;

import java.util.Calendar;
import java.util.Locale;

import lineageos.providers.LineageSettings;

public class AODSettingsRepository {
    private static final String KEY_DOZE = Settings.Secure.DOZE_ALWAYS_ON;
    public static final String KEY_CHARGE_ENABLED = "aod_on_charge_enabled";
    public static final String KEY_SCHEDULE_TIME = "aod_schedule_time";
    public static final String KEY_SCHEDULE_TIME_ENABLED = "aod_schedule_time_enabled";
    public static final String KEY_DOZE_ENABLED_BY_USER = "always_on_enabled_by_user";

    public static boolean isAODEnabled(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(), KEY_DOZE, 0) != 0;
    }

    public static boolean isDozeEnabledByUser(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(), KEY_DOZE_ENABLED_BY_USER, 0) != 0;
    }

    public static void setAODEnabled(Context context, boolean enabled) {
        Settings.Secure.putInt(context.getContentResolver(), KEY_DOZE, enabled ? 1 : 0);
    }

    public static boolean isChargeAODEnabled(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(), KEY_CHARGE_ENABLED, 0) != 0;
    }

    public static boolean isScheduleAODEnabled(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(), KEY_SCHEDULE_TIME_ENABLED, 0) != 0;
    }

    public static boolean isAodFeaturesActive(Context context) {
        return isChargeAODEnabled(context) || isWithinSchedule(context);
    }

    public static boolean isChargingOrPlugged(Intent intent) {
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int plug = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);

        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL;

        boolean plugged = plug == BatteryManager.BATTERY_PLUGGED_AC ||
                          plug == BatteryManager.BATTERY_PLUGGED_USB ||
                          plug == BatteryManager.BATTERY_PLUGGED_WIRELESS ||
                          plug == BatteryManager.BATTERY_PLUGGED_DOCK;

        return charging || plugged;
    }

    public static Pair<Calendar, Calendar> getScheduleRange(Context context) {
        String schedule = Settings.Secure.getString(context.getContentResolver(), KEY_SCHEDULE_TIME);
        if (schedule == null || !schedule.contains("-")) return null;
        try {
            String[] parts = schedule.split("-");
            Calendar start = parseTime(parts[0]);
            Calendar end = parseTime(parts[1]);
            if (end.before(start)) end.add(Calendar.DAY_OF_YEAR, 1);
            return new Pair<>(start, end);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isWithinSchedule(Context context) {
        if (!isScheduleAODEnabled(context)) return false;
        Pair<Calendar, Calendar> range = getScheduleRange(context);
        if (range == null) return false;
        Calendar now = Calendar.getInstance();
        return now.after(range.first) && now.before(range.second);
    }

    public static Calendar parseTime(String timeStr) {
        String[] parts = timeStr.split(":");
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(parts[0]));
        cal.set(Calendar.MINUTE, Integer.parseInt(parts[1]));
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal;
    }

    public static String formatTime(Calendar cal) {
        return String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
    }

    public static Uri[] getMonitoredUris() {
        return new Uri[] {
            Settings.Secure.getUriFor(KEY_SCHEDULE_TIME),
            Settings.Secure.getUriFor(KEY_CHARGE_ENABLED),
            Settings.Secure.getUriFor(KEY_DOZE_ENABLED_BY_USER)
        };
    }
}
