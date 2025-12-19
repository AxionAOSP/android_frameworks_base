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
package com.android.server.wm.sandbox;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HideDevOptsController {
    private static final String TAG = "AxSandbox.DevOpts";
    private static final String SETTING_DEV_OPTS_HIDE_LIST = "hide_devs_opt_app_list";
    
    private final Context mContext;
    
    public HideDevOptsController(Context context) {
        mContext = context;
    }
    
    public boolean isDevOptionsHidden(String packageName) {
        if (TextUtils.isEmpty(packageName)) return false;
        try {
            ContentResolver resolver = mContext.getContentResolver();
            String devOptsList = Settings.Secure.getString(resolver, SETTING_DEV_OPTS_HIDE_LIST);
            if (!TextUtils.isEmpty(devOptsList)) {
                for (String pkg : devOptsList.split(",")) {
                    if (pkg.trim().equals(packageName)) return true;
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to check dev options hide list", e);
        }
        return false;
    }
    
    public void addDevOptionsHiddenPackage(String packageName) {
        if (TextUtils.isEmpty(packageName)) return;
        
        try {
            ContentResolver resolver = mContext.getContentResolver();
            String devOptsList = Settings.Secure.getString(resolver, SETTING_DEV_OPTS_HIDE_LIST);
            Set<String> packages = new HashSet<>();
            if (!TextUtils.isEmpty(devOptsList)) {
                for (String pkg : devOptsList.split(",")) {
                    if (!TextUtils.isEmpty(pkg)) packages.add(pkg.trim());
                }
            }
            packages.add(packageName);
            Settings.Secure.putString(resolver, SETTING_DEV_OPTS_HIDE_LIST, String.join(",", packages));
            Slog.d(TAG, "addDevOptionsHiddenPackage: " + packageName);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to add dev options hidden package", e);
        }
    }
    
    public void removeDevOptionsHiddenPackage(String packageName) {
        if (TextUtils.isEmpty(packageName)) return;
        
        try {
            ContentResolver resolver = mContext.getContentResolver();
            String devOptsList = Settings.Secure.getString(resolver, SETTING_DEV_OPTS_HIDE_LIST);
            if (TextUtils.isEmpty(devOptsList)) return;
            
            Set<String> packages = new HashSet<>();
            for (String pkg : devOptsList.split(",")) {
                if (!TextUtils.isEmpty(pkg) && !pkg.trim().equals(packageName)) {
                    packages.add(pkg.trim());
                }
            }
            Settings.Secure.putString(resolver, SETTING_DEV_OPTS_HIDE_LIST, String.join(",", packages));
            Slog.d(TAG, "removeDevOptionsHiddenPackage: " + packageName);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to remove dev options hidden package", e);
        }
    }
    
    public List<String> getDevOptionsHiddenPackages() {
        List<String> result = new ArrayList<>();
        try {
            ContentResolver resolver = mContext.getContentResolver();
            String devOptsList = Settings.Secure.getString(resolver, SETTING_DEV_OPTS_HIDE_LIST);
            if (!TextUtils.isEmpty(devOptsList)) {
                for (String pkg : devOptsList.split(",")) {
                    if (!TextUtils.isEmpty(pkg)) result.add(pkg.trim());
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to get dev options hidden packages", e);
        }
        return result;
    }
}
