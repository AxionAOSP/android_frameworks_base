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
