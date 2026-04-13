/*
 * Copyright (C) 2025-2026 AxionOS
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val grayscaleMatrix = ColorMatrix().apply { setToSaturation(0f) }

private const val AOD_ALPHA = 0.35f
private const val NORMAL_ALPHA = 1f

@Composable
fun MediaArt(
    state: MediaArtUiState,
    modifier: Modifier = Modifier
) {
    if (!state.isEnabled || !state.isVisible || state.artworkDrawable == null) return

    val bitmap by produceState<ImageBitmap?>(null, state.artworkDrawable) {
        value = withContext(Dispatchers.IO) {
            state.artworkDrawable.toBitmap().asImageBitmap()
        }
    }
    val imageBitmap = bitmap ?: return

    val targetAlpha = if (state.isDozing) AOD_ALPHA else NORMAL_ALPHA
    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha * state.fadeAlpha,
        animationSpec = tween(durationMillis = 300),
        label = "aod_alpha"
    )

    val colorFilter = remember(state.isDozing) {
        if (state.isDozing) ColorFilter.colorMatrix(grayscaleMatrix) else null
    }

    if (state.artStyle == MEDIA_ART_STYLE_CONCEPT) {
        ConceptModeArt(
            imageBitmap = imageBitmap,
            blurLevel = state.blurLevel,
            colorFilter = colorFilter,
            animatedAlpha = animatedAlpha,
            isDozing = state.isDozing,
            modifier = modifier,
        )
    } else {
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
                bitmap = imageBitmap,
                contentDescription = null,
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
}

@Composable
private fun ConceptModeArt(
    imageBitmap: ImageBitmap,
    blurLevel: Int,
    colorFilter: ColorFilter?,
    animatedAlpha: Float,
    isDozing: Boolean,
    modifier: Modifier = Modifier,
) {
    val bgBlur = maxOf(blurLevel, 30).dp
    val overlayAlpha = if (isDozing) 0.25f else 0.45f

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(animatedAlpha)
    ) {
        Image(
            bitmap = imageBitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = colorFilter,
            modifier = Modifier.fillMaxSize().blur(radius = bgBlur)
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = overlayAlpha))
        )

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val cardSize = (maxWidth * 0.78f).coerceAtMost(380.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(cardSize)
                        .clip(RoundedCornerShape(28.dp))
                )
                Spacer(modifier = Modifier.height(136.dp))
            }
        }
    }
}
