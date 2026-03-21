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
 * limitations under the License
 */
package com.android.systemui.util;

import android.app.ActivityManager;
import android.hardware.display.AmbientDisplayConfiguration;

public class ScreenAnimationController {

    private static ScreenAnimationController sInstance;

    private AmbientDisplayConfiguration mAmbientDisplayConfiguration = null;

    private boolean mPanelExpandedWhenScreenOff = false;
    private boolean mLandscapeWhenScreenOff = false;
    private boolean mIsPressSleepButton = false;

    private ScreenAnimationController() {}

    public static synchronized ScreenAnimationController INSTANCE() {
        if (sInstance == null) {
            sInstance = new ScreenAnimationController();
        }
        return sInstance;
    }

    public void updateCsfStates(boolean expanded, boolean landscape, boolean powerButton) {
        mPanelExpandedWhenScreenOff = expanded;
        mLandscapeWhenScreenOff = landscape;
        mIsPressSleepButton = powerButton;
    }

    public void init(AmbientDisplayConfiguration ambientConfig) {
        mAmbientDisplayConfiguration = ambientConfig;
    }

    public boolean isLandscapeScreenOff() {
        return mLandscapeWhenScreenOff;
    }

    public boolean isPanelExpandedWhenScreenOff() {
        return mPanelExpandedWhenScreenOff;
    }

    public boolean shouldPlayAnimation() {
        return (mPanelExpandedWhenScreenOff
            || mLandscapeWhenScreenOff
            || mAmbientDisplayConfiguration != null && !mAmbientDisplayConfiguration.enabled(ActivityManager.getCurrentUser())
            || mIsPressSleepButton) ? false : true;
    }
}
