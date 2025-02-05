/**
 * Copyright (C) 2025 the AxionAOSP Project
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

package com.android.systemui.biometrics;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.util.DisplayUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;

import com.android.systemui.res.R;
import com.android.systemui.biometrics.AuthController;

import com.airbnb.lottie.LottieDrawable;
import com.airbnb.lottie.LottieAnimationView;

public class UdfpsAnimation extends LottieAnimationView {

    private static final boolean DEBUG = android.os.SystemProperties.getBoolean("persist.sys.udfps_animation_debug", false);
    private static final String LOG_TAG = "UdfpsAnimations";

    private boolean mShowing = false;
    private boolean mKeyguardShowing = true;
    private final Context mContext;
    private int mAnimationSize;
    private final WindowManager.LayoutParams mAnimParams = new WindowManager.LayoutParams();
    private final WindowManager mWindowManager;

    private final AuthController mAuthController;
    private final FingerprintSensorPropertiesInternal mProps;

    public UdfpsAnimation(Context context, 
            WindowManager windowManager,
            FingerprintSensorPropertiesInternal props, 
            AuthController authController) {
        super(context);
        mContext = context;
        mAuthController = authController;
        mProps = props;
        mWindowManager = windowManager;

        mAnimationSize = mContext.getResources().getDimensionPixelSize(R.dimen.udfps_animation_size);

        mAnimParams.height = mAnimationSize;
        mAnimParams.width = mAnimationSize;
        mAnimParams.format = PixelFormat.TRANSLUCENT;
        mAnimParams.type = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY; // must be behind UDFPS icon
        mAnimParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        mAnimParams.gravity = Gravity.TOP | Gravity.CENTER;

        setAnimation(R.raw.nt_udfps_lockscreen_fp_scanning);
        setRepeatCount(LottieDrawable.INFINITE);
        setSpeed(1.7f);

        updatePosition();
    }

    private float getDisplayFactor() {
        return DisplayUtils.getScaleFactor(mContext);
    }

    public void updatePosition() {
        Point displaySize = new Point();
        mWindowManager.getDefaultDisplay().getRealSize(displaySize);
        boolean isFullResolution = displaySize.y > 3000; 
        Point udfpsLocation = mAuthController.getUdfpsLocation();
        float scaleFactor = getDisplayFactor();
        float udfpsRadius = isFullResolution ? mAuthController.getUdfpsRadius() : mProps.getLocation().sensorRadius;
        float udfpsLocationY = isFullResolution && udfpsLocation != null ? udfpsLocation.y : mProps.getLocation().sensorLocationY;
        int animationOffset = (int) (mContext.getResources().getDimensionPixelSize(R.dimen.udfps_animation_offset) * scaleFactor);
        mAnimParams.y = (int) (udfpsLocationY * scaleFactor) - (int) (udfpsRadius * scaleFactor)
                        - (mAnimationSize / 2) + animationOffset;
        if (DEBUG) {
            Log.d(LOG_TAG, "updatePosition: displaySize=" + displaySize + 
                           ", isFullResolution=" + isFullResolution + 
                           ", udfpsLocation=" + udfpsLocation + 
                           ", udfpsRadius=" + udfpsRadius + 
                           ", scaleFactor=" + scaleFactor + 
                           ", udfpsLocationY=" + udfpsLocationY + 
                           ", animationOffset=" + animationOffset + 
                           ", mAnimParams.y=" + mAnimParams.y);
        }
    }

    public void show() {
        if (mShowing || !mKeyguardShowing) return;
        try {
            if (getWindowToken() == null) {
                mWindowManager.addView(this, mAnimParams);
            } else {
                mWindowManager.updateViewLayout(this, mAnimParams);
            }
            mShowing = true;
            playAnimation();
            if (DEBUG) Log.d(LOG_TAG, "Showing/Playing UDFPS scanning animation");
        } catch (RuntimeException e) {
            if (DEBUG) Log.e(LOG_TAG, "Error adding view to WindowManager", e);
        }
    }

    public void hide() {
        if (!mShowing || getWindowToken() == null) return;
        try {
            pauseAnimation();
            setProgress(0f);
            mWindowManager.removeView(this);
            mShowing = false;
            if (DEBUG) Log.d(LOG_TAG, "Hiding/Stopping UDFPS scanning animation");
        } catch (RuntimeException e) {
            if (DEBUG) Log.e(LOG_TAG, "Error removing view from WindowManager", e);
        }
    }
    
    public void setKeyguardShowing(boolean showing) {
        mKeyguardShowing = showing;
    }
}
