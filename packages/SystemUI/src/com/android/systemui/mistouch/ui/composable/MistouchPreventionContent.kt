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
package com.android.systemui.mistouch.ui.composable

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.res.R

private val OVERLAY_COLOR = Color.Black.copy(alpha = 0.9f)
private val DESCRIPTION_COLOR = Color(0xFF767579)
private const val IMAGE_HEIGHT_PORTRAIT_FRACTION = 0.55f
private const val IMAGE_HEIGHT_LANDSCAPE_FRACTION = 0.70f

@Composable
fun MistouchPreventionContent() {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val imageHeightFraction = if (isLandscape) {
        IMAGE_HEIGHT_LANDSCAPE_FRACTION
    } else {
        IMAGE_HEIGHT_PORTRAIT_FRACTION
    }
    val screenHeightDp = configuration.screenHeightDp.dp
    val imageHeight = screenHeightDp * imageHeightFraction

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OVERLAY_COLOR),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 400.dp),
        ) {
            Box(contentAlignment = Alignment.TopCenter) {
                Image(
                    painter = painterResource(R.drawable.keyguard_mistouch_prevention),
                    contentDescription = null,
                    modifier = Modifier.height(imageHeight),
                    contentScale = ContentScale.Fit,
                )
                Text(
                    text = stringResource(R.string.keyguard_mistouch_description),
                    color = DESCRIPTION_COLOR,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    letterSpacing = 0.037.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier
                        .padding(top = imageHeight * 0.175f)
                        .widthIn(max = 140.dp),
                )
            }
            Spacer(modifier = Modifier.height(if (isLandscape) 16.dp else 40.dp))
            Text(
                text = stringResource(R.string.keyguard_mistouch_title),
                color = Color.White,
                fontSize = if (isLandscape) 20.sp else 23.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(if (isLandscape) 8.dp else 15.dp))
            Text(
                text = stringResource(R.string.keyguard_mistouch_exit_desciption),
                color = DESCRIPTION_COLOR,
                fontSize = if (isLandscape) 13.sp else 14.sp,
                textAlign = TextAlign.Center,
                letterSpacing = 0.01.sp,
            )
        }
    }
}
