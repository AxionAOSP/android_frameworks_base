/*
 * Copyright (C) 2025 The AxionAOSP Project
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
import static com.android.systemui.statusbar.StatusBarState.SHADE_LOCKED;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class ScrimUtils {

    public interface ScrimEventListener {
        default void onKeyguardShowingChanged(boolean showing) {}
        default void onKeyguardFadingAwayChanged(boolean fadingAway) {}
        default void onKeyguardGoingAwayChanged(boolean goingAway) {}
        default void onPrimaryBouncerShowingChanged(boolean showing) {}
        default void onDozingChanged() {}
        default void onExpandedFractionChanged(float expandedFraction) {}
        default void onBarStateChanged(int state) {}
        default void onQsVisibilityChanged(boolean visible) {}
        default void onStartedWakingUp() {}
        default void onScreenTurnedOff() {}
    }

    private static volatile ScrimUtils instance;
    private final WeakListenerManager<ScrimEventListener> listeners = new WeakListenerManager<>();

    private final AtomicBoolean mIsDozing = new AtomicBoolean(false);
    private final AtomicBoolean mQsVisible = new AtomicBoolean(false);
    private volatile float mExpandedFraction = 0f;
    private volatile int mBarState = -1;
    private volatile boolean mKeyguardShowing = true;

    private ScrimUtils() {}

    public static ScrimUtils get() {
        if (instance == null) {
            synchronized (ScrimUtils.class) {
                if (instance == null) {
                    instance = new ScrimUtils();
                }
            }
        }
        return instance;
    }

    public void addListener(ScrimEventListener listener) {
        listeners.addListener(listener);
    }

    public void removeListener(ScrimEventListener listener) {
        listeners.removeListener(listener);
    }

    private void notifyListeners(Consumer<ScrimEventListener> callback) {
        listeners.notifyConsumer(callback);
    }

    public void setKeyguardShowing(boolean showing) {
        if (mKeyguardShowing != showing) {
            mKeyguardShowing = showing;
            notifyListeners(listener -> listener.onKeyguardShowingChanged(showing));
        }
    }

    public void setExpandedFraction(float fraction) {
        if ((fraction == 0.0f || fraction == 1.0f) && mExpandedFraction != fraction) {
            mExpandedFraction = fraction;
            notifyListeners(listener -> listener.onExpandedFractionChanged(fraction));
        }
    }

    public void onDozingChanged(boolean dozing) {
        if (mIsDozing.getAndSet(dozing) != dozing) {
            notifyListeners(ScrimEventListener::onDozingChanged);
        }
    }

    public void onKeyguardGoingAwayChanged(boolean goingAway) {
        notifyListeners(listener -> listener.onKeyguardGoingAwayChanged(goingAway));
    }

    public void onKeyguardFadingAwayChanged(boolean fadingAway) {
        notifyListeners(listener -> listener.onKeyguardFadingAwayChanged(fadingAway));
    }

    public void onPrimaryBouncerShowingChanged(boolean showing) {
        notifyListeners(listener -> listener.onPrimaryBouncerShowingChanged(showing));
    }

    public void setBarState(int state) {
        if (mBarState != state) {
            mBarState = state;
            notifyListeners(listener -> listener.onBarStateChanged(state));
        }
    }

    public void setQsVisible(boolean visible) {
        if (mQsVisible.getAndSet(visible) != visible) {
            notifyListeners(listener -> listener.onQsVisibilityChanged(visible));
        }
    }

    public void onStartedWakingUp() {
        notifyListeners(ScrimEventListener::onStartedWakingUp);
    }

    public void onScreenTurnedOff() {
        notifyListeners(ScrimEventListener::onScreenTurnedOff);
    }

    public boolean isDozing() {
        return mIsDozing.get();
    }

    public boolean isKeyguardShowing() {
        return mKeyguardShowing || mBarState == KEYGUARD;
    }

    public boolean isPanelFullyCollapsed() {
        return (mBarState == SHADE_LOCKED || mBarState == KEYGUARD)
                ? !mQsVisible.get()
                : mExpandedFraction <= 0.0f;
    }
}
