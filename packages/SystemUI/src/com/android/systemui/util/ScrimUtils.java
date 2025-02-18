/*
 * Copyright (C) 2023-2024 The risingOS Android Project
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
package com.android.systemui.util;

import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;

import android.content.Context;
import android.os.Handler;

import com.android.systemui.Dependency;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

public class ScrimUtils {

    public enum ExpansionState {
        QS_NOT_EXPANDED,
        QS_FULLY_EXPANDED
    }

    private static ScrimUtils instance;
    private final Context mContext;
    private final Handler mHandler;
    private final ScrimController mScrimController;
    private final StatusBarStateController mStatusBarStateController;
    private final KeyguardStateController mKeyguardStateController;
    
    private ExpansionState mExpansionState = ExpansionState.QS_NOT_EXPANDED;

    private final KeyguardStateController.Callback mKeyguardStateCallback =
            new KeyguardStateController.Callback() {
                @Override
                public void onKeyguardShowingChanged() {
                    onKgShowingChanged(mKeyguardStateController.isShowing());
                }
                @Override
                public void onKeyguardFadingAwayChanged() {
                    onKgFadingAwayChanged();
                }

                @Override
                public void onKeyguardGoingAwayChanged() {
                    onKgGoingAwayChanged();
                }
            };

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStateChanged(int newState) {
                }
                @Override
                public void onDozingChanged(boolean dozing) {
                    onDozeChanged(dozing);
                }
            };

    private ScrimUtils(Context context) {
        mContext = context.getApplicationContext();
        mHandler = new Handler();
        mScrimController = Dependency.get(ScrimController.class);
        mStatusBarStateController = Dependency.get(StatusBarStateController.class);
        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mStatusBarStateListener.onDozingChanged(mStatusBarStateController.isDozing());
        
        mKeyguardStateController = Dependency.get(KeyguardStateController.class);
        mKeyguardStateController.addCallback(mKeyguardStateCallback);
    }

    public static ScrimUtils getInstance(Context context) {
        if (instance == null) {
            instance = new ScrimUtils(context);
        }
        return instance;
    }
    
    private MediaArtUtils getMediaInstance() {
        return MediaArtUtils.Companion.getInstance(mContext);
    }
    
    public void setViewAlpha(float subjectAlpha) {
        getMediaInstance().setSubjectAlpha(subjectAlpha);
    }

    public void setQsExpansion(float expansion) {
        ExpansionState state = expansion < 1
                ? ExpansionState.QS_NOT_EXPANDED
                : ExpansionState.QS_FULLY_EXPANDED;
        if (state == mExpansionState) {
            return;
        }
        mExpansionState = state;
        if (mExpansionState == ExpansionState.QS_NOT_EXPANDED) {
        } else if (mExpansionState == ExpansionState.QS_FULLY_EXPANDED) {
        }
        getMediaInstance().setQsExpansion(mExpansionState == ExpansionState.QS_FULLY_EXPANDED);
    }
    
    public void onScreenStateChange() {}

    private void updateNotifContainerElements() {
    }

    public void onScrimDispatched() {
        getMediaInstance().updateMediaVisibility();
    }

    private void onDozeChanged(boolean dozing) {
        MediaArtUtils mediaArtUtils = getMediaInstance();
        if (mediaArtUtils != null) {
            mediaArtUtils.onDozingChanged(dozing);
        }
    }

    private void onKgShowingChanged(boolean showing) {
        getMediaInstance().setOnKeyguard(showing);
    }

    private void onKgFadingAwayChanged() {
        getMediaInstance().setOnKeyguard(false);
    }

    private void onKgGoingAwayChanged() {
        getMediaInstance().setOnKeyguard(false);
    }

    public float getScrimBehindAlphaKeyguard() {
        return mScrimController.getScrimBehindAlpha();
    }
}
