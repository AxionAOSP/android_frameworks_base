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

package android.content.res;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @hide
 */
public class ThemeEngine {
    private static final String TAG = "ThemeEngine";
    
    public static final String SETTINGS_THEME_ENGINE_DATA = "theme_engine_data";
    
    public static final String ACTION_THEME_CHANGED = "android.intent.action.THEME_ENGINE_CHANGED";
    public static final String EXTRA_THEME_CATEGORY = "theme_category";
    
    public static final String CATEGORY_ICON_PACK = "icon_pack";
    public static final String CATEGORY_STATUSBAR_WIFI = "statusbar_wifi";
    public static final String CATEGORY_STATUSBAR_SIGNAL = "statusbar_signal";
    public static final String CATEGORY_ANDROID = "android";
    public static final String CATEGORY_SYSTEMUI = "systemui";
    public static final String CATEGORY_UI_QS = "ui_qs";
    public static final String CATEGORY_UI_VOLUME = "ui_volume";
    public static final String CATEGORY_UI_STYLE = "ui_style";
    public static final String CATEGORY_ICON_SHAPE = "icon_shape";
    
    public static final String STYLE_AXION = "axion";
    public static final String STYLE_MATERIAL3_EXPRESSIVE = "material3_expressive";
    public static final String STYLE_MINIMAL = "minimal";
    
    public static final String SHAPE_CIRCLE = "circle";
    public static final String SHAPE_SQUIRCLE = "squircle";
    public static final String SHAPE_ROUNDED_RECT = "rounded_rect";
    public static final String SHAPE_TEARDROP = "teardrop";
    public static final String SHAPE_CYLINDER = "cylinder";
    public static final String SHAPE_HEXAGON = "hexagon";
    
    public static final String SHAPE_PATH_CIRCLE = 
        "M50,0A50,50,0,1,1,50,100A50,50,0,1,1,50,0";
    public static final String SHAPE_PATH_SQUIRCLE = 
        "M50,0C77.6,0 100,22.4 100,50C100,77.6 77.6,100 50,100C22.4,100 0,77.6 0,50C0,22.4 22.4,0 50,0Z";
    public static final String SHAPE_PATH_ROUNDED_RECT = 
        "M50,0L92,0C96.42,0 100,4.58 100,8L100,92C100,96.42 96.42,100 92,100L8,100C4.58,100 0,96.42 0,92L0,8C0,4.42 4.42,0 8,0L50,0Z";
    public static final String SHAPE_PATH_TEARDROP = 
        "M50,0A50,50,0,0,1,100,50L100,92C100,96.42 96.42,100 92,100L8,100C4.42,100 0,96.42 0,92L0,50A50,50,0,0,1,50,0Z";
    public static final String SHAPE_PATH_CYLINDER = 
        "M50,0C77.6,0 100,11.2 100,25L100,75C100,88.8 77.6,100 50,100C22.4,100 0,88.8 0,75L0,25C0,11.2 22.4,0 50,0Z";
    public static final String SHAPE_PATH_HEXAGON = 
        "M50,0L93.3,25L93.3,75L50,100L6.7,75L6.7,25L50,0Z";

    private static volatile ThemeEngine sInstance;
    
    private final Context mContext;
    private IThemeEngineManager mService;
    
    private ThemeEngine(@NonNull Context context) {
        mContext = context.getApplicationContext();
    }
    
    @Nullable
    public static ThemeEngine getInstance(@Nullable Context context) {
        if (sInstance == null) {
            synchronized (ThemeEngine.class) {
                if (sInstance == null) {
                    Context app = context != null ? context.getApplicationContext() : null;
                    if (app == null) {
                        Application currentApp = ActivityThread.currentApplication();
                        if (currentApp != null) {
                            app = currentApp;
                        }
                    }
                    if (app != null) {
                        sInstance = new ThemeEngine(app);
                    }
                }
            }
        }
        return sInstance;
    }
    
    @Nullable
    public static ThemeEngine getInstance() {
        if (sInstance != null) return sInstance;
        Application app = ActivityThread.currentApplication();
        return app != null ? getInstance(app) : null;
    }
    
    @Nullable
    private IThemeEngineManager getService() {
        if (mService != null) return mService;
        IBinder binder = ServiceManager.getService(Context.THEME_ENGINE_SERVICE);
        if (binder != null) {
            mService = IThemeEngineManager.Stub.asInterface(binder);
        }
        return mService;
    }
    
    @Nullable
    public Drawable getIconPackDrawable(@NonNull ComponentName componentName, int density) {
        IThemeEngineManager service = getService();
        if (service == null) return null;
        try {
            Bitmap bitmap = service.getIconPackIcon(componentName, density);
            return bitmap != null ? new BitmapDrawable(mContext.getResources(), bitmap) : null;
        } catch (RemoteException e) {
            Log.w(TAG, "getIconPackDrawable failed", e);
            return null;
        }
    }
    
    @Nullable
    public Drawable getIconPackDrawable(@NonNull ComponentName componentName) {
        return getIconPackDrawable(componentName, 0);
    }
    
    public boolean hasActiveIconPack() {
        IThemeEngineManager service = getService();
        if (service == null) return false;
        try {
            return service.hasActiveIconPack();
        } catch (RemoteException e) {
            return false;
        }
    }
    
    @Nullable
    public Drawable getIconThemeDrawable(@NonNull String resourceName) {
        IThemeEngineManager service = getService();
        if (service == null) return null;
        try {
            Bitmap bitmap = service.getIconThemeDrawable(resourceName, 0);
            return bitmap != null ? new BitmapDrawable(mContext.getResources(), bitmap) : null;
        } catch (RemoteException e) {
            Log.w(TAG, "getIconThemeDrawable failed", e);
            return null;
        }
    }
    
