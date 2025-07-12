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
package com.android.server.wm;

import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.UserHandle;
import android.widget.Toast;

class PackageHandler {
    private final Context mContext;
    private final PackageManager mPackageManager;
    private final GameListManager mGameListManager;

    PackageHandler(Context context, GameListManager manager) {
        this.mContext = context;
        this.mPackageManager = context.getPackageManager();
        this.mGameListManager = manager;
    }

    void registerPackageReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        filter.addDataScheme("package");

        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String pkg = intent.getData() != null ? intent.getData().getSchemeSpecificPart() : null;
                if (pkg == null) return;

                if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
                    handlePackageAdded(pkg);
                } else if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(intent.getAction())) {
                    handlePackageRemoved(pkg);
                }
            }
        }, filter);
    }

    private void handlePackageAdded(String pkg) {
        if (isGame(pkg)) {
            mGameListManager.addGame(pkg);
            showGameAddedNotification(pkg);
        }
    }

    private void handlePackageRemoved(String pkg) {
        mGameListManager.removeGame(pkg);
    }

    private boolean isGame(String pkg) {
        try {
            ApplicationInfo info = mPackageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA));
            return info.category == ApplicationInfo.CATEGORY_GAME;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void showGameAddedNotification(String pkg) {
        String label;
        try {
            label = mPackageManager.getApplicationLabel(mPackageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString();
        } catch (PackageManager.NameNotFoundException e) {
            label = pkg;
        }

        final String msg = "Added" + label + "to GameSpace";
        new Handler(mContext.getMainLooper()).post(() ->
            Toast.makeText(mContext, msg, Toast.LENGTH_LONG).show()
        );
    }
}
