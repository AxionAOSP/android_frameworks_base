/*
 * Copyright (C) 2025 Axion OS
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

package com.android.server.theme;

import static android.content.res.ThemeEngine.*;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.IThemeEngineCallback;
import android.content.res.IThemeEngineManager;
import android.content.res.Resources;
import android.content.res.ThemeEngine;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LruCache;
import android.util.Slog;

import com.android.server.NtServiceInjector;
import com.android.server.SystemService;
import com.android.server.am.ActivityManagerService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @hide
 */
public class ThemeEngineManagerService extends SystemService {
    private static final String TAG = "ThemeEngineManagerService";
    private static final boolean DEBUG = false;
    
    private static final String TARGET_ARRAY_SYSTEMUI = "target_systemui";
    private static final String TARGET_ARRAY_ANDROID = "target_android";
    private static final String TARGET_ARRAY_SETTINGS = "target_settings";
    
    private static final int BITMAP_CACHE_SIZE = 5 * 1024 * 1024;
    
    private static final String[] BUILTIN_WIFI_TARGETS = {
        "ic_wifi_signal_0", "ic_wifi_signal_1", "ic_wifi_signal_2", 
        "ic_wifi_signal_3", "ic_wifi_signal_4",
        "ic_wifi_0", "ic_wifi_1", "ic_wifi_2", "ic_wifi_3",
        "ic_wifi_0_error", "ic_wifi_1_error", "ic_wifi_2_error", "ic_wifi_3_error",
        "ic_no_internet_wifi_signal_0", "ic_no_internet_wifi_signal_1", 
        "ic_no_internet_wifi_signal_2", "ic_no_internet_wifi_signal_3", 
        "ic_no_internet_wifi_signal_4",
        "ic_show_x_wifi_signal_0", "ic_show_x_wifi_signal_1",
        "ic_show_x_wifi_signal_2", "ic_show_x_wifi_signal_3", 
        "ic_show_x_wifi_signal_4",
        "ic_wifi_call_strength_0", "ic_wifi_call_strength_1",
        "ic_wifi_call_strength_2", "ic_wifi_call_strength_3", 
        "ic_wifi_call_strength_4"
    };
    
    private static final String[] BUILTIN_SIGNAL_TARGETS = {
        "ic_signal_0", "ic_signal_1", "ic_signal_2", 
        "ic_signal_3", "ic_signal_4", "ic_signal_5",
        "stat_sys_signal_0", "stat_sys_signal_1", "stat_sys_signal_2",
        "stat_sys_signal_3", "stat_sys_signal_4"
    };

