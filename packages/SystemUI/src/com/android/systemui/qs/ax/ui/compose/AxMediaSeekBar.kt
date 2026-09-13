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

package com.android.systemui.qs.ax.ui.compose

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.media.controls.ui.drawable.SquigglyProgress
import com.android.systemui.media.controls.ui.view.WaveformSeekBar
import com.android.systemui.media.remedia.domain.model.MediaSessionModel
import com.android.systemui.media.remedia.shared.model.MediaSessionState
import com.android.systemui.qs.ax.ui.viewmodel.AxMediaViewModel
import com.android.systemui.res.R

private class SeekBarStateHolder {
    var lastColorArgb: Int = 0
    var lastPlaying: Boolean = false
    var lastThumbTintList: ColorStateList? = null
    var lastProgressBackgroundTintList: ColorStateList? = null
}

@Composable
@SuppressLint("ClickableViewAccessibility")
internal fun MediaSeekBar(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    dense: Boolean,
    interactive: Boolean,
    modifier: Modifier = Modifier,
) {
    val height = if (dense) 20.dp else 32.dp
    val currentSession by rememberUpdatedState(session)
    val currentViewModel by rememberUpdatedState(viewModel)
    val progress = session?.let(viewModel::progress) ?: 0f
    val seekDescription =
        session
            ?.let {
                stringResource(
                    R.string.controls_media_seekbar_description,
                    DateUtils.formatElapsedTime((progress * it.durationMs).toLong() / 1000L),
                    DateUtils.formatElapsedTime(it.durationMs / 1000L),
                )
            }
            .orEmpty()

    val stateHolder = remember { SeekBarStateHolder() }

    Column(modifier = modifier) {
        AndroidView(
            factory = { context ->
                var start = Offset.Zero
                var delta = Offset.Zero
                WaveformSeekBar(context).apply {
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    splitTrack = false
                    showThumbWhenDisabled = true
                    setPadding(0, 0, 0, 0)
                    thumbOffset = 0
                    setOnTouchListener { view, event ->
                        val position = Offset(event.x, event.y)
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                view.parent?.requestDisallowInterceptTouchEvent(true)
                                start = position
                                delta = Offset.Zero
                            }
                            MotionEvent.ACTION_MOVE -> delta = position - start
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_CANCEL -> {
                                delta = position - start
                                view.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                        false
                    }
                    setOnSeekBarChangeListener(
                        object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(
                                seekBar: SeekBar,
                                progress: Int,
                                fromUser: Boolean,
                            ) {
                                val media = currentSession
                                if (fromUser && media != null && seekBar.max > 0) {
                                    currentViewModel.onScrubChange(
                                        media,
                                        progress.toFloat() / seekBar.max,
                                    )
                                }
                            }

                            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                            override fun onStopTrackingTouch(seekBar: SeekBar) {
                                currentSession?.let { currentViewModel.onScrubFinished(it, delta) }
                            }
                        }
                    )
                }
            },
            update = { seekBar ->
                val duration =
                    session?.durationMs?.coerceIn(1L, Int.MAX_VALUE.toLong())?.toInt() ?: 1
                if (seekBar.max != duration) {
                    seekBar.max = duration
                }
                if (!seekBar.isPressed) {
                    seekBar.progress = (progress * duration).toInt().coerceIn(0, duration)
                }
                val enabled = interactive && session?.canBeScrubbed == true
                if (seekBar.isEnabled != enabled) {
                    seekBar.isEnabled = enabled
                }
                seekBar.contentDescription = seekDescription

                val accent = colors.primary.toArgb()
                if (stateHolder.lastColorArgb != accent || seekBar.progressTintList?.defaultColor != accent) {
                    stateHolder.lastColorArgb = accent
                    val tint = ColorStateList.valueOf(accent)
                    val bgTint = ColorStateList.valueOf(colors.foreground.copy(alpha = 0.3f).toArgb())
                    stateHolder.lastProgressBackgroundTintList = bgTint

                    seekBar.setMediaColor(accent)
                    seekBar.thumbTintList = tint
                    seekBar.progressTintList = tint
                    seekBar.progressBackgroundTintList = bgTint
                }

                val playing = session?.state == MediaSessionState.Playing && !seekBar.isPressed
                val squiggly = seekBar.progressDrawable as? SquigglyProgress
                if (squiggly != null) {
                    if (squiggly.animate != playing) {
                        squiggly.animate = playing
                    }
                    squiggly.setTint(accent)
                }
                seekBar.setWaveformPlaying(playing)
            },
            modifier = Modifier.fillMaxWidth().height(height),
        )
    }
}
