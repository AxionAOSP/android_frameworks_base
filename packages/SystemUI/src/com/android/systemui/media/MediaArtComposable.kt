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
package com.android.systemui.media

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

private val grayscaleMatrix = ColorMatrix().apply { setToSaturation(0f) }

private const val AOD_ALPHA = 0.35f
private const val NORMAL_ALPHA = 1f

@Composable
fun MediaArt(
    state: MediaArtUiState,
    modifier: Modifier = Modifier
) {
    if (!state.isEnabled || !state.isVisible || state.artworkDrawable == null) return

    val bitmap = remember(state.artworkDrawable) {
        state.artworkDrawable.toBitmap()
    }

    val targetAlpha = if (state.isDozing) AOD_ALPHA else NORMAL_ALPHA
    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha * state.fadeAlpha,
        animationSpec = tween(durationMillis = 300),
        label = "aod_alpha"
    )

    val colorFilter = remember(state.isDozing) {
        if (state.isDozing) ColorFilter.colorMatrix(grayscaleMatrix) else null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(animatedAlpha)
    ) {
        val imageModifier = if (state.blurLevel > 0) {
            Modifier.fillMaxSize().blur(radius = state.blurLevel.dp)
        } else {
            Modifier.fillMaxSize()
        }

        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Media artwork",
            contentScale = ContentScale.Crop,
            colorFilter = colorFilter,
            modifier = imageModifier
        )
        
        val overlayAlpha = if (state.isDozing) 0.05f else 0.1f
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = overlayAlpha))
        )
    }
}
