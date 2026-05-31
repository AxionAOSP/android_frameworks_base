/*
 * Copyright (C) 2025 AxionOS
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

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;

import com.android.server.NtServiceInjector;

import java.util.function.Consumer;

public class BoostSettingsRepository {

    private final Context mContext;
    private final Handler mHandler;
    private final DeviceData mDeviceData;
    private Consumer<DeviceData.BoostData> mListener;

    public BoostSettingsRepository(DeviceData boostConfig, Handler handler) {
        mContext = NtServiceInjector.getCtx();
        mDeviceData = boostConfig;
        mHandler = handler;
    }

    private static final int MAX = Integer.MAX_VALUE;

    private static final String MIN_FREQ = "axion_min_freq";
    private static final String MIN_FREQ_BIG = "axion_min_freq_big";
    private static final String MIN_FREQ_PRIME = "axion_min_freq_prime";

    private static final String MAX_FREQ = "axion_max_freq";
    private static final String MAX_FREQ_BIG = "axion_max_freq_big";
    private static final String MAX_FREQ_PRIME = "axion_max_freq_prime";
    
    private static final ArrayMap<String, Integer> DEFAULTS = new ArrayMap<>();
    static {
        DEFAULTS.put(MIN_FREQ, 0);
        DEFAULTS.put(MIN_FREQ_BIG, 0);
        DEFAULTS.put(MIN_FREQ_PRIME, 0);
        DEFAULTS.put(MAX_FREQ, MAX);
        DEFAULTS.put(MAX_FREQ_BIG, MAX);
        DEFAULTS.put(MAX_FREQ_PRIME, MAX);
    }

    public DeviceData.BoostData loadDeviceData() {
        int minFreqLittle = getInt(MIN_FREQ);
        int minFreqBig = getInt(MIN_FREQ_BIG);
        int minFreqPrime = getInt(MIN_FREQ_PRIME);

        int maxFreqLittle = getInt(MAX_FREQ);
        int maxFreqBig = getInt(MAX_FREQ_BIG);
        int maxFreqPrime = getInt(MAX_FREQ_PRIME);
        
        mDeviceData.updateSettings(
                minFreqLittle, minFreqBig, minFreqPrime,
                maxFreqLittle, maxFreqBig, maxFreqPrime
        );

        return mDeviceData.getData();
    }

    private int getInt(String key) {
        Integer def = DEFAULTS.get(key);
        return Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                key,
                def != null ? def : 0,
                UserHandle.USER_CURRENT
        );
    }

    private void registerObserver() {
        DEFAULTS.keySet().forEach(key -> {
            Uri uri = Settings.Secure.getUriFor(key);
            if (uri != null) {
                mContext.getContentResolver().registerContentObserver(uri, false, new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        notifyListener();
                    }
                });
            }
        });
    }

    public void setOnSettingsChangeListener(Consumer<DeviceData.BoostData> listener) {
        mListener = listener;
        registerObserver();
        mHandler.post(this::notifyListener);
    }

    private void notifyListener() {
        if (mListener != null) {
            DeviceData.BoostData data = loadDeviceData();
            pushBoostData(data);
            mListener.accept(data);
        }
    }

    static void pushBoostData(DeviceData.BoostData data) {
        String[] paths = new String[] {
            data.sMin, data.bMin, data.pMin,
            data.sMax, data.bMax, data.pMax
        };
        String[] values = new String[] {
            data.uSMin, data.uBMin, data.uPMin,
            data.uSMax, data.uBMax, data.uPMax
        };
        AxBoostManager.native_set_boost_data(paths, values);
    }
}
