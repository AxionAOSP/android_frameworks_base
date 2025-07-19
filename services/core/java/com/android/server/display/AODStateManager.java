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

import android.app.AlarmManager;
import android.content.*;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Pair;
import android.util.Slog;

import java.util.Calendar;

public class AODStateManager {

    private static final String TAG = "AODStateManager";
    private static final String PULSE_ACTION = "com.android.systemui.doze.pulse";

    public static final int AOD_STATUS_UNDEFINED = -1;
    public static final int AOD_STATUS_DISABLED = 0;
    public static final int AOD_STATUS_SCHEDULE = 1 << 1;
    public static final int AOD_STATUS_CHARGE = 1 << 2;
    
    public static int AOD_OWNER_NONE = -1;
    public static int AOD_OWNER_USER = 0;
    public static int AOD_OWNER_SERVICE = 1;

    private final Context mContext;
    private final AlarmManager mAlarmManager;
    private final PowerManager mPowerManager;

    private boolean mReceiverRegistered = false;
    private boolean mAlarmScheduled = false;
    private int mAODStatus = AOD_STATUS_UNDEFINED;

    private final Alarm mEnableAlarm = new Alarm(true);
    private final Alarm mDisableAlarm = new Alarm(false);
    
    private int mAodOwner = AOD_OWNER_NONE;

    public AODStateManager(Context context) {
        mContext = context;
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    }

    public void start() {
        if (mReceiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        mContext.registerReceiver(mAODReceiver, filter);
        mReceiverRegistered = true;
    }

    public void stop() {
        if (mReceiverRegistered) {
            mContext.unregisterReceiver(mAODReceiver);
            mReceiverRegistered = false;
        }
    }

    public void updateAODStatus() {
        boolean scheduleActive = AODSettingsRepository.isScheduleAODEnabled(mContext) &&
                                 AODSettingsRepository.isWithinSchedule(mContext);

        Intent battery = mContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        boolean chargeActive = battery != null &&
                               AODSettingsRepository.isChargeAODEnabled(mContext) &&
                               AODSettingsRepository.isChargingOrPlugged(battery);

        int newStatus = AOD_STATUS_DISABLED;
        if (scheduleActive) newStatus |= AOD_STATUS_SCHEDULE;
        if (chargeActive) newStatus |= AOD_STATUS_CHARGE;

        if (newStatus == mAODStatus) {
            Slog.v(TAG, "AOD triggers unchanged; skipping update");
            return;
        }

        mAODStatus = newStatus;

        boolean enable = mAODStatus != AOD_STATUS_DISABLED 
            && mAODStatus != AOD_STATUS_UNDEFINED;
        boolean currentlyEnabled = AODSettingsRepository.isAODEnabled(mContext);

        if (enable && !currentlyEnabled) {
            AODSettingsRepository.setAODEnabled(mContext, true);
            runWithWakeLock("AodStateChanged", () -> {
                sendDozePulse();
            });
            Slog.v(TAG, "AOD enabled");
        } else if (!enable && currentlyEnabled) {
            if (mAodOwner == AOD_OWNER_USER) {
                Slog.v(TAG, "AOD disable request ignored due to user ownership");
                return;
            }
            AODSettingsRepository.setAODEnabled(mContext, false);
            runWithWakeLock("AodStateChanged", () -> {
                sendDozePulse();
            });
            Slog.v(TAG, "AOD disabled");
        } else {
            Slog.v(TAG, "AOD state already " + (enable ? "enabled" : "disabled") + "; skipping");
        }
    }

    public void setupScheduleAlarms() {
        Pair<Calendar, Calendar> range = AODSettingsRepository.getScheduleRange(mContext);
        if (range == null) {
            Slog.w(TAG, "Invalid schedule range");
            return;
        }
        if (mAlarmScheduled) {
            cancelAlarms();
        }
        Calendar now = Calendar.getInstance();
        Calendar start = range.first;
        Calendar end = range.second;
        if (end.before(now)) {
            start.add(Calendar.DAY_OF_YEAR, 1);
            end.add(Calendar.DAY_OF_YEAR, 1);
        }
        mAlarmManager.setExact(AlarmManager.RTC_WAKEUP, start.getTimeInMillis(), TAG + "_enable", mEnableAlarm, null);
        mAlarmManager.setExact(AlarmManager.RTC_WAKEUP, end.getTimeInMillis(), TAG + "_disable", mDisableAlarm, null);
        mAlarmScheduled = true;
        Slog.v(TAG, "Scheduled AOD ON at " + AODSettingsRepository.formatTime(start));
        Slog.v(TAG, "Scheduled AOD OFF at " + AODSettingsRepository.formatTime(end));
    }

    private void cancelAlarms() {
        if (!mAlarmScheduled) return;
        mAlarmManager.cancel(mEnableAlarm);
        mAlarmManager.cancel(mDisableAlarm);
        mAlarmScheduled = false;
    }

    private void onScheduleChange(boolean enable) {
        if (!enable) {
            updateAODStatus();
            setupScheduleAlarms();
            return;
        }
        if (!mPowerManager.isInteractive()) {
            runWithWakeLock("ScheduleWake", () -> {
                sendDozePulse();
                updateAODStatus();
            });
        } else {
            updateAODStatus();
        }
    }

    private final BroadcastReceiver mAODReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case Intent.ACTION_BATTERY_CHANGED:
                case Intent.ACTION_POWER_CONNECTED:
                case Intent.ACTION_POWER_DISCONNECTED:
                    updateAODStatus();
                    break;
            }
        }
    };

    private void sendDozePulse() {
        if (!mPowerManager.isInteractive()) {
            Intent pulseIntent = new Intent(PULSE_ACTION);
            pulseIntent.setPackage("com.android.systemui");
            mContext.sendBroadcastAsUser(pulseIntent, UserHandle.CURRENT);
        }
    }
    
    public void setAodOwner(int owner) {
        mAodOwner = owner;
    }
    
    public void onSettingsChanged(boolean scheduleChanged) {
        if (scheduleChanged) {
            boolean scheduleAodEnabled =
                 AODSettingsRepository.isScheduleAODEnabled(mContext);
            if (scheduleAodEnabled) {
                setupScheduleAlarms();
            } else {
                cancelAlarms();
            }
        }
        updateAODStatus();
    }

    private class Alarm implements AlarmManager.OnAlarmListener {
        private final boolean mEnable;
        public Alarm(boolean enable) {
            mEnable = enable;
        }
        @Override
        public void onAlarm() {
            onScheduleChange(mEnable);
        }
    }
    
    private void runWithWakeLock(String tagSuffix, Runnable action) {
        PowerManager.WakeLock wakeLock = 
            mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":" + tagSuffix);
        wakeLock.acquire(3000);
        try {
            action.run();
        } finally {
            wakeLock.release();
        }
    }
}
