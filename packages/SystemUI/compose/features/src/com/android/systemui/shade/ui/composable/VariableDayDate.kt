package com.android.systemui.shade.ui.composable

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VariableDayDate(
    longerDateText: String,
    shorterDateText: String,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val textStyle =
        MaterialTheme.typography.bodyMediumEmphasized.copy(
            platformStyle = PlatformTextStyle(includeFontPadding = true)
        )
    Layout(
        contents =
            listOf(
                {
                    Text(
                        text = longerDateText,
                        style = textStyle,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                    )
                },
                {
                    Text(
                        text = shorterDateText,
                        style = textStyle,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                    )
                },
            ),
        modifier = modifier,
    ) { measureables, constraints ->
        check(measureables.size == 2)
        check(measureables[0].size == 1)
        check(measureables[1].size == 1)

        val longerMeasurable = measureables[0][0]
        val shorterMeasurable = measureables[1][0]

        val fallbackConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val maxIntrinsicHeight =
            if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity
        val longerWidth = longerMeasurable.maxIntrinsicWidth(maxIntrinsicHeight)
        val shorterWidth = shorterMeasurable.maxIntrinsicWidth(maxIntrinsicHeight)
        val longerFits = longerWidth <= constraints.maxWidth
        val shorterFits = shorterWidth <= constraints.maxWidth
        val selectedMeasurable = if (longerFits) longerMeasurable else shorterMeasurable
        val selectedConstraints =
            if (!longerFits && !shorterFits) {
                fallbackConstraints
            } else {
                constraints.copy(
                    minWidth = 0,
                    maxWidth = Constraints.Infinity,
                    minHeight = 0,
                    maxHeight = Constraints.Infinity,
                )
            }
        val placeable = selectedMeasurable.measure(selectedConstraints)

        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }
}
