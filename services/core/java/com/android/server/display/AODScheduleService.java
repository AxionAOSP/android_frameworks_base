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

import static com.android.server.display.AODSettingsRepository.KEY_SCHEDULE_TIME;
import static com.android.server.display.AODSettingsRepository.KEY_SCHEDULE_TIME_ENABLED;
import static com.android.server.display.AODStateManager.AOD_OWNER_NONE;
import static com.android.server.display.AODStateManager.AOD_OWNER_SERVICE;
import static com.android.server.display.AODStateManager.AOD_OWNER_USER;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.Settings;
import android.util.Slog;

import com.android.server.SystemService;

public class AODScheduleService extends SystemService {
    private static final String TAG = "AODScheduleService";

    private final Context mContext;
    private final AODStateManager mStateManager;
    private final SettingsObserver mSettingsObserver;
    private boolean mListening = false;

    public AODScheduleService(Context context) {
        super(context);
        mContext = context;
        mStateManager = new AODStateManager(context);
        mSettingsObserver = new SettingsObserver();
    }

    @Override
    public void onStart() {
        Slog.v(TAG, "AODScheduleService starting");
        mSettingsObserver.register(mContext.getContentResolver());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            for (Uri uri : AODSettingsRepository.getMonitoredUris()) {
                mSettingsObserver.onChange(false, uri);
            }
        }
    }

    private final class SettingsObserver extends android.database.ContentObserver {
        SettingsObserver() {
            super(null);
        }

        void register(ContentResolver resolver) {
            for (Uri uri : AODSettingsRepository.getMonitoredUris()) {
                resolver.registerContentObserver(uri, false, this);
            }
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            boolean chargeEnabled = AODSettingsRepository.isChargeAODEnabled(mContext);
            boolean featuresActive = AODSettingsRepository.isAodFeaturesActive(mContext);
            boolean isScheduleUri = uri.equals(Settings.Secure.getUriFor(KEY_SCHEDULE_TIME)) 
                || uri.equals(Settings.Secure.getUriFor(KEY_SCHEDULE_TIME_ENABLED));
            boolean enabledByUser = AODSettingsRepository.isDozeEnabledByUser(mContext);

            int aodOwner;
            if (enabledByUser) {
                aodOwner = AOD_OWNER_USER;
            } else if (featuresActive) {
                aodOwner = AOD_OWNER_SERVICE;
            } else {
                aodOwner = AOD_OWNER_NONE;
            }

            mStateManager.setAodOwner(aodOwner);

            if (chargeEnabled && !mListening) {
                mStateManager.start();
                mListening = true;
            } else if (!chargeEnabled && mListening) {
                mStateManager.stop();
                mListening = false;
            }

            mStateManager.onSettingsChanged(isScheduleUri);
        }
    }
}
