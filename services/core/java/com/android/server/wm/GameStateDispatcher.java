/*
 * Copyright (C) 2025 AxionOS Project
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
package com.android.server.wm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.app.IGameSpaceCallback;
import com.android.server.am.ActivityManagerService;

import java.util.ArrayList;
import java.util.List;

class GameStateDispatcher {
    private final Context mContext;
    private final List<IGameSpaceCallback> mCallbacks;
    private final ActivityManagerService mAm;

    private static final String TAG = "GameStateDispatcher";
    private static final String SERVICE_COMPONENT =
            "io.chaldeaprjkt.gamespace/.gamebar.GameSpaceService";
    private static final String GAMESPACE_PACKAGE = "io.chaldeaprjkt.gamespace";

    private static final String PROPERTY_PERSIST_PERFORMANCE_MODE =
            "persist.sys.power_mode_perf";

    GameStateDispatcher(Context context,
                        List<IGameSpaceCallback> callbacks,
                        ActivityManagerService am) {
        this.mContext = context;
        this.mCallbacks = callbacks;
        this.mAm = am;
    }

    void dispatchGameState(boolean isActive, String activeGame) {
        boolean suppress = Settings.System.getIntForUser(
                mContext.getContentResolver(),
                "gamespace_suppress_fullscreen_intent",
                0,
                UserHandle.USER_CURRENT
        ) == 1;
        
        int suppressStatus = suppress && isActive ? 1 : 0;

        Settings.System.putIntForUser(
                mContext.getContentResolver(),
                "gamespace_suppress_fullscreen_intent_status",
                suppressStatus,
                UserHandle.USER_CURRENT
        );

        notifyDispatchGameState(isActive, activeGame);
    }

    private void notifyDispatchGameState(boolean active, String activeGame) {
        for (IGameSpaceCallback callback : new ArrayList<>(mCallbacks)) {
            try {
                if (active && activeGame != null) {
                    callback.onGameStart(activeGame);
                } else {
                    callback.onGameLeave();
                }
            } catch (Exception e) {
                Slog.w(TAG, "Removing dead callback", e);
                mCallbacks.remove(callback);
            }
        }
    }

    public void boostGame(boolean enable) {
        final boolean perfModeEnabledByUser = Settings.System.getIntForUser(
                mContext.getContentResolver(), "power_mode_perf_by_user", 0,
                UserHandle.USER_CURRENT) == 1;
        if (perfModeEnabledByUser) return;
        Settings.System.putIntForUser(mContext.getContentResolver(),
                PROPERTY_PERSIST_PERFORMANCE_MODE, enable ? 1 : 0,
                UserHandle.USER_CURRENT);
        SystemProperties.set(PROPERTY_PERSIST_PERFORMANCE_MODE, enable ? "1" : "0");
    }
}
