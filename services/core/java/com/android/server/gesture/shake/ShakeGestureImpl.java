/*
 * Copyright (C) 2023-2024 The RisingOS Android Project
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
package com.android.server.gesture.shake;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.provider.Settings;

import org.rising.server.ShakeGestureUtils;

import com.android.server.NtServiceInjector;

public final class ShakeGestureImpl {

    public interface Callbacks {
        void onShake();
    }

    private static final Uri SHAKE_GESTURES_ENABLED_URI =
            Settings.Secure.getUriFor("shake_gestures_enabled");
    private static final Uri SHAKE_GESTURES_ACTION_URI =
            Settings.Secure.getUriFor("shake_gestures_action");

    private final Context mContext;
    private final Handler mHandler;
    private final ShakeGestureHandler mGestureHandler;
    private final SettingsObserver mSettingsObserver;

    private boolean gestureCurrentlyEnabled = false;

    public ShakeGestureImpl(Callbacks callbacks) {
        mContext = NtServiceInjector.getCtx();

        HandlerThread thread = new HandlerThread("Shake-Gesture");
        thread.start();
        mHandler = new Handler(thread.getLooper());

        mGestureHandler = new ShakeGestureHandler(callbacks);

        mSettingsObserver = new SettingsObserver(mHandler);
        mSettingsObserver.onChange(false, SHAKE_GESTURES_ENABLED_URI);
        mSettingsObserver.onChange(false, SHAKE_GESTURES_ACTION_URI);

        mGestureHandler.updateSettings();
        updateGestureMonitoring();
    }

    private void updateGestureMonitoring() {
        boolean enabled = mGestureHandler.isEnabled();
        if (enabled != gestureCurrentlyEnabled) {
            if (enabled) {
                mGestureHandler.registerListener();
            } else {
                mGestureHandler.unregisterListener();
            }
            gestureCurrentlyEnabled = enabled;
        }
    }

    public void onUserSwitching() {
        mGestureHandler.updateSettings();
        updateGestureMonitoring();
    }

    private class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
            mGestureHandler.registerObservers(mContext.getContentResolver(), this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri != null) {
                mGestureHandler.onSettingsChanged(uri);
                updateGestureMonitoring();
            }
        }
    }

    private class ShakeGestureHandler {

        private final Callbacks mCallbacks;
        private final ShakeGestureUtils mShakeGestureUtils;
        private final ShakeGestureUtils.OnShakeListener mShakeListener;

        private boolean mEnabled = false;
        private int mAction = 0;

        ShakeGestureHandler(Callbacks callbacks) {
            mCallbacks = callbacks;
            mShakeGestureUtils = new ShakeGestureUtils(mContext);
            mShakeListener = () -> mCallbacks.onShake();
        }

        void registerObservers(ContentResolver resolver, ContentObserver observer) {
            resolver.registerContentObserver(SHAKE_GESTURES_ENABLED_URI, false, observer, UserHandle.USER_ALL);
            resolver.registerContentObserver(SHAKE_GESTURES_ACTION_URI, false, observer, UserHandle.USER_ALL);
        }

        void onSettingsChanged(Uri uri) {
            updateSettings();
        }

        void updateSettings() {
            mEnabled = Settings.Secure.getInt(
                    mContext.getContentResolver(), "shake_gestures_enabled", 0) == 1;
            mAction = Settings.Secure.getInt(
                    mContext.getContentResolver(), "shake_gestures_action", 0);
        }

        boolean isEnabled() {
            return mEnabled && mAction != 0;
        }

        void registerListener() {
            mShakeGestureUtils.registerListener(mShakeListener);
        }

        void unregisterListener() {
            mShakeGestureUtils.unregisterListener(mShakeListener);
        }
    }
}
