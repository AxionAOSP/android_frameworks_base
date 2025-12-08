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
package com.axion.systemui.statusbar.notification.stack

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.stack.NotificationSection
import com.android.systemui.statusbar.notification.stack.NotificationSectionsManager
import com.android.systemui.statusbar.notification.stack.BUCKET_ESSENTIAL
import com.android.systemui.statusbar.notification.stack.SectionHeaderView

class EssentialSectionBackgroundDelegate(
    private val hostView: View,
    private val sectionsManager: NotificationSectionsManager
) {
    private val essentialSectionRect = RectF()
    private val essentialSectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var essentialSectionPadding = 0
    private var essentialSectionCornerRadius = 0
    private var sidePaddings = 0
    private var notificationAlpha = 1f
    private var blurRadius = 0f
    private var hasEssentialHeadsUp = false
    private var isOnKeyguard = false
    private var dozeAmount = 0f

    private val essentialBgAlpha = 0.6f

    private val blurNode = RenderNode("essentialSectionBlur")

    fun init() {
        val res = hostView.context.resources
        essentialSectionPadding = res.getDimensionPixelSize(R.dimen.essential_section_padding)
        essentialSectionCornerRadius = res.getDimensionPixelSize(R.dimen.essential_section_corner_radius)
        essentialSectionPaint.color = hostView.context.getColor(
            com.android.internal.R.color.surface_effect_1
        )
    }

    fun setSidePaddings(sidePaddings: Int) {
        this.sidePaddings = sidePaddings
    }

    fun setNotificationAlpha(alpha: Float) {
        this.notificationAlpha = alpha
    }

    fun setBlurRadius(radius: Float) {
        this.blurRadius = radius
    }

    fun setHasEssentialHeadsUp(hasHeadsUp: Boolean) {
        this.hasEssentialHeadsUp = hasHeadsUp
    }

    fun setOnKeyguard(onKeyguard: Boolean) {
        this.isOnKeyguard = onKeyguard
    }

    fun setDozeAmount(amount: Float) {
        this.dozeAmount = amount
    }

    fun draw(canvas: Canvas, sections: Array<NotificationSection>) {
        if (hasEssentialHeadsUp && !isOnKeyguard) return

        val dozeAlpha = 1f - dozeAmount
        if (dozeAlpha <= 0f) return

        val headerView: SectionHeaderView? = sectionsManager.essentialHeaderView
        if (headerView == null || headerView.visibility != View.VISIBLE) return

        val essentialRows = findEssentialNotificationRows()
        if (essentialRows.isEmpty()) return

        val firstRow = essentialRows.first()
        val lastRow = essentialRows.last()

        if (firstRow.visibility != View.VISIBLE || lastRow.visibility != View.VISIBLE) {
            return
        }

        val top: Float = headerView.translationY - essentialSectionPadding
        val bottom = lastRow.translationY + lastRow.actualHeight + (essentialSectionPadding * 2)
        val left = (sidePaddings - essentialSectionPadding).toFloat()
        val right = (hostView.width - sidePaddings + essentialSectionPadding).toFloat()

        if (top >= bottom || left >= right) return

        essentialSectionRect.set(left, top, right, bottom)

        val alpha = (essentialBgAlpha * notificationAlpha * dozeAlpha * 255).toInt().coerceIn(0, 255)
        essentialSectionPaint.alpha = alpha

        if (blurRadius > 0) {
            drawWithBlur(canvas)
        } else {
            canvas.drawRoundRect(
                essentialSectionRect,
                essentialSectionCornerRadius.toFloat(),
                essentialSectionCornerRadius.toFloat(),
                essentialSectionPaint
            )
        }
    }

    private fun findEssentialNotificationRows(): List<ExpandableNotificationRow> {
        val rows = mutableListOf<ExpandableNotificationRow>()
        val parent = hostView as? ViewGroup ?: return rows

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child is ExpandableNotificationRow && child.visibility == View.VISIBLE) {
                if (child.isEssentialBackground) {
                    rows.add(child)
                }
            }
        }
        return rows
    }

    private fun drawWithBlur(canvas: Canvas) {
        val width = (essentialSectionRect.right - essentialSectionRect.left).toInt()
        val height = (essentialSectionRect.bottom - essentialSectionRect.top).toInt()

        if (width <= 0 || height <= 0) return

        blurNode.setPosition(
            essentialSectionRect.left.toInt(),
            essentialSectionRect.top.toInt(),
            essentialSectionRect.right.toInt(),
            essentialSectionRect.bottom.toInt()
        )
        blurNode.setRenderEffect(
            RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)
        )

        val blurCanvas = blurNode.beginRecording()
        blurCanvas.drawRoundRect(
            0f, 0f, width.toFloat(), height.toFloat(),
            essentialSectionCornerRadius.toFloat(),
            essentialSectionCornerRadius.toFloat(),
            essentialSectionPaint
        )
        blurNode.endRecording()

        canvas.drawRenderNode(blurNode)
    }
}
