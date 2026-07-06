/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package android.os;

import android.annotation.NonNull;
import android.app.ActivityManager;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** @hide */
public final class AxKernelManager {
    private long mPreviousCpuActiveTimeTicks = AxKernelMetrics.CPU_TIME_UNAVAILABLE_TICKS;
    private long mPreviousCpuTimeTicks = AxKernelMetrics.CPU_TIME_UNAVAILABLE_TICKS;

    public AxKernelManager() {}

    @NonNull
    public List<AxKernelControl> getControls() {
        try {
            List<AxKernelControl> controls = ActivityManager.getService().getAxKernelControls();
            return controls != null ? controls : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public boolean setControlValue(@NonNull String id, int value) {
        try {
            return ActivityManager.getService().setAxKernelControlValue(
                    Objects.requireNonNull(id), value);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @NonNull
    public synchronized AxKernelMetrics getMetrics() {
        try {
            AxKernelMetrics metrics =
                    Objects.requireNonNull(
                            ActivityManager.getService()
                                    .getAxKernelMetrics(
                                            mPreviousCpuActiveTimeTicks, mPreviousCpuTimeTicks));
            long activeTimeTicks = metrics.getTotalCpuActiveTimeTicks();
            long totalTimeTicks = metrics.getTotalCpuTimeTicks();
            if (activeTimeTicks >= 0L && totalTimeTicks >= activeTimeTicks) {
                mPreviousCpuActiveTimeTicks = activeTimeTicks;
                mPreviousCpuTimeTicks = totalTimeTicks;
            }
            return metrics;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
