/*
 * Copyright (C) 2025 the AxionAOSP Project
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

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.internal.util.android.PinnerUtils;

public class PinnedTaskService extends SystemService {

    private final ContentResolver contentResolver;
    private final Handler handler = new Handler();
    private final Context context;

    private final ContentObserver recentsLockedTasksObserver = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            updatePinnedTasks();
        }
    };

    public PinnedTaskService(Context context) {
        super(context);
        this.context = context;
        this.contentResolver = context.getContentResolver();
    }

    @Override
    public void onStart() {
        contentResolver.registerContentObserver(
            Settings.System.getUriFor("recents_locked_tasks"),
            false,
            recentsLockedTasksObserver,
            UserHandle.USER_ALL
        );
        updatePinnedTasks();
    }

    private void updatePinnedTasks() {
        try {
            String value = Settings.System.getString(
                contentResolver,
                "recents_locked_tasks"
            );
            PinnerUtils.INSTANCE().clear();
            if (value != null && !value.isEmpty()) {
                String[] packages = value.split(",");
                for (String pkg : packages) {
                    PinnerUtils.INSTANCE().setPinned(pkg.trim(), true);
                }
            }
        } catch (Exception e) {}
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            clearLockedTasksOnBoot();
        }
    }
    
    private void clearLockedTasksOnBoot() {
        try {
            Settings.System.putStringForUser(
                contentResolver,
                "recents_locked_tasks",
                "",
                UserHandle.USER_SYSTEM
            );
            PinnerUtils.INSTANCE().clear();
        } catch (Exception e) {}
    }
}
