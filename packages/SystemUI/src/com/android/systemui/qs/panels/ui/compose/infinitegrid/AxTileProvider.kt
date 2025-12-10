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
package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import androidx.compose.*
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.android.compose.modifiers.*
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.load
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TileHeight
import com.android.systemui.res.R
import com.android.systemui.qs.panels.ui.compose.infinitegrid.SmallTileContent
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import com.android.systemui.theme.UiStyleProvider

object TileGridDefaults {
    val SmallScreenThreshold = 420.dp
    val VerticalPadding = 20.dp
    val FixedColumnsForSmallScreen = 4
    val DefaultLargeTileSpan = 2
}

private object TileConstants {
    val IconSize = 24.dp
    val IconPaddingStart = 24.dp
    val IconPaddingEnd = 10.dp
    val DividerPaddingEnd = 14.dp
    val DividerWidth = 1.dp
    val DividerHeight = 18.dp
    val DividerAlpha = 0.2f
    val LabelEndPadding = 24.dp

    val SingleLabelTileSpecs = setOf("internet", "bt")

    val MarqueeIterations = 1
    val MarqueeInitialDelayMillis = 2000
}

@Stable
data class TileSpacing(
    val tileSpacing: Dp,
    val horizontalMargin: Dp,
)

@Stable
class AxTileProviderViewModel(
    private val coroutineScope: CoroutineScope,
    private val squishinessProvider: () -> Float,
) {
    val squishiness: Float
        @Composable get() = squishinessProvider()

    fun launchBounce(onBounce: suspend () -> Unit) {
        coroutineScope.launch { onBounce() }
    }

    @Composable
    fun rememberBounceClickHandler(
        enabled: Boolean,
        onClick: () -> Unit,
        onBounce: suspend () -> Unit,
    ): () -> Unit {
        val currentOnClick by rememberUpdatedState(onClick)
        val currentOnBounce by rememberUpdatedState(onBounce)

        return remember(enabled, coroutineScope) {
            {
                if (enabled) {
                    currentOnClick()
                    coroutineScope.launch { currentOnBounce() }
                }
            }
        }
    }
}

@Composable
fun rememberAxTileProviderViewModel(
    coroutineScope: CoroutineScope,
    squishiness: () -> Float,
): AxTileProviderViewModel = remember(coroutineScope, squishiness) {
    AxTileProviderViewModel(coroutineScope, squishiness)
}

@Stable
class AxTileProvider(private val viewModel: AxTileProviderViewModel) {

    object Flags {
        var useAxProvider: Boolean = true
    }

    @Composable
    fun IconTile(
        iconProvider: Context.() -> Icon,
        iconColor: Color,
        backgroundColor: Color,
        isClickable: Boolean,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)?,
        onBounce: suspend () -> Unit,
        modifier: Modifier = Modifier,
    ): Boolean {
        IconTileImpl(
            viewModel = viewModel,
            iconProvider = iconProvider,
            iconColor = iconColor,
            backgroundColor = backgroundColor,
            onClick = viewModel.rememberBounceClickHandler(isClickable, onClick, onBounce),
            onLongClick = onLongClick,
            enabled = isClickable,
            modifier = modifier.fillMaxWidth().height(TileHeight),
        )
        return true
    }

    @Composable
    fun LargeTile(
        tileSpec: String,
        label: String,
        secondaryLabel: String?,
        iconProvider: Context.() -> Icon,
        colors: TileColors,
        shape: Shape,
        isClickable: Boolean,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)?,
        onToggleClick: (() -> Unit)?,
        onBounce: suspend () -> Unit,
        modifier: Modifier = Modifier,
    ): Boolean {
        LargeTileImpl(
            viewModel = viewModel,
            tileSpec = tileSpec,
            label = label,
            secondaryLabel = secondaryLabel,
            iconProvider = iconProvider,
            colors = colors,
            shape = shape,
            onClick = viewModel.rememberBounceClickHandler(isClickable, onClick, onBounce),
            onLongClick = onLongClick,
            onToggleClick = onToggleClick?.let {
                viewModel.rememberBounceClickHandler(true, it, onBounce)
            },
            enabled = isClickable,
            modifier = modifier.fillMaxWidth().height(TileHeight),
        )
        return true
    }
}

