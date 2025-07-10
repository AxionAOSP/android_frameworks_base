/*
 * Copyright (C) 2025 AxionOS Project
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
package com.android.server.wm;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;

import java.util.*;

public class AppLockSettingsRepository {
    public interface OnSettingsChangedListener {
        void onSettingsChanged(Set<String> lockedApps, int lockType);
    }

    private static final String SETTING_LOCK_TYPE = "nothing_applocker_locktype";
    private static final String SETTING_PRIVATE_PASSWORD = "nothing_applocker_use_private_password";
    private static final String SETTING_LOCKED_APPS = "nt_locked_apps";

    private final Context mContext;
    private final ContentResolver mResolver;
    private final OnSettingsChangedListener mListener;

    private final LockSettingsObserver mObserver;

    public AppLockSettingsRepository(Context context, Handler handler, OnSettingsChangedListener listener) {
        mContext = context;
        mResolver = context.getContentResolver();
        mListener = listener;
        mObserver = new LockSettingsObserver(handler);
        registerObserver();
        notifySettingsChanged();
    }

    private void registerObserver() {
        mResolver.registerContentObserver(Settings.Secure.getUriFor(SETTING_LOCK_TYPE), false, mObserver, UserHandle.USER_ALL);
        mResolver.registerContentObserver(Settings.Secure.getUriFor(SETTING_PRIVATE_PASSWORD), false, mObserver, UserHandle.USER_ALL);
        mResolver.registerContentObserver(Settings.Secure.getUriFor(SETTING_LOCKED_APPS), false, mObserver, UserHandle.USER_ALL);
    }

    private void notifySettingsChanged() {
        int lockType = Settings.Secure.getIntForUser(
                mResolver,
                SETTING_LOCK_TYPE,
                0,
                UserHandle.USER_CURRENT);

        Set<String> lockedApps = new HashSet<>(getLockedApps());

        if (mListener != null) {
            mListener.onSettingsChanged(lockedApps, lockType);
        }
    }

    private List<String> getLockedApps() {
        String lockedAppsStr = Settings.Secure.getStringForUser(
                mResolver,
                SETTING_LOCKED_APPS,
                UserHandle.USER_CURRENT);

        if (lockedAppsStr == null || lockedAppsStr.isEmpty()) return Collections.emptyList();

        String[] locked = lockedAppsStr.split(",");
        List<String> list = new ArrayList<>();
        for (String pkg : locked) {
            if (!pkg.trim().isEmpty()) list.add(pkg.trim());
        }
        return list;
    }

    private class LockSettingsObserver extends ContentObserver {
        LockSettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            notifySettingsChanged();
        }
    }
}
