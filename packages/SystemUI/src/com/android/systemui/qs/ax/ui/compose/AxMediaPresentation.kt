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

import android.R as AndroidR
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.compose.animation.Easings
import com.android.compose.animation.Expandable as ExpandableContainer
import com.android.compose.animation.rememberExpandableController
import com.android.systemui.common.shared.model.Icon as IconModel
import com.android.systemui.common.shared.model.asImageBitmap
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.media.remedia.domain.model.MediaSessionModel
import com.android.systemui.qs.ax.ui.model.AxMediaSurface
import com.android.systemui.qs.ax.ui.viewmodel.AxMediaViewModel
import com.android.systemui.res.R
import kotlin.math.max

@Immutable
internal data class AxMediaColors(
    val primary: Color,
    val onPrimary: Color,
    val background: Color,
    val foreground: Color,
)

@Immutable
private data class MediaArtworkState(
    val songKey: String,
    val artwork: IconModel?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaArtworkState) return false
        return songKey == other.songKey
    }

    override fun hashCode(): Int = songKey.hashCode()
}

@Composable
internal fun MediaArtwork(
    artwork: IconModel?,
    songKey: String,
    overlayColor: Color,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = MediaArtworkState(songKey = songKey, artwork = artwork),
        transitionSpec = {
            (fadeIn(animationSpec = tween(durationMillis = 400, easing = Easings.EmphasizedDecelerate)) +
                scaleIn(
                    initialScale = 1.15f,
                    animationSpec = tween(durationMillis = 500, easing = Easings.EmphasizedDecelerate),
                )) togetherWith
                (fadeOut(animationSpec = tween(durationMillis = 350, easing = Easings.EmphasizedAccelerate)) +
                    scaleOut(
                        targetScale = 0.95f,
                        animationSpec = tween(durationMillis = 350, easing = Easings.EmphasizedAccelerate),
                    ))
        },
        label = "AxMediaSongArtwork",
        modifier = modifier.fillMaxSize(),
    ) { targetState ->
        val currentArtwork = targetState.artwork
        val overlayModifier =
            Modifier.fillMaxSize().drawWithCache {
                val radius = max(size.width, size.height) / 2f
                val brushCenter = Offset(size.width / 2f, size.height / 2f)
                val brush =
                    Brush.radialGradient(
                        0f to overlayColor.copy(alpha = 0.65f),
                        1f to overlayColor.copy(alpha = 0.75f),
                        center = brushCenter,
                        radius = radius,
                    )
                onDrawWithContent {
                    drawContent()
                    drawRect(brush)
                }
            }
        when (currentArtwork) {
            null -> Unit
            is IconModel.Loaded -> {
                val bitmap = remember(targetState.songKey) { currentArtwork.asImageBitmap() }
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = overlayModifier,
                )
            }
            is IconModel.Resource ->
                Icon(icon = currentArtwork, tint = Color.Unspecified, modifier = overlayModifier)
        }
    }
}

@Composable
internal fun MediaAppIcon(
    session: MediaSessionModel?,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    if (session != null) {
        Icon(icon = session.appIcon, tint = tint, modifier = modifier.size(size).clip(CircleShape))
    } else {
        MaterialIcon(
            painter = painterResource(R.drawable.ic_music_note),
            contentDescription = null,
            tint = tint,
            modifier = modifier.size(size),
        )
    }
}