@Composable
private fun IconTileImpl(
    viewModel: AxTileProviderViewModel,
    iconProvider: Context.() -> Icon,
    iconColor: Color,
    backgroundColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val animatedBgColor by animateColorAsState(backgroundColor, label = "IconTileBg")
    val s = viewModel.squishiness
    val style = UiStyleProvider.rememberCurrentStyle()

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(TileHeight * s)
                .clip(style.qsTileShape())
                .background(animatedBgColor)
                .tileClickable(enabled, onClick, onLongClick),
            contentAlignment = Alignment.Center,
        ) {
            SmallTileContent(
                iconProvider = iconProvider,
                color = iconColor,
                modifier = Modifier.squishy(s),
                size = { style.qsTileIconSize },
            )
        }
    }
}

@Composable
private fun LargeTileImpl(
    viewModel: AxTileProviderViewModel,
    tileSpec: String,
    label: String,
    secondaryLabel: String?,
    iconProvider: Context.() -> Icon,
    colors: TileColors,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onToggleClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val animatedBgColor by animateColorAsState(colors.background, label = "LargeTileBg")
    val hasDualTarget = onToggleClick != null
    val tileSpacing = dimensionResource(R.dimen.qs_tile_margin_horizontal)
    val s = viewModel.squishiness
    val style = UiStyleProvider.rememberCurrentStyle()

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val targetWidth = remember(maxWidth, tileSpacing) {
            val smallTileSize = (maxWidth - tileSpacing) / 2f
            val centerPadding = (smallTileSize - TileHeight) / 2f
            (centerPadding * 2) + (TileHeight * TileGridDefaults.DefaultLargeTileSpan) + tileSpacing
        }

        Box(
            modifier = Modifier
                .size(width = targetWidth * s, height = TileHeight * s)
                .clip(style.qsTileShape())
                .background(animatedBgColor)
                .tileClickable(enabled, onClick, onLongClick),
            contentAlignment = Alignment.CenterStart,
        ) {
            LargeTileContent(
                viewModel = viewModel,
                iconProvider = iconProvider,
                label = label,
                secondaryLabel = secondaryLabel,
                colors = colors,
                tileSpec = tileSpec,
                hasDualTarget = hasDualTarget,
                enabled = enabled,
                onToggleClick = onToggleClick,
                onLongClick = onLongClick,
                squishiness = s,
            )
        }
    }
}

@Composable
private fun TileLabels(
    tileSpec: String,
    label: String,
    secondaryLabel: String?,
    colors: TileColors,
    modifier: Modifier = Modifier,
) {
    val useSingleLabel = tileSpec in TileConstants.SingleLabelTileSpecs
    val fontFamily = rememberSystemFontFamily()

    val (displayLabel, displaySecondary) = remember(useSingleLabel, label, secondaryLabel) {
        if (useSingleLabel && !secondaryLabel.isNullOrEmpty()) {
            secondaryLabel to null
        } else {
            label to secondaryLabel
        }
    }

    val baseStyle = TextStyle(fontFamily = fontFamily)
    val primaryStyle = MaterialTheme.typography.labelLarge.merge(baseStyle.copy(fontSize = 14.sp))
    val secondaryStyle = MaterialTheme.typography.bodySmall.merge(baseStyle.copy(fontSize = 12.sp))

    Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
        MarqueeText(text = displayLabel, style = primaryStyle, color = colors.label)

        displaySecondary?.takeIf { it.isNotEmpty() }?.let {
            MarqueeText(text = it, style = secondaryStyle, color = colors.secondaryLabel)
        }
    }
}

