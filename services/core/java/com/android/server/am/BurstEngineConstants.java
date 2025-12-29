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
package com.android.server.am;

import static android.os.Process.*;
import static com.android.server.am.AxUtils.THREAD_GROUP_NT_FOREGROUND;
import static com.android.server.am.AxUtils.THREAD_GROUP_SVP;

import android.util.SparseArray;

public final class BurstEngineConstants {
    
    public static final int AFFINITY_BIG = 0;
    public static final int AFFINITY_LITTLE = 1;
    public static final int AFFINITY_ALL = 2;
    public static final int AFFINITY_BALANCED = 3;

    public static final int OPT_ADJ_OOM_SCORE = 201;

    private BurstEngineConstants() {
    }
}
