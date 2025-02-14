/*
 * Copyright (C) 2020 The Pixel Experience Project
 *               2025 the AxionAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.android;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.SystemProperties;
import android.util.Log;
import android.text.TextUtils;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PropsHooksUtils {

    private static final String TAG = PropsHooksUtils.class.getSimpleName();
    private static final String PROP_HOOKS = "persist.sys.pihooks_";
    private static final String PROP_HOOKS_MAINLINE = "persist.sys.pihooks_mainline_";
    private static final boolean DEBUG = SystemProperties.getBoolean(PROP_HOOKS + "DEBUG", false);

    public static final String SPOOF_PIXEL_GMS = "persist.sys.pixelprops.gms";
    public static final String SPOOF_PIXEL_GPHOTOS = "persist.sys.pixelprops.gphotos";
    
    private static volatile boolean sIsGms, sIsFinsky, sIsPhotos;

    private static final Map<String, Object> propsToChangePixelXL;
    private static final Map<String, Field> fieldCache = new HashMap<>();
    private static final String[] GMS_SPOOF_KEYS;
    private static final String[] GMS_SPOOF_PROPERTIES;
    private static Boolean isPixelDevice = null;

    private static final Set<String> featuresPixel = new HashSet<>(Set.of(
            "PIXEL_2017_PRELOAD",
            "PIXEL_2018_PRELOAD",
            "PIXEL_2019_MIDYEAR_PRELOAD",
            "PIXEL_2019_PRELOAD",
            "PIXEL_2020_EXPERIENCE",
            "PIXEL_2020_MIDYEAR_EXPERIENCE",
            "PIXEL_EXPERIENCE"
    ));

    private static final Set<String> featuresNexus = new HashSet<>(Set.of(
            "com.google.android.apps.photos.NEXUS_PRELOAD",
            "com.google.android.apps.photos.nexus_preload",
            "com.google.android.feature.PIXEL_EXPERIENCE",
            "com.google.android.feature.GOOGLE_BUILD",
            "com.google.android.feature.GOOGLE_EXPERIENCE"
    ));

    static {
        propsToChangePixelXL = new HashMap<>();
        propsToChangePixelXL.put("BRAND", "google");
        propsToChangePixelXL.put("MANUFACTURER", "Google");
        propsToChangePixelXL.put("DEVICE", "marlin");
        propsToChangePixelXL.put("PRODUCT", "marlin");
        propsToChangePixelXL.put("HARDWARE", "marlin");
        propsToChangePixelXL.put("ID", "QP1A.191005.007.A3");
        propsToChangePixelXL.put("MODEL", "Pixel XL");
        propsToChangePixelXL.put("FINGERPRINT", "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys");

        GMS_SPOOF_KEYS = new String[] {
            "BRAND", "DEVICE", "DEVICE_INITIAL_SDK_INT", "FINGERPRINT", "ID",
            "MANUFACTURER", "MODEL", "PRODUCT", "RELEASE", "SECURITY_PATCH",
            "TAGS", "TYPE"
        };
        
        GMS_SPOOF_PROPERTIES = new String[GMS_SPOOF_KEYS.length];
        for (int i = 0; i < GMS_SPOOF_KEYS.length; i++) {
            GMS_SPOOF_PROPERTIES[i] = PROP_HOOKS + GMS_SPOOF_KEYS[i];
        }
    }

    public static void setProps(Context context) {
        if (context == null) return;
        String packageName = context.getPackageName();

        if (TextUtils.isEmpty(packageName)) {
            return;
        }

        final String processName = Application.getProcessName();
        if (TextUtils.isEmpty(processName)) {
            return;
        }
        
        sIsGms = packageName.equals("com.google.android.gms") 
            && processName.toLowerCase().contains("unstable");
        sIsFinsky = packageName.equals("com.android.vending");
        sIsPhotos = packageName.equals("com.google.android.apps.photos");

        if (shouldSpoofPhotos()) {
            for (Map.Entry<String, Object> entry : propsToChangePixelXL.entrySet()) {
                setPropValue(entry.getKey(), entry.getValue());
            }
        }

        if (packageName.equals("com.google.android.settings.intelligence")) {
            setPropValue("FINGERPRINT", "eng.nobody." +
                new java.text.SimpleDateFormat("yyyyMMdd.HHmmss").format(new java.util.Date()));
        }

        if (sIsGms) {
            setPropValue("TIME", System.currentTimeMillis());
            if (shouldSpoofGMS()) {
                spoofBuildGms();
            }
        }
    }

    private static void setPropValue(String key, Object value) {
        try {
            Field field = getBuildClassField(key);
            if (field == null) {
                Log.e(TAG, "Field " + key + " not found in Build or Build.VERSION classes");
                return;
            }
            
            field.setAccessible(true);
            if (field.getType() == int.class) {
                field.set(null, value instanceof String ? 
                    Integer.parseInt((String) value) : (Integer) value);
            } else if (field.getType() == long.class) {
                field.set(null, value instanceof String ? 
                    Long.parseLong((String) value) : (Long) value);
            } else {
                field.set(null, value.toString());
            }
            field.setAccessible(false);
            dlog("Set prop " + key + " to " + value);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            Log.e(TAG, "Failed to set prop " + key, e);
        }
    }

    private static Field getBuildClassField(String key) {
        Field field = fieldCache.get(key);
        if (field != null) {
            return field;
        }
        
        try {
            field = Build.class.getDeclaredField(key);
            dlog("Field " + key + " found in Build.class");
        } catch (NoSuchFieldException e) {
            try {
                field = Build.VERSION.class.getDeclaredField(key);
                dlog("Field " + key + " found in Build.VERSION.class");
            } catch (NoSuchFieldException ex) {
                Log.e(TAG, "Field " + key + " not found", ex);
                return null;
            }
        }
        
        fieldCache.put(key, field);
        return field;
    }
    
    public static boolean shouldSpoofGMS() {
        return SystemProperties.getBoolean(SPOOF_PIXEL_GMS, true);
    }

    private static void spoofBuildGms() {
        for (int i = 0; i < GMS_SPOOF_KEYS.length; i++) {
            String key = GMS_SPOOF_KEYS[i];
            String prop = GMS_SPOOF_PROPERTIES[i];
            String value = SystemProperties.get(prop);
            if (!TextUtils.isEmpty(value)) {
                setPropValue(key, value);
                if (DEBUG) dlog("Defining " + key + " prop for: " + value);
            } else if (DEBUG) {
                dlog("Skipping empty property for " + key);
            }
        }
    }

    private static boolean isCallerSafetyNet() {
        if (!sIsGms) return false;
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stackTrace) {
            if (element.getClassName().contains("DroidGuard")) {
                return true;
            }
        }
        return false;
    }

    public static void onEngineGetCertificateChain() {
        if (!shouldSpoofGMS()) return;
        if (isCallerSafetyNet() || sIsFinsky) {
            Log.i(TAG, "Blocked key attestation");
            throw new UnsupportedOperationException();
        }
    }

    public static boolean hasSystemFeature(String name, int version, boolean hasSystemFeature) {
        if (shouldSpoofPhotos()) {
            if (!isPixelDevice() && featuresPixel.contains(name)) return false;
            return featuresNexus.contains(name);
        }
        return hasSystemFeature;
    }
    
    private static boolean shouldSpoofPhotos() {
        return sIsPhotos && SystemProperties.getBoolean(SPOOF_PIXEL_GPHOTOS, true);
    }

    private static boolean isPixelDevice() {
        if (isPixelDevice == null) {
            isPixelDevice = SystemProperties.get("ro.soc.manufacturer", "")
                .equalsIgnoreCase("google");
        }
        return isPixelDevice;
    }

    private static void dlog(String msg) {
        if (DEBUG) Log.d(TAG, msg);
    }
}
