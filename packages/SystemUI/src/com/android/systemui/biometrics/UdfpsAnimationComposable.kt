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
package com.android.systemui.biometrics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.android.systemui.res.R

@Composable
fun UdfpsAnimation(
    state: UdfpsAnimationUiState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val animType = context.resources.getString(R.string.config_udfps_animation_type)

    val animationSizeDp = with(LocalDensity.current) {
        state.animationSize.toDp()
    }

    AnimatedVisibility(
        visible = state.isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.size(animationSizeDp)
        ) {
            if (animType == "drawable") {
                val drawable = remember(context) { context.getDrawable(R.drawable.udfps_animation) }
                val painter = rememberDrawablePainter(drawable)
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.size(animationSizeDp),
                    contentScale = ContentScale.Fit
                )
            } else {
                val compositionResult = rememberLottieComposition(
                    LottieCompositionSpec.RawRes(R.raw.nt_udfps_lockscreen_fp_scanning)
                )
                val composition by compositionResult
                val progress by animateLottieCompositionAsState(
                    composition = composition,
                    iterations = LottieConstants.IterateForever,
                    speed = 1.7f,
                    isPlaying = true
                )

                LottieAnimation(
                    composition = composition,
                    progress = { progress },
                    modifier = Modifier.size(animationSizeDp)
                )
            }
        }
    }
}
