/*
 * Copyright (C) 2025-2026 AxionOS Project
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

package com.android.systemui.shared.clocks.view

import android.app.PendingIntent
import android.graphics.Bitmap

sealed interface DateDisplay {
    val text: String
    val icon: Bitmap?
    val tintIcon: Boolean
    val tapAction: PendingIntent?

    object Hidden : DateDisplay {
        override val text: String get() = ""
        override val icon: Bitmap? get() = null
        override val tintIcon: Boolean get() = true
        override val tapAction: PendingIntent? get() = null
    }

    data class DateOnly(override val text: String) : DateDisplay {
        override val icon: Bitmap? get() = null
        override val tintIcon: Boolean get() = true
        override val tapAction: PendingIntent? get() = null
    }

    data class IconText(
        override val text: String,
        override val icon: Bitmap?,
        override val tintIcon: Boolean,
        override val tapAction: PendingIntent? = null,
    ) : DateDisplay

    data class Weather(
        val date: String,
        val temp: String,
        override val icon: Bitmap?,
        override val tintIcon: Boolean,
        override val tapAction: PendingIntent? = null,
    ) : DateDisplay {
        override val text: String get() = date
    }
}
