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
package com.android.wm.shell.freeform

import android.app.ActivityManager.RunningTaskInfo
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.util.Log
import android.view.*
import android.view.animation.*
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.android.wm.shell.R
import com.android.wm.shell.ShellTaskOrganizer
import kotlin.math.hypot

class FloatingIconManager(
    private val context: Context,
    private val shellTaskOrganizer: ShellTaskOrganizer,
    private val callback: FloatingIconCallback,
    private val mainHandler: Handler
) {

    companion object {
        private const val TAG = "FloatingIconManager"
        private const val LONG_PRESS_TIMEOUT = 300L
        private const val SLIDE_ANIMATION_DELAY = 300L
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val taskIdToIconViews = mutableMapOf<Int, MutableList<View>>()
    private val iconFactory = IconFactory(context)
    private val animationManager = FloatingIconAnimationManager(context, windowManager)

    private var dismissAreaView: View? = null
    private var dimBackgroundView: View? = null
    private var isDragging = false
    private var hoveringOverDismissArea = false

    private val accentColor by lazy {
        ContextCompat.getColor(context, android.R.color.system_accent1_100)
    }

    private val strokeWidth by lazy {
        context.resources.getDimensionPixelSize(R.dimen.floating_icon_border_width)
    }

    private val touchManager = FloatingTouchManager(
        context,
        mainHandler,
        windowManager,
        iconFactory,
        animationManager,
        { dismissAreaView },
        ::showDismissArea,
        ::hideDismissArea,
        { view -> onDismissArea(view) },
        { view, taskId -> onDismissRequested(view, taskId) },
        { taskId -> onRelaunchRequested(taskId) }
    )

    fun showFloatingIcon(taskInfo: RunningTaskInfo) {
        val packageName = taskInfo.baseActivity?.packageName ?: return
        val iconView = iconFactory.createIconView(taskInfo).apply {
            alpha = 0f
            translationX = 0f
            tag = packageName
        }
        val layoutParams = iconFactory.createLayoutParams(taskInfo)
        val screenWidth = FreeformDisplayRepository.widthPixels
        val screenHeight = FreeformDisplayRepository.heightPixels
        val iconSize = layoutParams.width
        val startX = layoutParams.x
        val startY = layoutParams.y
        var x = layoutParams.x
        var y = layoutParams.y
        val spacing = iconSize + 16
        var wrappedOnce = false
        while (isPositionOverlapping(x, y, iconSize)) {
            y += spacing
            if (y + iconSize > screenHeight) {
                y = context.resources.getDimensionPixelSize(R.dimen.floating_icon_fallback_y)
                x -= spacing
                if (x < 0) {
                    x = screenWidth - iconSize - 16
                    if (wrappedOnce) {
                        return
                    }
                    wrappedOnce = true
                }
            }
            if (x == startX && y == startY) {
                return
            }
        }
        layoutParams.x = x
        layoutParams.y = y
        windowManager.addView(iconView, layoutParams)
        iconView.setOnTouchListener(
            touchManager.createTouchListener(iconView, layoutParams, taskInfo.taskId)
        )
        val iconList = taskIdToIconViews.getOrPut(taskInfo.taskId) { mutableListOf() }
        iconList.add(iconView)
        mainHandler.postDelayed({
            animationManager.animateSlideIn(iconView, layoutParams, mainHandler)
        }, SLIDE_ANIMATION_DELAY)
    }

    fun removeFloatingIcon(taskId: Int) {
        taskIdToIconViews.remove(taskId)?.forEach { view ->
            if (view.isAttachedToWindow) {
                view.setOnTouchListener(null)
                view.clearAnimation()
                windowManager.removeViewImmediate(view)
            }
        }
        try {
            val views = taskIdToIconViews.remove(taskId)
            views?.firstOrNull()?.tag?.let { tag ->
                iconFactory.removeIconPosition(tag.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove icon position", e)
        }
    }

    private fun isPositionOverlapping(x: Int, y: Int, size: Int): Boolean {
        val tempRect = Rect(x, y, x + size, y + size)
        return taskIdToIconViews.values.flatten().any { view ->
            if (!view.isAttachedToWindow) return@any false
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val existingRect = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
            Rect.intersects(tempRect, existingRect)
        }
    }

    private fun onDismissRequested(view: View, taskId: Int) {
        animationManager.playDismissAnimation(view, taskId) {
            removeIconView(taskId, view)
            shellTaskOrganizer.getRunningTaskInfo(taskId)?.let(callback::onRequestDismiss)
        }
    }

    private fun onRelaunchRequested(taskId: Int) {
        shellTaskOrganizer.getRunningTaskInfo(taskId)?.let(callback::onRequestRelaunch)
    }

    private fun showDismissArea() {
        if (dimBackgroundView == null) createDimBackgroundView()
        if (dismissAreaView == null) createdismissAreaView()
        dimBackgroundView?.apply {
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .setDuration(200)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
        dismissAreaView?.let { animationManager.animateDismissAreaShow(it) }
    }

    private fun hideDismissArea() {
        isDragging = false
        dismissAreaView?.let { animationManager.animateDismissAreaHide(it) }
        dimBackgroundView?.animate()
            ?.alpha(0f)
            ?.setDuration(200)
            ?.setInterpolator(AccelerateDecelerateInterpolator())
            ?.withEndAction {
                dimBackgroundView?.visibility = View.GONE
            }
            ?.start()
    }

    private fun onDismissArea(icon: View): Boolean {
        val iconLoc = IntArray(2)
        val removeLoc = IntArray(2)
        icon.getLocationOnScreen(iconLoc)
        dismissAreaView?.getLocationOnScreen(removeLoc)

        val iconCenterX = iconLoc[0] + icon.width / 2
        val iconCenterY = iconLoc[1] + icon.height / 2
        val removeCenterX = removeLoc[0] + (dismissAreaView?.width ?: 0) / 2
        val removeCenterY = removeLoc[1] + (dismissAreaView?.height ?: 0) / 2

        val distance = hypot(
            (iconCenterX - removeCenterX).toDouble(),
            (iconCenterY - removeCenterY).toDouble()
        )

        val isNowOver = distance < (dismissAreaView?.width ?: 0) / 2

        if (isNowOver != hoveringOverDismissArea) {
            hoveringOverDismissArea = isNowOver
            updateDismissAreaAppearance(isNowOver)
            if (isNowOver) icon.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        return isNowOver
    }

    private fun updateDismissAreaAppearance(isOver: Boolean) {
        (dismissAreaView as? TextView)?.apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 100f
                if (isOver) {
                    setColor(accentColor)
                } else {
                    setColor(Color.TRANSPARENT)
                    setStroke(strokeWidth, accentColor)
                }
            }
            setTextColor(if (isOver) Color.BLACK else accentColor)
        }
    }

    private fun createdismissAreaView() {
        val padding = context.resources.getDimensionPixelSize(R.dimen.floating_icon_padding)
        val margin = context.resources.getDimensionPixelSize(R.dimen.floating_icon_remove_zone_margin)

        val textView = TextView(context).apply {
            text = context.getString(R.string.pip_phone_dismiss_hint)
            setPadding(padding * 2, padding, padding * 2, padding)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(accentColor)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 100f
                setColor(Color.TRANSPARENT)
                setStroke(strokeWidth, accentColor)
            }
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = margin
        }

        dismissAreaView = textView
        windowManager.addView(textView, layoutParams)
        textView.visibility = View.GONE
    }

    private fun createDimBackgroundView() {
        val fadeHeight = context.resources.getDimensionPixelSize(R.dimen.floating_icon_dismiss_fade_height)
        dimBackgroundView = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#80000000"))
            )
            alpha = 0f
            visibility = View.GONE
        }
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            fadeHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }
        windowManager.addView(dimBackgroundView, layoutParams)
    }

    private fun removeIconView(taskId: Int, view: View) {
        taskIdToIconViews[taskId]?.remove(view)
        if (view.isAttachedToWindow) {
            view.setOnTouchListener(null)
            view.clearAnimation()
            windowManager.removeViewImmediate(view)
        }
        if (taskIdToIconViews[taskId]?.isEmpty() == true) {
            taskIdToIconViews.remove(taskId)
            try {
                view.tag?.let { iconFactory.removeIconPosition(it.toString()) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove icon position", e)
            }
        }
    }

    fun onConfigurationChanged() {
        val activeTaskIds = taskIdToIconViews.keys.toList()
        activeTaskIds.forEach { removeFloatingIcon(it) }
        val repoTasks = FreeformTaskRepository.minimizedTasks
        for (taskInfo in repoTasks) {
            showFloatingIcon(taskInfo)
        }
    }

    interface FloatingIconCallback {
        fun onRequestRelaunch(taskInfo: RunningTaskInfo)
        fun onRequestDismiss(taskInfo: RunningTaskInfo)
    }
}
