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

import androidx.compose.animation.core.CubicBezierEasing

internal const val FONT_FAMILY_BODY = "variable-title-small"
internal const val FONT_FAMILY_DATE = "variable-title-medium-emphasized"

internal const val SMALL_CLOCK_BOTTOM_PAD_DP = 12f

internal const val ALARM_VISIBILITY_HOURS = 12L

internal const val CLOCK_PATTERN_12 = "hhmm"
internal const val CLOCK_PATTERN_12_STANDARD = "hh:mm"
internal const val CLOCK_PATTERN_24 = "HHmm"
internal const val CLOCK_PATTERN_24_STANDARD = "HH:mm"
internal const val CLOCK_PATTERN_ALL = "hhmmss"

internal const val APPEAR_DURATION = 400L

const val PREVIEW_TIME_12 = "1008"
const val PREVIEW_TIME_12_STANDARD = "10:08"
const val PREVIEW_TIME_24 = "1008"
const val PREVIEW_TIME_24_STANDARD = "10:08"
const val PREVIEW_TIME_ALL = "100830"
const val PREVIEW_DATE = "Wed, 12 Mar"

internal const val COMPOSE_FIDGET_SQUEEZE = 0.95f
internal const val COMPOSE_FIDGET_EXPAND = 1.03f
internal const val COMPOSE_FIDGET_PHASE_MS = 120
internal const val COMPOSE_FIDGET_SETTLE_MS = 150
internal val COMPOSE_FIDGET_EASING = CubicBezierEasing(0.26873f, 0f, 0.45042f, 1f)

internal const val DOZE_WAKE_START = 0.96f
internal const val DOZE_WAKE_MS = 600
internal val DOZE_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)
