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

package com.android.systemui.qs.ax.ui.keyguard

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.dimensionResource
import com.android.app.animation.Interpolators
import com.android.compose.theme.PlatformTheme
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.remedia.ui.viewmodel.MediaViewModel
import com.android.systemui.qs.ax.shared.model.AxQsSpan
import com.android.systemui.qs.ax.ui.compose.AxMediaPanel
import com.android.systemui.qs.ax.ui.model.AxMediaSurface
import com.android.systemui.qs.ax.ui.viewmodel.AxMediaViewModel
import com.android.systemui.qs.ui.composable.QuickSettingsTheme
import com.android.systemui.res.R
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.flow.distinctUntilChanged

@SysUISingleton
class AxKeyguardMediaContent
@Inject
constructor(
    private val viewModel: AxMediaViewModel,
    private val mediaViewModelFactory: MediaViewModel.Factory,
) {
    val hasVisibleMedia =
        snapshotFlow { viewModel.hasVisibleSessions(AxMediaSurface.LOCKSCREEN) }
            .distinctUntilChanged()

    fun createHost(composeView: ComposeView): ViewGroup {
        return KeyguardMediaSwipeFrame(
                context = composeView.context,
                canDismiss = ::canDismiss,
                isFalseTouch = viewModel::isSwipeFalseTouch,
                onDismissed = { viewModel.dismissBySwipe(AxMediaSurface.LOCKSCREEN) },
            )
            .apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                addView(
                    composeView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
    }

    fun setContent(composeView: ComposeView) {
        composeView.setContent {
            PlatformTheme {
                QuickSettingsTheme {
                    Box(
                        modifier =
                            Modifier.fillMaxWidth()
                                .height(
                                    dimensionResource(R.dimen.qs_media_session_height_expanded)
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        AxMediaPanel(
                            viewModel = viewModel,
                            span = AxQsSpan(4, 2),
                            mediaViewModelFactory = mediaViewModelFactory,
                            modifier = Modifier.fillMaxWidth(),
                            allowGuts = true,
                            surface = AxMediaSurface.LOCKSCREEN,
                        )
                    }
                }
            }
        }
    }

    private fun canDismiss(deltaX: Float, isRtl: Boolean): Boolean {
        if (viewModel.hasVisibleGuts()) return false
        val sessions = viewModel.visibleSessions(AxMediaSurface.LOCKSCREEN)
        if (sessions.size <= 1) return sessions.isNotEmpty()
        val selectedKey = viewModel.currentSession?.key
        val selectedIndex = sessions.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
        val towardStart = if (isRtl) deltaX < 0f else deltaX > 0f
        return if (towardStart) selectedIndex == 0 else selectedIndex == sessions.lastIndex
    }
}

private class KeyguardMediaSwipeFrame(
    context: Context,
    private val canDismiss: (deltaX: Float, isRtl: Boolean) -> Boolean,
    private val isFalseTouch: () -> Boolean,
    private val onDismissed: () -> Unit,
) : FrameLayout(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val maximumFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private val dismissVelocity = DismissVelocityDp * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var startTranslation = 0f
    private var dragDirection = 0f
    private var gestureResolved = false
    private var dragging = false
    private var dismissing = false
    private var parentInterceptBlocked = false
    private var velocityTracker: VelocityTracker? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            recycleVelocityTracker()
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)
        return try {
            super.dispatchTouchEvent(event)
        } finally {
            if (
                event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                recycleVelocityTracker()
            }
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (dismissing) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                contentView()?.animate()?.cancel()
                downX = event.x
                downY = event.y
                startTranslation = contentView()?.translationX ?: 0f
                dragDirection = 0f
                gestureResolved = false
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!gestureResolved) {
                    val deltaX = event.x - downX
                    val deltaY = event.y - downY
                    if (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop) {
                        gestureResolved = true
                        dragging =
                            abs(deltaX) > abs(deltaY) &&
                                canDismiss(
                                    deltaX,
                                    layoutDirection == View.LAYOUT_DIRECTION_RTL,
                                )
                        if (dragging) {
                            dragDirection = deltaX.sign
                            blockParentIntercept()
                        }
                    }
                }
                if (dragging) updateTranslation(event.x)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> releaseParentIntercept()
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dismissing) return true
        return when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (dragging) updateTranslation(event.x)
                dragging
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) finishDrag() else false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    animateBack()
                    true
                } else {
                    false
                }
            }
            else -> dragging
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) reset()
    }

    override fun onDetachedFromWindow() {
        reset()
        super.onDetachedFromWindow()
    }

    private fun updateTranslation(x: Float) {
        val content = contentView() ?: return
        val limit = width.toFloat().coerceAtLeast(1f)
        val translation =
            if (dragDirection > 0f) {
                (startTranslation + x - downX).coerceIn(0f, limit)
            } else {
                (startTranslation + x - downX).coerceIn(-limit, 0f)
            }
        content.translationX = translation
        content.alpha = 1f - abs(translation / limit) * DragFadeAmount
    }

    private fun finishDrag(): Boolean {
        val content = contentView() ?: return false
        velocityTracker?.computeCurrentVelocity(1000, maximumFlingVelocity.toFloat())
        val velocity = velocityTracker?.xVelocity ?: 0f
        val translation = content.translationX
        val width = width.toFloat().coerceAtLeast(1f)
        val distanceDismiss = abs(translation) >= width * DismissDistanceFraction
        val velocityDismiss =
            abs(velocity) >= dismissVelocity &&
                velocity.sign == dragDirection &&
                abs(translation) >= width * MinimumFlingDistanceFraction
        val shouldDismiss = (distanceDismiss || velocityDismiss) && !isFalseTouch()
        dragging = false
        gestureResolved = true
        releaseParentIntercept()
        if (shouldDismiss) {
            dismiss(content, dragDirection)
        } else {
            animateBack()
        }
        return true
    }

    private fun dismiss(content: View, direction: Float) {
        dismissing = true
        content
            .animate()
            .translationX(width.toFloat() * direction)
            .alpha(0f)
            .setDuration(DismissDuration)
            .setInterpolator(Interpolators.FAST_OUT_LINEAR_IN)
            .withEndAction { onDismissed() }
            .start()
    }

    private fun animateBack() {
        dragging = false
        gestureResolved = true
        releaseParentIntercept()
        contentView()
            ?.animate()
            ?.translationX(0f)
            ?.alpha(1f)
            ?.setDuration(SnapBackDuration)
            ?.setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
            ?.start()
    }

    private fun blockParentIntercept() {
        if (parentInterceptBlocked) return
        parentInterceptBlocked = true
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun releaseParentIntercept() {
        if (!parentInterceptBlocked) return
        parentInterceptBlocked = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun reset() {
        releaseParentIntercept()
        recycleVelocityTracker()
        dragging = false
        dismissing = false
        dragDirection = 0f
        gestureResolved = false
        contentView()?.run {
            animate().cancel()
            translationX = 0f
            alpha = 1f
        }
    }

    private fun contentView(): View? = getChildAt(0)

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private companion object {
        const val DismissVelocityDp = 1000f
        const val DismissDistanceFraction = 0.5f
        const val MinimumFlingDistanceFraction = 0.1f
        const val DragFadeAmount = 0.5f
        const val DismissDuration = 180L
        const val SnapBackDuration = 220L
    }
}
