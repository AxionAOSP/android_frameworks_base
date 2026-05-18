/*
 * Copyright (C) 2026 AxionOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.shared.clocks

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

object ClockSettingsRepository {

    const val SETTING_CLOCK_FACE = "lock_screen_custom_clock_face"
    const val SETTING_ALIGNMENT = "ax_clock_alignment"
    const val SETTING_SIZE = "ax_clock_size"
    const val SETTING_SIZE_SCALE = "ax_clock_size_scale"
    const val SETTING_TOP_PADDING = "ax_clock_top_padding_dp"
    const val SETTING_LOCKSCREEN_WIDGETS_ENABLED = "lockscreen_widgets_enabled"
    const val SETTING_LOCKSCREEN_WIDGETS_CONFIG = "lockscreen_widgets_config"
    const val SETTING_DATE_POSITION = "ax_clock_compose_date_position"
    const val SETTING_INFO_DISPLAY_MODE = "ax_clock_info_display_mode"
    const val SETTING_INFO_DISPLAY_SOURCES = "ax_clock_info_display_sources"
    const val SETTING_CLOCK_COLOR = "ax_clock_color"
    const val SETTING_OPLUS_CLASSIC_FACE = "ax_clock_oplus_classic_face"
    const val SETTING_OPLUS_BIG_FACE = "ax_clock_oplus_big_face"
    const val SETTING_OPLUS_BIG_DUAL_TONE = "ax_clock_oplus_big_dual_tone"
    const val SETTING_OPLUS_GRAFFITI_FACE = "ax_clock_oplus_graffiti_face"
    const val SETTING_OPLUS_GRAFFITI_ANGLE = "ax_clock_oplus_graffiti_angle"

    const val COLOR_AUTO = "auto"
    const val OPLUS_CLASSIC_FACE_DEFAULT = "default"
    const val OPLUS_CLASSIC_FACE_STACKED = "stacked"
    const val OPLUS_BIG_FACE_DEFAULT = "default"
    const val OPLUS_BIG_FACE_STRETCH = "stretch"
    const val OPLUS_BIG_FACE_WIDE = "wide"
    const val OPLUS_GRAFFITI_FACE_SANS = "sans"
    const val OPLUS_GRAFFITI_FACE_DEFAULT = "default"
    const val OPLUS_GRAFFITI_FACE_BRIGHT = "bright"
    const val OPLUS_GRAFFITI_FACE_CUTE = "cute"
    const val OPLUS_GRAFFITI_FACE_DIGIT04 = "digit04"
    const val OPLUS_GRAFFITI_FACE_GAME = "game"
    const val OPLUS_GRAFFITI_FACE_KEEP = "keep"
    const val OPLUS_GRAFFITI_FACE_WENDAO = "wendao"
    const val OPLUS_GRAFFITI_FACE_SHENQI = "shenqi"
    const val OPLUS_GRAFFITI_FACE_GALADA = "galada"
    const val OPLUS_GRAFFITI_FACE_MODAK = "modak"
    const val OPLUS_GRAFFITI_ANGLE_LEFT = "left"
    const val OPLUS_GRAFFITI_ANGLE_CENTER = "center"
    const val OPLUS_GRAFFITI_ANGLE_RIGHT = "right"

    const val ALIGNMENT_LEFT = "left"
    const val ALIGNMENT_CENTER = "center"
    const val ALIGNMENT_RIGHT = "right"
    const val SIZE_DEFAULT = "default"
    const val SIZE_LARGE = "large"
    const val DATE_POSITION_ABOVE = "above"
    const val DATE_POSITION_BELOW = "below"
    const val INFO_DISPLAY_AUTO = "auto"
    const val INFO_DISPLAY_DATE = "date"
    const val INFO_DISPLAY_OFF = "off"
    const val INFO_DISPLAY_MEDIA = "media"
    const val INFO_DISPLAY_SMARTSPACE = "smartspace"
    const val INFO_DISPLAY_ALARM = "alarm"
    const val INFO_DISPLAY_CALENDAR = "calendar"
    const val INFO_DISPLAY_WEATHER = "weather"

    const val SIZE_SCALE_MIN = 0.5f
    const val SIZE_SCALE_MAX = 1.35f
    const val TOP_PADDING_MIN_DP = -24f
    const val TOP_PADDING_MAX_DP = 240f

    val INFO_DISPLAY_SOURCE_PRIORITY = listOf(
        INFO_DISPLAY_MEDIA,
        INFO_DISPLAY_SMARTSPACE,
        INFO_DISPLAY_ALARM,
        INFO_DISPLAY_CALENDAR,
        INFO_DISPLAY_WEATHER,
    )
    val DEFAULT_INFO_DISPLAY_SOURCES = INFO_DISPLAY_SOURCE_PRIORITY.toSet()

    private const val DEFAULT_SIZE_SCALE = 1f
    private const val LARGE_SIZE_SCALE = 1.35f
    private const val DEFAULT_TOP_PADDING_DP = 0f

    val sizeScaleRange = ClockSizeScaleRange(
        SIZE_SCALE_MIN,
        DEFAULT_SIZE_SCALE,
        LARGE_SIZE_SCALE,
        SIZE_SCALE_MAX,
    )

    @JvmField val clockFaceUri: Uri = Settings.Secure.getUriFor(SETTING_CLOCK_FACE)
    @JvmField val alignmentUri: Uri = Settings.Secure.getUriFor(SETTING_ALIGNMENT)
    @JvmField val sizeUri: Uri = Settings.Secure.getUriFor(SETTING_SIZE)
    @JvmField val sizeScaleUri: Uri = Settings.Secure.getUriFor(SETTING_SIZE_SCALE)
    @JvmField val topPaddingUri: Uri = Settings.Secure.getUriFor(SETTING_TOP_PADDING)
    @JvmField val lockscreenWidgetsEnabledUri: Uri =
        Settings.System.getUriFor(SETTING_LOCKSCREEN_WIDGETS_ENABLED)
    @JvmField val lockscreenWidgetsConfigUri: Uri =
        Settings.System.getUriFor(SETTING_LOCKSCREEN_WIDGETS_CONFIG)
    @JvmField val datePositionUri: Uri = Settings.Secure.getUriFor(SETTING_DATE_POSITION)
    @JvmField val infoDisplayModeUri: Uri = Settings.Secure.getUriFor(SETTING_INFO_DISPLAY_MODE)
    @JvmField val infoDisplaySourcesUri: Uri =
        Settings.Secure.getUriFor(SETTING_INFO_DISPLAY_SOURCES)
    @JvmField val clockColorUri: Uri = Settings.Secure.getUriFor(SETTING_CLOCK_COLOR)
    @JvmField val oplusClassicFaceSettingUri: Uri =
        Settings.Secure.getUriFor(SETTING_OPLUS_CLASSIC_FACE)
    @JvmField val oplusBigFaceSettingUri: Uri =
        Settings.Secure.getUriFor(SETTING_OPLUS_BIG_FACE)
    @JvmField val oplusBigDualToneUri: Uri =
        Settings.Secure.getUriFor(SETTING_OPLUS_BIG_DUAL_TONE)
    @JvmField val oplusGraffitiFaceSettingUri: Uri =
        Settings.Secure.getUriFor(SETTING_OPLUS_GRAFFITI_FACE)
    @JvmField val oplusGraffitiAngleSettingUri: Uri =
        Settings.Secure.getUriFor(SETTING_OPLUS_GRAFFITI_ANGLE)

    private val _clockId = MutableStateFlow("DEFAULT")
    val clockId: StateFlow<String> = _clockId.asStateFlow()

    private val _alignment = MutableStateFlow(ALIGNMENT_CENTER)
    val alignment: StateFlow<String> = _alignment.asStateFlow()

    private val _resolvedClockAlignment = MutableStateFlow(ALIGNMENT_CENTER)
    val resolvedClockAlignment: StateFlow<String> = _resolvedClockAlignment.asStateFlow()

    private val _sizeScale = MutableStateFlow(DEFAULT_SIZE_SCALE)
    val sizeScale: StateFlow<Float> = _sizeScale.asStateFlow()

    private val _clockEditGeometryVersion = MutableStateFlow(0)
    val clockEditGeometryVersion: StateFlow<Int> = _clockEditGeometryVersion.asStateFlow()

    private val _topPaddingDp = MutableStateFlow(DEFAULT_TOP_PADDING_DP)
    val topPaddingDp: StateFlow<Float> = _topPaddingDp.asStateFlow()

    private val _lockscreenWidgetLayoutState = MutableStateFlow(ClockWidgetLayoutState.Empty)
    val lockscreenWidgetLayoutState: StateFlow<ClockWidgetLayoutState> =
        _lockscreenWidgetLayoutState.asStateFlow()

    private val _isDateBelow = MutableStateFlow(false)
    val isDateBelow: StateFlow<Boolean> = _isDateBelow.asStateFlow()

    private val _infoDisplayMode = MutableStateFlow(INFO_DISPLAY_AUTO)
    val infoDisplayMode: StateFlow<String> = _infoDisplayMode.asStateFlow()

    private val _infoDisplaySources = MutableStateFlow(DEFAULT_INFO_DISPLAY_SOURCES)
    val infoDisplaySources: StateFlow<Set<String>> = _infoDisplaySources.asStateFlow()

    private val _clockColorOverride = MutableStateFlow<Int?>(null)
    val clockColorOverride: StateFlow<Int?> = _clockColorOverride.asStateFlow()

    private val _oplusClassicFace = MutableStateFlow(OPLUS_CLASSIC_FACE_DEFAULT)
    val oplusClassicFace: StateFlow<String> = _oplusClassicFace.asStateFlow()

    private val _oplusBigFace = MutableStateFlow(OPLUS_BIG_FACE_DEFAULT)
    val oplusBigFace: StateFlow<String> = _oplusBigFace.asStateFlow()

    private val _oplusBigDualTone = MutableStateFlow(false)
    val oplusBigDualTone: StateFlow<Boolean> = _oplusBigDualTone.asStateFlow()

    private val _oplusGraffitiFace = MutableStateFlow(OPLUS_GRAFFITI_FACE_DEFAULT)
    val oplusGraffitiFace: StateFlow<String> = _oplusGraffitiFace.asStateFlow()

    private val _oplusGraffitiAngle = MutableStateFlow(OPLUS_GRAFFITI_ANGLE_CENTER)
    val oplusGraffitiAngle: StateFlow<String> = _oplusGraffitiAngle.asStateFlow()

    private val _shouldCenterIcons = MutableStateFlow(true)
    val shouldCenterIcons: StateFlow<Boolean> = _shouldCenterIcons.asStateFlow()

    interface ClockLayoutAlignmentListener {
        fun onClockLayoutAlignmentChanged(alignment: String)
    }

    private val clockLayoutAlignmentListeners = mutableSetOf<ClockLayoutAlignmentListener>()
    private var contentResolver: ContentResolver? = null
    private var appContext: Context? = null
    private var registered = false
    private val handler = Handler(Looper.getMainLooper())

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            val cr = contentResolver ?: return
            val context = appContext ?: return
            when (uri) {
                clockFaceUri -> {
                    _clockId.value = readClockId(cr)
                    updateClockLayoutAlignment(context, cr)
                }
                alignmentUri -> {
                    _alignment.value = readAlignment(cr)
                    updateClockLayoutAlignment(context, cr)
                }
                sizeUri, sizeScaleUri -> {
                    _sizeScale.value = readSizeScale(cr)
                }
                topPaddingUri -> {
                    _topPaddingDp.value = readTopPaddingDp(cr)
                }
                lockscreenWidgetsEnabledUri, lockscreenWidgetsConfigUri -> {
                    _lockscreenWidgetLayoutState.value = readLockscreenWidgetLayoutState(cr)
                }
                datePositionUri -> {
                    _isDateBelow.value = readDateBelow(cr)
                }
                infoDisplayModeUri -> {
                    _infoDisplayMode.value = readInfoDisplayMode(cr)
                    _infoDisplaySources.value = readInfoDisplaySources(cr)
                }
                infoDisplaySourcesUri -> {
                    _infoDisplaySources.value = readInfoDisplaySources(cr)
                }
                clockColorUri -> {
                    _clockColorOverride.value = readClockColor(cr)
                }
                oplusClassicFaceSettingUri -> {
                    _oplusClassicFace.value = readOplusClassicFace(cr)
                }
                oplusBigFaceSettingUri -> {
                    _oplusBigFace.value = readOplusBigFace(cr)
                    updateClockLayoutAlignment(context, cr)
                }
                oplusBigDualToneUri -> {
                    _oplusBigDualTone.value = readOplusBigDualTone(cr)
                }
                oplusGraffitiFaceSettingUri -> {
                    _oplusGraffitiFace.value = readOplusGraffitiFace(cr)
                }
                oplusGraffitiAngleSettingUri -> {
                    _oplusGraffitiAngle.value = readOplusGraffitiAngle(cr)
                }
            }
            _clockEditGeometryVersion.value++
        }
    }

    @JvmStatic
    fun init(context: Context) {
        val stableContext = context.applicationContext ?: context
        appContext = stableContext
        contentResolver = stableContext.contentResolver
        val cr = stableContext.contentResolver

        if (registered) {
            readAll(stableContext, cr)
            return
        }
        registered = true

        cr.registerContentObserver(clockFaceUri, false, observer)
        cr.registerContentObserver(alignmentUri, false, observer)
        cr.registerContentObserver(sizeUri, false, observer)
        cr.registerContentObserver(sizeScaleUri, false, observer)
        cr.registerContentObserver(topPaddingUri, false, observer)
        cr.registerContentObserver(lockscreenWidgetsEnabledUri, false, observer)
        cr.registerContentObserver(lockscreenWidgetsConfigUri, false, observer)
        cr.registerContentObserver(datePositionUri, false, observer)
        cr.registerContentObserver(infoDisplayModeUri, false, observer)
        cr.registerContentObserver(infoDisplaySourcesUri, false, observer)
        cr.registerContentObserver(clockColorUri, false, observer)
        cr.registerContentObserver(oplusClassicFaceSettingUri, false, observer)
        cr.registerContentObserver(oplusBigFaceSettingUri, false, observer)
        cr.registerContentObserver(oplusBigDualToneUri, false, observer)
        cr.registerContentObserver(oplusGraffitiFaceSettingUri, false, observer)
        cr.registerContentObserver(oplusGraffitiAngleSettingUri, false, observer)

        readAll(stableContext, cr)
    }

    private fun readAll(context: Context, cr: ContentResolver) {
        _clockId.value = readClockId(cr)
        _alignment.value = readAlignment(cr)
        _sizeScale.value = readSizeScale(cr)
        _topPaddingDp.value = readTopPaddingDp(cr)
        _lockscreenWidgetLayoutState.value = readLockscreenWidgetLayoutState(cr)
        _isDateBelow.value = readDateBelow(cr)
        _infoDisplayMode.value = readInfoDisplayMode(cr)
        _infoDisplaySources.value = readInfoDisplaySources(cr)
        _clockColorOverride.value = readClockColor(cr)
        _oplusClassicFace.value = readOplusClassicFace(cr)
        _oplusBigFace.value = readOplusBigFace(cr)
        _oplusBigDualTone.value = readOplusBigDualTone(cr)
        _oplusGraffitiFace.value = readOplusGraffitiFace(cr)
        _oplusGraffitiAngle.value = readOplusGraffitiAngle(cr)
        updateClockLayoutAlignment(context, cr)
        _clockEditGeometryVersion.value++
    }

    @JvmStatic
    fun shouldCenterIcons(context: Context): Boolean {
        return clockLayoutAlignment(context) == ALIGNMENT_CENTER
    }

    @JvmStatic
    fun clockLayoutAlignment(context: Context): String {
        init(context)
        return _resolvedClockAlignment.value
    }

    @JvmStatic
    fun readInfoDisplayMode(context: Context): String {
        return readInfoDisplayMode(context.contentResolver)
    }

    @JvmStatic
    fun readInfoDisplaySources(context: Context): Set<String> {
        return readInfoDisplaySources(context.contentResolver)
    }

    @JvmStatic
    fun addClockLayoutAlignmentListener(
        context: Context,
        listener: ClockLayoutAlignmentListener,
    ) {
        init(context)
        clockLayoutAlignmentListeners.add(listener)
        listener.onClockLayoutAlignmentChanged(_resolvedClockAlignment.value)
    }

    @JvmStatic
    fun removeClockLayoutAlignmentListener(listener: ClockLayoutAlignmentListener) {
        clockLayoutAlignmentListeners.remove(listener)
    }

    private fun readClockId(cr: ContentResolver): String {
        return try {
            val json = Settings.Secure.getString(cr, SETTING_CLOCK_FACE)
            if (!json.isNullOrEmpty()) JSONObject(json).optString("clockId", "DEFAULT")
            else "DEFAULT"
        } catch (_: Exception) {
            "DEFAULT"
        }
    }

    private fun readAlignment(cr: ContentResolver): String {
        return readAlignmentOrNull(cr) ?: ALIGNMENT_CENTER
    }

    private fun readAlignmentOrNull(cr: ContentResolver): String? {
        return when (Settings.Secure.getString(cr, SETTING_ALIGNMENT)) {
            ALIGNMENT_LEFT -> ALIGNMENT_LEFT
            ALIGNMENT_CENTER -> ALIGNMENT_CENTER
            ALIGNMENT_RIGHT -> ALIGNMENT_RIGHT
            else -> null
        }
    }

    private fun readSizeScale(cr: ContentResolver): Float {
        val rawScale = Settings.Secure.getString(cr, SETTING_SIZE_SCALE)?.toFloatOrNull()
        return sizeScaleRange.resolve(
            rawScale,
            Settings.Secure.getString(cr, SETTING_SIZE) == SIZE_LARGE,
        )
    }

    private fun readTopPaddingDp(cr: ContentResolver): Float {
        return Settings.Secure.getString(cr, SETTING_TOP_PADDING)
            ?.toFloatOrNull()
            ?.coerceIn(TOP_PADDING_MIN_DP, TOP_PADDING_MAX_DP)
            ?: DEFAULT_TOP_PADDING_DP
    }

    private fun readLockscreenWidgetLayoutState(cr: ContentResolver): ClockWidgetLayoutState {
        val enabled =
            Settings.System.getInt(
                cr,
                SETTING_LOCKSCREEN_WIDGETS_ENABLED,
                0,
            ) == 1
        val config =
            Settings.System.getString(
                cr,
                SETTING_LOCKSCREEN_WIDGETS_CONFIG,
            )
        return ClockWidgetLayoutState.fromSettings(enabled, config)
    }

    private fun readDateBelow(cr: ContentResolver): Boolean {
        return Settings.Secure.getString(cr, SETTING_DATE_POSITION) == DATE_POSITION_BELOW
    }

    private fun readInfoDisplayMode(cr: ContentResolver): String {
        return when (Settings.Secure.getString(cr, SETTING_INFO_DISPLAY_MODE)) {
            INFO_DISPLAY_DATE -> INFO_DISPLAY_DATE
            INFO_DISPLAY_OFF -> INFO_DISPLAY_OFF
            else -> INFO_DISPLAY_AUTO
        }
    }

    private fun readInfoDisplaySources(cr: ContentResolver): Set<String> {
        val raw = Settings.Secure.getString(cr, SETTING_INFO_DISPLAY_SOURCES)
        if (raw != null) {
            return raw.split(',')
                .map { it.trim() }
                .filterTo(mutableSetOf()) { isInfoDisplaySource(it) }
        }

        val legacyMode = Settings.Secure.getString(cr, SETTING_INFO_DISPLAY_MODE)
        return if (isInfoDisplaySource(legacyMode)) setOf(legacyMode) else DEFAULT_INFO_DISPLAY_SOURCES
    }

    private fun isInfoDisplaySource(value: String?): Boolean {
        return value == INFO_DISPLAY_MEDIA ||
            value == INFO_DISPLAY_SMARTSPACE ||
            value == INFO_DISPLAY_ALARM ||
            value == INFO_DISPLAY_CALENDAR ||
            value == INFO_DISPLAY_WEATHER
    }

    private fun readClockColor(cr: ContentResolver): Int? {
        val raw = Settings.Secure.getString(cr, SETTING_CLOCK_COLOR)
        if (raw.isNullOrEmpty() || raw == COLOR_AUTO) return null
        return try {
            AndroidColor.parseColor(raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun readOplusClassicFace(cr: ContentResolver): String {
        return when (Settings.Secure.getString(cr, SETTING_OPLUS_CLASSIC_FACE)) {
            OPLUS_CLASSIC_FACE_STACKED -> OPLUS_CLASSIC_FACE_STACKED
            else -> OPLUS_CLASSIC_FACE_DEFAULT
        }
    }

    private fun readOplusBigFace(cr: ContentResolver): String {
        return when (Settings.Secure.getString(cr, SETTING_OPLUS_BIG_FACE)) {
            OPLUS_BIG_FACE_STRETCH -> OPLUS_BIG_FACE_WIDE
            OPLUS_BIG_FACE_WIDE -> OPLUS_BIG_FACE_WIDE
            else -> OPLUS_BIG_FACE_DEFAULT
        }
    }

    private fun readOplusBigDualTone(cr: ContentResolver): Boolean {
        return Settings.Secure.getInt(cr, SETTING_OPLUS_BIG_DUAL_TONE, 0) == 1
    }

    private fun readOplusGraffitiFace(cr: ContentResolver): String {
        return when (Settings.Secure.getString(cr, SETTING_OPLUS_GRAFFITI_FACE)) {
            OPLUS_GRAFFITI_FACE_SANS -> OPLUS_GRAFFITI_FACE_SANS
            OPLUS_GRAFFITI_FACE_BRIGHT -> OPLUS_GRAFFITI_FACE_BRIGHT
            OPLUS_GRAFFITI_FACE_CUTE -> OPLUS_GRAFFITI_FACE_CUTE
            OPLUS_GRAFFITI_FACE_DIGIT04 -> OPLUS_GRAFFITI_FACE_DIGIT04
            OPLUS_GRAFFITI_FACE_GAME -> OPLUS_GRAFFITI_FACE_GAME
            OPLUS_GRAFFITI_FACE_KEEP -> OPLUS_GRAFFITI_FACE_KEEP
            OPLUS_GRAFFITI_FACE_WENDAO -> OPLUS_GRAFFITI_FACE_WENDAO
            OPLUS_GRAFFITI_FACE_SHENQI -> OPLUS_GRAFFITI_FACE_SHENQI
            OPLUS_GRAFFITI_FACE_GALADA -> OPLUS_GRAFFITI_FACE_GALADA
            OPLUS_GRAFFITI_FACE_MODAK -> OPLUS_GRAFFITI_FACE_MODAK
            else -> OPLUS_GRAFFITI_FACE_DEFAULT
        }
    }

    private fun readOplusGraffitiAngle(cr: ContentResolver): String {
        return when (Settings.Secure.getString(cr, SETTING_OPLUS_GRAFFITI_ANGLE)) {
            OPLUS_GRAFFITI_ANGLE_LEFT -> OPLUS_GRAFFITI_ANGLE_LEFT
            OPLUS_GRAFFITI_ANGLE_RIGHT -> OPLUS_GRAFFITI_ANGLE_RIGHT
            else -> OPLUS_GRAFFITI_ANGLE_CENTER
        }
    }

    private fun updateClockLayoutAlignment(context: Context, cr: ContentResolver) {
        val clockId = readClockId(cr)
        val resolvedAlignment = resolveClockLayoutAlignment(
            context,
            clockId,
            readAlignmentOrNull(cr),
            resolveOplusBigFace(cr, clockId),
        )
        val oldAlignment = _resolvedClockAlignment.value
        _resolvedClockAlignment.value = resolvedAlignment
        _shouldCenterIcons.value = resolvedAlignment == ALIGNMENT_CENTER
        if (oldAlignment != resolvedAlignment) {
            notifyClockLayoutAlignmentChanged(resolvedAlignment)
        }
    }

    private fun notifyClockLayoutAlignmentChanged(alignment: String) {
        clockLayoutAlignmentListeners.toList().forEach {
            it.onClockLayoutAlignmentChanged(alignment)
        }
    }

    private fun resolveClockLayoutAlignment(
        context: Context,
        clockId: String,
        alignValue: String?,
        oplusBigFace: String,
    ): String {
        val clockType = AxClockType.resolve(context.resources, clockId)
        if (clockType == AxClockType.OPLUS_BIG &&
            oplusBigFace == OPLUS_BIG_FACE_WIDE &&
            alignValue == null
        ) {
            return ALIGNMENT_LEFT
        }
        val configKey = clockType.clockConfigKey
            ?: return ALIGNMENT_CENTER
        val config = ClockConfigs.resolveConfig(configKey, false, alignValue ?: ALIGNMENT_CENTER)
        return when (config?.align) {
            ClockConfigs.Align.LEFT -> ALIGNMENT_LEFT
            ClockConfigs.Align.RIGHT -> ALIGNMENT_RIGHT
            else -> ALIGNMENT_CENTER
        }
    }

    private fun resolveOplusBigFace(cr: ContentResolver, clockId: String): String {
        val selectedFace = readOplusBigFace(cr)
        if (selectedFace != OPLUS_BIG_FACE_DEFAULT) return selectedFace
        return when (clockId) {
            "OPLUS_BIG_VERTICAL",
            "OPLUS_BIG_START_HORIZONTAL",
            "OPLUS_BIG_END_HORIZONTAL",
            "OPLUS_BIG_WIDE" -> OPLUS_BIG_FACE_WIDE
            else -> selectedFace
        }
    }
}
