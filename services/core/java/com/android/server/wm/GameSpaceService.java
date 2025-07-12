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
        if (!isServiceActive()) {
            if (DEBUG) Slog.d(TAG, "service is dead, re-starting! isServiceActive: " + isServiceActive());
            mGameStateDispatcher.startService(mCurrentGame);
        }
        mGameStateDispatcher.dispatchGameState(true, mCurrentGame);
    }
    
    @Override
    public void onWindowingModeChanged(Task task, int mode) {
        // todo: handle freeform here?    
    }

    @Override
    public void onAppFocusChanged(ActivityRecord record, Task task) {
        if (record == null || record.packageName == null 
            || task != null && task.getWindowingMode() == WINDOWING_MODE_FREEFORM 
                && mCurrentGame != null) {
            // ignore freeform tasks during app focus changed, if the user switches to another app, 
            // the next focused task will be either launcher or a new fullscreen app
            return;
        }

        String packageName = record.packageName;
        boolean isGame = mGameListManager.isGame(packageName);

        if (DEBUG) Slog.d(TAG, "onFocusAppChanged: " + packageName + " isGame=" + isGame);

        synchronized (mLock) {
            if (isGame && !packageName.equals(mCurrentGame)) {
                mCurrentGame = packageName;
                startOverlay();
            } else if (!isGame && mCurrentGame != null) {
                mCurrentGame = null;
                mGameStateDispatcher.dispatchGameState(false, null);
            }
        }
    }

    @Override
    public void removeTask(Task task, String reason) {
        if (task == null) return;
        ActivityRecord top = task.getTopMostActivity();
        synchronized (mLock) {
            if (mCurrentGame == null) return;
            if (top != null && mCurrentGame.equals(top.packageName)) {
                if (DEBUG) Slog.d(TAG, "removeTask: clearing active game " + mCurrentGame);
                mCurrentGame = null;
                mGameStateDispatcher.dispatchGameState(false, null);
            }
        }
    }

    @Override
    public void setKeyguardDoneLocked(boolean showing) {
        if (DEBUG) Slog.d(TAG, "setKeyguardShowing: " + showing);
        if (showing && mCurrentGame != null) {
            mGameStateDispatcher.dispatchGameState(false, null);
        } else if (mCurrentGame != null && !showing) {
            startOverlay();
        }
    }

    @Override
    public void registerCallback(IGameSpaceCallback callback) {
        if (callback != null && !mCallbacks.contains(callback)) mCallbacks.add(callback);
    }

    @Override
    public void unregisterCallback(IGameSpaceCallback callback) {
        mCallbacks.remove(callback);
    }
}
