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

import android.app.ActivityManager.RunningServiceInfo;
import android.content.Context;
import android.os.ServiceManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.server.am.ActivityManagerService;
import com.android.internal.app.IGameSpaceCallback;
import com.android.internal.app.IGameSpaceService;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;

public class GameSpaceService extends IGameSpaceService.Stub implements IWindowEventListener {
    private static final String TAG = "GameSpaceService";
    private static final String GAMESPACE_PACKAGE = "io.chaldeaprjkt.gamespace";
    private static final boolean DEBUG = false;

    private static GameSpaceService sInstance;

    private final Context mContext;
    private final CopyOnWriteArrayList<IGameSpaceCallback> mCallbacks = new CopyOnWriteArrayList<>();
    private final GameListManager mGameListManager;
    private final GameStateDispatcher mGameStateDispatcher;
    private final PackageHandler mPackageHandler;
    private final ActivityManagerService mActivityManager;
    private final ExecutorService mBackgroundExecutor = Executors.newSingleThreadExecutor();

    private String mCurrentGame;
    private final Object mLock = new Object();

    private GameSpaceService(Context context, ActivityManagerService am) {
        this.mContext = context;
        this.mGameListManager = new GameListManager(context);
        this.mGameStateDispatcher = new GameStateDispatcher(context, mCallbacks);
        this.mPackageHandler = new PackageHandler(context, mGameListManager);
        this.mActivityManager = am;
        mGameListManager.registerGameListObserver();
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

    private boolean isServiceActive() {
        List<RunningServiceInfo> services = mActivityManager.getServices(Integer.MAX_VALUE, 0);
        for (RunningServiceInfo info : services) {
            if (info.service.getPackageName().equals(GAMESPACE_PACKAGE)) {
                return true;
            }
        }
        return false;
    }

    private void startOverlay() {
        final String currentGame = mCurrentGame;
        if (currentGame == null) return;

        mBackgroundExecutor.execute(() -> {
            boolean serviceActive = isServiceActive();
            if (!serviceActive) {
                if (DEBUG) Slog.d(TAG, "Service is dead, restarting!");
                mGameStateDispatcher.startService(currentGame);
            }
            if (mGameListManager.isGameInPerfMode(currentGame)) {
                mGameStateDispatcher.boostGame(true);
            }
            mGameStateDispatcher.dispatchGameState(true, currentGame);
        });
    }

    private void stopOverlay() {
        mGameStateDispatcher.dispatchGameState(false, null);
        mGameStateDispatcher.boostGame(false);
    }

    @Override
    public void onWindowingModeChanged(Task task, int mode) {
        // todo: handle freeform here?
    }

    @Override
    public void onAppFocusChanged(ActivityRecord record, Task task) {
        if (record == null || record.packageName == null) return;

        String packageName = record.packageName;
        boolean gameActive = mCurrentGame != null && mActivityManager.isPackageTopApp(mCurrentGame);

        if (task != null && task.getWindowingMode() == WINDOWING_MODE_FREEFORM && gameActive) {
            if (DEBUG) Slog.d(TAG, "Freeform focused but game still TOP_APP, ignoring.");
            return;
        }

        boolean isGame = mGameListManager.isGame(packageName);
        boolean shouldStartOverlay = false;
        boolean shouldStopOverlay = false;

        synchronized (mLock) {
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
        }

        if (shouldStopOverlay) stopOverlay();
        if (shouldStartOverlay) startOverlay();
    }

    @Override
    public void removeTask(Task task, String reason) {
        if (task == null) return;

        ActivityRecord top = task.getTopMostActivity();
        boolean shouldStopOverlay = false;

        synchronized (mLock) {
            if (mCurrentGame != null && top != null && mCurrentGame.equals(top.packageName)) {
                if (DEBUG) Slog.d(TAG, "removeTask: clearing active game " + mCurrentGame);
                mCurrentGame = null;
                shouldStopOverlay = true;
            }
        }

        if (shouldStopOverlay) stopOverlay();
    }

    @Override
    public void setKeyguardDoneLocked(boolean showing) {
        boolean shouldStartOverlay = false;
        boolean shouldStopOverlay = false;

        synchronized (mLock) {
            if (showing && mCurrentGame != null) {
                shouldStopOverlay = true;
            } else if (!showing && mCurrentGame != null) {
                shouldStartOverlay = true;
            }
        }

        if (shouldStopOverlay) stopOverlay();
        if (shouldStartOverlay) startOverlay();
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
