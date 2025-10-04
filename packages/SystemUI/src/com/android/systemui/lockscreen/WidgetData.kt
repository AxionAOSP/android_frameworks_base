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
package com.android.systemui.lockscreen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.gestures.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.*
import com.android.systemui.res.R

data class WidgetSpec(
    val action: WidgetAction,
    val type: WidgetType
)

enum class WidgetType(
    val span: Int,
    val content: @Composable (
        spec: WidgetSpec,
        bgColor: Color,
        border: Modifier,
        iconTint: Color,
        theme: Theme,
        dimens: Dimens,
        ctrl: LockScreenWidgetsController,
        active: Boolean
    ) -> Unit
) {
    SMALL(
        span = 1,
        content = { spec, bg, border, tint, theme, dimens, ctrl, active ->
            WidgetSmall(spec, bg, border, tint, theme, dimens, ctrl, active)
        }
    ),
    PILL(
        span = 2,
        content = { spec, bg, border, tint, theme, dimens, ctrl, active ->
            WidgetPill(spec, bg, border, tint, theme, dimens, ctrl, active)
        }
    );
    
    companion object {
        fun fromString(raw: String?): WidgetType {
            return when (raw?.lowercase()) {
                "pill", "2" -> PILL
                "small", "1" -> SMALL
                else -> SMALL
            }
        }
    }
}
