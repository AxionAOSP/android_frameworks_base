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
import com.android.server.am.ProcessRecord;
import com.android.server.utils.SimpleAppRecord;
import java.io.PrintWriter;
import java.util.ArrayList;

public interface INtAppUsageManager {

    default void systemReady() {
    }

    default void cleanAllData(long millis) {
    }

    default void setScreenState(boolean isOff) {
    }

    default void removePackage(String packageName) {
    }

    default void setUpdatingPackage(String packageName) {
    }

    default void addNewPackages(String packageName) {
    }

    default void updateLaunchTime(String packageName) {
    }

    default void updateDuration(String packageName) {
    }

    default ArrayList<String> getHighUsedPackageList(boolean needUpdate) {
        return new ArrayList<>();
    }

    default ArrayList<String> getGeneralUsedPackageList(boolean needUpdate) {
        return new ArrayList<>();
    }

    default ArrayList<String> getLowUsedPackageList(boolean needUpdate) {
        return new ArrayList<>();
    }

    default boolean isHighUsedPackages(String pkgName) {
        return false;
    }

    default ArrayList<SimpleAppRecord> getHighUsedRecords(boolean needUpdate) {
        return new ArrayList<>();
    }

    default SimpleAppRecord geedHighUsedRecord(boolean needUpdatHighUste, String packageName) {
        return null;
    }

    default void setRemoveTaskTime(String pkgName) {
    }

    default void setLastCachedPss(String pkgName, long pss) {
    }

    default void appDied(ProcessRecord p) {
    }

    default void setTargetAdj(String pkgName, int adj) {
    }
}
