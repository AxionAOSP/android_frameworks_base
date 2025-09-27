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

public interface IAxExtServiceFactory {
    enum ExtType {
        NT_MEMORY_MANAGER(INtMemoryManager.class),
        NT_APP_USAGE_MANAGER(INtAppUsageManager.class),
        BOOST_ADJUSTER(IBoostAdjuster.class),
        PROCESS_MANAGER(IProcessManager.class),
        UX_PERFORMANCE(IUxPerformance.class);

        private final Class<?> clazz;

        ExtType(Class<?> clazz) {
            this.clazz = clazz;
        }

        public Class<?> getClazz() {
            return clazz;
        }
    }
}