@Composable
private fun LargeTileContent(
    viewModel: AxTileProviderViewModel,
    iconProvider: Context.() -> Icon,
    label: String,
    secondaryLabel: String?,
    colors: TileColors,
    tileSpec: String,
    hasDualTarget: Boolean,
    enabled: Boolean,
    onToggleClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    squishiness: Float,
) {
    Row(
        modifier = Modifier.fillMaxHeight().squishy(squishiness),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(TileConstants.IconPaddingStart))

        Box(
            modifier = Modifier
                .size(TileConstants.IconSize)
                .tileClickable(hasDualTarget && enabled, { onToggleClick?.invoke() }, onLongClick),
            contentAlignment = Alignment.Center,
        ) {
            SmallTileContent(
                iconProvider = iconProvider,
                color = colors.icon,
                size = { TileConstants.IconSize },
            )
        }

        Spacer(
            Modifier.width(
                if (hasDualTarget) TileConstants.IconPaddingEnd
                else TileConstants.DividerPaddingEnd
            )
        )

        if (hasDualTarget) {
            VerticalDivider(
                modifier = Modifier.size(TileConstants.DividerWidth, TileConstants.DividerHeight),
                color = colors.label.copy(alpha = TileConstants.DividerAlpha),
            )
            Spacer(Modifier.width(TileConstants.DividerPaddingEnd))
        }

        TileLabels(
            tileSpec = tileSpec,
            label = label,
            secondaryLabel = secondaryLabel,
            colors = colors,
            modifier = Modifier.weight(1f).padding(end = TileConstants.LabelEndPadding),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarqueeText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    var textWidth by remember { mutableIntStateOf(0) }

    BasicText(
        text = text,
        style = style.copy(color = color),
        maxLines = 1,
        overflow = TextOverflow.Clip,
        onTextLayout = { layoutResult: TextLayoutResult -> textWidth = layoutResult.size.width },
        modifier = modifier
            .fillMaxWidth()
            .thenIf(textWidth > 0) {
                Modifier.basicMarquee(
                    iterations = TileConstants.MarqueeIterations,
                    initialDelayMillis = TileConstants.MarqueeInitialDelayMillis,
                )
            },
    )
}

@Composable
fun rememberAxTileProvider(
    coroutineScope: CoroutineScope,
    squishiness: () -> Float,
): AxTileProvider {
    val viewModel = rememberAxTileProviderViewModel(coroutineScope, squishiness)
    return remember(viewModel) { AxTileProvider(viewModel) }
}

@Composable
fun rememberTileSpacing(): TileSpacing {
    val tileSpacing = dimensionResource(R.dimen.qs_tile_margin_horizontal)
    val horizontalMargin = dimensionResource(R.dimen.qs_horizontal_margin)
    return remember(tileSpacing, horizontalMargin) { TileSpacing(tileSpacing, horizontalMargin) }
}

@Composable
fun rememberTileColumns(): Int {
    val config = LocalConfiguration.current
    val spacing = rememberTileSpacing()

    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    val effectiveWidthDp = if (isLandscape) {
        (config.screenHeightDp.dp / 2)
    } else {
        config.screenWidthDp.dp
    }

    return remember(effectiveWidthDp, spacing, isLandscape) {
        val columns = if (effectiveWidthDp < TileGridDefaults.SmallScreenThreshold) {
            TileGridDefaults.FixedColumnsForSmallScreen
        } else {
            val availableWidth = effectiveWidthDp - (spacing.horizontalMargin * 2)
            ((availableWidth + spacing.tileSpacing) / (TileHeight + spacing.tileSpacing))
                .toInt()
                .coerceAtLeast(TileGridDefaults.FixedColumnsForSmallScreen)
        }
        if (columns % 2 != 0) columns - 1 else columns
    }
}

@SuppressLint("DiscouragedApi")
private fun Context.getAndroidConfig(configName: String): String {
    val configId = resources.getIdentifier(configName, "string", "android")
    return if (configId != 0) resources.getString(configId) else "sans-serif"
}

@Composable
private fun rememberSystemFontFamily(): FontFamily {
    val context = LocalContext.current
    val fontName = remember(context) { context.getAndroidConfig("config_bodyFontFamily") }
    return remember(fontName) { FontFamily(Typeface.create(fontName, Typeface.NORMAL)) }
}

@Composable
private fun rememberIcon(iconProvider: Context.() -> Icon): Icon {
    val context = LocalContext.current
    return remember(iconProvider) { context.iconProvider() }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.tileClickable(
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
): Modifier = thenIf(enabled) {
    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
}

private fun Modifier.squishy(squishiness: Float): Modifier = graphicsLayer {
    scaleX = squishiness
    scaleY = squishiness
    alpha = squishiness
}
