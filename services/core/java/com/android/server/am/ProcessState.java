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
import static com.android.server.am.ProcessList.*;
import static com.android.server.am.BurstEngineConstants.*;

import com.android.server.NtServiceInjector;
import com.android.server.am.ProcessRecord;
import com.android.server.am.ProcessStateRecord;

import android.util.Slog;

class ProcessState {
    final int pid;
    volatile String name;
    volatile ProcessRecord record;

    volatile Integer adj = null;

    volatile boolean isUiPerfPkg = false;
    volatile boolean isBlacklisted = false;
    volatile boolean isUiProc = false;
    volatile boolean isPerceptible = false;
    volatile boolean isSystemUI = false;

    volatile int group = THREAD_GROUP_DEFAULT;
    volatile int rtid = 0;

    ProcessState(int pid, String processName, int targetGroup) {
        this.pid = pid;
        this.name = processName;
        this.isUiProc = targetGroup == THREAD_GROUP_TOP_APP;
        this.isUiPerfPkg = AxUtils.isInPerfList(name);
        this.isBlacklisted = AxUtils.isInPerfBlackList(name);
        this.isSystemUI = processName.contains("systemui");
        updateFromRecord(targetGroup);
    }

    void updateFromRecord(int group) {
        ProcessRecord newRecord = NtServiceInjector.getAm().getProcessRecordByPid(pid);
        
        if (newRecord == null) return;
        
        record = newRecord;

        name = record.getProcessName();

        record = newRecord;
        this.group = group;

        if (record == null) {
            return;
        }

        this.name = record.getProcessName();
        this.isUiPerfPkg = AxUtils.isInPerfList(name);
        this.isBlacklisted = AxUtils.isInPerfBlackList(name);
        this.isSystemUI = name.contains("systemui");
        this.rtid = record.getRenderThreadTid();

        ProcessStateRecord s = record.mState;
        int currentAdj = record.getSetAdj();

        isPerceptible =
                currentAdj <= PERCEPTIBLE_LOW_APP_ADJ
                        || currentAdj == HOME_APP_ADJ
                        || currentAdj == PREVIOUS_APP_ADJ;

        adj = isPerceptible ? OOM_ADJ_PROTECTED : null;

        isUiProc = group == THREAD_GROUP_TOP_APP
                || s.hasTopUi()
                || s.hasOverlayUi()
                || s.isRunningRemoteAnimation();
    }

    private String groupToString(int g) {
        switch (g) {
            case THREAD_GROUP_DEFAULT: return "DEFAULT";
            case THREAD_GROUP_BACKGROUND: return "BACKGROUND";
            case THREAD_GROUP_TOP_APP: return "TOP_APP";
            case THREAD_GROUP_RESTRICTED: return "RESTRICTED";
            case AxUtils.THREAD_GROUP_SVP: return "SVP";
            case AxUtils.THREAD_GROUP_NT_FOREGROUND: return "NT_FOREGROUND";
            default: return "GROUP(" + g + ")";
        }
    }

    void dump() {
        final String TAG = "ProcessState";

        StringBuilder sb = new StringBuilder(256);

        sb.append("ProcessState{");
        sb.append(" pid=").append(pid);
        sb.append(" name=").append(name);

        sb.append(" group=").append(groupToString(group));

        sb.append(" rtid=").append(rtid);

        sb.append(" flags=[");
        sb.append("uiPerf=").append(isUiPerfPkg).append(", ");
        sb.append("blacklisted=").append(isBlacklisted).append(", ");
        sb.append("top=").append(isUiProc).append(", ");
        sb.append("perceptible=").append(isPerceptible).append(", ");
        sb.append("systemUI=").append(isSystemUI);
        sb.append("]");

        sb.append(" adj=").append(adj != null ? adj : "null");

        sb.append(" record=").append(record);

        sb.append(" }");

        Slog.d(TAG, sb.toString());
    }
}
