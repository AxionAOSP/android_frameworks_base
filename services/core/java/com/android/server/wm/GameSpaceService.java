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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ServiceManager;
import android.util.Slog;

import com.android.internal.app.IGameSpaceCallback;
import com.android.internal.app.IGameSpaceService;
import com.android.server.UiThread;
import com.android.server.am.ActivityManagerService;

import java.util.concurrent.CopyOnWriteArrayList;

public class GameSpaceService extends IGameSpaceService.Stub implements IWindowEventListener {
    private static final String TAG = "GameSpaceService";
    private static final boolean DEBUG = false;

    private static GameSpaceService sInstance;

    private final Context mContext;
    private final CopyOnWriteArrayList<IGameSpaceCallback> mCallbacks = new CopyOnWriteArrayList<>();
    private final GameListManager mGameListManager;
    private final GameStateDispatcher mGameStateDispatcher;
    private final PackageHandler mPackageHandler;
    private final ActivityManagerService mActivityManager;

    private final HandlerThread mBgThread = new HandlerThread("GameSpaceBg");
    private final Handler mBgHandler;

    private String mCurrentGame;
    private Runnable mPendingBoost;

    private GameSpaceService(Context context, ActivityManagerService am) {
        this.mContext = context;
        this.mActivityManager = am;
        this.mGameListManager = new GameListManager(context);
        this.mGameStateDispatcher = new GameStateDispatcher(context, mCallbacks, am);
        this.mPackageHandler = new PackageHandler(context, mGameListManager);

        mBgThread.start();
        mBgHandler = new Handler(mBgThread.getLooper());

        mGameListManager.registerGameListObserver();
        mGameListManager.addListener(() -> {
            mBgHandler.post(() -> {
                if (mCurrentGame != null && mGameListManager.isGame(mCurrentGame)) {
                    boolean inPerfMode = mGameListManager.isGameInPerfMode(mCurrentGame);
                    mGameStateDispatcher.boostGame(inPerfMode);
                }
            });
        });
        mPackageHandler.registerPackageReceiver();
    }

    public static void init(Context context, ActivityManagerService am) {
        if (sInstance == null) {
            sInstance = new GameSpaceService(context, am);
            ServiceManager.addService("game_space", sInstance);
            WindowEventDispatcher.get().registerListener(sInstance);
            Slog.i(TAG, "GameSpaceService initialized");
        }
    }

    public static GameSpaceService get() {
        return sInstance;
    }

    private void startOverlay() {
        final String currentGame = mCurrentGame;
        if (currentGame == null) return;

        mBgHandler.post(() -> {
            if (mPendingBoost != null) {
                mBgHandler.removeCallbacks(mPendingBoost);
            }

            if (mGameListManager.isGameInPerfMode(currentGame)) {
                mPendingBoost = () -> mGameStateDispatcher.boostGame(true);
                mBgHandler.postDelayed(mPendingBoost, 500);
            }

            UiThread.getHandler().post(
                    () -> mGameStateDispatcher.dispatchGameState(true, currentGame));
        });
    }

    private void stopOverlay() {
        mBgHandler.post(() -> {
            if (mPendingBoost != null) {
                mBgHandler.removeCallbacks(mPendingBoost);
                mPendingBoost = null;
            }
            UiThread.getHandler().post(
                    () -> mGameStateDispatcher.dispatchGameState(false, null));
            mGameStateDispatcher.boostGame(false);
        });
    }

    @Override
    public void onWindowingModeChanged(Task task, int mode) {
        // todo: handle freeform here?
    }

    @Override
    public void onAppFocusChanged(ActivityRecord record, Task task) {
        if (record == null || record.packageName == null) return;

        String packageName = record.packageName;

        mBgHandler.post(() -> {
            boolean gameActive = mCurrentGame != null
                    && mActivityManager.isPackageTopApp(mCurrentGame);

            if (task != null && task.getWindowingMode() == WINDOWING_MODE_FREEFORM && gameActive) {
                if (DEBUG) Slog.d(TAG, "Freeform focused but game still TOP_APP, ignoring.");
                return;
            }

            boolean isGame = mGameListManager.isGame(packageName);
            boolean shouldStartOverlay = false;
            boolean shouldStopOverlay = false;

            if (isGame) {
                if (!packageName.equals(mCurrentGame)) {
                    if (mCurrentGame != null) {
                        shouldStopOverlay = true;
                    }
                    mCurrentGame = packageName;
                    shouldStartOverlay = true;
                }
            } else if (mCurrentGame != null) {
                mCurrentGame = null;
                shouldStopOverlay = true;
            }

            if (shouldStopOverlay) stopOverlay();
            if (shouldStartOverlay) startOverlay();
        });
    }

    @Override
    public void removeTask(Task task, String reason) {
        if (task == null) return;

        mBgHandler.post(() -> {
            ActivityRecord top = task.getTopMostActivity();
            boolean shouldStopOverlay = false;

            if (mCurrentGame != null && top != null
                    && mCurrentGame.equals(top.packageName)) {
                if (DEBUG) Slog.d(TAG, "removeTask: clearing active game " + mCurrentGame);
                mCurrentGame = null;
                shouldStopOverlay = true;
            }

            if (shouldStopOverlay) stopOverlay();
        });
    }

    @Override
    public void setKeyguardDoneLocked(boolean showing) {
        mBgHandler.post(() -> {
            boolean shouldStartOverlay = false;
            boolean shouldStopOverlay = false;

            if (showing && mCurrentGame != null) {
                shouldStopOverlay = true;
            } else if (!showing && mCurrentGame != null) {
                shouldStartOverlay = true;
            }

            if (shouldStopOverlay) stopOverlay();
            if (shouldStartOverlay) startOverlay();
        });
    }

    @Override
    public void registerCallback(IGameSpaceCallback callback) {
        if (callback != null && !mCallbacks.contains(callback)) {
            mCallbacks.add(callback);
        }
    }

    @Override
    public void unregisterCallback(IGameSpaceCallback callback) {
        mCallbacks.remove(callback);
    }
}
