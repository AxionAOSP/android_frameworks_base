/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.systemui.doze;

import android.app.AlarmManager;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Log;

import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.dagger.DozeScope;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.AlarmTimeout;
import com.android.systemui.util.settings.SecureSettings;

import javax.inject.Inject;

@DozeScope
public class AodDurationController implements DozeMachine.Part {
    private static final String TAG = "AodDurationController";

    public static final String SCREEN_OFF_AOD_DURATION = "screen_off_aod_duration";
    public static final int DURATION_OFF = 0;

    private final AlarmTimeout mTimeout;
    private final SecureSettings mSecureSettings;
    private final AodScheduleController mAodScheduleController;
    private final AmbientDisplayConfiguration mAmbientDisplayConfig;
    private final UserTracker mUserTracker;
    private DozeMachine mMachine;
    private int mDurationSeconds = DURATION_OFF;

    @Inject
    public AodDurationController(@Main Handler handler, AlarmManager alarmManager,
            SecureSettings secureSettings, AodScheduleController aodScheduleController,
            AmbientDisplayConfiguration ambientDisplayConfig, UserTracker userTracker) {
        mTimeout = new AlarmTimeout(alarmManager, this::onTimeout, TAG, handler);
        mSecureSettings = secureSettings;
        mAodScheduleController = aodScheduleController;
        mAmbientDisplayConfig = ambientDisplayConfig;
        mUserTracker = userTracker;
    }

    @Override
    public void setDozeMachine(DozeMachine dozeMachine) {
        mMachine = dozeMachine;
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        mDurationSeconds = mSecureSettings.getIntForUser(
                SCREEN_OFF_AOD_DURATION, DURATION_OFF, UserHandle.USER_CURRENT);

        switch (newState) {
            case DOZE_AOD:
            case DOZE_AOD_DOCKED:
            case DOZE_AOD_MINMODE:
                if (isEnabled() && isSoleReasonForAod()) {
                    scheduleTimeout();
                } else {
                    mTimeout.cancel();
                }
                break;
            default:
                mTimeout.cancel();
                break;
        }
    }

    boolean isEnabled() {
        return mDurationSeconds > DURATION_OFF;
    }

    private boolean isSoleReasonForAod() {
        if (mAodScheduleController.isScheduleMode()
                && mAodScheduleController.shouldShowAod()) {
            return false;
        }
        if (mAmbientDisplayConfig.alwaysOnEnabled(mUserTracker.getUserId())) {
            return false;
        }
        return true;
    }

    private void scheduleTimeout() {
        long durationMs = mDurationSeconds * 1000L;
        mTimeout.schedule(durationMs, AlarmTimeout.MODE_RESCHEDULE_IF_SCHEDULED);
    }

    private void onTimeout() {
        if (mMachine != null && !mMachine.isUninitializedOrFinished()) {
            Log.d(TAG, "AOD duration timeout reached, transitioning to DOZE");
            mMachine.requestState(DozeMachine.State.DOZE);
        }
    }
}