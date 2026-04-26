/*
 * Copyright (C) 2025-2026 AxionOS
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
package com.android.server.thermal;

public final class AxAdvancedThermalMitigationInfo {
    private final String mUnit;
    private final int mStatus;

    public AxAdvancedThermalMitigationInfo(String unit, int status) {
        this.mUnit = unit;
        this.mStatus = status;
    }

    public String getUnit() {
        return this.mUnit;
    }

    public int getStatus() {
        return this.mStatus;
    }

    @Override
    public int hashCode() {
        int hash = (mUnit != null) ? mUnit.hashCode() : 0;
        return (hash * 31) + mStatus;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof AxAdvancedThermalMitigationInfo)) {
            return false;
        }
        AxAdvancedThermalMitigationInfo other = (AxAdvancedThermalMitigationInfo) o;
        return other.mStatus == this.mStatus
                && (mUnit == null ? other.mUnit == null : mUnit.equals(other.mUnit));
    }

    @Override
    public String toString() {
        return "AxAdvancedThermalMitigationInfo{unit=" + mUnit + ", status=" + mStatus + "}";
    }
}