    private static final Map<String, String> ICON_NAME_FALLBACK = new HashMap<>();
    static {
        ICON_NAME_FALLBACK.put("ic_wifi_0", "ic_wifi_signal_1");
        ICON_NAME_FALLBACK.put("ic_wifi_1", "ic_wifi_signal_2");
        ICON_NAME_FALLBACK.put("ic_wifi_2", "ic_wifi_signal_3");
        ICON_NAME_FALLBACK.put("ic_wifi_3", "ic_wifi_signal_4");
        ICON_NAME_FALLBACK.put("ic_wifi_0_error", "ic_no_internet_wifi_signal_1");
        ICON_NAME_FALLBACK.put("ic_wifi_1_error", "ic_no_internet_wifi_signal_2");
        ICON_NAME_FALLBACK.put("ic_wifi_2_error", "ic_no_internet_wifi_signal_3");
        ICON_NAME_FALLBACK.put("ic_wifi_3_error", "ic_no_internet_wifi_signal_4");
        
        ICON_NAME_FALLBACK.put("ic_signal_cellular_0_5_bar", "ic_signal_0");
        ICON_NAME_FALLBACK.put("ic_signal_cellular_1_5_bar", "ic_signal_1");
        ICON_NAME_FALLBACK.put("ic_signal_cellular_2_5_bar", "ic_signal_2");
        ICON_NAME_FALLBACK.put("ic_signal_cellular_3_5_bar", "ic_signal_3");
        ICON_NAME_FALLBACK.put("ic_signal_cellular_4_5_bar", "ic_signal_4");
        ICON_NAME_FALLBACK.put("ic_signal_cellular_5_5_bar", "ic_signal_5");
        
        ICON_NAME_FALLBACK.put("ic_signal_cellular_0_4_bar", "ic_signal_0");
        ICON_NAME_FALLBACK.put("ic_signal_cellular_1_4_bar", "ic_signal_1");
        ICON_NAME_FALLBACK.put("ic_signal_cellular_2_4_bar", "ic_signal_2");
        ICON_NAME_FALLBACK.put("ic_signal_cellular_3_4_bar", "ic_signal_3");
        ICON_NAME_FALLBACK.put("ic_signal_cellular_4_4_bar", "ic_signal_4");
        
        ICON_NAME_FALLBACK.put("ic_mobile_signal_strength_0", "ic_signal_0");
        ICON_NAME_FALLBACK.put("ic_mobile_signal_strength_1", "ic_signal_1");
        ICON_NAME_FALLBACK.put("ic_mobile_signal_strength_2", "ic_signal_2");
        ICON_NAME_FALLBACK.put("ic_mobile_signal_strength_3", "ic_signal_3");
        ICON_NAME_FALLBACK.put("ic_mobile_signal_strength_4", "ic_signal_4");
        ICON_NAME_FALLBACK.put("ic_mobile_signal_strength_5", "ic_signal_5");
        
        ICON_NAME_FALLBACK.put("ic_mobile_0_5_bar", "ic_signal_0");
        ICON_NAME_FALLBACK.put("ic_mobile_1_5_bar", "ic_signal_1");
        ICON_NAME_FALLBACK.put("ic_mobile_2_5_bar", "ic_signal_2");
        ICON_NAME_FALLBACK.put("ic_mobile_3_5_bar", "ic_signal_3");
        ICON_NAME_FALLBACK.put("ic_mobile_4_5_bar", "ic_signal_4");
        ICON_NAME_FALLBACK.put("ic_mobile_5_5_bar", "ic_signal_5");
        
        ICON_NAME_FALLBACK.put("ic_mobile_0_5_bar_error", "ic_signal_0");
        ICON_NAME_FALLBACK.put("ic_mobile_1_5_bar_error", "ic_signal_1");
        ICON_NAME_FALLBACK.put("ic_mobile_2_5_bar_error", "ic_signal_2");
        ICON_NAME_FALLBACK.put("ic_mobile_3_5_bar_error", "ic_signal_3");
        ICON_NAME_FALLBACK.put("ic_mobile_4_5_bar_error", "ic_signal_4");
        ICON_NAME_FALLBACK.put("ic_mobile_5_5_bar_error", "ic_signal_5");
    
        ICON_NAME_FALLBACK.put("stat_sys_signal_0", "ic_signal_0");
        ICON_NAME_FALLBACK.put("stat_sys_signal_1", "ic_signal_1");
        ICON_NAME_FALLBACK.put("stat_sys_signal_2", "ic_signal_2");
        ICON_NAME_FALLBACK.put("stat_sys_signal_3", "ic_signal_3");
        ICON_NAME_FALLBACK.put("stat_sys_signal_4", "ic_signal_4");
    }
    
    
    private final Context mContext;
    private final BinderService mBinderService;
    private final Handler mHandler;
    
    private final Map<String, String> mEnabledThemes = new ConcurrentHashMap<>();
    
    private final Map<String, String> mStyleIds = new ConcurrentHashMap<>();
    
    private final Map<String, Resources> mThemeResourcesCache = new ConcurrentHashMap<>();
    
    private final Map<String, Set<String>> mTargetArrayCache = new ConcurrentHashMap<>();
    
    private final Map<String, String> mResourceCategoryCache = new ConcurrentHashMap<>();
    
    private volatile String mActiveIconTheme = null;
    
    private final Map<String, String> mCategoryThemes = new ConcurrentHashMap<>();
    
