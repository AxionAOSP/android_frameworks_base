/*
 * Copyright (C) 2025-2026 AxionOS
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
package com.android.server.thermal;

import android.app.GameManager;
import android.app.IGameManagerService;
import android.app.IGameModeListener;
import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Temperature;
import android.util.Log;

import com.android.internal.os.ProcessCpuTracker;
import com.android.server.AxExtServiceFactory;
import com.android.server.am.AxPerfConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public final class AxAdvancedThermalMitigationProducer {
    private static final String TAG = "AxAdvancedThermalMitigationProducer";
    private static final boolean DBG = false;

    public static final int LEVEL_NONE = 0;
    public static final int LEVEL_MIN = 1;
    public static final int LEVEL_MAX = 14;

    private static final int LEVEL_FOR_LIGHT = 5;
    private static final int LEVEL_FOR_MODERATE = 8;
    private static final int LEVEL_FOR_SEVERE = 11;
    private static final int LEVEL_FOR_CRITICAL = 13;
    private static final int LEVEL_FOR_EMERGENCY = 14;

    private final Context mContext;
    private final HandlerThread mHandlerThread;
    private final Handler mHandler;
    private final AxAdvancedThermalMitigationDispatcher mRegistry;

    private IThermalService mThermalService;
    private IThermalEventListener mSkinListener;
    private IGameManagerService mGameService;
    private IGameModeListener mGameListener;
    private CameraManager mCameraManager;
    private CameraManager.AvailabilityCallback mCameraCallback;
    private final Set<String> mOpenCameras = new HashSet<>();
    private boolean mGameSceneActive = false;
    private boolean mHeatingFgsActive = false;
    private int mTopAppPid = -1;
    private ProcessCpuTracker mCpuTracker;
    private static final long HEATING_SCAN_INTERVAL_MS = 5000L;
    private static final long HEATING_SCAN_DEFER_MS = 1000L;
    private static final int HEATING_CPU_THRESHOLD_PCT = 30;
    private static final int FIRST_APPLICATION_UID = 10000;

    private AxAdvancedThermalMitigationConfig mConfig = AxAdvancedThermalMitigationConfig.EMPTY;
    private int mCurrentLevel = LEVEL_NONE;
    private float mCurrentTemp = 0f;
    private String mCurrentScene = AxAdvancedThermalMitigationConfig.SCENE_DEFAULT;
    private String mCurrentTopApp = null;
    private Map<String, Integer> mLastEmittedActions = Collections.emptyMap();

    public AxAdvancedThermalMitigationProducer(
            Context context, AxAdvancedThermalMitigationDispatcher registry) {
        this.mContext = context;
        this.mRegistry = registry;
        this.mHandlerThread = new HandlerThread(TAG);
        this.mHandlerThread.start();
        this.mHandler = new Handler(mHandlerThread.getLooper());
    }

    public void start() {
        mHandler.post(this::initLocked);
    }

    private void initLocked() {
        mConfig = AxPerfConfig.getAtmc();
        if (mConfig.isEmpty()) {
            Log.i(TAG, "ATMC config empty, producer idle");
            return;
        }
        connectThermalService();
        connectGameService();
        connectCameraService();
        startHeatingFgsScan();
    }

    private void startHeatingFgsScan() {
        mCpuTracker = new ProcessCpuTracker(false);
        mHandler.postDelayed(this::scanHeatingFgs, HEATING_SCAN_INTERVAL_MS);
    }

    private void scanHeatingFgs() {
        if (AxExtServiceFactory.getAxBurstEngine().shouldDeferProcessPss()) {
            mHandler.postDelayed(this::scanHeatingFgs, HEATING_SCAN_DEFER_MS);
            return;
        }
        try {
            mCpuTracker.update();
            int n = mCpuTracker.countWorkingStats();
            boolean heating = false;
            for (int i = 0; i < n; i++) {
                ProcessCpuTracker.Stats st = mCpuTracker.getWorkingStats(i);
                if (st.pid == mTopAppPid) continue;
                if (st.uid < FIRST_APPLICATION_UID) continue;
                long uptime = st.rel_uptime;
                if (uptime <= 0) continue;
                long cpuMs = (long) st.rel_utime + (long) st.rel_stime;
                long pct = (cpuMs * 100) / uptime;
                if (pct >= HEATING_CPU_THRESHOLD_PCT) {
                    heating = true;
                    break;
                }
            }
            if (heating != mHeatingFgsActive) {
                mHeatingFgsActive = heating;
                recomputeScene();
            }
        } catch (Exception e) {
            Log.w(TAG, "scanHeatingFgs failed", e);
        }
        mHandler.postDelayed(this::scanHeatingFgs, HEATING_SCAN_INTERVAL_MS);
    }

    private void recomputeScene() {
        String scene;
        if (mGameSceneActive) {
            scene = AxAdvancedThermalMitigationConfig.SCENE_GAME;
        } else if (mHeatingFgsActive) {
            scene = AxAdvancedThermalMitigationConfig.SCENE_HEATING_FGS;
        } else if (!mOpenCameras.isEmpty()) {
            scene = AxAdvancedThermalMitigationConfig.SCENE_CAMERA_PHOTO;
        } else {
            scene = AxAdvancedThermalMitigationConfig.SCENE_DEFAULT;
        }
        if (!Objects.equals(scene, mCurrentScene)) {
            mCurrentScene = scene;
            evaluateAndEmit();
        }
    }

    private void connectCameraService() {
        if (mCameraManager != null) return;
        mCameraManager = mContext.getSystemService(CameraManager.class);
        if (mCameraManager == null) {
            Log.w(TAG, "CameraManager not available, camera scene observer disabled");
            return;
        }
        mCameraCallback =
                new CameraManager.AvailabilityCallback() {
                    @Override
                    public void onCameraOpened(String cameraId, String packageId) {
                        mHandler.post(
                                () -> {
                                    mOpenCameras.add(cameraId);
                                    updateCameraScene();
                                });
                    }

                    @Override
                    public void onCameraClosed(String cameraId) {
                        mHandler.post(
                                () -> {
                                    mOpenCameras.remove(cameraId);
                                    updateCameraScene();
                                });
                    }
                };
        try {
            mCameraManager.registerAvailabilityCallback(mCameraCallback, mHandler);
            Log.i(TAG, "Camera availability callback registered");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register camera callback", e);
        }
    }

    private void updateCameraScene() {
        recomputeScene();
    }

    private void connectGameService() {
        if (mGameService != null) return;
        mGameService =
                IGameManagerService.Stub.asInterface(
                        ServiceManager.getService(Context.GAME_SERVICE));
        if (mGameService == null) {
            Log.w(TAG, "GameService not available, scene observer disabled");
            return;
        }
        mGameListener =
                new IGameModeListener.Stub() {
                    @Override
                    public void onGameModeChanged(
                            String packageName, int from, int to, int userId) {
                        boolean active =
                                (to == GameManager.GAME_MODE_PERFORMANCE
                                        || to == GameManager.GAME_MODE_STANDARD
                                        || to == GameManager.GAME_MODE_BATTERY
                                        || to == GameManager.GAME_MODE_CUSTOM);
                        mHandler.post(
                                () -> {
                                    mGameSceneActive = active;
                                    recomputeScene();
                                });
                    }
                };
        try {
            mGameService.addGameModeListener(mGameListener);
            Log.i(TAG, "GameMode listener registered");
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register GameMode listener", e);
        }
    }

    private void connectThermalService() {
        if (mThermalService != null) return;
        mThermalService =
                IThermalService.Stub.asInterface(ServiceManager.getService("thermalservice"));
        if (mThermalService == null) {
            Log.e(TAG, "thermalservice not available");
            mHandler.postDelayed(this::connectThermalService, 1000);
            return;
        }
        mSkinListener = new SkinThermalEventListener();
        try {
            boolean ok =
                    mThermalService.registerThermalEventListenerWithType(
                            mSkinListener, Temperature.TYPE_SKIN);
            if (!ok) {
                Log.e(TAG, "Failed to register skin thermal listener");
            } else {
                Log.i(TAG, "Skin thermal listener registered");
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Exception registering skin thermal listener", e);
        }
    }

    public void onTopAppChanged(int pid, String packageName) {
        if (Objects.equals(packageName, mCurrentTopApp)) return;
        mHandler.post(
                () -> {
                    mTopAppPid = pid;
                    mCurrentTopApp = packageName;
                    evaluateAndEmit();
                });
    }

    public void onSceneChanged(String scene) {
        if (Objects.equals(scene, mCurrentScene)) return;
        mHandler.post(
                () -> {
                    mCurrentScene =
                            (scene != null)
                                    ? scene
                                    : AxAdvancedThermalMitigationConfig.SCENE_DEFAULT;
                    evaluateAndEmit();
                });
    }

    public void stop() {
        mHandler.post(
                () -> {
                    if (mThermalService != null && mSkinListener != null) {
                        try {
                            mThermalService.unregisterThermalEventListener(mSkinListener);
                        } catch (RemoteException e) {
                            Log.w(TAG, "Failed to unregister skin thermal listener", e);
                        }
                    }
                    if (mGameService != null && mGameListener != null) {
                        try {
                            mGameService.removeGameModeListener(mGameListener);
                        } catch (RemoteException e) {
                            Log.w(TAG, "Failed to unregister GameMode listener", e);
                        }
                    }
                    if (mCameraManager != null && mCameraCallback != null) {
                        try {
                            mCameraManager.unregisterAvailabilityCallback(mCameraCallback);
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to unregister camera callback", e);
                        }
                    }
                    mHandlerThread.quitSafely();
                });
    }

    public int getCurrentLevel() {
        return mCurrentLevel;
    }

    public float getCurrentTemp() {
        return mCurrentTemp;
    }

    public String getCurrentScene() {
        return mCurrentScene;
    }

    public String getCurrentTopApp() {
        return mCurrentTopApp;
    }

    private final class SkinThermalEventListener extends IThermalEventListener.Stub {
        @Override
        public void notifyThrottling(Temperature temperature) {
            if (temperature == null) return;
            float value = temperature.getValue();
            int status = temperature.getStatus();
            mHandler.post(() -> handleSkinTemperature(value, status));
        }
    }

    private void handleSkinTemperature(float temp, int severity) {
        mCurrentTemp = temp;
        int newLevel = severityToLevel(severity);
        if (newLevel == mCurrentLevel) return;
        if (DBG)
            Log.d(TAG, "level transition: " + mCurrentLevel + " -> " + newLevel + " temp=" + temp);
        mCurrentLevel = newLevel;
        evaluateAndEmit();
    }

    private static int severityToLevel(int severity) {
        switch (severity) {
            case Temperature.THROTTLING_NONE:
                return LEVEL_NONE;
            case Temperature.THROTTLING_LIGHT:
                return LEVEL_FOR_LIGHT;
            case Temperature.THROTTLING_MODERATE:
                return LEVEL_FOR_MODERATE;
            case Temperature.THROTTLING_SEVERE:
                return LEVEL_FOR_SEVERE;
            case Temperature.THROTTLING_CRITICAL:
                return LEVEL_FOR_CRITICAL;
            case Temperature.THROTTLING_EMERGENCY:
                return LEVEL_FOR_EMERGENCY;
            case Temperature.THROTTLING_SHUTDOWN:
                return LEVEL_FOR_EMERGENCY;
            default:
                return LEVEL_NONE;
        }
    }

    private void evaluateAndEmit() {
        Map<String, Integer> resolved = resolveActions();
        if (resolved.equals(mLastEmittedActions)) return;

        Map<String, Integer> resetActions = new HashMap<>();
        for (String unit : mLastEmittedActions.keySet()) {
            if (!resolved.containsKey(unit)) {
                resetActions.put(unit, -1);
            }
        }
        Map<String, Integer> combined = new HashMap<>(resolved);
        combined.putAll(resetActions);

        List<AxAdvancedThermalMitigationInfo> infos = new ArrayList<>(combined.size());
        for (Map.Entry<String, Integer> e : combined.entrySet()) {
            infos.add(new AxAdvancedThermalMitigationInfo(e.getKey(), e.getValue()));
        }

        Bundle extras = new Bundle();
        extras.putInt("thermalLevel", mCurrentLevel);
        extras.putFloat("thermalTemp", mCurrentTemp);
        extras.putString("scene", mCurrentScene);
        if (mCurrentTopApp != null) extras.putString("topApp", mCurrentTopApp);

        if (DBG) Log.d(TAG, "emit infos=" + infos + " extras=" + extras);
        mRegistry.notifyThermalStatusUpdate(infos, extras);

        mLastEmittedActions = resolved;
    }

    private Map<String, Integer> resolveActions() {
        Map<String, Integer> out = new HashMap<>();
        if (mCurrentLevel <= LEVEL_NONE) return out;

        AxAdvancedThermalMitigationConfig.Scene scene = mConfig.getScene(mCurrentScene);
        if (scene == null
                && !AxAdvancedThermalMitigationConfig.SCENE_DEFAULT.equals(mCurrentScene)) {
            scene = mConfig.getScene(AxAdvancedThermalMitigationConfig.SCENE_DEFAULT);
        }
        if (scene != null) applyCumulative(scene.levels, mCurrentLevel, out);

        AxAdvancedThermalMitigationConfig.AppRule app = mConfig.findAppRule(mCurrentTopApp);
        if (app != null) applyCumulative(app.levels, mCurrentLevel, out);

        for (AxAdvancedThermalMitigationConfig.ComplexRule c : mConfig.getComplexes()) {
            if (matchesComplex(c)) {
                applyCumulative(c.levels, mCurrentLevel, out);
            }
        }
        return out;
    }

    private static void applyCumulative(
            TreeMap<Integer, AxAdvancedThermalMitigationConfig.LevelRule> levels,
            int currentLevel,
            Map<String, Integer> out) {
        for (Map.Entry<Integer, AxAdvancedThermalMitigationConfig.LevelRule> e :
                levels.entrySet()) {
            if (e.getKey() > currentLevel) break;
            out.putAll(e.getValue().actions);
        }
    }

    private boolean matchesComplex(AxAdvancedThermalMitigationConfig.ComplexRule c) {
        for (String token : c.tokens) {
            if (!matchesToken(token)) return false;
        }
        return !c.tokens.isEmpty();
    }

    private boolean matchesToken(String token) {
        if (token == null || token.isEmpty()) return false;
        if (token.equals(mCurrentScene)) return true;
        if (token.equals(mCurrentTopApp)) return true;
        return false;
    }
}
