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
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.app.IGameSpaceCallback;

import java.util.ArrayList;
import java.util.List;

class GameStateDispatcher {
    private final Context mContext;
    private final List<IGameSpaceCallback> mCallbacks;

    private static final String TAG = "GameStateDispatcher";
    private static final String SERVICE_COMPONENT =
            "io.chaldeaprjkt.gamespace/.gamebar.GameSpaceService";

    GameStateDispatcher(Context context, List<IGameSpaceCallback> callbacks) {
        this.mContext = context;
        this.mCallbacks = callbacks;
    }

    void dispatchGameState(boolean isActive, String activeGame) {
        boolean suppress = Settings.System.getIntForUser(
                mContext.getContentResolver(),
                "gamespace_suppress_fullscreen_intent",
                0,
                UserHandle.USER_CURRENT
        ) == 1;

        notifySuppressFullScreenIntent(suppress && isActive);
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

    private void notifySuppressFullScreenIntent(boolean suppress) {
        for (IGameSpaceCallback callback : new ArrayList<>(mCallbacks)) {
            try {
                callback.shouldSuppressFullScreenIntent(suppress);
            } catch (Exception e) {
                Slog.w(TAG, "Removing dead callback", e);
                mCallbacks.remove(callback);
            }
        }
    }

    public void startService(String activeGame) {
        Intent intent = new Intent();
        intent.setComponent(ComponentName.unflattenFromString(SERVICE_COMPONENT));
        intent.setAction("game_start");
        intent.putExtra("package_name", activeGame);
        try {
            mContext.startServiceAsUser(intent, UserHandle.CURRENT);
            Slog.i(TAG, "Started SessionService for: " + activeGame);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to start SessionService", e);
        }
    }
}
