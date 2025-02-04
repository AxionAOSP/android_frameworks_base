/*
 * Copyright (C) 2025 The AxionAOSP Project
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

package com.android.systemui.settings

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

import com.android.systemui.res.R

class NTDotLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : View(context, attrs, defStyleAttr, defStyleRes) {

    private var mDotColor: Int
    private var mDotWidth: Float
    private var mDotGap: Float
    private var mSpecialDotPercent: Int
    private var mSpecialDotColor: Int
    private val mPaint = Paint()

    init {
        context.obtainStyledAttributes(attrs, R.styleable.NTDotLineSeekBar).apply {
            mDotColor = getColor(
                R.styleable.NTDotLineSeekBar_ntDotColor,
                context.resources.getColor(R.color.dot_color)
            )
            mDotWidth = getInt(R.styleable.NTDotLineSeekBar_ntDotRadiusPx, 10).toFloat()
            mDotGap = getInt(R.styleable.NTDotLineSeekBar_ntDotGapPx, 15).toFloat()
            mSpecialDotPercent = getInt(R.styleable.NTDotLineSeekBar_ntDotStartPercentage, 0)
            mSpecialDotColor = getColor(
                R.styleable.NTDotLineSeekBar_ntDotSpecialColor,
                mDotColor
            )
            recycle()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDraw(canvas: Canvas) {
        mPaint.isAntiAlias = true
        mPaint.color = mDotColor

        val measuredWidth = (measuredWidth - paddingStart - paddingEnd).toFloat()
        val measuredHeight = measuredHeight.toFloat()

        val specialWidth = measuredWidth - (mSpecialDotPercent / 100f) * measuredWidth
        val radius = mDotWidth / 2f

        var currentX = measuredWidth - mDotGap - radius
        while (currentX > 0f) {
            mPaint.color = if (currentX >= specialWidth && mSpecialDotPercent > 0) {
                mSpecialDotColor
            } else {
                mDotColor
            }
            canvas.drawCircle(currentX, measuredHeight / 2f, radius, mPaint)
            currentX -= mDotGap + mDotWidth
        }

        super.onDraw(canvas)
    }

    fun setSpecialDotPercent(specialDotPercent: Int) {
        mSpecialDotPercent = specialDotPercent
        invalidate()
    }
}
