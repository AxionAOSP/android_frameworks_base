/*
 * Copyright (C) 2025 AxionOS Project
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

package com.android.systemui.plugins.keyguard.ui.clocks

import android.app.PendingIntent
import android.text.TextUtils
import java.util.Objects

class CalendarSimpleData(
    val id: Long = 0L,
    val title: String? = null,
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val location: String? = null,
    val description: String = "",
    val formattedTime: String = "",
    val eventStatus: Int = EVENT_STATUS_TO_SCHEDULE,
    val tapAction: PendingIntent? = null,
) {

    fun isEventVisible(): Boolean =
        eventStatus == EVENT_STATUS_TO_BEGIN || eventStatus == EVENT_STATUS_NOW

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CalendarSimpleData) return false

        return id == other.id &&
            startTime == other.startTime &&
            endTime == other.endTime &&
            eventStatus == other.eventStatus &&
            TextUtils.equals(title, other.title) &&
            TextUtils.equals(location, other.location)
    }

    override fun hashCode(): Int {
        return Objects.hash(id, title, startTime, endTime, location, eventStatus)
    }

    companion object {
        const val EVENT_STATUS_TO_SCHEDULE = 0
        const val EVENT_STATUS_TO_BEGIN = 1
        const val EVENT_STATUS_NOW = 2
        const val EVENT_STATUS_END = 3

        val EMPTY = CalendarSimpleData()
    }
}

