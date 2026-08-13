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

package com.android.systemui.qs.ax.shared.model

import androidx.compose.runtime.Immutable

@Immutable
data class AxQsSpan(val columns: Int, val rows: Int) {
    init {
        require(columns in MIN_COLUMNS..MAX_COLUMNS)
        require(rows in MIN_ROWS..MAX_ROWS)
    }

    fun coerceIn(min: AxQsSpan, max: AxQsSpan): AxQsSpan {
        return AxQsSpan(
            columns = columns.coerceIn(min.columns, max.columns),
            rows = rows.coerceIn(min.rows, max.rows),
        )
    }

    fun coerceForControlTile(columns: Int): AxQsSpan {
        return coerceIn(ControlTileMin, controlTileMax(columns))
    }

    override fun toString(): String = "${columns}x$rows"

    companion object {
        const val MIN_COLUMNS = 1
        const val MAX_COLUMNS = 16
        const val MIN_ROWS = 1
        const val MAX_ROWS = 4

        val TileDefault = AxQsSpan(1, 1)
        val TileWideDefault = AxQsSpan(2, 1)
        val ControlTileMin = TileDefault
        val MediaDefault = AxQsSpan(2, 2)
        val MediaMin = AxQsSpan(2, 1)
        val Max = AxQsSpan(4, 4)

        fun controlTileMax(columns: Int): AxQsSpan {
            return AxQsSpan(
                columns.coerceAtLeast(ControlTileMin.columns).coerceAtMost(Max.columns),
                Max.rows,
            )
        }

        fun parse(value: String): AxQsSpan? {
            val parts = value.split('x', limit = 2)
            if (parts.size != 2) return null
            val columns = parts[0].toIntOrNull() ?: return null
            val rows = parts[1].toIntOrNull() ?: return null
            if (columns !in MIN_COLUMNS..MAX_COLUMNS || rows !in MIN_ROWS..MAX_ROWS) return null
            return AxQsSpan(columns, rows)
        }
    }
}

enum class AxQsControl(
    val id: String,
    val defaultSpan: AxQsSpan,
    val minSpan: AxQsSpan,
    val maxSpan: AxQsSpan,
) {
    BRIGHTNESS(
        id = "control:brightness",
        defaultSpan = AxQsSpan(1, 2),
        minSpan = AxQsSpan(1, 2),
        maxSpan = AxQsSpan(1, AxQsSpan.MAX_ROWS),
    ),
    BRIGHTNESS_HORIZONTAL(
        id = "control:brightness_horizontal",
        defaultSpan = AxQsSpan.TileWideDefault,
        minSpan = AxQsSpan.TileWideDefault,
        maxSpan = AxQsSpan(AxQsSpan.MAX_COLUMNS, 1),
    ),
    VOLUME(
        id = "control:volume",
        defaultSpan = AxQsSpan(1, 2),
        minSpan = AxQsSpan(1, 2),
        maxSpan = AxQsSpan(1, AxQsSpan.MAX_ROWS),
    ),
    VOLUME_HORIZONTAL(
        id = "control:volume_horizontal",
        defaultSpan = AxQsSpan.TileWideDefault,
        minSpan = AxQsSpan.TileWideDefault,
        maxSpan = AxQsSpan(AxQsSpan.MAX_COLUMNS, 1),
    ),
    AUTO_BRIGHTNESS(
        id = "control:auto_brightness",
        defaultSpan = AxQsSpan.TileDefault,
        minSpan = AxQsSpan.TileDefault,
        maxSpan = AxQsSpan.TileDefault,
    ),
    VOLUME_MUTE(
        id = "control:volume_mute",
        defaultSpan = AxQsSpan.TileDefault,
        minSpan = AxQsSpan.TileDefault,
        maxSpan = AxQsSpan.TileDefault,
    ),
    RINGER(
        id = "control:ringer",
        defaultSpan = AxQsSpan(4, 1),
        minSpan = AxQsSpan.TileWideDefault,
        maxSpan = AxQsSpan.Max,
    ),
    MEDIA(
        id = "control:media",
        defaultSpan = AxQsSpan.MediaDefault,
        minSpan = AxQsSpan.MediaMin,
        maxSpan = AxQsSpan(4, 2),
    );

    fun spans(columns: Int): AxQsControlSpans {
        val resolvedMax =
            if (this == MEDIA || this == RINGER || isHorizontalSlider) {
                maxSpan.copy(columns = columns.coerceIn(minSpan.columns, AxQsSpan.MAX_COLUMNS))
            } else {
                maxSpan
            }
        return AxQsControlSpans(defaultSpan.coerceIn(minSpan, resolvedMax), minSpan, resolvedMax)
    }

    fun isSpanAllowed(span: AxQsSpan, columns: Int): Boolean {
        val spans = spans(columns)
        return span == span.coerceIn(spans.min, spans.max)
    }

    fun coerceSpan(span: AxQsSpan, columns: Int): AxQsSpan {
        val spans = spans(columns)
        return when {
            isVerticalSlider -> AxQsSpan(1, span.rows.coerceIn(spans.min.rows, spans.max.rows))
            isHorizontalSlider ->
                AxQsSpan(span.columns.coerceIn(spans.min.columns, spans.max.columns), 1)
            else -> span.coerceIn(spans.min, spans.max)
        }
    }

    fun nextSpan(span: AxQsSpan, columns: Int): AxQsSpan {
        if (!isSlider) return span
        val spans = spans(columns)
        return if (isVerticalSlider) {
            val rows = if (span.rows < spans.max.rows) span.rows + 1 else spans.min.rows
            AxQsSpan(1, rows)
        } else {
            val width =
                if (span.columns < spans.max.columns) span.columns + 1 else spans.min.columns
            AxQsSpan(width, 1)
        }
    }

    fun resizeSpan(startSpan: AxQsSpan, columnDelta: Int, rowDelta: Int, columns: Int): AxQsSpan {
        if (!isSlider) {
            return AxQsSpan(
                columns =
                    (startSpan.columns + columnDelta).coerceIn(
                        AxQsSpan.MIN_COLUMNS,
                        AxQsSpan.MAX_COLUMNS,
                    ),
                rows = (startSpan.rows + rowDelta).coerceIn(AxQsSpan.MIN_ROWS, AxQsSpan.MAX_ROWS),
            )
        }
        val spans = spans(columns)
        return if (isVerticalSlider) {
            AxQsSpan(1, (startSpan.rows + rowDelta).coerceIn(spans.min.rows, spans.max.rows))
        } else {
            AxQsSpan(
                (startSpan.columns + columnDelta).coerceIn(
                    spans.min.columns,
                    spans.max.columns,
                ),
                1,
            )
        }
    }

    val isVerticalSlider: Boolean
        get() = this == BRIGHTNESS || this == VOLUME

    val isHorizontalSlider: Boolean
        get() = this == BRIGHTNESS_HORIZONTAL || this == VOLUME_HORIZONTAL

    val isSlider: Boolean
        get() = isVerticalSlider || isHorizontalSlider

    val canUseTileGrid: Boolean
        get() =
            defaultSpan == AxQsSpan.TileDefault &&
                minSpan == AxQsSpan.TileDefault &&
                maxSpan == AxQsSpan.TileDefault
}

