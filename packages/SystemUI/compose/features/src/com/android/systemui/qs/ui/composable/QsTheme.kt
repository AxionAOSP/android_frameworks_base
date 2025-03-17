package com.android.systemui.qs.ui.composable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

import com.android.compose.theme.colorAttr

import com.android.systemui.res.R

data class QsColors(
    val activeBackground: Color,
    val activeLabel: Color,
    val activeSecondaryLabel: Color,
    val inactiveBackground: Color,
    val inactiveLabel: Color,
    val inactiveSecondaryLabel: Color,
    val unavailableBackground: Color,
    val unavailableLabel: Color,
    val unavailableSecondaryLabel: Color
)

@Composable
fun QsTheme(): QsColors {
    return QsColors(
        activeBackground = colorAttr(R.attr.shadeActive),
        activeLabel = colorAttr(R.attr.onShadeActive),
        activeSecondaryLabel = colorAttr(R.attr.onShadeActiveVariant),
        inactiveBackground = colorAttr(R.attr.shadeInactive),
        inactiveLabel = colorAttr(R.attr.onShadeInactive),
        inactiveSecondaryLabel = colorAttr(R.attr.onShadeInactiveVariant),
        unavailableBackground = colorAttr(R.attr.shadeInactive),
        unavailableLabel = colorAttr(R.attr.outline),
        unavailableSecondaryLabel = colorAttr(R.attr.outline)
    )
}

