/*
 * SPDX-FileCopyrightText: 2024 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.custom;

import android.app.ActivityThread;
import android.content.Context;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.text.TextUtils;

import com.android.internal.R;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Manager class for handling keybox providers.
 * @hide
 */
public final class KeyProviderManager {
    private static final String TAG = "KeyProviderManager";
    private static final IKeyboxProvider PROVIDER = new DefaultKeyboxProvider();

    public static IKeyboxProvider getProvider() {
        return PROVIDER;
    }

    public static boolean isKeyboxAvailable() {
        return PROVIDER.hasKeybox();
    }

    private static void dlog(String msg) {
        if (SystemProperties.getBoolean("persist.sys.keybox_debug", false)) {
            Log.d(TAG, msg);
        }
    }

    private static class DefaultKeyboxProvider implements IKeyboxProvider {
        private final Map<String, String> keyboxData = new HashMap<>();

        private DefaultKeyboxProvider() {
            try {
                Context context = ActivityThread.currentApplication().getApplicationContext();
                
                if (context == null) return;

                String json = Settings.System.getString(context.getContentResolver(), "custom_keybox_data");

                if (TextUtils.isEmpty(json)) {
                    dlog("No keybox data in Settings.System");
                    return;
                }

                JSONObject keyboxJson = new JSONObject(json);
                Iterator<String> keys = keyboxJson.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    keyboxData.put(key, keyboxJson.getString(key));
                }

                if (!hasKeybox()) {
                    dlog("Incomplete keybox data loaded");
                    logMissingKeys();
                } else {
                    logLoadedKeys();
                }

            } catch (Exception e) {
                dlog("Error retrieving keybox from settings: " + e.getMessage());
            }
        }

        private void logLoadedKeys() {
            dlog("Successfully loaded keybox data:");
            for (String key : Arrays.asList(
                    "EC.PRIV", "EC.CERT_1", "EC.CERT_2", "EC.CERT_3",
                    "RSA.PRIV", "RSA.CERT_1", "RSA.CERT_2", "RSA.CERT_3")) {
                String value = keyboxData.get(key);
                if (value != null) {
                    dlog(key + ": " + value);
                }
            }
        }

        private void logMissingKeys() {
            for (String key : Arrays.asList(
                    "EC.PRIV", "EC.CERT_1", "EC.CERT_2", "EC.CERT_3",
                    "RSA.PRIV", "RSA.CERT_1", "RSA.CERT_2", "RSA.CERT_3")) {
                if (!keyboxData.containsKey(key)) {
                    dlog("Missing key: " + key);
                }
            }
        }

        @Override
        public boolean hasKeybox() {
            return Arrays.asList("EC.PRIV", "EC.CERT_1", "EC.CERT_2", "EC.CERT_3",
                    "RSA.PRIV", "RSA.CERT_1", "RSA.CERT_2", "RSA.CERT_3")
                    .stream()
                    .allMatch(keyboxData::containsKey);
        }

        @Override
        public String getEcPrivateKey() {
            return keyboxData.get("EC.PRIV");
        }

        @Override
        public String getRsaPrivateKey() {
            return keyboxData.get("RSA.PRIV");
        }

        @Override
        public String[] getEcCertificateChain() {
            return getCertificateChain("EC");
        }

        @Override
        public String[] getRsaCertificateChain() {
            return getCertificateChain("RSA");
        }

        private String[] getCertificateChain(String prefix) {
            return new String[]{
                    keyboxData.get(prefix + ".CERT_1"),
                    keyboxData.get(prefix + ".CERT_2"),
                    keyboxData.get(prefix + ".CERT_3")
            };
        }
    }
}
