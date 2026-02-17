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
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.animation.LaunchableView
import com.android.systemui.animation.LaunchableViewDelegate
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.ScrimUtils
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

data class WidgetEntry(
    val appWidgetId: Int,
    val provider: ComponentName?,
    val cellX: Int,
    val cellY: Int,
    val spanX: Int,
    val spanY: Int,
    var hostView: AppWidgetHostView? = null,
)

class KeyguardWidgetHostController(
    private val context: Context,
    private val container: ViewGroup,
    private val activityStarter: ActivityStarter,
    private val configurationController: ConfigurationController,
    private val broadcastDispatcher: BroadcastDispatcher,
) {
    private val appWidgetManager = AppWidgetManager.getInstance(context)
    private val interactionHandler = KeyguardWidgetInteractionHandler(activityStarter)
    private val widgetHost =
        KeyguardAppWidgetHost(context, KeyguardAppWidgetHost.HOST_ID, interactionHandler)

    private val contentResolver: ContentResolver = context.contentResolver
    private val userManager = context.getSystemService(UserManager::class.java)!!
    private val handler = Handler(Looper.getMainLooper())

    private val _widgets = mutableStateListOf<WidgetEntry>()
    private val _enabled = mutableStateOf(false)
    private val _viewGeneration = mutableStateOf(0)
    private val _clockCentered = mutableStateOf(false)

    private var listening = false
    private var hostListening = false
    private var initialized = false
    private var pendingSelfUpdates = 0
    private var configDirty = false

    var clockCentered: Boolean
        get() = _clockCentered.value
        set(value) { _clockCentered.value = value }

    private val cornerRadius: Float
        get() = context.resources.getDimension(R.dimen.kg_widget_corner_radius)

    private val sidePadding: Int
        get() = context.resources.getDimensionPixelSize(R.dimen.kg_widget_side_padding)

    private val cellGap: Int
        get() = context.resources.getDimensionPixelSize(R.dimen.kg_widget_cell_gap)

    private val isSplitShade: Boolean
        get() {
            val resId =
                context.resources.getIdentifier(
                    "config_use_split_notification_shade",
                    "bool",
                    "com.android.systemui",
                )
            return if (resId != 0) context.resources.getBoolean(resId) else false
        }

    val cellSize: Int
        get() {
            val dm = context.resources.displayMetrics
            val maxWidth = context.resources.getDimensionPixelSize(R.dimen.shade_panel_width)
            val baseWidth =
                if (isSplitShade) {
                    min(dm.widthPixels / 2, maxWidth)
                } else {
                    min(min(dm.widthPixels, dm.heightPixels), maxWidth)
                }
            return (baseWidth - 2 * sidePadding - (GRID_COLUMNS - 1) * cellGap) / GRID_COLUMNS
        }

    val cellHeight: Int
        get() {
            val density = context.resources.displayMetrics.density
            val maxContainerHeight = (MAX_CONTAINER_HEIGHT_DP * density).toInt()
            val composePadding = (COMPOSE_TOP_PADDING_DP * density).toInt()
            val availableHeight = maxContainerHeight - composePadding
            val maxCellH = (availableHeight - (MAX_ROWS - 1) * cellGap) / MAX_ROWS
            return min(cellSize, maxCellH)
        }

    private val hostView by lazy { WidgetHostFrameLayout(context) }

    private inner class WidgetHostFrameLayout(context: Context) :
        FrameLayout(context), LaunchableView {
        private val composeView =
            ComposeView(context).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    )
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                )
                setContent { WidgetGridContent() }
            }

        init {
            layoutParams =
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                )
            addView(composeView)
        }

        private val delegate =
            LaunchableViewDelegate(this, superSetVisibility = { super.setVisibility(it) })

        override fun setShouldBlockVisibilityChanges(block: Boolean) {
            delegate.setShouldBlockVisibilityChanges(block)
        }

        override fun setVisibility(visibility: Int) {
            delegate.setVisibility(visibility)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!ScrimUtils.get().isKeyguardShowing()) return false
            composeView.onTouchEvent(event)
            return super.onTouchEvent(event)
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (!ScrimUtils.get().isKeyguardShowing()) return false
            composeView.onInterceptTouchEvent(event)
            return super.onInterceptTouchEvent(event)
        }
    }

    private val settingsObserver =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                if (pendingSelfUpdates > 0) {
                    pendingSelfUpdates--
                    return
                }
                loadConfig()
            }
        }

    private val scrimListener =
        object : ScrimUtils.ScrimEventListener {
            override fun onKeyguardShowingChanged(showing: Boolean) {
                if (showing) {
                    startHostListening()
                } else {
                    stopHostListening()
                    hostView.visibility = View.GONE
                    container.visibility = View.GONE
                }
            }
        }

    private val userUnlockReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_UNLOCKED && !initialized) {
                    initWidgets()
                }
            }
        }

    private val configListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onUiModeChanged() {
                scheduleResetWidgets()
            }

            override fun onThemeChanged() {
                scheduleResetWidgets()
            }

            override fun onConfigChanged(newConfig: Configuration) {
                updateWidgetSizes()
            }
        }

    private var resetPending = false

    private fun scheduleResetWidgets() {
        if (resetPending) return
        resetPending = true
        handler.postDelayed(
            {
                resetPending = false
                if (hostListening) {
                    performFullReset()
                } else {
                    configDirty = true
                }
            },
            RESET_DELAY_MS,
        )
    }

    private fun performFullReset() {
        configDirty = false

        cleanupWidgetViews()
        _widgets.clear()
        loadConfig()
    }

    fun init() {
        container.addView(hostView)
        ScrimUtils.get().addListener(scrimListener)
        startObserving()
        configurationController.addCallback(configListener)
        widgetHost.onWidgetRemovedListener = { appWidgetId -> onWidgetRemoved(appWidgetId) }

        if (userManager.isUserUnlocked) {
            initWidgets()
        } else {
            broadcastDispatcher.registerReceiver(
                userUnlockReceiver,
                IntentFilter(Intent.ACTION_USER_UNLOCKED),
            )
        }
    }

    private fun initWidgets() {
        if (initialized) return
        initialized = true
        broadcastDispatcher.unregisterReceiver(userUnlockReceiver)
        startHostListening()
        loadConfig()
    }

    fun dispose() {
        widgetHost.onWidgetRemovedListener = null
        ScrimUtils.get().removeListener(scrimListener)
        configurationController.removeCallback(configListener)
        handler.removeCallbacksAndMessages(null)
        broadcastDispatcher.unregisterReceiver(userUnlockReceiver)
        stopObserving()
        stopHostListening()
        cleanupWidgetViews()
        container.removeAllViews()
        initialized = false
    }

    private fun startObserving() {
        if (listening) return
        contentResolver.registerContentObserver(
            ENABLED_URI,
            false,
            settingsObserver,
            UserHandle.USER_CURRENT,
        )
        contentResolver.registerContentObserver(
            CONFIG_URI,
            false,
            settingsObserver,
            UserHandle.USER_CURRENT,
        )
        listening = true
    }

    private fun stopObserving() {
        if (!listening) return
        contentResolver.unregisterContentObserver(settingsObserver)
        listening = false
    }

    private fun startHostListening() {
        if (hostListening) return
        widgetHost.startListening()
        hostListening = true

        if (configDirty) {
            performFullReset()
            return
        }

        var needsSave = false
        _widgets.forEachIndexed { idx, entry ->
            if (entry.hostView == null && entry.provider != null) {
                val resolved = resolveWidget(entry)
                if (resolved != null) {
                    if (resolved.appWidgetId != entry.appWidgetId) {
                        needsSave = true
                    }
                    _widgets[idx] = resolved
                }
            }
        }
        if (needsSave) saveConfig()
        updateVisibility()
    }

    private fun stopHostListening() {
        if (!hostListening) return
        widgetHost.stopListening()
        hostListening = false
    }

    private fun loadConfig() {
        if (!userManager.isUserUnlocked) return

        val isEnabled =
            Settings.System.getIntForUser(
                contentResolver,
                SETTING_ENABLED,
                0,
                UserHandle.USER_CURRENT,
            ) == 1

        val configJson =
            Settings.System.getStringForUser(
                contentResolver,
                SETTING_CONFIG,
                UserHandle.USER_CURRENT,
            ) ?: ""

        _enabled.value = isEnabled && configJson.isNotEmpty()

        val entries = parseConfig(configJson)
        updateWidgetEntries(entries)
        updateVisibility()
    }

    private fun parseConfig(json: String): List<WidgetEntry> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val providerStr = obj.optString("provider", "")
                val provider =
                    if (providerStr.isNotEmpty()) {
                        ComponentName.unflattenFromString(providerStr)
                    } else null

                WidgetEntry(
                    appWidgetId = obj.optInt("appWidgetId", -1),
                    provider = provider,
                    cellX = obj.optInt("cellX", 0),
                    cellY = obj.optInt("cellY", 0),
                    spanX = obj.optInt("spanX", 1).coerceIn(1, GRID_COLUMNS),
                    spanY = obj.optInt("spanY", 1).coerceIn(1, MAX_ROWS),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse widget config", e)
            emptyList()
        }
    }

    private fun updateWidgetEntries(entries: List<WidgetEntry>) {

        _widgets.forEach { existing ->
            if (existing.appWidgetId >= 0 && existing.provider != null) {
                val stillPresent =
                    entries.any {
                        it.appWidgetId == existing.appWidgetId ||
                            (it.provider?.flattenToString() ==
                                existing.provider.flattenToString() &&
                                it.cellX == existing.cellX &&
                                it.cellY == existing.cellY)
                    }
                if (!stillPresent) {
                    widgetHost.deleteAppWidgetId(existing.appWidgetId)
                }
            }
        }

        cleanupWidgetViews()
        _widgets.clear()

        if (!hostListening) {
            _widgets.addAll(entries)
            return
        }

        var needsSave = false
        entries.forEach { entry ->
            val resolved = resolveWidget(entry)
            if (resolved != null) {
                if (resolved.appWidgetId != entry.appWidgetId) {
                    needsSave = true
                }
                _widgets.add(resolved)
            }
        }
        if (needsSave) saveConfig()

        _viewGeneration.value++
    }

    private fun resolveWidget(entry: WidgetEntry): WidgetEntry? {
        var widgetId = entry.appWidgetId
        val provider = entry.provider ?: return null

        if (widgetId < 0) {
            widgetId = allocateAndBind(provider) ?: return null
        } else {
            val info = appWidgetManager.getAppWidgetInfo(widgetId)
            if (info == null) {
                widgetHost.deleteAppWidgetId(widgetId)
                widgetId = allocateAndBind(provider) ?: return null
            }
        }

        val hostView =
            try {
                widgetHost.createView(
                    context,
                    widgetId,
                    appWidgetManager.getAppWidgetInfo(widgetId),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create widget view for id=$widgetId", e)
                return null
            }

        (hostView as? KeyguardAppWidgetHostView)?.cornerRadius = cornerRadius

        val cs = cellSize
        val ch = cellHeight
        val gap = cellGap
        val w = entry.spanX * cs + (entry.spanX - 1).coerceAtLeast(0) * gap
        val h = entry.spanY * ch + (entry.spanY - 1).coerceAtLeast(0) * gap
        hostView.updateAppWidgetSize(Bundle(), pxToDp(w), pxToDp(h), pxToDp(w), pxToDp(h))

        return entry.copy(appWidgetId = widgetId, hostView = hostView)
    }

    private fun allocateAndBind(provider: ComponentName): Int? {
        val widgetId = widgetHost.allocateAppWidgetId()
        val options =
            Bundle().apply {
                putInt(
                    AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                    AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD,
                )
            }
        val bound = appWidgetManager.bindAppWidgetIdIfAllowed(widgetId, provider, options)
        if (!bound) {
            Log.w(TAG, "Failed to bind widget $provider, id=$widgetId")
            widgetHost.deleteAppWidgetId(widgetId)
            return null
        }
        return widgetId
    }

    private fun cleanupWidgetViews() {
        _widgets.forEach {
            it.hostView?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
        }
    }

    private fun saveConfig() {
        val arr = JSONArray()
        _widgets.forEach { entry ->
            arr.put(
                JSONObject().apply {
                    put("appWidgetId", entry.appWidgetId)
                    put("provider", entry.provider?.flattenToString() ?: "")
                    put("cellX", entry.cellX)
                    put("cellY", entry.cellY)
                    put("spanX", entry.spanX)
                    put("spanY", entry.spanY)
                }
            )
        }
        pendingSelfUpdates++
        Settings.System.putStringForUser(
            contentResolver,
            SETTING_CONFIG,
            arr.toString(),
            UserHandle.USER_CURRENT,
        )
    }

    private fun updateWidgetSizes() {
        val cs = cellSize
        val ch = cellHeight
        val gap = cellGap
        _widgets.forEach { entry ->
            val view = entry.hostView ?: return@forEach
            val w = entry.spanX * cs + (entry.spanX - 1).coerceAtLeast(0) * gap
            val h = entry.spanY * ch + (entry.spanY - 1).coerceAtLeast(0) * gap
            view.updateAppWidgetSize(Bundle(), pxToDp(w), pxToDp(h), pxToDp(w), pxToDp(h))
        }
        _viewGeneration.value++
    }

    private fun updateVisibility() {
        val hasViews = _enabled.value && _widgets.any { it.hostView != null }
        val vis = if (hasViews) View.VISIBLE else View.GONE
        hostView.visibility = vis
        container.visibility = vis
    }

    private fun onWidgetRemoved(appWidgetId: Int) {
        val idx = _widgets.indexOfFirst { it.appWidgetId == appWidgetId }
        if (idx < 0) return
        val entry = _widgets[idx]
        entry.hostView?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
        _widgets.removeAt(idx)
        saveConfig()
        updateVisibility()
        _viewGeneration.value++
    }

    private fun pxToDp(px: Int): Int {
        return (px / context.resources.displayMetrics.density).toInt()
    }

    @Composable
    private fun WidgetGridContent() {
        val widgets = _widgets.toList()
        val density = LocalDensity.current
        val gen = _viewGeneration.value

        if (widgets.isEmpty()) return

        val cs = cellSize
        val ch = cellHeight
        val gap = cellGap
        val maxRow = widgets.maxOf { it.cellY + it.spanY }
        val gridHeight = maxRow * ch + (maxRow - 1).coerceAtLeast(0) * gap
        val gridWidth = GRID_COLUMNS * cs + (GRID_COLUMNS - 1) * gap

        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .wrapContentHeight()
                    .padding(
                        start = with(density) { sidePadding.toDp() },
                        end = with(density) { sidePadding.toDp() },
                        top = 8.dp,
                    ),
            contentAlignment = if (isSplitShade && !_clockCentered.value) Alignment.TopStart else Alignment.TopCenter,
        ) {
            Box(
                modifier =
                    Modifier.size(
                        width = with(density) { gridWidth.toDp() },
                        height = with(density) { gridHeight.toDp() },
                    )
            ) {
                widgets.forEach { entry ->
                    val view = entry.hostView ?: return@forEach
                    val x = entry.cellX * (cs + gap)
                    val y = entry.cellY * (ch + gap)
                    val w = entry.spanX * cs + (entry.spanX - 1).coerceAtLeast(0) * gap
                    val h = entry.spanY * ch + (entry.spanY - 1).coerceAtLeast(0) * gap

                    key(entry.appWidgetId, gen) {
                        AndroidView(
                            factory = { _ ->
                                (view.parent as? ViewGroup)?.removeView(view)
                                view
                            },
                            modifier =
                                Modifier.offset { IntOffset(x, y) }
                                    .size(
                                        width = with(density) { w.toDp() },
                                        height = with(density) { h.toDp() },
                                    ),
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "KeyguardWidgetHostCtrl"
        private const val RESET_DELAY_MS = 300L
        const val MAX_CONTAINER_HEIGHT_DP = 190
        private const val COMPOSE_TOP_PADDING_DP = 8
        const val GRID_COLUMNS = 4
        const val MAX_ROWS = 2

        const val SETTING_ENABLED = "lockscreen_widgets_enabled"
        const val SETTING_CONFIG = "lockscreen_widgets_config"

        private val ENABLED_URI: Uri = Settings.System.getUriFor(SETTING_ENABLED)
        private val CONFIG_URI: Uri = Settings.System.getUriFor(SETTING_CONFIG)
    }
}

