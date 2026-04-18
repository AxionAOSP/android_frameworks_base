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
    const val SETTING_DATE_POSITION = "ax_clock_compose_date_position"
    const val SETTING_CLOCK_COLOR = "ax_clock_color"

    const val COLOR_AUTO = "auto"

    const val ALIGNMENT_LEFT = "left"
    const val ALIGNMENT_CENTER = "center"
    const val ALIGNMENT_RIGHT = "right"
    const val SIZE_DEFAULT = "default"
    const val SIZE_LARGE = "large"
    const val DATE_POSITION_ABOVE = "above"
    const val DATE_POSITION_BELOW = "below"

    private const val LARGE_SIZE_SCALE = 1.4f

    @JvmField val clockFaceUri: Uri = Settings.Secure.getUriFor(SETTING_CLOCK_FACE)
    @JvmField val alignmentUri: Uri = Settings.Secure.getUriFor(SETTING_ALIGNMENT)
    @JvmField val sizeUri: Uri = Settings.Secure.getUriFor(SETTING_SIZE)
    @JvmField val datePositionUri: Uri = Settings.Secure.getUriFor(SETTING_DATE_POSITION)
    @JvmField val clockColorUri: Uri = Settings.Secure.getUriFor(SETTING_CLOCK_COLOR)

    private val _clockId = MutableStateFlow("DEFAULT")
    val clockId: StateFlow<String> = _clockId.asStateFlow()

    private val _alignment = MutableStateFlow(ALIGNMENT_CENTER)
    val alignment: StateFlow<String> = _alignment.asStateFlow()

    private val _sizeScale = MutableStateFlow(1f)
    val sizeScale: StateFlow<Float> = _sizeScale.asStateFlow()

    private val _isDateBelow = MutableStateFlow(false)
    val isDateBelow: StateFlow<Boolean> = _isDateBelow.asStateFlow()

    private val _clockColorOverride = MutableStateFlow<Int?>(null)
    val clockColorOverride: StateFlow<Int?> = _clockColorOverride.asStateFlow()

    private val _shouldCenterIcons = MutableStateFlow(true)
    val shouldCenterIcons: StateFlow<Boolean> = _shouldCenterIcons.asStateFlow()

    private var contentResolver: ContentResolver? = null
    private var registered = false
    private val handler = Handler(Looper.getMainLooper())

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            val cr = contentResolver ?: return
            when (uri) {
                clockFaceUri -> {
                    _clockId.value = readClockId(cr)
                    _shouldCenterIcons.value = computeShouldCenter(cr)
                }
                alignmentUri -> {
                    _alignment.value = readAlignment(cr)
                    _shouldCenterIcons.value = computeShouldCenter(cr)
                }
                sizeUri -> {
                    _sizeScale.value = readSizeScale(cr)
                }
                datePositionUri -> {
                    _isDateBelow.value = readDateBelow(cr)
                }
                clockColorUri -> {
                    _clockColorOverride.value = readClockColor(cr)
                }
            }
        }
    }

    @JvmStatic
    fun init(context: Context) {
        contentResolver = context.contentResolver
        val cr = context.contentResolver

        if (registered) {
            _clockColorOverride.value = readClockColor(cr)
            return
        }
        registered = true

        cr.registerContentObserver(clockFaceUri, false, observer)
        cr.registerContentObserver(alignmentUri, false, observer)
        cr.registerContentObserver(sizeUri, false, observer)
        cr.registerContentObserver(datePositionUri, false, observer)
        cr.registerContentObserver(clockColorUri, false, observer)

        _clockId.value = readClockId(cr)
        _alignment.value = readAlignment(cr)
        _sizeScale.value = readSizeScale(cr)
        _isDateBelow.value = readDateBelow(cr)
        _clockColorOverride.value = readClockColor(cr)
        _shouldCenterIcons.value = computeShouldCenter(cr)
    }

    @JvmStatic
    fun shouldCenterIcons(context: Context): Boolean {
        init(context)
        return _shouldCenterIcons.value
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
        return Settings.Secure.getString(cr, SETTING_ALIGNMENT) ?: ALIGNMENT_CENTER
    }

    private fun readSizeScale(cr: ContentResolver): Float {
        return if (Settings.Secure.getString(cr, SETTING_SIZE) == SIZE_LARGE) LARGE_SIZE_SCALE
        else 1f
    }

    private fun readDateBelow(cr: ContentResolver): Boolean {
        return Settings.Secure.getString(cr, SETTING_DATE_POSITION) == DATE_POSITION_BELOW
    }

    private fun readClockColor(cr: ContentResolver): Int? {
        val raw = Settings.Secure.getString(cr, SETTING_CLOCK_COLOR)
        if (raw.isNullOrEmpty() || raw == COLOR_AUTO) return null
        return try {
            android.graphics.Color.parseColor(raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun computeShouldCenter(cr: ContentResolver): Boolean {
        val align = readAlignment(cr)
        if (align == ALIGNMENT_LEFT || align == ALIGNMENT_RIGHT) return false
        if (align == ALIGNMENT_CENTER) return true
        val id = readClockId(cr)
        return !isDefaultLeftAlignedClock(id)
    }

    @JvmStatic
    fun isDefaultLeftAlignedClock(clockId: String): Boolean {
        return clockId.equals("GENERAL", ignoreCase = true) ||
               clockId.equals("OLD_QUICKLOOK", ignoreCase = true) ||
               clockId.equals("AXION_AGE", ignoreCase = true)
    }
}
