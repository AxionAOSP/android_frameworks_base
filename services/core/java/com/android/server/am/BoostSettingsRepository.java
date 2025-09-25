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

import com.android.server.NtServiceInjector;

import java.util.Map;
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
    private static final int DEF = 1000000;

    private static final String ACB = "axion_cpu_boost";
    private static final String ABCB = "axion_big_core_boost";
    private static final String APCB = "axion_prime_core_boost";
    private static final String ASFB = "axion_sf_boost";
    private static final String ATB = "axion_touch_boost";

    private static final String MIN_FREQ_BOOST = "axion_min_freq_boost";
    private static final String MIN_FREQ_BIG_BOOST = "axion_min_freq_big_boost";
    private static final String MIN_FREQ_PRIME_BOOST = "axion_min_freq_prime_boost";

    private static final String MIN_FREQ = "axion_min_freq";
    private static final String MIN_FREQ_BIG = "axion_min_freq_big";
    private static final String MIN_FREQ_PRIME = "axion_min_freq_prime";

    private static final String MAX_FREQ = "axion_max_freq";
    private static final String MAX_FREQ_BIG = "axion_max_freq_big";
    private static final String MAX_FREQ_PRIME = "axion_max_freq_prime";
    
    private static final String GAME_GPU_BOOST = "axion_game_gpu_boost_level";
    private static final String SYS_GPU_BOOST = "axion_sys_gpu_boost_level";

    private static final Map<String, Number> DEFAULTS = Map.ofEntries(
            Map.entry(ACB, 1),
            Map.entry(ABCB, 0),
            Map.entry(APCB, 0),
            Map.entry(ASFB, 1),
            Map.entry(ATB, 0),
            Map.entry(MIN_FREQ_BOOST, DEF),
            Map.entry(MIN_FREQ_BIG_BOOST, DEF),
            Map.entry(MIN_FREQ_PRIME_BOOST, DEF),
            Map.entry(MIN_FREQ, 0),
            Map.entry(MIN_FREQ_BIG, 0),
            Map.entry(MIN_FREQ_PRIME, 0),
            Map.entry(MAX_FREQ, MAX),
            Map.entry(MAX_FREQ_BIG, MAX),
            Map.entry(MAX_FREQ_PRIME, MAX),
            Map.entry(GAME_GPU_BOOST, 1),
            Map.entry(SYS_GPU_BOOST, 1)
    );

    public DeviceData.BoostData loadDeviceData() {
        boolean cpuBoost = getInt(ACB) == 1;
        boolean bigCoreBoost = getInt(ABCB) == 1;
        boolean primeCoreBoost = getInt(APCB) == 1;
        boolean sfBoost = getInt(ASFB) == 1;
        boolean inputBoost = getInt(ATB) == 1;

        int minFreqBoostLittle = getInt(MIN_FREQ_BOOST);
        int minFreqBoostBig = getInt(MIN_FREQ_BIG_BOOST);
        int minFreqBoostPrime = getInt(MIN_FREQ_PRIME_BOOST);

        int minFreqLittle = getInt(MIN_FREQ);
        int minFreqBig = getInt(MIN_FREQ_BIG);
        int minFreqPrime = getInt(MIN_FREQ_PRIME);

        int maxFreqLittle = getInt(MAX_FREQ);
        int maxFreqBig = getInt(MAX_FREQ_BIG);
        int maxFreqPrime = getInt(MAX_FREQ_PRIME);
        
        int gameGpuLvlBoost = getInt(GAME_GPU_BOOST);
        int sysGpuLvlBoost = getInt(SYS_GPU_BOOST);

        mDeviceData.updateSettings(
                cpuBoost, bigCoreBoost, primeCoreBoost, sfBoost, inputBoost,
                minFreqBoostLittle, minFreqBoostBig, minFreqBoostPrime,
                minFreqLittle, minFreqBig, minFreqPrime,
                maxFreqLittle, maxFreqBig, maxFreqPrime,
                gameGpuLvlBoost, sysGpuLvlBoost
        );

        return mDeviceData.getData();
    }

    private int getInt(String key) {
        Number def = DEFAULTS.get(key);
        return Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                key,
                def != null ? def.intValue() : 0,
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
        notifyListener();
    }

    private void notifyListener() {
        if (mListener != null) {
            mListener.accept(loadDeviceData());
        }
    }
}
