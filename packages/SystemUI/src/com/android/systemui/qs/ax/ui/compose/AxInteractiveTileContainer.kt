/*
 * Copyright 2025-2026 AxionOS
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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import com.android.compose.modifiers.thenIf
import com.android.systemui.qs.ax.shared.model.AxQsSpan
import com.android.systemui.qs.panels.ui.compose.selection.TileState
import com.android.systemui.qs.panels.ui.compose.selection.TileState.Selected
import com.android.systemui.qs.ui.compose.borderOnFocus
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
internal fun AxInteractiveTileContainer(
    tileState: TileState,
    resizeHandleModifier: Modifier,
    selectionColor: Color,
    selectionShape: Shape,
    selectionHorizontalPadding: Dp,
    selectionVerticalPadding: Dp,
    resizable: Boolean,
    modifier: Modifier = Modifier,
    onRemoveClick: () -> Unit,
    onResizeClick: () -> Unit,
    removeContentDescription: String? = null,
    resizeContentDescription: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val selected = tileState == Selected
    val borderAlpha by animateFloatAsState(if (selected) 1f else 0f)
    Box(modifier.zIndex(if (selected) 2f else 1f)) {
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .selectionDecoration(
                        color = selectionColor,
                        borderWidth = 3.dp,
                        handleWidth = 6.dp,
                        handleExtent = 28.dp,
                        shape = selectionShape,
                        horizontalPadding = selectionHorizontalPadding,
                        verticalPadding = selectionVerticalPadding,
                        alpha = borderAlpha,
                    ),
            content = content,
        )
        RemoveBadge(contentDescription = removeContentDescription, onClick = onRemoveClick)
        ResizeHandle(
            visible = selected && resizable,
            modifier = resizeHandleModifier,
            contentDescription = resizeContentDescription,
            onClick = onResizeClick,
        )
    }
}

@Composable
private fun BoxScope.RemoveBadge(contentDescription: String?, onClick: () -> Unit) {
    val touchSize = LocalMinimumInteractiveComponentSize.current
    Box(
        modifier =
            Modifier.align(Alignment.TopEnd)
                .offset(x = RemoveBadgeSize / 2, y = -RemoveBadgeSize / 2)
                .size(touchSize)
                .zIndex(2f)
                .clickable(onClick = onClick)
                .semantics { contentDescription?.let { this.contentDescription = it } }
                .borderOnFocus(MaterialTheme.colorScheme.secondary, CornerSize(50)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier.size(RemoveBadgeSize)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = null,
                modifier = Modifier.size(RemoveBadgeIconSize),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun BoxScope.ResizeHandle(
    visible: Boolean,
    modifier: Modifier,
    contentDescription: String?,
    onClick: () -> Unit,
) {
    val touchSize = LocalMinimumInteractiveComponentSize.current
    Spacer(
        modifier =
            Modifier.align(Alignment.BottomEnd)
                .offset(x = touchSize / 2, y = touchSize / 2)
                .size(touchSize)
                .zIndex(2f)
                .thenIf(visible) {
                    Modifier.systemGestureExclusion { Rect(Offset.Zero, it.size.toSize()) }
                        .then(modifier)
                        .clickable(onClick = onClick)
                        .semantics { contentDescription?.let { this.contentDescription = it } }
                }
                .borderOnFocus(MaterialTheme.colorScheme.secondary, CornerSize(50))
    )
}

private val RemoveBadgeSize = 24.dp
private val RemoveBadgeIconSize = 16.dp

private fun Modifier.selectionDecoration(
    color: Color,
    borderWidth: Dp,
    handleWidth: Dp,
    handleExtent: Dp,
    shape: Shape,
    horizontalPadding: Dp,
    verticalPadding: Dp,
    alpha: Float,
): Modifier {
    return drawWithCache {
        val borderWidthPx = borderWidth.toPx()
        val handleWidthPx = handleWidth.toPx()
        val handleExtentPx = handleExtent.toPx()
        val horizontalPaddingPx = horizontalPadding.toPx() + borderWidthPx / 2
        val verticalPaddingPx = verticalPadding.toPx() + borderWidthPx / 2
        onDrawWithContent {
            drawContent()
            inset(horizontalPaddingPx, verticalPaddingPx, horizontalPaddingPx, verticalPaddingPx) {
                val outline = shape.createOutline(size, layoutDirection, this)
                val handleLeft =
                    if (layoutDirection == LayoutDirection.Ltr) {
                        (size.width - handleExtentPx).coerceAtLeast(0f)
                    } else {
                        0f
                    }
                val handleRight =
                    if (layoutDirection == LayoutDirection.Ltr) {
                        size.width
                    } else {
                        handleExtentPx.coerceAtMost(size.width)
                    }
                val handleTop = (size.height - handleExtentPx).coerceAtLeast(0f)
                drawOutline(
                    outline = outline,
                    color = color,
                    style = Stroke(borderWidthPx),
                    alpha = alpha,
                )
                clipRect(
                    left = handleLeft,
                    top = handleTop,
                    right = handleRight + handleWidthPx,
                    bottom = size.height + handleWidthPx,
                ) {
                    drawOutline(
                        outline = outline,
                        color = color,
                        style =
                            Stroke(handleWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        alpha = alpha,
                    )
                }
                outline.handleCapCenters(layoutDirection, handleExtentPx)?.let {
                    drawCircle(
                        color = color,
                        radius = handleWidthPx / 2,
                        center = it.first,
                        alpha = alpha,
                    )
                    drawCircle(
                        color = color,
                        radius = handleWidthPx / 2,
                        center = it.second,
                        alpha = alpha,
                    )
                }
            }
        }
    }
}

private fun Outline.handleCapCenters(
    layoutDirection: LayoutDirection,
    handleExtent: Float,
): Pair<Offset, Offset>? {
    if (this !is Outline.Rounded) return null
    val corner =
        if (layoutDirection == LayoutDirection.Ltr) {
            roundRect.bottomRightCornerRadius
        } else {
            roundRect.bottomLeftCornerRadius
        }
    val top = (roundRect.bottom - handleExtent).coerceAtLeast(roundRect.top)
    val centerY = roundRect.bottom - corner.y
    val verticalFactor = ellipseFactor(top - centerY, corner.y)
    return if (layoutDirection == LayoutDirection.Ltr) {
        val left = (roundRect.right - handleExtent).coerceAtLeast(roundRect.left)
        val centerX = roundRect.right - corner.x
        val verticalX =
            if (top <= centerY || corner.y == 0f) {
                roundRect.right
            } else {
                centerX + corner.x * verticalFactor
            }
        val horizontalY =
            if (left <= centerX || corner.x == 0f) {
                roundRect.bottom
            } else {
                centerY + corner.y * ellipseFactor(left - centerX, corner.x)
            }
        Offset(verticalX, top) to Offset(left, horizontalY)
    } else {
        val right = (roundRect.left + handleExtent).coerceAtMost(roundRect.right)
        val centerX = roundRect.left + corner.x
        val verticalX =
            if (top <= centerY || corner.y == 0f) {
                roundRect.left
            } else {
                centerX - corner.x * verticalFactor
            }
        val horizontalY =
            if (right >= centerX || corner.x == 0f) {
                roundRect.bottom
            } else {
                centerY + corner.y * ellipseFactor(centerX - right, corner.x)
            }
        Offset(verticalX, top) to Offset(right, horizontalY)
    }
}

private fun ellipseFactor(offset: Float, radius: Float): Float {
    if (radius == 0f) return 0f
    val normalized = (offset / radius).coerceIn(-1f, 1f)
    return sqrt((1f - normalized * normalized).coerceAtLeast(0f))
}

@Composable
internal fun Modifier.axQsResizeHandle(
    id: String,
    span: () -> AxQsSpan,
    itemSize: () -> IntSize,
    spacing: Dp,
    resolveSpan: (AxQsSpan, Int, Int) -> AxQsSpan,
    canResize: (AxQsSpan) -> Boolean,
    onResizeStarted: () -> Unit,
    onResizeStopped: () -> Unit,
    onResize: (AxQsSpan) -> Unit,
    onResizeFinished: (AxQsSpan) -> Unit,
): Modifier {
    val currentSpan by rememberUpdatedState(span)
    val currentItemSize by rememberUpdatedState(itemSize)
    val currentResolveSpan by rememberUpdatedState(resolveSpan)
    val currentCanResize by rememberUpdatedState(canResize)
    val currentOnResizeStarted by rememberUpdatedState(onResizeStarted)
    val currentOnResizeStopped by rememberUpdatedState(onResizeStopped)
    val currentOnResize by rememberUpdatedState(onResize)
    val currentOnResizeFinished by rememberUpdatedState(onResizeFinished)
    val layoutDirection = LocalLayoutDirection.current
    val spacingPx = with(LocalDensity.current) { spacing.toPx() }

    return pointerInput(id, layoutDirection, spacingPx) {
        var startSpan = currentSpan()
        var dragOffset = Offset.Zero
        var columnStep = 1f
        var rowStep = 1f
        detectDragGestures(
            onDragStart = {
                currentOnResizeStarted()
                startSpan = currentSpan()
                dragOffset = Offset.Zero
                val size = currentItemSize()
                columnStep = ((size.width + spacingPx) / startSpan.columns).coerceAtLeast(1f)
                rowStep = ((size.height + spacingPx) / startSpan.rows).coerceAtLeast(1f)
            },
            onDragCancel = {
                currentOnResize(startSpan)
                currentOnResizeStopped()
            },
            onDragEnd = {
                currentOnResizeFinished(currentSpan())
                currentOnResizeStopped()
            },
        ) { change, amount ->
            change.consume()
            dragOffset += amount
            val horizontalOffset =
                if (layoutDirection == LayoutDirection.Ltr) dragOffset.x else -dragOffset.x
            val target =
                currentResolveSpan(
                    startSpan,
                    (horizontalOffset / columnStep).roundToInt(),
                    (dragOffset.y / rowStep).roundToInt(),
                )
            if (target != currentSpan() && currentCanResize(target)) currentOnResize(target)
        }
    }
}