    @Nullable
    public Drawable getIconThemeDrawable(@NonNull Resources userResources, int resId) {
        try {
            return getIconThemeDrawable(userResources.getResourceEntryName(resId));
        } catch (Exception e) {
            return null;
        }
    }
    
    public boolean isTargetedResource(@NonNull String resourceName) {
        IThemeEngineManager service = getService();
        if (service == null) return false;
        try {
            return service.isTargetedResource(resourceName);
        } catch (RemoteException e) {
            return false;
        }
    }
    
    public boolean hasActiveIconTheme() {
        IThemeEngineManager service = getService();
        if (service == null) return false;
        try {
            return service.hasActiveIconTheme();
        } catch (RemoteException e) {
            return false;
        }
    }
    
    @Nullable
    public String getActiveIconTheme() {
        IThemeEngineManager service = getService();
        if (service == null) return null;
        try {
            return service.getActiveIconTheme();
        } catch (RemoteException e) {
            return null;
        }
    }

    @Nullable
    public String getIconPackPackage() {
        IThemeEngineManager service = getService();
        if (service == null) return null;
        try {
            return service.getIconPackPackage();
        } catch (RemoteException e) {
            return null;
        }
    }
    
    public boolean shouldOverlayResource(@NonNull Resources userResources, int resId) {
        try {
            String entryName = userResources.getResourceEntryName(resId);
            if (!isTargetedResource(entryName)) return false;
            String packageName = userResources.getResourcePackageName(resId);
            String themePackage = getActiveIconTheme();
            return themePackage == null || !themePackage.equals(packageName);
        } catch (Exception e) {
            return false;
        }
    }
    
    @Nullable
    public String getThemedString(@NonNull String stringName) {
        IThemeEngineManager service = getService();
        if (service == null) return null;
        try {
            return service.getThemedString(stringName);
        } catch (RemoteException e) {
            return null;
        }
    }
    
    @NonNull
    public String getQsStyleId() {
        IThemeEngineManager service = getService();
        if (service == null) return STYLE_AXION;
        try {
            String style = service.getQsStyleId();
            return style != null ? style : STYLE_AXION;
        } catch (RemoteException e) {
            return STYLE_AXION;
        }
    }
    
    @NonNull
    public String getVolumeStyleId() {
        IThemeEngineManager service = getService();
        if (service == null) return STYLE_AXION;
        try {
            String style = service.getVolumeStyleId();
            return style != null ? style : STYLE_AXION;
        } catch (RemoteException e) {
            return STYLE_AXION;
        }
    }
    
    @NonNull
    public String getIconShape() {
        IThemeEngineManager service = getService();
        if (service == null) return SHAPE_SQUIRCLE;
        try {
            String shape = service.getIconShape();
            return (shape != null && !shape.isEmpty()) ? shape : SHAPE_SQUIRCLE;
        } catch (RemoteException e) {
            return SHAPE_SQUIRCLE;
        }
    }
    
    @NonNull
    public String getIconShapePath() {
        IThemeEngineManager service = getService();
        if (service == null) return "";
        try {
            String path = service.getIconShapePath();
            return path != null ? path : "";
        } catch (RemoteException e) {
            return "";
        }
    }
    
    @Nullable
    public String getEnabledPackage(@NonNull String category) {
        IThemeEngineManager service = getService();
        if (service == null) return null;
        try {
            return service.getCategoryTheme(category);
        } catch (RemoteException e) {
            return null;
        }
    }
    
    public interface ThemeChangeListener {
        void onThemeChanged(@Nullable String category);
    }
    
    private final List<ThemeChangeListener> mListeners = new CopyOnWriteArrayList<>();
    private IThemeEngineCallback mServiceCallback;
    
    /**
     * @hide
     */
    public void addThemeChangeListener(@NonNull ThemeChangeListener listener) {
        if (listener == null) return;
        mListeners.add(listener);
        
        if (mListeners.size() == 1) {
            registerServiceCallback();
        }
    }
    
    /**
     * @hide
     */
    public void removeThemeChangeListener(@NonNull ThemeChangeListener listener) {
        mListeners.remove(listener);
        
        if (mListeners.isEmpty()) {
            unregisterServiceCallback();
        }
    }
    
    private void registerServiceCallback() {
        IThemeEngineManager service = getService();
        if (service == null) return;
        
        mServiceCallback = new IThemeEngineCallback.Stub() {
            @Override
            public void onThemeChanged(String category) {
                for (ThemeChangeListener listener : mListeners) {
                    try {
                        listener.onThemeChanged(category);
                    } catch (Exception e) {
                        Log.w(TAG, "Error dispatching theme change", e);
                    }
                }
            }
        };
        
        try {
            service.registerCallback(mServiceCallback);
            Log.d(TAG, "Registered theme change callback with service");
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to register callback", e);
        }
    }
    
    private void unregisterServiceCallback() {
        if (mServiceCallback == null) return;
        
        IThemeEngineManager service = getService();
        if (service != null) {
            try {
                service.unregisterCallback(mServiceCallback);
                Log.d(TAG, "Unregistered theme change callback from service");
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to unregister callback", e);
            }
        }
        mServiceCallback = null;
    }
    
    /**
     * @hide
     */
    public void notifyThemeChanged(@Nullable String category) {
        IThemeEngineManager service = getService();
        if (service == null) {
            Log.w(TAG, "notifyThemeChanged: service not available");
            return;
        }
        try {
            service.notifyThemeChanged(category);
            Log.d(TAG, "notifyThemeChanged sent, category=" + category);
        } catch (RemoteException e) {
            Log.w(TAG, "notifyThemeChanged failed", e);
        }
    }
}