@Composable
internal fun MediaOutputChip(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    interactive: Boolean,
    showLabel: Boolean,
    compact: Boolean,
    label: String? = null,
    modifier: Modifier = Modifier,
) {
    val outputDescription =
        session?.outputDevice?.name?.takeUnless { it.isBlank() || it == "null" }
            ?: stringResource(R.string.ax_dynamic_bar_media_output)
    val interactionSource = remember { MutableInteractionSource() }
    val chipHeight = if (compact) 20.dp else 24.dp
    val iconSize = if (compact) 12.dp else 13.dp

    ExpandableContainer(
        controller =
            rememberExpandableController(color = { Color.Transparent }, shape = CircleShape),
        modifier = modifier,
        defaultMinSize = false,
        useModifierBasedImplementation = true,
    ) { expandable ->
        val chipBackground = colors.primary
        val contentColor = colors.onPrimary
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.clip(CircleShape)
                    .background(chipBackground)
                    .indication(interactionSource, ripple())
                    .heightIn(min = chipHeight)
                    .semantics { contentDescription = outputDescription }
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = interactive && session != null,
                    ) {
                        session?.let { viewModel.openOutput(it.outputDevice, expandable) }
                    }
                    .then(
                        if (showLabel) {
                            Modifier.padding(
                                horizontal = if (compact) 8.dp else 10.dp,
                                vertical = if (compact) 2.dp else 3.dp,
                            )
                        } else {
                            Modifier.size(chipHeight)
                        }
                    ),
        ) {
            if (session != null) {
                Icon(
                    icon = session.outputDevice.icon,
                    tint = contentColor,
                    modifier = Modifier.size(iconSize),
                )
            } else {
                MaterialIcon(
                    painter = painterResource(R.drawable.ic_phone_expressive),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(iconSize),
                )
            }
            if (showLabel) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = label ?: outputDescription,
                    color = contentColor,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun AnimatedMediaText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    AnimatedContent(
        targetState = text,
        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
        contentAlignment = Alignment.CenterStart,
        label = "AxMediaText",
        modifier = modifier,
    ) { value ->
        Text(
            text = value,
            color = color,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth().basicMarquee(iterations = 1),
        )
    }
}

@Composable
internal fun MediaTimestamps(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    modifier: Modifier = Modifier,
) {
    val progress = session?.let(viewModel::progress) ?: 0f
    val totalMs = session?.durationMs ?: 0L
    val elapsedMs = (progress * totalMs).toLong()

    val elapsedSeconds = elapsedMs / 1000L
    val totalSeconds = totalMs.coerceAtLeast(0L) / 1000L
    val elapsedStr = remember(elapsedSeconds) { DateUtils.formatElapsedTime(elapsedSeconds) }
    val totalStr = remember(totalSeconds) { DateUtils.formatElapsedTime(totalSeconds) }

    Row(
        modifier = modifier.fillMaxWidth().padding(top = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = elapsedStr,
            color = colors.foreground.copy(alpha = 0.65f),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        )
        Text(
            text = totalStr,
            color = colors.foreground.copy(alpha = 0.65f),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        )
    }
}

@Composable
internal fun MediaGuts(
    session: MediaSessionModel,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    compact: Boolean,
    surface: AxMediaSurface,
) {
    val message =
        if (session.canBeHidden) {
            stringResource(R.string.controls_media_close_session, session.appName)
        } else {
            stringResource(R.string.controls_media_active_session)
        }
    Box(Modifier.fillMaxSize().padding(12.dp)) {
        IconButton(onClick = viewModel::openSettings, modifier = Modifier.align(Alignment.TopEnd)) {
            MaterialIcon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = stringResource(R.string.controls_media_settings_button),
                tint = colors.foreground,
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
        ) {
            Text(
                text = message,
                color = colors.foreground,
                style = MaterialTheme.typography.labelMedium,
                maxLines = if (compact) 3 else 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(end = 48.dp),
            )
            val actionButtons: @Composable () -> Unit = {
                if (session.canBeHidden) {
                    Button(onClick = { viewModel.dismissFromSurface(session, surface) }) {
                        Text(stringResource(R.string.controls_media_dismiss_button))
                    }
                }
                OutlinedButton(onClick = viewModel::cancelGuts) {
                    Text(stringResource(AndroidR.string.cancel))
                }
            }
            if (compact) {
                actionButtons()
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = { actionButtons() },
                )
            }
        }
    }
}
