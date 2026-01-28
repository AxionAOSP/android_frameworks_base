/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

import com.android.systemui.LauncherProxyService;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.shared.recents.ILauncherProxy;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import javax.inject.Inject;

/**
 * An implementation of the Recents interface which proxies to the LauncherProxyService.
 */
@SysUISingleton
public class OverviewProxyRecentsImpl implements RecentsImplementation {

    private final static String TAG = "OverviewProxyRecentsImpl";
    
    private static final String AXPCMODE_PACKAGE = "com.android.axion.axpcmode";
    private static final String TASKS_OVERVIEW_ACTIVITY = 
            "com.android.axion.axpcmode.activities.TasksOverviewActivity";
    
    private Handler mHandler;
    private Context mContext;
    private final LauncherProxyService mLauncherProxyService;
    private final ActivityStarter mActivityStarter;
    private final KeyguardStateController mKeyguardStateController;

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    @Inject
    public OverviewProxyRecentsImpl(
            LauncherProxyService launcherProxyService,
            ActivityStarter activityStarter,
            KeyguardStateController keyguardStateController) {
        mLauncherProxyService = launcherProxyService;
        mActivityStarter = activityStarter;
        mKeyguardStateController = keyguardStateController;
    }

    @Override
    public void onStart(Context context) {
        mContext = context;
        mHandler = new Handler();
    }
    
    private boolean isAxPcModeEnabled() {
        try {
            return Settings.Secure.getInt(mContext.getContentResolver(), "ax_pc_mode", 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }
    
    private void launchAxPcModeRecents() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(AXPCMODE_PACKAGE, TASKS_OVERVIEW_ACTIVITY));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            mContext.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch AxPcMode TasksOverviewActivity", e);
        }
    }

    @Override
    public void showRecentApps(boolean triggeredFromAltTab) {
        if (isAxPcModeEnabled()) {
            launchAxPcModeRecents();
            return;
        }
        
        ILauncherProxy launcherProxy = mLauncherProxyService.getProxy();
        if (launcherProxy != null) {
            try {
                launcherProxy.onOverviewShown(triggeredFromAltTab);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send overview show event to launcher.", e);
            }
        }
    }

    @Override
    public void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
        ILauncherProxy launcherProxy = mLauncherProxyService.getProxy();
        if (launcherProxy != null) {
            try {
                launcherProxy.onOverviewHidden(triggeredFromAltTab, triggeredFromHomeKey);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send overview hide event to launcher.", e);
            }
        }
    }

    @Override
    public void toggleRecentApps() {
        if (isAxPcModeEnabled()) {
            launchAxPcModeRecents();
            return;
        }
        
        // If connected to launcher service, let it handle the toggle logic
        ILauncherProxy launcherProxy = mLauncherProxyService.getProxy();
        if (launcherProxy != null) {
            final Runnable toggleRecents = () -> {
                try {
                    if (mLauncherProxyService.getProxy() != null) {
                        mLauncherProxyService.getProxy().onOverviewToggle();
                        mLauncherProxyService.notifyToggleRecentApps();
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Cannot send toggle recents through proxy service.", e);
                }
            };
            // Preload only if device for current user is unlocked
            if (mKeyguardStateController.isShowing()) {
                mActivityStarter.executeRunnableDismissingKeyguard(
                        () -> mHandler.post(toggleRecents), null, true /* dismissShade */,
                        false /* afterKeyguardGone */,
                        true /* deferred */);
            } else {
                toggleRecents.run();
            }
        }
    }
}
