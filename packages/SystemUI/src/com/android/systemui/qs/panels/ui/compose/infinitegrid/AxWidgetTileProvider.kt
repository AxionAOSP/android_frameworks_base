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

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.*
import com.android.systemui.common.ringer.*
import com.android.systemui.common.slider.*
import com.android.systemui.SystemUIApplication
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TileHeight
import com.android.systemui.res.R
import com.android.systemui.util.AxColorScheme
import javax.inject.Inject

@Stable
@SysUISingleton
class AxWidgetTileProvider @Inject constructor(
    private val context: Context,
    private val torchInteractor: TorchLevelInteractor,
    private val volumeInteractor: VolumeInteractor,
    private val ringerInteractor: RingerModeInteractorImpl
) {

    companion object {
        @Composable
        fun get(): AxWidgetTileProvider {
            val context = LocalContext.current.applicationContext as SystemUIApplication
            val component = context.sysUIComponent
            return remember { component.getAxWidgetTileProvider() }
        }
    }

    fun isWidgetTile(spec: String): Boolean {
        return when (spec) {
            "sound", "volume" -> true
            "flashlight" -> torchInteractor.isSupported
            else -> false
        }
    }

    @Composable
    fun provideAxTile(
        spec: String,
        shape: Shape,
        squishinessProvider: () -> Float,
        modifier: Modifier = Modifier
    ): Boolean {
        return when (spec) {
            "sound" -> {
                WidgetTileContainer(shape, squishinessProvider, modifier) {
                    RingerSliderContent()
                }
                true
            }
            "volume" -> {
                WidgetTileContainer(shape, squishinessProvider, modifier) {
                    VolumeSliderContent()
                }
                true
            }
            "flashlight" -> {
                if (torchInteractor.isSupported) {
                    WidgetTileContainer(shape, squishinessProvider, modifier) {
                        TorchSliderContent()
                    }
                    true
                } else false
            }
            else -> false
        }
    }

    @Composable
    private fun WidgetTileContainer(
        shape: Shape,
        squishinessProvider: () -> Float,
        modifier: Modifier,
        content: @Composable () -> Unit
    ) {
        val tileSpacing = dimensionResource(R.dimen.qs_tile_margin_horizontal)

        BoxWithConstraints(
            modifier = modifier.fillMaxWidth().height(TileHeight),
            contentAlignment = Alignment.Center
        ) {
            val targetWidth = remember(maxWidth, tileSpacing) {
                val smallTileSize = (maxWidth - tileSpacing) / 2f
                val centerPadding = (smallTileSize - TileHeight) / 2f
                (centerPadding * 2) + (TileHeight * TileGridDefaults.DefaultLargeTileSpan) + tileSpacing
            }

            Box(
                modifier = Modifier
                    .size(width = targetWidth, height = TileHeight)
                    .graphicsLayer {
                        val s = squishinessProvider()
                        scaleX = s
                        scaleY = s
                        alpha = s
                    }
                    .clip(shape),
                contentAlignment = Alignment.Center,
            ) {
                content()
            }
        }
    }

    @Composable
    private fun RingerSliderContent() {
        val interactor = remember { ringerInteractor }
        RingerSliderWidget(
            interactor = interactor,
            theme = AxWidgetTheme,
            dimens = AxWidgetRingerDimens,
            modifier = Modifier.fillMaxSize(),
            isDozing = false
        )
    }

    @Composable
    private fun VolumeSliderContent() {
        val interactor = remember { volumeInteractor }
        LevelSliderWidget(
            interactor = interactor,
            theme = AxWidgetTheme,
            dimens = AxWidgetLevelDimens,
            modifier = Modifier.fillMaxSize(),
            isDozing = false
        )
    }

    @Composable
    private fun TorchSliderContent() {
        val interactor = remember { torchInteractor }
        LevelSliderWidget(
            interactor = interactor,
            theme = AxWidgetTheme,
            dimens = AxWidgetLevelDimens,
            modifier = Modifier.fillMaxSize(),
            isDozing = false
        )
    }
}

@Stable
object AxWidgetTheme : RingerSliderTheme, LevelSliderTheme {
    override val activeBg: Color
        @Composable get() = AxColorScheme.primary
    
    override val neutralBg: Color
        @Composable get() = AxColorScheme.secondary
    
    override val activeIcon: Color
        @Composable get() = AxColorScheme.onPrimary
    
    override val neutralIcon: Color
        @Composable get() = AxColorScheme.onSurface
    
    override val labelColor: Color
        @Composable get() = AxColorScheme.onSurface
    
    override val dozeStroke: Dp = 2.dp
}

@Stable
object AxWidgetRingerDimens : RingerSliderDimens {
    override val totalWidth: Dp? = null
    override val thumbSize: Dp get() = TileHeight
    override val iconSize: Dp = 24.dp
    override val thumbPadding: Dp = 8.dp
    override val dotSize: Dp = 6.dp
}

@Stable
object AxWidgetLevelDimens : LevelSliderDimens {
    override val totalWidth: Dp? = null
    override val height: Dp get() = TileHeight
    override val iconSize: Dp = 24.dp
    override val horizontalPadding: Dp = 16.dp
    override val labelPadding: Dp = 12.dp
}
