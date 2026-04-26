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

import com.android.server.am.*;
import com.android.server.spoof.IAxSpoofManager;
import com.android.server.thermal.IAxAdvancedThermalMitigationService;
import com.android.server.uifirst.IAxUiFirstManager;

public interface IAxExtServiceFactory {
    enum ExtType {
        AX_BURST_ENGINE(IAxBurstEngine.class),
        AX_MEMORY_MANAGER(IAxMemoryManager.class),
        UX_PERFORMANCE(IUxPerformance.class),
        PC_MODE_SERVICE(IAxPcModeService.class),
        AX_SPOOF_MANAGER(IAxSpoofManager.class),
        AX_UI_FIRST_MANAGER(IAxUiFirstManager.class),
        AX_ADVANCED_THERMAL_MITIGATION(IAxAdvancedThermalMitigationService.class);
        private final Class<?> clazz;

        ExtType(Class<?> clazz) {
            this.clazz = clazz;
        }

        public Class<?> getClazz() {
            return clazz;
        }
    }
}
