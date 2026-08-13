/*
 * Copyright (C) 2026 AxionOS
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

package com.android.systemui.statusbar.notification.row;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import com.android.axion.blur.BlurEngine;
import com.android.systemui.common.shared.colors.SurfaceEffectColors;

import java.io.PrintWriter;

public class BundleHeaderBlurView extends View {
    private final BlurEngine mBlur;
    private final GradientDrawable mFallback = new GradientDrawable();
    private final float[] mCornerRadii = new float[8];
    private boolean mBlurEnabled;
    private boolean mLastBlurActive;
    private boolean mHasCornerRadii;
    private Runnable mOnBlurStateChanged;

    public BundleHeaderBlurView(Context context) {
        super(context);
        mBlur = new BlurEngine(this);
        mFallback.setColor(SurfaceEffectColors.surfaceEffect1(context));
    }

    public void setOnBlurStateChangedListener(Runnable listener) {
        mOnBlurStateChanged = listener;
    }

    @Override
    public void draw(Canvas canvas) {
        boolean active = drawBlurBackground(canvas);
        if (!active && mBlurEnabled) {
            drawFallbackBackground(canvas);
        }
        if (active != mLastBlurActive) {
            mLastBlurActive = active;
            if (mOnBlurStateChanged != null) {
                post(mOnBlurStateChanged);
            }
        }
        super.draw(canvas);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateColors();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        mBlur.onVisibilityAggregated(isVisible);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mOnBlurStateChanged != null) {
            removeCallbacks(mOnBlurStateChanged);
        }
        mLastBlurActive = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateColors();
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return mBlur.verifyDrawable(who) || super.verifyDrawable(who);
    }

    public boolean isBlurActive() {
        return mBlur.isBlurActive();
    }

    public void setAxBlurEnabled(boolean enabled) {
        if (mBlurEnabled == enabled) {
            return;
        }
        mBlurEnabled = enabled;
        mBlur.setEnabled(enabled);
        invalidate();
    }

    public void setAxBlurAlphaSource(View source) {
        mBlur.setCrossWindowAlphaSource(source);
        invalidate();
    }

    public void setBlurFadeRange(float fadeTop, float fadeBottom) {
        mBlur.setFadeRange(fadeTop, fadeBottom);
        invalidate();
    }

    public void dump(PrintWriter pw, String[] args) {
        pw.println("mBlurEnabled: " + mBlurEnabled);
        pw.println("mLastBlurActive: " + mLastBlurActive);
        pw.println("mHasCornerRadii: " + mHasCornerRadii);
        mBlur.dump(pw);
    }

    public void setCornerRadii(float[] radii) {
        if (radii == null || radii.length < mCornerRadii.length) {
            return;
        }
        System.arraycopy(radii, 0, mCornerRadii, 0, mCornerRadii.length);
        mHasCornerRadii = true;
        invalidate();
    }

    private void updateColors() {
        int color = SurfaceEffectColors.surfaceEffect1(getContext());
        mBlur.setOverlayColor(color);
        mFallback.setColor(color);
        invalidate();
    }

    private void drawFallbackBackground(Canvas canvas) {
        mFallback.setBounds(0, 0, getWidth(), getHeight());
        if (mHasCornerRadii) {
            mFallback.setCornerRadii(mCornerRadii);
        } else {
            mFallback.setCornerRadius(Math.min(getWidth(), getHeight()) * 0.5f);
        }
        mFallback.draw(canvas);
    }

    private boolean drawBlurBackground(Canvas canvas) {
        if (!mBlurEnabled || getWidth() <= 0 || getHeight() <= 0) {
            return false;
        }
        if (mHasCornerRadii) {
            return mBlur.draw(
                    canvas,
                    0,
                    0,
                    getWidth(),
                    getHeight(),
                    mCornerRadii,
                    255);
        }
        float cornerRadius = Math.min(getWidth(), getHeight()) * 0.5f;
        return mBlur.draw(canvas, 0, 0, getWidth(), getHeight(), cornerRadius, 255);
    }
}
