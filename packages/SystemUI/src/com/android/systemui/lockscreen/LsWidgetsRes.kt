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
package com.android.systemui.lockscreen

import android.content.res.Configuration.UI_MODE_NIGHT_MASK
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import com.android.systemui.lockscreen.LsWidgetsRes.BG_DOZE_ACTIVE
import com.android.systemui.lockscreen.LsWidgetsRes.BG_DOZE_INACTIVE
import com.android.systemui.lockscreen.LsWidgetsRes.BG_ACTIVE
import com.android.systemui.lockscreen.LsWidgetsRes.BG_DARK
import com.android.systemui.lockscreen.LsWidgetsRes.BG_LIGHT
import com.android.systemui.lockscreen.LsWidgetsRes.COLOR_BG_LIGHT
import com.android.systemui.lockscreen.LsWidgetsRes.COLOR_BG_DARK
import com.android.systemui.lockscreen.LsWidgetsRes.COLOR_BG_ADARK
import com.android.systemui.lockscreen.LsWidgetsRes.COLOR_BG_ALIGHT
import com.android.systemui.lockscreen.LsWidgetsRes.WIDGET_CIRCLE_SIZE
import com.android.systemui.lockscreen.LsWidgetsRes.WIDGET_MARGIN_HORIZONTAL
import com.android.systemui.lockscreen.LsWidgetsRes.WIDGET_ICON_PADDING
import com.android.systemui.lockscreen.LsWidgetsRes.SIDE_PADDING

import android.content.Context
import android.content.res.Resources
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.content.ContextCompat
import com.android.systemui.res.R

object LsWidgetsRes {
    val BT_ACTIVE = R.drawable.qs_bluetooth_icon_on
    val BT_INACTIVE = R.drawable.qs_bluetooth_icon_off
    val DATA_ACTIVE = R.drawable.ic_signal_cellular_alt_24
    val DATA_INACTIVE = R.drawable.ic_mobiledata_off_24
    val RINGER_ACTIVE = R.drawable.ic_vibration_24
    val RINGER_INACTIVE = R.drawable.ic_ring_volume_24
    val TORCH_RES_ACTIVE = R.drawable.ic_flashlight_on
    val TORCH_RES_INACTIVE = R.drawable.ic_flashlight_off
    val WIFI_ACTIVE = R.drawable.ic_wifi_24
    val WIFI_INACTIVE = R.drawable.ic_wifi_off_24
    val HOTSPOT_ACTIVE = R.drawable.qs_hotspot_icon_on
    val HOTSPOT_INACTIVE = R.drawable.qs_hotspot_icon_off
    val BG_LIGHT = R.drawable.lockscreen_widget_bg_light
    val BG_DARK = R.drawable.lockscreen_widget_bg_dark
    val BG_ACTIVE = R.drawable.lockscreen_widget_bg_active
    val BG_DOZE_ACTIVE = R.drawable.lockscreen_widget_bg_dozing_active
    val BG_DOZE_INACTIVE = R.drawable.lockscreen_widget_bg_dozing_inactive
    val COLOR_BG_DARK = R.color.lockscreen_widget_background_color_dark
    val COLOR_BG_LIGHT = R.color.lockscreen_widget_background_color_light
    val COLOR_BG_ADARK = R.color.lockscreen_widget_background_color_adark
    val COLOR_BG_ALIGHT = R.color.lockscreen_widget_background_color_alight
    val WIDGET_CIRCLE_SIZE = R.dimen.kg_widget_circle_size
    val WIDGET_MARGIN_HORIZONTAL = R.dimen.kg_widgets_margin_horizontal
    val WIDGET_ICON_PADDING = R.dimen.kg_widgets_icon_padding
    val SIDE_PADDING = R.dimen.kg_widgets_margin_side
}

class Theme(private val ctx: Context) {

    val res: Resources get() = ctx.resources

    val night: Boolean get() = (res.configuration.uiMode and UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES

    val n50 = color(COLOR_BG_LIGHT)
    val n900 = color(COLOR_BG_DARK)
    val a100 = color(COLOR_BG_ADARK)
    val a600 = color(COLOR_BG_ALIGHT)

    val white = Color.WHITE

    val accent get() = themed(a600, a100)
    val accentInverse get() = themed(a100, a600)
    val neutral get() = themed(n50, n900)

    val widgetSize = ratioRes(WIDGET_CIRCLE_SIZE)
    val spacing = ratioRes(WIDGET_MARGIN_HORIZONTAL)
    val iconPadding = ratioRes(WIDGET_ICON_PADDING)
    val sidePadding = ratioRes(SIDE_PADDING)

    fun tint(t: Int): ColorStateList = ColorStateList.valueOf(t)
    fun themed(nc: Int, lc: Int, b: Boolean = night): Int = if (b) nc else lc
    fun color(c: Int): Int = ContextCompat.getColor(ctx, c)
    fun ratioRes(r: Int): Int = (res.getDimensionPixelSize(r) * ratio()).toInt()

    fun doze(a: Boolean = night): Int = themed(BG_DOZE_ACTIVE, BG_DOZE_INACTIVE, a)
    fun default(): Int = themed(BG_DARK, BG_LIGHT)

    fun ratio(): Float {
        val d = res.displayMetrics
        val sw = minOf(d.widthPixels, d.heightPixels) / d.density
        return sw / 420f
    }
}
