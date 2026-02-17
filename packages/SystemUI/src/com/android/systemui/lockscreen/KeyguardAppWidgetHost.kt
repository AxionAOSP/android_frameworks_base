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

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.DeadObjectException
import android.os.Looper
import android.os.TransactionTooLargeException
import android.util.Log
import android.widget.RemoteViews

class KeyguardAppWidgetHost(
    context: Context,
    hostId: Int,
    private val interactionHandler: RemoteViews.InteractionHandler,
) : AppWidgetHost(context, hostId, interactionHandler, Looper.getMainLooper()) {

    var onWidgetRemovedListener: ((Int) -> Unit)? = null

    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?,
    ): AppWidgetHostView {
        return KeyguardAppWidgetHostView(context, interactionHandler)
    }

    override fun onAppWidgetRemoved(appWidgetId: Int) {
        Log.i(TAG, "Widget removed from system: id=$appWidgetId")
        onWidgetRemovedListener?.invoke(appWidgetId)
    }

    override fun startListening() {
        try {
            super.startListening()
        } catch (e: Exception) {
            if (!e.isBinderSizeError()) {
                Log.e(TAG, "Error starting widget host listening", e)
            }
        }
    }

    override fun stopListening() {
        try {
            super.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping widget host listening", e)
        }
    }

    companion object {
        private const val TAG = "KeyguardAppWidgetHost"
        const val HOST_ID = 1027

        private fun Exception.isBinderSizeError(): Boolean {
            return cause is TransactionTooLargeException || cause is DeadObjectException
        }
    }
}