data class AxQsControlSpans(val default: AxQsSpan, val min: AxQsSpan, val max: AxQsSpan)

data class AxQsGridPosition(val column: Int, val row: Int) {
    init {
        require(column >= 0)
        require(row >= 0)
    }

    override fun toString(): String = "$column:$row"

    companion object {
        fun parse(value: String): AxQsGridPosition? {
            val parts = value.split(':', limit = 2)
            if (parts.size != 2) return null
            val column = parts[0].toIntOrNull() ?: return null
            val row = parts[1].toIntOrNull() ?: return null
            if (column < 0 || row < 0) return null
            return AxQsGridPosition(column, row)
        }
    }
}

enum class AxQsGridSection {
    CONTROLS,
    TILES,
}

enum class AxQsGridLayout(
    val layout: AxQsLayout,
    val section: AxQsGridSection,
) {
    PORTRAIT_QQS_CONTROLS(AxQsLayout.QQS, AxQsGridSection.CONTROLS),
    PORTRAIT_QQS_TILES(AxQsLayout.QQS, AxQsGridSection.TILES),
    PORTRAIT_QS_CONTROLS(AxQsLayout.QS, AxQsGridSection.CONTROLS),
    PORTRAIT_QS_TILES(AxQsLayout.QS, AxQsGridSection.TILES),
    SPLIT_SHADE_CONTROLS(AxQsLayout.SPLIT_SHADE, AxQsGridSection.CONTROLS),
    SPLIT_SHADE_TILES(AxQsLayout.SPLIT_SHADE, AxQsGridSection.TILES);

    val isSplitShade: Boolean
        get() = layout == AxQsLayout.SPLIT_SHADE

    val supportsTileLabels: Boolean
        get() = section == AxQsGridSection.TILES && layout != AxQsLayout.QQS

    val showTileLabelsByDefault: Boolean
        get() = supportsTileLabels

    fun columnRange(defaultColumns: Int): IntRange {
        return (defaultColumns - 1).coerceAtLeast(MIN_COLUMNS)..(defaultColumns + 1)
    }

    companion object {
        fun from(layout: AxQsLayout, section: AxQsGridSection): AxQsGridLayout {
            return entries.first { it.layout == layout && it.section == section }
        }

        private const val MIN_COLUMNS = 2
    }
}

enum class AxQsLayout {
    QQS,
    QS,
    SPLIT_SHADE,
}

enum class AxQsPanelMode(val settingValue: Int) {
    TOGETHER(0),
    SEPARATE(1);

    companion object {
        fun fromSetting(value: Int): AxQsPanelMode =
            entries.firstOrNull { it.settingValue == value } ?: TOGETHER
    }
}

enum class AxQsVerticalSliderStyle(val settingValue: Int) {
    M3_EXPRESSIVE(0),
    PLATFORM(1);

    companion object {
        fun fromSetting(value: Int): AxQsVerticalSliderStyle =
            entries.firstOrNull { it.settingValue == value } ?: M3_EXPRESSIVE
    }
}

data class AxQsVerticalSliderKey(val layout: AxQsLayout, val control: AxQsControl)

object AxQsLayoutPadding {
    const val PORTRAIT_SIDE_FRACTION = 0.0637f
    const val LANDSCAPE_SIDE_FRACTION = 0.07f
    const val LANDSCAPE_SPLIT_GRID_SPACING_DP = 28f
}
