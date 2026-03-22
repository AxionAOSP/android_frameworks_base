/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.keyguard;

import android.database.ContentObserver;
import android.os.Handler;
import android.view.View;

import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.settings.SecureSettings;

import javax.inject.Inject;

/**
 * Controller for {@link CarrierText}.
 */
public class CarrierTextController extends ViewController<CarrierText> {
    private static final String SHOW_LOCKSCREEN_CARRIER_TEXT = "show_lockscreen_carrier_text";

    private final CarrierTextManager mCarrierTextManager;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final SecureSettings mSecureSettings;
    private final CarrierTextManager.CarrierTextCallback mCarrierTextCallback =
            new CarrierTextManager.CarrierTextCallback() {
                @Override
                public void updateCarrierInfo(CarrierTextManager.CarrierTextCallbackInfo info) {
                    mView.setText(info.carrierText);
                }

                @Override
                public void startedGoingToSleep() {
                    mView.setSelected(false);
                }

                @Override
                public void finishedWakingUp() {
                    mView.setSelected(true);
                }
            };

    private final ContentObserver mCarrierTextSettingObserver;

    @Inject
    public CarrierTextController(CarrierText view,
            CarrierTextManager.Builder carrierTextManagerBuilder,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            SecureSettings secureSettings,
            @Main Handler mainHandler) {
        super(view);

        mCarrierTextManager = carrierTextManagerBuilder
                .setShowAirplaneMode(mView.getShowAirplaneMode())
                .setShowMissingSim(mView.getShowMissingSim())
                .setDebugLocationString(mView.getDebugLocation())
                .build();
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mSecureSettings = secureSettings;
        mCarrierTextSettingObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                updateCarrierTextVisibility();
            }
        };
    }

    @Override
    protected void onInit() {
        super.onInit();
        mView.setSelected(mKeyguardUpdateMonitor.isDeviceInteractive());
    }

    @Override
    protected void onViewAttached() {
        mCarrierTextManager.setListening(mCarrierTextCallback);
        mSecureSettings.registerContentObserverForUserSync(
                SHOW_LOCKSCREEN_CARRIER_TEXT,
                false,
                mCarrierTextSettingObserver,
                mSecureSettings.getUserId());
        updateCarrierTextVisibility();
    }

    @Override
    protected void onViewDetached() {
        mCarrierTextManager.setListening(null);
        mSecureSettings.unregisterContentObserverSync(mCarrierTextSettingObserver);
    }

    private void updateCarrierTextVisibility() {
        int show = mSecureSettings.getInt(SHOW_LOCKSCREEN_CARRIER_TEXT, 1);
        mView.setVisibility(show != 0 ? View.VISIBLE : View.GONE);
    }
}
