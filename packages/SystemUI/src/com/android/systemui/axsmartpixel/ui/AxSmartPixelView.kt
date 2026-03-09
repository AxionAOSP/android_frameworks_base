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

package com.android.systemui.axsmartpixel.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.SurfaceControl
import android.view.View
import android.view.ViewRootImpl

class AxSmartPixelView(context: Context) : View(context), ViewRootImpl.SurfaceChangedCallback {

    private val patternPaint = Paint()
    private var patternBitmap: Bitmap? = null
    private val exclusionPath = Path()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun updatePattern(bitmap: Bitmap) {
        patternBitmap?.recycle()
        patternBitmap = bitmap
        patternPaint.shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        invalidate()
    }

    fun updateExclusion(area: Rect?) {
        exclusionPath.reset()
        if (area != null && !area.isEmpty) {
            exclusionPath.addOval(RectF(area), Path.Direction.CW)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (patternBitmap == null) return
        if (!exclusionPath.isEmpty) {
            canvas.clipOutPath(exclusionPath)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), patternPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewRootImpl?.addSurfaceChangedCallback(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewRootImpl?.removeSurfaceChangedCallback(this)
        patternBitmap?.recycle()
        patternBitmap = null
    }

    override fun surfaceCreated(t: SurfaceControl.Transaction) {
        excludeFromCapture(t)
    }

    override fun surfaceReplaced(t: SurfaceControl.Transaction) {
        excludeFromCapture(t)
    }

    override fun surfaceDestroyed() {}

    private fun excludeFromCapture(t: SurfaceControl.Transaction) {
        val surfaceControl = viewRootImpl?.surfaceControl ?: return
        if (surfaceControl.isValid) {
            t.setSkipScreenshot(surfaceControl, true)
        }
    }
}
