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
package com.android.server;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class OnlineConfigObserver {

    public interface ConfigObserver {
        void onConfigChanged(JSONObject config);
    }
    
    private static final String TAG = "OnlineConfigObserver";
    private static final boolean DEBUG = false;

    private static final String CONFIG_URL =
            "https://raw.githubusercontent.com/AxionAOSP/axion_online_configs/refs/heads/lineage-23.0/axion.json";

    private static final long CONFIG_INTERVAL_MS = 60000;

    private static OnlineConfigObserver sInstance;

    private Handler mMainHandler;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    private final Set<ConfigObserver> mObservers = new HashSet<>();
    private JSONObject mLastConfig = null;
    private Context mContext;
    private ConnectivityManager mConnectivityManager;
    private boolean mPendingFetch = false;
    private final AtomicBoolean mIsFetching = new AtomicBoolean(false);

    private OnlineConfigObserver() {
    }

    public static synchronized OnlineConfigObserver get() {
        if (sInstance == null) {
            sInstance = new OnlineConfigObserver();
        }
        return sInstance;
    }
    
    public static void systemReady() {
        get().systemReadyInternal();
    }

    public static void addConfigObserver(ConfigObserver observer) {
        get().addObserverInternal(observer);
    }

    private void addObserverInternal(ConfigObserver observer) {
        synchronized (mObservers) {
            mObservers.add(observer);
            if (mLastConfig != null) {
                final JSONObject config = mLastConfig;
                mMainHandler.post(() -> observer.onConfigChanged(config));
            }
        }
    }

    private void notifyObservers(JSONObject config) {
        mMainHandler.post(() -> {
            synchronized (mObservers) {
                for (ConfigObserver observer : mObservers) {
                    try {
                        observer.onConfigChanged(config);
                    } catch (Exception e) {
                        Log.e(TAG, "Error notifying observer", e);
                    }
                }
            }
        });
    }

    private boolean isInternetConnected() {
        Network network = mConnectivityManager.getActiveNetwork();
        if (network != null) {
            NetworkCapabilities capabilities = 
                    mConnectivityManager.getNetworkCapabilities(network);
            return capabilities != null && 
                   capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                   capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        }
        return false;
    }

    private void registerNetworkCallback() {
        mConnectivityManager.registerDefaultNetworkCallback(
                new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                logger("Network available, attempting pending fetch");
                if (mPendingFetch && !mIsFetching.get()) {
                    mPendingFetch = false;
                    mBackgroundHandler.post(() -> fetchConfig());
                }
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    logger("Network validated, attempting pending fetch");
                    if (mPendingFetch && !mIsFetching.get()) {
                        mPendingFetch = false;
                        mBackgroundHandler.post(() -> fetchConfig());
                    }
                }
            }
        });
    }

    private void fetchConfig() {
        if (mIsFetching.getAndSet(true)) {
            logger("Fetch already in progress, skipping");
            return;
        }

        logger("Starting config fetch…");

        if (!isInternetConnected()) {
            Log.w(TAG, "Network not available, deferring fetch");
            mPendingFetch = true;
            mIsFetching.set(false);
            return;
        }

        HttpURLConnection conn = null;
        InputStream is = null;
        Scanner scanner = null;

        try {
            logger("Connecting to URL: " + CONFIG_URL);
            URL url = new URL(CONFIG_URL);

            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "OnlineConfigObserver/1.0");

            int responseCode = conn.getResponseCode();
            logger("HTTP response code: " + responseCode);

            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Bad HTTP response: " + responseCode);
                return;
            }

            is = conn.getInputStream();
            scanner = new Scanner(is, "UTF-8").useDelimiter("\\A");
            String jsonString = scanner.hasNext() ? scanner.next() : "";

            logger("Fetched config JSON length: " + jsonString.length());

            if (jsonString.isEmpty()) {
                Log.e(TAG, "Empty response received");
                return;
            }

            JSONObject newConfig = new JSONObject(jsonString);

            if (!isSameConfig(mLastConfig, newConfig)) {
                Log.i(TAG, "Config changed — updating");
                mLastConfig = newConfig;
                notifyObservers(newConfig);
            } else {
                logger("Config unchanged");
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch config", e);
            mPendingFetch = true;

        } finally {
            mIsFetching.set(false);
            logger("Releasing network resources");
            if (scanner != null) {
                try { scanner.close(); } catch (Exception ignored) {}
            }
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private boolean isSameConfig(JSONObject oldConfig, JSONObject newConfig) {
        return oldConfig != null && Objects.equals(oldConfig.toString(), newConfig.toString());
    }

    private void logger(String message) {
        if (DEBUG) Log.d(TAG, message);
    }

    private void systemReadyInternal() {
        logger("System ready, initializing handlers");
        
        mContext = NtServiceInjector.getCtx();
        mMainHandler = new Handler(Looper.getMainLooper());
        
        mBackgroundThread = new HandlerThread("OnlineConfigObserver");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        
        mConnectivityManager = 
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);

        registerNetworkCallback();
        
        mBackgroundHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                fetchConfig();
                mBackgroundHandler.postDelayed(this, CONFIG_INTERVAL_MS);
            }
        }, CONFIG_INTERVAL_MS);
    }
}
