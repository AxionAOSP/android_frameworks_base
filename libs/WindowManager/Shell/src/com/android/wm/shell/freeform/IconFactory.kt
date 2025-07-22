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
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.android.wm.shell.R
import org.json.JSONArray
import org.json.JSONObject

class IconFactory(private val context: Context) {

    private val resources = context.resources
    private val packageManager = context.packageManager
    private val contentResolver = context.contentResolver

    private val padding = resources.getDimensionPixelSize(R.dimen.floating_icon_padding)
    private val iconSize = resources.getDimensionPixelSize(R.dimen.floating_icon_size)
    private val fallbackY = resources.getDimensionPixelSize(R.dimen.floating_icon_fallback_y)
    private val iconMargin = resources.getDimensionPixelSize(R.dimen.floating_icon_margin)

    private val darkBackgroundColor = ContextCompat.getColor(context, R.color.decor_bg_dark)

    companion object {
        private const val TAG = "IconFactory"
    }

    fun createIconView(taskInfo: RunningTaskInfo): ImageView = ImageView(context).apply {
        setPadding(padding, padding, padding, padding)
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = createBackground()
        val pkgName = taskInfo.baseActivity?.packageName
        if (pkgName != null) {
            try {
                setImageDrawable(packageManager.getApplicationIcon(pkgName))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load icon for package: $pkgName", e)
                setImageResource(android.R.drawable.sym_def_app_icon)
            }
        } else {
            Log.e(TAG, "Task has null package name")
            setImageResource(android.R.drawable.sym_def_app_icon)
        }
        post {
            val rect = Rect(0, 0, width, height)
            systemGestureExclusionRects = listOf(rect)
        }
    }

    fun createLayoutParams(taskInfo: RunningTaskInfo): WindowManager.LayoutParams {
        val packageName = taskInfo.baseActivity?.packageName
        val (x, y) = packageName?.let { loadIconPosition(it) }
            ?: getDefaultPosition(resources.displayMetrics.widthPixels)

        return WindowManager.LayoutParams(
            iconSize, iconSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }
    }

    fun saveIconPosition(view: View, x: Int, y: Int) {
        val tag = view.tag
        if (tag is String && tag.isNotBlank()) {
            updateIconPositions { it.put(tag, JSONArray(listOf(x, y))) }
        } else {
            Log.e(TAG, "View tag is missing or invalid when saving icon position")
        }
    }

    fun removeIconPosition(packageName: String) {
        updateIconPositions { it.remove(packageName) }
    }

    private fun getDefaultPosition(screenWidth: Int): Pair<Int, Int> =
        (screenWidth - iconSize - iconMargin) to fallbackY

    fun loadIconPosition(packageName: String): Pair<Int, Int>? {
        return try {
            val positions = getAllIconPositions()
            positions.optJSONArray(packageName)?.takeIf { it.length() == 2 }?.let {
                it.getInt(0) to it.getInt(1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load icon position for package $packageName", e)
            null
        }
    }

    private fun createBackground(): GradientDrawable {
        val color = darkBackgroundColor
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun getAllIconPositions(): JSONObject {
        return try {
            Settings.Secure.getString(contentResolver, getPositionsKey())?.let {
                JSONObject(it)
            } ?: JSONObject()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get icon positions", e)
            JSONObject()
        }
    }

    private fun saveAllIconPositions(obj: JSONObject) {
        Settings.Secure.putString(contentResolver, getPositionsKey(), obj.toString())
    }

    private inline fun updateIconPositions(modify: (JSONObject) -> Unit) {
        try {
            val positions = getAllIconPositions()
            modify(positions)
            saveAllIconPositions(positions)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update icon positions", e)
        }
    }

    private fun getPositionsKey(): String {
        val orientation = resources.configuration.orientation
        return if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            "floating_icon_positions_landscape"
        } else {
            "floating_icon_positions"
        }
    }
}
