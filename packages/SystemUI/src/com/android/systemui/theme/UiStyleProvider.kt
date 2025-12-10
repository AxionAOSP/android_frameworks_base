/*
 * Copyright (C) 2025 Axion OS
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

package com.android.systemui.theme

import android.content.Context
import android.content.res.ThemeEngine
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import javax.inject.Inject
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.SystemUIApplication
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ThemeState(
    val version: Int,
    val hasActiveIconTheme: Boolean,
    val activeIconTheme: String?,
    val qsStyleId: String,
)

@SysUISingleton
class UiStyleProvider @Inject constructor(private val context: Context) {

    interface ThemeChangeListener {
        fun onThemeChanged()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<ThemeChangeListener>()
    private val listenersLock = Any()
    
    private var themeVersionCounter = 0
    
    private val _themeState = MutableStateFlow(loadThemeState())
    val themeStateFlow: StateFlow<ThemeState> = _themeState.asStateFlow()

    private val debounceRunnable = Runnable {
        updateThemeState()
        notifyListeners()
    }

    private val themeEngineListener = ThemeEngine.ThemeChangeListener { _ ->
        Log.d(TAG, "ThemeEngine callback received")
        mainHandler.post {
            scheduleThemeChange()
        }
    }
    
    init {
        ThemeEngine.getInstance(context)?.addThemeChangeListener(themeEngineListener)
    }

    private fun scheduleThemeChange() {
        mainHandler.removeCallbacks(debounceRunnable)
        mainHandler.postDelayed(debounceRunnable, DEBOUNCE_DELAY_MS)
    }
    
    private fun loadThemeState(): ThemeState {
        val engine = ThemeEngine.getInstance(context)
        return ThemeState(
            version = themeVersionCounter,
            hasActiveIconTheme = engine?.hasActiveIconTheme() ?: false,
            activeIconTheme = engine?.activeIconTheme,
            qsStyleId = engine?.qsStyleId ?: ThemeEngine.STYLE_AXION,
        )
    }
    
    private fun updateThemeState() {
        themeVersionCounter++
        val newState = loadThemeState()
        _themeState.value = newState
        Log.d(TAG, "Theme state updated: $newState")
    }

    private fun notifyListeners() {
        val listenersCopy = synchronized(listenersLock) {
            ArrayList(listeners)
        }
        Log.d(TAG, "Notifying ${listenersCopy.size} theme change listeners")
        listenersCopy.forEach { listener ->
            try {
                listener.onThemeChanged()
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying theme change listener", e)
            }
        }
    }

    fun addThemeChangeListener(listener: ThemeChangeListener) {
        synchronized(listenersLock) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }

    fun removeThemeChangeListener(listener: ThemeChangeListener) {
        synchronized(listenersLock) {
            listeners.remove(listener)
        }
    }

    companion object {
        private const val TAG = "UiStyleProvider"
        private const val DEBOUNCE_DELAY_MS = 500L

        @JvmStatic
        fun get(context: Context): UiStyleProvider {
            val app = context.applicationContext as SystemUIApplication
            return app.sysUIComponent.getUiStyleProvider()
        }

        @JvmStatic
        fun getCurrentStyle(context: Context): UiStyle {
            val styleId = ThemeEngine.getInstance(context)?.qsStyleId
            return UiStyle.fromId(styleId ?: ThemeEngine.STYLE_AXION)
        }
        
        @Composable
        @JvmStatic
        fun rememberThemeState(): ThemeState {
            val context = LocalContext.current
            val provider = get(context)
            val themeState by provider.themeStateFlow.collectAsState()
            return themeState
        }
        
        @Composable
        @JvmStatic
        fun rememberCurrentStyle(): UiStyle {
            val themeState = rememberThemeState()
            return remember(themeState) { UiStyle.fromId(themeState.qsStyleId) }
        }

        @Composable
        @JvmStatic
        fun rememberThemeVersion(): Int {
            val themeState = rememberThemeState()
            return themeState.version
        }
    }
}

enum class TrackPattern {
    DOT_MATRIX,
    SOLID,
}

enum class UiStyle(
    val id: String,
    val displayName: String,
    
    val volumeSliderTrackWidthExpanded: Dp,
    val volumeSliderTrackWidthCollapsed: Dp,
    val volumeSliderCornerRadius: Dp,
    val volumeSliderHeight: Dp,
    val volumeTrackPattern: TrackPattern,
    val volumeTrackDotRadius: Float,
    val volumeTrackDotSpacing: Float,
    
    val qsTileCornerRadius: Dp,
    val qsTilePadding: Dp,
    val qsTileIconSize: Dp,
    val qsTileShape: () -> Shape,
    
    val buttonCornerRadius: Dp,
    val dialogCornerRadius: Dp
) {
    AXION(
        id = ThemeEngine.STYLE_AXION,
        displayName = "Axion",
        volumeSliderTrackWidthExpanded = 36.dp,
        volumeSliderTrackWidthCollapsed = 12.dp,
        volumeSliderCornerRadius = 50.dp,
        volumeSliderHeight = 204.dp,
        volumeTrackPattern = TrackPattern.DOT_MATRIX,
        volumeTrackDotRadius = 4f,
        volumeTrackDotSpacing = 16f,
        qsTileCornerRadius = 50.dp,
        qsTilePadding = 12.dp,
        qsTileIconSize = 24.dp,
        qsTileShape = { CircleShape },
        buttonCornerRadius = 20.dp,
        dialogCornerRadius = 28.dp
    ),
    
    MATERIAL3_EXPRESSIVE(
        id = ThemeEngine.STYLE_MATERIAL3_EXPRESSIVE,
        displayName = "Material3 Expressive",
        volumeSliderTrackWidthExpanded = 48.dp,
        volumeSliderTrackWidthCollapsed = 16.dp,
        volumeSliderCornerRadius = 28.dp,
        volumeSliderHeight = 220.dp,
        volumeTrackPattern = TrackPattern.SOLID,
        volumeTrackDotRadius = 0f,
        volumeTrackDotSpacing = 0f,
        qsTileCornerRadius = 28.dp,
        qsTilePadding = 16.dp,
        qsTileIconSize = 28.dp,
        qsTileShape = { RoundedCornerShape(28.dp) },
        buttonCornerRadius = 28.dp,
        dialogCornerRadius = 36.dp
    ),
    
    MINIMAL(
        id = ThemeEngine.STYLE_MINIMAL,
        displayName = "Minimal",
        volumeSliderTrackWidthExpanded = 28.dp,
        volumeSliderTrackWidthCollapsed = 8.dp,
        volumeSliderCornerRadius = 8.dp,
        volumeSliderHeight = 180.dp,
        volumeTrackPattern = TrackPattern.SOLID,
        volumeTrackDotRadius = 0f,
        volumeTrackDotSpacing = 0f,
        qsTileCornerRadius = 12.dp,
        qsTilePadding = 8.dp,
        qsTileIconSize = 22.dp,
        qsTileShape = { RoundedCornerShape(12.dp) },
        buttonCornerRadius = 8.dp,
        dialogCornerRadius = 16.dp
    );
    
    companion object {
        fun fromId(id: String): UiStyle {
            return entries.find { it.id == id } ?: AXION
        }
    }
}

fun UiStyle.volumeSliderShape() = RoundedCornerShape(volumeSliderCornerRadius)
fun UiStyle.buttonShape() = RoundedCornerShape(buttonCornerRadius)
fun UiStyle.dialogShape() = RoundedCornerShape(dialogCornerRadius)
