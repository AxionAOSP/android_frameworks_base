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
import android.os.SystemProperties;
import android.util.Log;
import android.util.DisplayUtils;
import android.view.Gravity;
import android.view.WindowManager;

import com.android.systemui.res.R;

public class UdfpsAnimationController {

    private static final boolean DEBUG = SystemProperties.getBoolean("persist.sys.udfps_animation_debug", false);
    private static final String LOG_TAG = "UdfpsAnimationController";

    private static UdfpsAnimationController sInstance;
    private final Context mContext;
    private final WindowManager mWindowManager;
    private final AuthController mAuthController;
    private final FingerprintSensorPropertiesInternal mProps;
    private UdfpsAnimation mUdfpsAnimation;
    private final WindowManager.LayoutParams mAnimParams;

    private boolean mKeyguardShowing = true;

    private UdfpsAnimationController(Context context, WindowManager windowManager, 
                                     FingerprintSensorPropertiesInternal props, 
                                     AuthController authController) {
        mContext = context;
        mWindowManager = windowManager;
        mAuthController = authController;
        mProps = props;

        mAnimParams = new WindowManager.LayoutParams();
        int animationSize = mContext.getResources().getDimensionPixelSize(R.dimen.udfps_animation_size);

        mAnimParams.height = animationSize;
        mAnimParams.width = animationSize;
        mAnimParams.format = PixelFormat.TRANSLUCENT;
        mAnimParams.type = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY; // Must be behind UDFPS icon
        mAnimParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        mAnimParams.gravity = Gravity.TOP | Gravity.CENTER;
    }

    public static synchronized UdfpsAnimationController getInstance(Context context, 
                                                                   WindowManager windowManager, 
                                                                   FingerprintSensorPropertiesInternal props, 
                                                                   AuthController authController) {
        if (sInstance == null) {
            sInstance = new UdfpsAnimationController(context, windowManager, props, authController);
        }
        return sInstance;
    }

    private void updatePosition() {
        Point displaySize = new Point();
        mWindowManager.getDefaultDisplay().getRealSize(displaySize);
        boolean isFullResolution = displaySize.y > 3000;
        Point udfpsLocation = mAuthController.getUdfpsLocation();
        float scaleFactor = DisplayUtils.getScaleFactor(mContext);
        float udfpsRadius = isFullResolution ? mAuthController.getUdfpsRadius() : mProps.getLocation().sensorRadius;
        float udfpsLocationY = isFullResolution && udfpsLocation != null ? udfpsLocation.y : mProps.getLocation().sensorLocationY;
        int animationOffset = (int) (mContext.getResources().getDimensionPixelSize(R.dimen.udfps_animation_offset) * scaleFactor);
        mAnimParams.y = (int) (udfpsLocationY * scaleFactor) - (int) (udfpsRadius * scaleFactor)
                        - (mAnimParams.height / 2) + animationOffset;

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
        hide();
        if (!mKeyguardShowing) return;
        mUdfpsAnimation = new UdfpsAnimation(mContext);
        updatePosition();

        try {
            mWindowManager.addView(mUdfpsAnimation, mAnimParams);
            if (DEBUG) Log.d(LOG_TAG, "Added new UDFPS animation to WindowManager");
        } catch (RuntimeException e) {
            Log.e(LOG_TAG, "Error adding new UDFPS animation view", e);
            mUdfpsAnimation = null;
            return;
        }

        mUdfpsAnimation.startAnimation();
    }

    public void hide() {
        if (mUdfpsAnimation != null) {
            try {
                mUdfpsAnimation.stopAnimation();
                if (mUdfpsAnimation.getParent() != null) {
                    mWindowManager.removeView(mUdfpsAnimation);
                    if (DEBUG) Log.d(LOG_TAG, "Removed UDFPS animation from WindowManager");
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, "Error removing UDFPS animation view", e);
            }
            mUdfpsAnimation = null;
        }
    }

    public void setKeyguardShowing(boolean showing) {
        mKeyguardShowing = showing;
    }
}
