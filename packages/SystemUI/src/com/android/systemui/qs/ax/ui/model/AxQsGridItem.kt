/*
 * Copyright 2025-2026 AxionOS
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

package com.android.systemui.qs.ax.ui.model

import com.android.systemui.qs.ax.shared.model.AxQsControl
import com.android.systemui.qs.ax.shared.model.AxQsGridPosition
import com.android.systemui.qs.ax.shared.model.AxQsSpan
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel

data class AxQsGridItem<T>(
    val id: String,
    val span: AxQsSpan,
    val minSpan: AxQsSpan,
    val maxSpan: AxQsSpan,
    val value: T,
    val position: AxQsGridPosition? = null,
)

sealed interface AxQsGridValue {
    data class Tile(val viewModel: TileViewModel) : AxQsGridValue

    data class Control(val control: AxQsControl) : AxQsGridValue
}

enum class AxMediaSurface(val dismissible: Boolean) {
    CONTROL(false),
    SEPARATE_QQS(true),
    LOCKSCREEN(true),
}