    private final List<String> mIconThemeTargets = new CopyOnWriteArrayList<>();
    
    private volatile String mIconShape = SHAPE_PATH_CIRCLE;
    
    private volatile String mIconPackPackage = null;
    
    private final Map<ComponentName, String> mIconPackMap = new ConcurrentHashMap<>();
    
    private final List<String> mIconBackList = new CopyOnWriteArrayList<>();
    private final List<String> mIconMaskList = new CopyOnWriteArrayList<>();
    private volatile float mIconScale = 1.0f;
    
    private final LruCache<String, Bitmap> mBitmapCache;
    
    private volatile String mLastConfigJson = null;

    private ContentObserver mSettingsObserver;
    
    private final RemoteCallbackList<IThemeEngineCallback> mCallbacks = 
            new RemoteCallbackList<>();
    
    public ThemeEngineManagerService(Context context) {
        super(context);
        mContext = context;
        mBinderService = new BinderService();
        mHandler = new Handler(Looper.getMainLooper());
        
        mBitmapCache = new LruCache<String, Bitmap>(BITMAP_CACHE_SIZE) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount();
            }
        };
    }
    
    @Override
    public void onStart() {
        publishBinderService(Context.THEME_ENGINE_SERVICE, mBinderService);
        Slog.i(TAG, "ThemeEngineManagerService started");
    }
    
    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            mSettingsObserver = new ContentObserver(mHandler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    Slog.d(TAG, "Theme config changed via Settings, notifying...");
                    notifyThemeChangedInternal(null);
                }
            };
            
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(SETTINGS_THEME_ENGINE_DATA),
                    false,
                    mSettingsObserver,
                    UserHandle.USER_ALL
            );
            
            loadThemeConfig();
            
            Slog.i(TAG, "ThemeEngineManagerService ready");
        }
    }
    
    private synchronized void loadThemeConfig() {
        mEnabledThemes.clear();
        mStyleIds.clear();
        mThemeResourcesCache.clear();
        mTargetArrayCache.clear();
        mResourceCategoryCache.clear();
        mActiveIconTheme = null;
        mIconThemeTargets.clear();
        mCategoryThemes.clear();
        mBitmapCache.evictAll();
        
        initializeBuiltinTargets();
        
        try {
            String json = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    SETTINGS_THEME_ENGINE_DATA,
                    UserHandle.USER_CURRENT
            );
            
            Slog.i(TAG, "Loading theme config, raw JSON: " + (json != null ? json : "null"));
            
            if (json == null || json.isEmpty()) {
                Slog.d(TAG, "No theme config found");
                mLastConfigJson = "";
                return;
            }
            
            JSONObject config = new JSONObject(json);
            JSONObject themes = config.optJSONObject("themes");
            
            if (themes != null) {
                Iterator<String> keys = themes.keys();
                while (keys.hasNext()) {
                    String category = keys.next();
                    JSONObject categoryConfig = themes.getJSONObject(category);
                    
                    boolean enabled = categoryConfig.optBoolean("enabled", false);
                    String packageName = categoryConfig.optString("packageName", null);
                    String styleId = categoryConfig.optString("styleId", null);
                    
                    if (enabled && packageName != null && !packageName.isEmpty()) {
                        mEnabledThemes.put(category, packageName);
                        Slog.d(TAG, "Theme enabled: " + category + " -> " + packageName);
                    }
                    
                    if (enabled && styleId != null && !styleId.isEmpty()) {
                        mStyleIds.put(category, styleId);
                        Slog.d(TAG, "Style enabled: " + category + " -> " + styleId);
                    }
                }
            }
            
            String iconTheme = config.optString("iconTheme", null);
            if (iconTheme != null && !iconTheme.isEmpty()) {
                mActiveIconTheme = iconTheme;
                loadTargetArrays(iconTheme);
                Slog.d(TAG, "Icon theme loaded: " + iconTheme);
            }
            
            JSONArray iconThemeTargets = config.optJSONArray("iconThemeTargets");
            if (iconThemeTargets != null) {
                for (int i = 0; i < iconThemeTargets.length(); i++) {
                    String target = iconThemeTargets.optString(i);
                    if (target != null && !target.isEmpty()) {
                        mIconThemeTargets.add(target);
                    }
                }
                Slog.d(TAG, "Icon theme targets: " + mIconThemeTargets);
            }
            
            JSONObject categoryThemes = config.optJSONObject("categoryThemes");
            if (categoryThemes != null) {
                Iterator<String> catKeys = categoryThemes.keys();
                while (catKeys.hasNext()) {
                    String category = catKeys.next();
                    String pkgName = categoryThemes.optString(category);
                    boolean isCategoryEnabled = mEnabledThemes.containsKey(category) 
                            || mIconThemeTargets.contains(category);
                    if (pkgName != null && !pkgName.isEmpty() && isCategoryEnabled) {
                        mCategoryThemes.put(category, pkgName);
                        loadTargetArrays(pkgName);
                        Slog.d(TAG, "Category theme: " + category + " -> " + pkgName);
                    } else if (pkgName != null && !pkgName.isEmpty()) {
                        Slog.d(TAG, "Skipping disabled category theme: " + category + " -> " + pkgName);
                    }
                }
            }
            
            String iconShape = config.optString("iconShape", null);
            if (iconShape != null && !iconShape.isEmpty()) {
                mIconShape = iconShape;
                Slog.d(TAG, "Icon shape: " + iconShape);
            } else {
                mIconShape = SHAPE_SQUIRCLE;
            }
            
            mLastConfigJson = json;
            
            String iconPackPkg = mEnabledThemes.get(CATEGORY_ICON_PACK);
            Slog.i(TAG, "Icon pack from config: " + iconPackPkg + ", current: " + mIconPackPackage);
            if (iconPackPkg != null && (!iconPackPkg.equals(mIconPackPackage) || mIconPackMap.isEmpty())) {
                loadIconPack(iconPackPkg);
            } else if (iconPackPkg == null && mIconPackPackage != null) {
                Slog.i(TAG, "Clearing icon pack");
                mIconPackPackage = null;
                mIconPackMap.clear();
                mIconBackList.clear();
                mIconMaskList.clear();
            }
            
        } catch (JSONException e) {
            Slog.e(TAG, "Failed to parse theme config", e);
            mLastConfigJson = "";
        }
    }
    
    private void initializeBuiltinTargets() {
        for (String target : BUILTIN_WIFI_TARGETS) {
            mResourceCategoryCache.put(target, "wifi");
        }
        
        for (String target : BUILTIN_SIGNAL_TARGETS) {
            mResourceCategoryCache.put(target, "signal");
        }
        
        Slog.d(TAG, "Initialized built-in targets: wifi=" + BUILTIN_WIFI_TARGETS.length 
                + ", signal=" + BUILTIN_SIGNAL_TARGETS.length);
    }
    
    private void loadTargetArrays(@NonNull String packageName) {
        Resources themeResources = getThemeResources(packageName);
        if (themeResources == null) {
            return;
        }
        
        Set<String> allTargets = new HashSet<>();
        
        Map<String, String> arrayCategoryMap = new HashMap<>();
        arrayCategoryMap.put(TARGET_ARRAY_SYSTEMUI, CATEGORY_SYSTEMUI);
        arrayCategoryMap.put(TARGET_ARRAY_ANDROID, CATEGORY_ANDROID);
        arrayCategoryMap.put(TARGET_ARRAY_SETTINGS, "settings");
        arrayCategoryMap.put("target_wifi", CATEGORY_STATUSBAR_WIFI);
        arrayCategoryMap.put("target_signal", CATEGORY_STATUSBAR_SIGNAL);
        arrayCategoryMap.put("target_systemui_icons", CATEGORY_SYSTEMUI);
        
        for (Map.Entry<String, String> entry : arrayCategoryMap.entrySet()) {
            String arrayName = entry.getKey();
            String category = entry.getValue();
            
            int arrayId = themeResources.getIdentifier(arrayName, "array", packageName);
            if (arrayId != 0) {
                try {
                    String[] targets = themeResources.getStringArray(arrayId);
                    for (String target : targets) {
                        allTargets.add(target);
                        mResourceCategoryCache.put(target, category);
                    }
                    Slog.d(TAG, "Loaded " + targets.length + " targets from " + arrayName);
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to load target array: " + arrayName, e);
                }
            }
        }
        
        mTargetArrayCache.put(packageName, allTargets);
    }
    
    private void loadIconPack(String packageName) {
        mIconPackPackage = packageName;
        mIconPackMap.clear();
        mIconBackList.clear();
        mIconMaskList.clear();
        mIconScale = 1.0f;
        
        Resources res = getThemeResources(packageName);
        if (res == null) {
            Slog.w(TAG, "Failed to load icon pack resources: " + packageName);
            return;
        }
        
        int resId = res.getIdentifier("appfilter", "xml", packageName);
        if (resId == 0) {
            Slog.w(TAG, "Icon pack has no appfilter.xml: " + packageName);
            return;
        }
        
        try {
            XmlPullParser parser = res.getXml(resId);
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                String tagName = parser.getName();
                if (eventType == XmlPullParser.START_TAG) {
                    if ("item".equals(tagName)) {
                        String component = parser.getAttributeValue(null, "component");
                        String drawable = parser.getAttributeValue(null, "drawable");
                        if (component != null && drawable != null) {
                            ComponentName cn = parseComponentInfo(component);
                            if (cn != null) {
                                mIconPackMap.put(cn, drawable);
                            }
                        }
                    } else if ("iconback".equals(tagName)) {
                        for (int i = 0; i < parser.getAttributeCount(); i++) {
                            String val = parser.getAttributeValue(i);
                            if (val != null && !val.isEmpty()) mIconBackList.add(val);
                        }
                    } else if ("iconmask".equals(tagName)) {
                        for (int i = 0; i < parser.getAttributeCount(); i++) {
                            String val = parser.getAttributeValue(i);
                            if (val != null && !val.isEmpty()) mIconMaskList.add(val);
                        }
                    } else if ("scale".equals(tagName)) {
                        String factor = parser.getAttributeValue(null, "factor");
                        if (factor != null) {
                            try {
                                mIconScale = Float.parseFloat(factor);
                            } catch (NumberFormatException e) {
                            }
                        }
                    }
                }
                eventType = parser.next();
            }
            Slog.d(TAG, "Loaded icon pack: " + mIconPackMap.size() + " icons, " 
                    + mIconBackList.size() + " iconbacks from " + packageName);
        } catch (XmlPullParserException | IOException e) {
            Slog.w(TAG, "Failed to parse appfilter.xml for " + packageName, e);
        }
    }
    
    @Nullable
    private ComponentName parseComponentInfo(String component) {
        if (!component.startsWith("ComponentInfo{") || !component.endsWith("}")) {
            return null;
        }
        String content = component.substring(14, component.length() - 1);
        String[] parts = content.split("/");
        if (parts.length != 2) return null;
        return new ComponentName(parts[0], parts[1]);
    }
    
    @Nullable
    private Resources getThemeResources(@NonNull String packageName) {
        Resources cached = mThemeResourcesCache.get(packageName);
        if (cached != null) {
            return cached;
        }
        
        try {
            Context themeContext = mContext.createPackageContext(
                    packageName,
                    Context.CONTEXT_IGNORE_SECURITY
            );
            Resources resources = themeContext.getResources();
            mThemeResourcesCache.put(packageName, resources);
            return resources;
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Theme package not found: " + packageName);
            return null;
        }
    }
    
    @Nullable
    private String getThemePackageForResource(@NonNull String resourceName) {
        String category = mResourceCategoryCache.get(resourceName);
        
        if (category != null && mCategoryThemes.containsKey(category)) {
            return mCategoryThemes.get(category);
        }
        
        if (category != null) {
            String aliasCategory = "statusbar_" + category;
            if (mCategoryThemes.containsKey(aliasCategory)) {
                return mCategoryThemes.get(aliasCategory);
            }
        }
        
        if (mActiveIconTheme != null) {
            if (category == null || mIconThemeTargets.isEmpty() || mIconThemeTargets.contains(category)) {
                return mActiveIconTheme;
            }
            if (category != null && mIconThemeTargets.contains("statusbar_" + category)) {
                return mActiveIconTheme;
            }
        }
        
        return null;
    }
    
    @NonNull
    private Bitmap drawableToBitmap(@NonNull Drawable drawable, int density) {
        int size = density > 0 ? density : 192; 
        
        if (drawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            if (bitmap != null) {
                return bitmap;
            }
        }
        
        if (drawable instanceof AdaptiveIconDrawable) {
            size = Math.max(size, 108);
        }
        
        int width = drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : size;
        int height = drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : size;
        
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        
        return bitmap;
    }
    
    private void notifyThemeChangedInternal(@Nullable String category) {
        final long ident = Binder.clearCallingIdentity();
        try {
            loadThemeConfig();

            int count = mCallbacks.beginBroadcast();
            for (int i = 0; i < count; i++) {
                try {
                    mCallbacks.getBroadcastItem(i).onThemeChanged(category);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to notify theme callback", e);
                }
            }
            mCallbacks.finishBroadcast();
            
            Slog.d(TAG, "Theme change processed, notified " + count + " callbacks, category=" + category);
            
            if (category == null || CATEGORY_ICON_PACK.equals(category)) {
                Intent intent = new Intent(ThemeEngine.ACTION_THEME_CHANGED);
                intent.putExtra(ThemeEngine.EXTRA_THEME_CATEGORY, CATEGORY_ICON_PACK);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
                Slog.d(TAG, "Sent icon pack change broadcast");
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }
    
    @NonNull
    private String getIconShapePathInternal() {
        String shape = mIconShape != null ? mIconShape : SHAPE_SQUIRCLE;
        switch (shape) {
            case SHAPE_CIRCLE:
                return SHAPE_PATH_CIRCLE;
            case SHAPE_ROUNDED_RECT:
                return SHAPE_PATH_ROUNDED_RECT;
            case SHAPE_TEARDROP:
                return SHAPE_PATH_TEARDROP;
            case SHAPE_CYLINDER:
                return SHAPE_PATH_CYLINDER;
            case SHAPE_HEXAGON:
                return SHAPE_PATH_HEXAGON;
            case SHAPE_SQUIRCLE:
            default:
                return SHAPE_PATH_CIRCLE;
        }
    }
    
    private final class BinderService extends IThemeEngineManager.Stub {
        
        @Override
        public Bitmap getIconPackIcon(ComponentName component, int density) {
            if (component == null) {
                if (DEBUG) Slog.v(TAG, "getIconPackIcon: component is null");
                return null;
            }
            if (mIconPackPackage == null) {
                if (DEBUG) Slog.v(TAG, "getIconPackIcon: no icon pack active");
                return null;
            }
            if (DEBUG) {
                Slog.d(TAG, "getIconPackIcon: looking up " + component.flattenToShortString() 
                        + ", pack=" + mIconPackPackage + ", mapSize=" + mIconPackMap.size());
            }
            
            String cacheKey = "iconpack:" + component.flattenToShortString() + ":" + density;
            Bitmap cached = mBitmapCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            
            String drawableName = mIconPackMap.get(component);
            if (drawableName == null) {
                if (DEBUG) {
                    Slog.v(TAG, "No icon pack mapping for: " + component.flattenToShortString());
                }
                return null;
            }
            
            Resources res = getThemeResources(mIconPackPackage);
            if (res == null) {
                return null;
            }
            
            try {
                int resId = res.getIdentifier(drawableName, "drawable", mIconPackPackage);
                if (resId != 0) {
                    Drawable drawable = res.getDrawableInternal(resId);
                    if (drawable != null) {
                        Bitmap bitmap = drawableToBitmap(drawable, density);
                        mBitmapCache.put(cacheKey, bitmap);
                        
                        if (DEBUG) {
                            Slog.d(TAG, "Serving icon pack icon: " + component.flattenToShortString() 
                                    + " -> " + drawableName);
                        }
                        return bitmap;
                    }
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to get icon pack icon: " + component, e);
            }
            
            return null;
        }
        
        @Override
        public boolean hasActiveIconPack() {
            return mIconPackPackage != null && !mIconPackMap.isEmpty();
        }
        
        @Override
        public String getIconPackPackage() {
            return mIconPackPackage;
        }
        
        @Override
        public Bitmap getIconThemeDrawable(String resourceName, int density) {
            if (resourceName == null) {
                return null;
            }
            
            String cacheKey = "theme:" + resourceName + ":" + density;
            Bitmap cached = mBitmapCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            
            String themePackage = getThemePackageForResource(resourceName);
            if (themePackage == null) {
                return null;
            }
            
            Resources themeResources = getThemeResources(themePackage);
            if (themeResources == null) {
                return null;
            }
            
            String actualResourceName = resourceName;
            Set<String> targets = mTargetArrayCache.get(themePackage);
            
            if (DEBUG) {
                Slog.d(TAG, "getIconThemeDrawable: targets for " + themePackage + " = " 
                        + (targets != null ? targets.size() + " items" : "null"));
            }
            
            if (targets != null && !targets.contains(resourceName)) {
                String fallbackName = ICON_NAME_FALLBACK.get(resourceName);
                if (fallbackName != null && targets.contains(fallbackName)) {
                    actualResourceName = fallbackName;
                    if (DEBUG) {
                        Slog.d(TAG, "Using forward fallback icon: " + resourceName + " -> " + fallbackName);
                    }
                } else {
                    boolean found = false;
                    for (Map.Entry<String, String> entry : ICON_NAME_FALLBACK.entrySet()) {
                        if (entry.getValue().equals(resourceName) && targets.contains(entry.getKey())) {
                            actualResourceName = entry.getKey();
                            found = true;
                            if (DEBUG) {
                                Slog.d(TAG, "Using reverse fallback icon: " + resourceName + " -> " + actualResourceName);
                            }
                            break;
                        }
                    }
                    if (!found) {
                        if (DEBUG) {
                            Slog.d(TAG, "getIconThemeDrawable: resource not in targets, no fallback found");
                        }
                        return null;
                    }
                }
            } else if (targets == null) {
                Slog.w(TAG, "getIconThemeDrawable: no targets loaded for package " + themePackage);
                return null;
            }
            
            try {
                int resId = themeResources.getIdentifier(actualResourceName, "drawable", themePackage);
                if (resId != 0) {
                    Drawable drawable = themeResources.getDrawableInternal(resId);
                    if (drawable != null) {
                        Bitmap bitmap = drawableToBitmap(drawable, density);
                        mBitmapCache.put(cacheKey, bitmap);
                        
                        if (DEBUG) {
                            Slog.d(TAG, "Serving icon theme drawable: " + resourceName 
                                    + (actualResourceName.equals(resourceName) ? "" : " (fallback: " + actualResourceName + ")"));
                        }
                        return bitmap;
                    }
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to get icon theme drawable: " + resourceName, e);
            }
            
            return null;
        }
        
        @Override
        public boolean isTargetedResource(String resourceName) {
            if (resourceName == null) {
                return false;
            }
            
            String category = mResourceCategoryCache.get(resourceName);
            
            if (DEBUG) {
                Slog.v(TAG, "isTargetedResource: name=" + resourceName 
                        + ", category=" + category
                        + ", activeIconTheme=" + mActiveIconTheme
                        + ", categoryThemes=" + mCategoryThemes.keySet()
                        + ", targetCacheKeys=" + mTargetArrayCache.keySet());
            }
            
            if (category != null && mCategoryThemes.containsKey(category)) {
                String pkgName = mCategoryThemes.get(category);
                Set<String> targets = mTargetArrayCache.get(pkgName);
                boolean result = targets != null && 
                        (targets.contains(resourceName) || 
                         (ICON_NAME_FALLBACK.containsKey(resourceName) && 
                          targets.contains(ICON_NAME_FALLBACK.get(resourceName))));
                if (DEBUG) Slog.v(TAG, "isTargetedResource: category match, result=" + result);
                return result;
            }
            
            if (mActiveIconTheme == null) {
                if (DEBUG) Slog.v(TAG, "isTargetedResource: no active icon theme");
                return false;
            }
            
            Set<String> targets = mTargetArrayCache.get(mActiveIconTheme);
            boolean inTargets = targets != null && 
                    (targets.contains(resourceName) || 
                     (ICON_NAME_FALLBACK.containsKey(resourceName) && 
                      targets.contains(ICON_NAME_FALLBACK.get(resourceName))));
            if (!inTargets) {
                if (DEBUG) Slog.v(TAG, "isTargetedResource: not in target set");
                return false;
            }
            
            if (category != null && !mIconThemeTargets.isEmpty()) {
                boolean result = mIconThemeTargets.contains(category);
                if (DEBUG) Slog.v(TAG, "isTargetedResource: iconThemeTargets check, result=" + result);
                return result;
            }
            
            boolean result = !mIconThemeTargets.isEmpty();
            if (DEBUG) Slog.v(TAG, "isTargetedResource: final result=" + result);
            return result;
        }
        
        @Override
        public boolean hasActiveIconTheme() {
            return mActiveIconTheme != null && !mIconThemeTargets.isEmpty();
        }
        
        @Override
        public String getActiveIconTheme() {
            return mActiveIconTheme;
        }
        
        @Override
        public String getCategoryTheme(String category) {
            if (category == null) {
                return null;
            }
            return mCategoryThemes.get(category);
        }
        
        @Override
        public String getQsStyleId() {
            String qsStyle = mStyleIds.get(CATEGORY_UI_QS);
            if (qsStyle != null) {
                return qsStyle;
            }
            String unifiedStyle = mStyleIds.get(CATEGORY_UI_STYLE);
            return unifiedStyle != null ? unifiedStyle : STYLE_AXION;
        }
        
        @Override
        public String getVolumeStyleId() {
            String volumeStyle = mStyleIds.get(CATEGORY_UI_VOLUME);
            if (volumeStyle != null) {
                return volumeStyle;
            }
            String unifiedStyle = mStyleIds.get(CATEGORY_UI_STYLE);
            return unifiedStyle != null ? unifiedStyle : STYLE_AXION;
        }
        
        @Override
        public String getIconShape() {
            return mIconShape != null ? mIconShape : SHAPE_SQUIRCLE;
        }
        
        @Override
        public String getIconShapePath() {
            return getIconShapePathInternal();
        }
        
        @Override
        public String getThemedString(String stringName) {
            if (stringName == null) {
                return null;
            }
            
            if (DEBUG) {
                Slog.d(TAG, "getThemedString: " + stringName);
            }

            if ("config_icon_mask".equals(stringName)) {
                String shapePath = getIconShapePathInternal();
                if (shapePath != null && !shapePath.isEmpty()) {
                    if (DEBUG) Slog.d(TAG, "Returning themed icon shape path");
                    return shapePath;
                }
            }

            return null;
        }
        
        @Override
        public void registerCallback(IThemeEngineCallback callback) {
            if (callback != null) {
                mCallbacks.register(callback);
                Slog.d(TAG, "Registered theme callback");
            }
        }
        
        @Override
        public void unregisterCallback(IThemeEngineCallback callback) {
            if (callback != null) {
                mCallbacks.unregister(callback);
                Slog.d(TAG, "Unregistered theme callback");
            }
        }
        
        @Override
        public void notifyThemeChanged(String category) {
            Slog.d(TAG, "notifyThemeChanged called via AIDL, category=" + category);
            notifyThemeChangedInternal(category);
        }
    }
}
