/*
 * Copyright (C) 2025 AxionOS Project
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

package com.android.systemui.lockscreen

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Outline
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.RemoteViews
import com.android.systemui.util.ScrimUtils

class KeyguardAppWidgetHostView(
    context: Context,
    interactionHandler: RemoteViews.InteractionHandler,
) : AppWidgetHostView(context, interactionHandler) {

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!ScrimUtils.get().isKeyguardShowing()) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.dispatchTouchEvent(event)
    }

    var cornerRadius: Float = 0f
        set(value) {
            field = value
            if (value > 0f) {
                outlineProvider = roundedOutlineProvider
                clipToOutline = true
            } else {
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = false
            }
            invalidateOutline()
        }

    private val roundedOutlineProvider =
        object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }

    override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo?) {
        super.setAppWidget(appWidgetId, info)
        super.setPadding(0, 0, 0, 0)
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {

        super.setPadding(0, 0, 0, 0)
    }

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        if (isLaidOut) {

            post {
                super.updateAppWidget(remoteViews)
                super.setPadding(0, 0, 0, 0)
            }
        } else {

            super.updateAppWidget(remoteViews)
            super.setPadding(0, 0, 0, 0)
        }
    }
}

