/*
 * Copyright (C) 2025-2026 AxionOS Project
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

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class KeyguardWidgetConfigActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val providerStr = intent.getStringExtra(EXTRA_PROVIDER)
        if (providerStr.isNullOrEmpty()) {
            finish()
            return
        }

        val cn = ComponentName.unflattenFromString(providerStr)
        if (cn == null) {
            finish()
            return
        }

        if (savedInstanceState != null) {
            widgetId =
                savedInstanceState.getInt(STATE_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            return
        }

        val host = AppWidgetHost(this, KeyguardAppWidgetHost.HOST_ID)
        widgetId = host.allocateAppWidgetId()

        val options =
            Bundle().apply {
                putInt(
                    AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                    AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD,
                )
            }
        val bound =
            AppWidgetManager.getInstance(this).bindAppWidgetIdIfAllowed(widgetId, cn, options)

        if (!bound) {
            Log.w(TAG, "Failed to bind widget $cn, id=$widgetId")
            host.deleteAppWidgetId(widgetId)
            widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            finish()
            return
        }

        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)
        if (info != null && requiresConfiguration(info)) {
            val configIntent =
                Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                    component = info.configure
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                }
            try {
                startActivityForResult(configIntent, REQUEST_CONFIGURE)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start configure activity for widget $widgetId", e)
                cleanup()
                finish()
            }
        } else {

            saveWidgetToSettings()
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_WIDGET_ID, widgetId)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CONFIGURE) {
            if (resultCode == RESULT_OK) {
                saveWidgetToSettings()
            } else {
                cleanup()
            }
            finish()
        }
    }

    private fun saveWidgetToSettings() {
        try {
            val json =
                Settings.System.getStringForUser(
                    contentResolver,
                    KeyguardWidgetHostController.SETTING_CONFIG,
                    UserHandle.USER_CURRENT,
                ) ?: ""

            val arr = if (json.isNotBlank()) JSONArray(json) else JSONArray()
            arr.put(
                JSONObject().apply {
                    put("appWidgetId", widgetId)
                    put("provider", intent.getStringExtra(EXTRA_PROVIDER) ?: "")
                    put("cellX", intent.getIntExtra(EXTRA_CELL_X, 0))
                    put("cellY", intent.getIntExtra(EXTRA_CELL_Y, 0))
                    put("spanX", intent.getIntExtra(EXTRA_SPAN_X, 1))
                    put("spanY", intent.getIntExtra(EXTRA_SPAN_Y, 1))
                }
            )

            Settings.System.putStringForUser(
                contentResolver,
                KeyguardWidgetHostController.SETTING_CONFIG,
                arr.toString(),
                UserHandle.USER_CURRENT,
            )

            Settings.System.putIntForUser(
                contentResolver,
                KeyguardWidgetHostController.SETTING_ENABLED,
                1,
                UserHandle.USER_CURRENT,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save widget to settings", e)
        }
    }

    private fun cleanup() {
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            try {
                AppWidgetHost(this, KeyguardAppWidgetHost.HOST_ID).deleteAppWidgetId(widgetId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete widget ID $widgetId", e)
            }
            widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        }
    }

    private fun requiresConfiguration(info: AppWidgetProviderInfo): Boolean {
        if (info.configure == null) return false
        val features = info.widgetFeatures
        val configOptional =
            (features and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0) &&
                (features and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0)
        return !configOptional
    }

    companion object {
        private const val TAG = "KgWidgetConfigActivity"
        const val EXTRA_PROVIDER = "extra_provider"
        const val EXTRA_CELL_X = "extra_cell_x"
        const val EXTRA_CELL_Y = "extra_cell_y"
        const val EXTRA_SPAN_X = "extra_span_x"
        const val EXTRA_SPAN_Y = "extra_span_y"
        private const val STATE_WIDGET_ID = "state_widget_id"
        private const val REQUEST_CONFIGURE = 1
    }
}

