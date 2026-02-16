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

data class SmartspaceData(
    val id: String,
    val title: String,
    val subtitle: String,
    val featureType: Int,
    val iconBytes: ByteArray?,
    val componentName: String?,
    val isSensitive: Boolean,
    val sourceType: Int,
    val creationTime: Long,
    val score: Float,
    val tapAction: PendingIntent? = null,
) {
    val isFromSmartspacer: Boolean
        get() = sourceType == SOURCE_SMARTSPACER

    val isFromGoogleSmartspace: Boolean
        get() = sourceType == SOURCE_GOOGLE_SMARTSPACE

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SmartspaceData) return false
        return id == other.id &&
            title == other.title &&
            subtitle == other.subtitle &&
            featureType == other.featureType &&
            sourceType == other.sourceType
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + subtitle.hashCode()
        result = 31 * result + featureType
        result = 31 * result + sourceType
        return result
    }

    companion object {
        const val SOURCE_SMARTSPACER = 100
        const val SOURCE_GOOGLE_SMARTSPACE = 200

        val EMPTY = SmartspaceData(
            id = "",
            title = "",
            subtitle = "",
            featureType = 0,
            iconBytes = null,
            componentName = null,
            isSensitive = false,
            sourceType = 0,
            creationTime = 0L,
            score = 0f
        )
    }
}

