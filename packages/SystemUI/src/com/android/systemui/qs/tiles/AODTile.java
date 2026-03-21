/*
 * Copyright (C) 2018 The OmniROM Project
 *               2020-2021 The LineageOS Project
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

package com.android.systemui.qs.tiles;

import static com.android.internal.logging.MetricsLogger.VIEW_UNKNOWN;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.UserSettingObserver;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.doze.AodScheduleController;
import com.android.systemui.util.settings.SecureSettings;

import javax.inject.Inject;

public class AODTile extends QSTileImpl<BooleanState> implements
        BatteryController.BatteryStateChangeCallback {

    public static final String TILE_SPEC = "aod";

    @Nullable
    private Icon mIcon = null;

    private final BatteryController mBatteryController;
    private final AodScheduleController mAodScheduleController;

    private final UserSettingObserver mSetting;
    private final UserSettingObserver mScheduleModeSetting;

    @Inject
    public AODTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            SecureSettings secureSettings,
            BatteryController batteryController,
            AodScheduleController aodScheduleController,
            UserTracker userTracker
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);

        mAodScheduleController = aodScheduleController;

        mSetting = new UserSettingObserver(secureSettings, mHandler, Settings.Secure.DOZE_ALWAYS_ON,
                userTracker.getUserId()) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                handleRefreshState(value);
            }
        };

        mScheduleModeSetting = new UserSettingObserver(secureSettings, mHandler,
                AodScheduleController.AOD_SCHEDULE_MODE, userTracker.getUserId()) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                handleRefreshState(mSetting.getValue());
            }
        };

        mBatteryController = batteryController;
        batteryController.observe(getLifecycle(), this);
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        refreshState();
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        mSetting.setListening(false);
        mScheduleModeSetting.setListening(false);
    }

    @Override
    public boolean isAvailable() {
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_dozeAlwaysOnDisplayAvailable);
    }

    @Override
    public BooleanState newTileState() {
        BooleanState state = new BooleanState();
        state.handlesLongClick = false;
        return state;
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        mSetting.setListening(listening);
        mScheduleModeSetting.setListening(listening);
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
        mSetting.setUserId(newUserId);
        mScheduleModeSetting.setUserId(newUserId);
        handleRefreshState(mSetting.getValue());
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        int currentScheduleMode = mScheduleModeSetting.getValue();
        int nextMode = (currentScheduleMode + 1) % 5;
        mScheduleModeSetting.setValue(nextMode);
        mSetting.setValue(nextMode == AodScheduleController.MODE_DISABLED ? 0 : 1);
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        if (mBatteryController.isAodPowerSave()) {
            return mContext.getString(R.string.quick_settings_aod_off_powersave_label);
        }
        return mContext.getString(R.string.quick_settings_aod_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (mIcon == null) {
            mIcon = maybeLoadResourceIcon(R.drawable.ic_qs_aod);
        }

        state.icon = mIcon;
        state.label = getTileLabel();
        state.hasLongClickEffect = false;

        int scheduleMode = mScheduleModeSetting.getValue();
        boolean shouldShowAod = mAodScheduleController.shouldShowAod();

        if (mBatteryController.isAodPowerSave()) {
            state.state = Tile.STATE_UNAVAILABLE;
            state.secondaryLabel = mContext.getString(R.string.quick_settings_aod_powersave_label);
            state.value = false;
            return;
        }

        if (scheduleMode == AodScheduleController.MODE_DISABLED) {
            state.state = Tile.STATE_INACTIVE;
            state.value = false;
            state.secondaryLabel = mContext.getString(R.string.quick_settings_aod_disabled_label);
        } else {
            state.state = shouldShowAod ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
            state.value = shouldShowAod;
            state.secondaryLabel = buildSecondaryLabel(scheduleMode);
        }
    }

    private String buildSecondaryLabel(int scheduleMode) {
        switch (scheduleMode) {
            case AodScheduleController.MODE_ALWAYS:
                return mContext.getString(R.string.quick_settings_aod_always_label);
            case AodScheduleController.MODE_CHARGE_ONLY:
                return mContext.getString(R.string.quick_settings_aod_charge_only_label);
            case AodScheduleController.MODE_SCHEDULED: {
                String startTime = mAodScheduleController.getStartTime();
                String endTime = mAodScheduleController.getEndTime();
                return formatScheduleTime(startTime, endTime);
            }
            case AodScheduleController.MODE_SCHEDULED_CHARGE: {
                String startTime = mAodScheduleController.getStartTime();
                String endTime = mAodScheduleController.getEndTime();
                return formatScheduleTime(startTime, endTime) + " + "
                        + mContext.getString(R.string.quick_settings_aod_charge_only_label);
            }
            default:
                return "";
        }
    }

    @Override
    public int getMetricsCategory() {
        return VIEW_UNKNOWN;
    }

    private String formatScheduleTime(String startTime, String endTime) {
        try {
            String start = startTime.substring(0, 2) + ":" + startTime.substring(2, 4);
            String end = endTime.substring(0, 2) + ":" + endTime.substring(2, 4);
            return start + " - " + end;
        } catch (Exception e) {
            return "";
        }
    }
}
