/*
 * Copyright 2025-2026 AxionOS
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
package com.android.server.am;

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.util.android.OmniJawsClient;
import com.android.server.IoThread;
import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class OmniJawsClientService extends SystemService {
    private static final String TAG = "OmniJawsClientService";

    private static volatile OmniJawsClientService sInstance;

    private final Object mLock = new Object();
    private final Context mContext;
    private final SparseArray<OmniJawsClient.WeatherInfo> mCachedInfo = new SparseArray<>();
    private final SparseBooleanArray mEnabledUsers = new SparseBooleanArray();
    private final ContentObserver mSettingsObserver;
    private final ContentObserver mWeatherObserver;

    private boolean mWeatherObserverRegistered;

    public static OmniJawsClientService getService() {
        return sInstance;
    }

    public OmniJawsClientService(Context context) {
        super(context);
        mContext = context;
        mSettingsObserver = new ContentObserver(IoThread.getHandler()) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> uris, int flags, int userId) {
                handleSettingsChanged(userId);
            }
        };
        mWeatherObserver = new ContentObserver(IoThread.getHandler()) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> uris, int flags, int userId) {
                handleWeatherChanged(userId);
            }
        };
        sInstance = this;
    }

    @Override
    public void onStart() {
        registerSettingsObserver();
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            refreshUnlockedUsers();
        }
    }

    @Override
    public void onUserUnlocked(@NonNull TargetUser user) {
        refreshUser(user.getUserIdentifier());
    }

    @Override
    public void onUserStopped(@NonNull TargetUser user) {
        updateEnabledUser(user.getUserIdentifier(), false);
    }

    OmniJawsClient.WeatherInfo queryWeather(int userId) {
        boolean enabled = readOmniJawsEnabled(userId);
        updateEnabledUser(userId, enabled);
        if (!enabled) {
            removeCachedInfo(userId);
            return null;
        }
        return refreshWeather(userId);
    }

    OmniJawsClient.WeatherInfo getWeatherInfo(int userId) {
        synchronized (mLock) {
            OmniJawsClient.WeatherInfo info = mCachedInfo.get(userId);
            if (info != null) return info;
        }
        boolean enabled = readOmniJawsEnabled(userId);
        updateEnabledUser(userId, enabled);
        return enabled ? refreshWeather(userId) : null;
    }

    boolean isOmniJawsEnabled(int userId) {
        boolean enabled = readOmniJawsEnabled(userId);
        updateEnabledUser(userId, enabled);
        return enabled;
    }

    boolean isServicePackageUid(int uid) {
        int userId = UserHandle.getUserId(uid);
        try {
            PackageInfo info = mContext.getPackageManager().getPackageInfoAsUser(
                    OmniJawsClient.SERVICE_PACKAGE, 0, userId);
            return info.applicationInfo != null
                    && UserHandle.isSameApp(info.applicationInfo.uid, uid);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean readOmniJawsEnabled(int userId) {
        Context userContext = createUserContext(userId);
        if (!isAvailableApp(userId)) return false;
        try (Cursor cursor = userContext.getContentResolver().query(
                OmniJawsClient.SETTINGS_URI, OmniJawsClient.SETTINGS_PROJECTION, null, null,
                null)) {
            return cursor != null && cursor.moveToFirst() && cursor.getInt(0) == 1;
        } catch (Exception e) {
            Slog.e(TAG, "readOmniJawsEnabled", e);
            return false;
        }
    }

    private OmniJawsClient.WeatherInfo refreshWeather(int userId) {
        OmniJawsClient.WeatherInfo info = readWeather(userId);
        synchronized (mLock) {
            if (info == null) {
                mCachedInfo.remove(userId);
            } else {
                mCachedInfo.put(userId, info);
            }
        }
        return info;
    }

    private void removeCachedInfo(int userId) {
        synchronized (mLock) {
            mCachedInfo.remove(userId);
        }
    }

    private OmniJawsClient.WeatherInfo readWeather(int userId) {
        Context userContext = createUserContext(userId);
        OmniJawsClient.WeatherInfo info = readCurrentWeather(userContext);
        if (info == null) return null;
        readSettings(userContext, info);
        readHourlyForecasts(userContext, info);
        return info;
    }

    private OmniJawsClient.WeatherInfo readCurrentWeather(Context userContext) {
        try (Cursor cursor = userContext.getContentResolver().query(
                OmniJawsClient.WEATHER_URI, OmniJawsClient.WEATHER_PROJECTION, null, null, null)) {
            if (cursor == null || cursor.getCount() == 0) return null;
            OmniJawsClient.WeatherInfo info = new OmniJawsClient.WeatherInfo();
            List<OmniJawsClient.DayForecast> forecasts = new ArrayList<>();
            while (cursor.moveToNext()) {
                if (cursor.getPosition() == 0) {
                    info.city = cursor.getString(0);
                    info.windSpeed = OmniJawsClient.getFormattedValue(getFloat(cursor, 1));
                    info.windDirection = getInt(cursor, 2) + "\u00b0";
                    info.conditionCode = getInt(cursor, 3);
                    info.temp = OmniJawsClient.getFormattedValue(getFloat(cursor, 4));
                    info.humidity = cursor.getString(5);
                    info.condition = cursor.getString(6);
                    info.timeStamp = getLong(cursor, 11);
                    info.pinWheel = cursor.getString(13);
                    info.feelsLike = getFloat(cursor, 14);
                    info.pressure = getFloat(cursor, 15);
                    info.uvi = getFloat(cursor, 16);
                    info.visibility = getFloat(cursor, 17);
                    info.dewPoint = getFloat(cursor, 18);
                    info.sunrise = getLong(cursor, 19);
                    info.sunset = getLong(cursor, 20);
                } else {
                    OmniJawsClient.DayForecast day = new OmniJawsClient.DayForecast();
                    day.low = OmniJawsClient.getFormattedValue(getFloat(cursor, 7));
                    day.high = OmniJawsClient.getFormattedValue(getFloat(cursor, 8));
                    day.condition = cursor.getString(9);
                    day.conditionCode = getInt(cursor, 10);
                    day.date = cursor.getString(12);
                    forecasts.add(day);
                }
            }
            info.forecasts = forecasts;
            return info;
        } catch (Exception e) {
            Slog.e(TAG, "readCurrentWeather", e);
            return null;
        }
    }

    private void readSettings(Context userContext, OmniJawsClient.WeatherInfo info) {
        try (Cursor cursor = userContext.getContentResolver().query(
                OmniJawsClient.SETTINGS_URI, OmniJawsClient.SETTINGS_PROJECTION, null, null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                boolean metric = cursor.getInt(1) == 0;
                info.tempUnits = OmniJawsClient.getTemperatureUnit(metric);
                info.windUnits = OmniJawsClient.getWindUnit(metric);
                info.provider = cursor.getString(2);
                info.iconPack = cursor.getString(4);
            }
        } catch (Exception e) {
            Slog.e(TAG, "readSettings", e);
        }
    }

    private void readHourlyForecasts(Context userContext, OmniJawsClient.WeatherInfo info) {
        List<OmniJawsClient.HourlyForecast> hourlyForecasts = new ArrayList<>();
        try (Cursor cursor = userContext.getContentResolver().query(
                OmniJawsClient.HOURLY_URI, OmniJawsClient.HOURLY_PROJECTION, null, null, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    hourlyForecasts.add(new OmniJawsClient.HourlyForecast(
                            getFloat(cursor, 0), getInt(cursor, 1), cursor.getString(2),
                            getLong(cursor, 3), getFloat(cursor, 4), getFloat(cursor, 5)));
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "readHourlyForecasts", e);
        }
        info.hourlyForecasts = hourlyForecasts;
    }

    private void registerSettingsObserver() {
        mContext.getContentResolver().registerContentObserverAsUser(
                OmniJawsClient.SETTINGS_URI, true, mSettingsObserver, UserHandle.ALL);
    }

    private void updateEnabledUser(int userId, boolean enabled) {
        if (userId < 0) return;
        boolean shouldRegister;
        boolean shouldUnregister;
        synchronized (mLock) {
            if (enabled) {
                mEnabledUsers.put(userId, true);
            } else {
                mEnabledUsers.delete(userId);
                mCachedInfo.remove(userId);
            }
            boolean hasEnabledUsers = mEnabledUsers.size() > 0;
            shouldRegister = hasEnabledUsers && !mWeatherObserverRegistered;
            shouldUnregister = !hasEnabledUsers && mWeatherObserverRegistered;
            if (shouldRegister) mWeatherObserverRegistered = true;
            if (shouldUnregister) mWeatherObserverRegistered = false;
        }
        if (shouldRegister) {
            registerWeatherObserver();
        } else if (shouldUnregister) {
            unregisterWeatherObserver();
        }
    }

    private void registerWeatherObserver() {
        mContext.getContentResolver().registerContentObserverAsUser(
                OmniJawsClient.WEATHER_URI, true, mWeatherObserver, UserHandle.ALL);
    }

    private void unregisterWeatherObserver() {
        mContext.getContentResolver().unregisterContentObserver(mWeatherObserver);
    }

    private void handleSettingsChanged(int userId) {
        if (userId >= 0) {
            refreshUser(userId);
            return;
        }
        refreshUnlockedUsers();
    }

    private void handleWeatherChanged(int userId) {
        if (userId >= 0) {
            boolean enabled = readOmniJawsEnabled(userId);
            updateEnabledUser(userId, enabled);
            if (enabled) {
                refreshWeather(userId);
            }
            return;
        }
        for (int enabledUserId : getEnabledUserIds()) {
            handleWeatherChanged(enabledUserId);
        }
    }

    private void refreshUnlockedUsers() {
        UserManager userManager = UserManager.get(mContext);
        for (UserInfo user : userManager.getAliveUsers()) {
            if (userManager.isUserUnlockingOrUnlocked(user.id)) {
                refreshUser(user.id);
            }
        }
    }

    private void refreshUser(int userId) {
        boolean enabled = readOmniJawsEnabled(userId);
        updateEnabledUser(userId, enabled);
        if (enabled) {
            refreshWeather(userId);
        }
    }

    private int[] getEnabledUserIds() {
        synchronized (mLock) {
            int[] userIds = new int[mEnabledUsers.size()];
            for (int i = 0; i < mEnabledUsers.size(); i++) {
                userIds[i] = mEnabledUsers.keyAt(i);
            }
            return userIds;
        }
    }

    private boolean isAvailableApp(int userId) {
        try {
            PackageInfo info = mContext.getPackageManager().getPackageInfoAsUser(
                    OmniJawsClient.SERVICE_PACKAGE, PackageManager.GET_ACTIVITIES, userId);
            return info.applicationInfo != null && info.applicationInfo.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private Context createUserContext(int userId) {
        return mContext.createContextAsUser(UserHandle.of(userId), 0);
    }

    private static float getFloat(Cursor cursor, int index) {
        if (cursor.isNull(index)) return Float.NaN;
        return cursor.getFloat(index);
    }

    private static int getInt(Cursor cursor, int index) {
        if (cursor.isNull(index)) return 0;
        return cursor.getInt(index);
    }

    private static long getLong(Cursor cursor, int index) {
        if (cursor.isNull(index)) return 0L;
        try {
            return Long.parseLong(cursor.getString(index));
        } catch (NumberFormatException e) {
            return cursor.getLong(index);
        }
    }
}
