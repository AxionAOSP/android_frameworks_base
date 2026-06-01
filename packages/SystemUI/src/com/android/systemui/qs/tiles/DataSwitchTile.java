/*
 * SPDX-FileCopyrightText: 2025 Paranoid Android
 * SPDX-License-Identifer: Apache-2.0
 */

package com.android.systemui.qs.tiles;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.ArrayUtils;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;

import java.util.concurrent.Executor;

import javax.inject.Inject;

public class DataSwitchTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "dataswitch";
    private static final String SETTING_USER_PREF_DATA_SUB = "user_preferred_data_sub";

    private final SubscriptionManager mSubscriptionManager;
    private final TelephonyManager mTelephonyManager;
    private final Executor mExecutor;
    private boolean mRegistered = false;
    private boolean mIsCallIdle = true;
    private int mSimCount;
    private int mCurrentDds;
    private final TelephonyListener mTelephonyListener = new TelephonyListener();

    private final SubscriptionManager.OnSubscriptionsChangedListener mSubsListener =
            new SubscriptionManager.OnSubscriptionsChangedListener() {
                @Override
                public void onSubscriptionsChanged() {
                    int simCount = mSubscriptionManager.getActiveSubscriptionInfoCount();
                    if (simCount != mSimCount) {
                        Log.d(TAG, "simCount=" + simCount);
                        mSimCount = simCount;
                        refreshState();
                    }
                }
            };

    @Inject
    public DataSwitchTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            @Main Executor executor
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mSubscriptionManager = mContext.getSystemService(SubscriptionManager.class);
        mTelephonyManager = mContext.getSystemService(TelephonyManager.class);
        mExecutor = executor;
        mSimCount = mSubscriptionManager.getActiveSubscriptionInfoCount();
        mCurrentDds = SubscriptionManager.getDefaultDataSubscriptionId();
    }

    @Override
    public boolean isAvailable() {
        int count = mTelephonyManager.getActiveModemCount();
        Log.d(TAG, "modemCount: " + count);
        return count >= 2;
    }

    @Override
    public BooleanState newTileState() {
        BooleanState s = new BooleanState();
        s.label = getTileLabel();
        return s;
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (listening) {
            if (!mRegistered) {
                mSubscriptionManager.addOnSubscriptionsChangedListener(mExecutor, mSubsListener);
                mTelephonyManager.registerTelephonyCallback(mExecutor, mTelephonyListener);
                mRegistered = true;
            }
            refreshState();
        } else if (mRegistered) {
            mSubscriptionManager.removeOnSubscriptionsChangedListener(mSubsListener);
            mTelephonyManager.unregisterTelephonyCallback(mTelephonyListener);
            mRegistered = false;
        }
    }

    @Override
    public void handleClick(@Nullable Expandable expandable) {
        if (!mIsCallIdle || mSimCount < 2) {
            return;
        }
        mHandler.post(() -> {
            switchDds();
            refreshState();
        });
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.qs_data_switch_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (mSimCount < 2) {
            Log.d(TAG, "updateState: less than 2 sims!");
            state.icon = ResourceIcon.get(R.drawable.ic_qs_data_switch_1);
            state.secondaryLabel = null;
            state.value = false;
            state.state = Tile.STATE_UNAVAILABLE;
            return;
        }

        boolean isDdsActive = mSubscriptionManager.isActiveSubscriptionId(mCurrentDds);
        // default to sim 1 if dds is inactive or invalid
        int phoneId = isDdsActive ? SubscriptionManager.getPhoneId(mCurrentDds) : 0;
        int simNo = phoneId + 1;
        Log.d(TAG, "updateState: dds=" + mCurrentDds + " isDdsActive=" + isDdsActive
                + " phoneId=" + phoneId);

        state.icon = ResourceIcon.get(
                simNo == 1 ? R.drawable.ic_qs_data_switch_1 : R.drawable.ic_qs_data_switch_2);
        state.secondaryLabel = mContext.getString(R.string.qs_data_switch_sim_label, simNo);
        state.value = mIsCallIdle;
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_UNAVAILABLE;
        state.contentDescription = mContext.getString(R.string.qs_data_switch_sim_desc, simNo);
    }

    private void switchDds() {
        int[] subIds = mSubscriptionManager.getActiveSubscriptionIdList();
        if (ArrayUtils.isEmpty(subIds)) {
            Log.e(TAG, "switchDds: empty subs list");
            return;
        }

        // fallback to first active subid is dds is inactive or invalid
        int currentDds = mSubscriptionManager.isActiveSubscriptionId(mCurrentDds)
                ? mCurrentDds : subIds[0];
        boolean isDataEnabled =
                mTelephonyManager.createForSubscriptionId(currentDds).isDataEnabled();
        // get the next subid from the list
        int newDds = subIds[(indexOf(subIds, currentDds) + 1) % subIds.length];
        Log.d(TAG, "switchDds: " + currentDds + " => " + newDds + ", dataEnabled=" + isDataEnabled);
        // set new dds
        mSubscriptionManager.setDefaultDataSubId(newDds);
        Settings.Global.putInt(mContext.getContentResolver(), SETTING_USER_PREF_DATA_SUB, newDds);
        mCurrentDds = newDds;

        // enable mobile data on new dds if it was enabled before
        if (isDataEnabled) {
            mTelephonyManager.createForSubscriptionId(newDds).setDataEnabled(true);
            Log.i(TAG, "Enabled data on subid " + newDds);
        }
    }

    private static int indexOf(int[] array, int value) {
        for (int i = 0; i < array.length; i++) {
            if (array[i] == value) return i;
        }
        return -1;
    }

    private class TelephonyListener extends TelephonyCallback implements
            TelephonyCallback.CallStateListener,
            TelephonyCallback.ActiveDataSubscriptionIdListener {

        @Override
        public void onCallStateChanged(int state) {
            boolean isIdle = state == TelephonyManager.CALL_STATE_IDLE;
            if (isIdle != mIsCallIdle) {
                Log.d(TAG, "isCallIdle=" + isIdle);
                mIsCallIdle = isIdle;
                refreshState();
            }
        }

        @Override
        public void onActiveDataSubscriptionIdChanged(int subId) {
            if (subId != mCurrentDds) {
                Log.d(TAG, "active data sub changed: " + mCurrentDds + " => " + subId);
                mCurrentDds = subId;
                refreshState();
            }
        }
    }
}
