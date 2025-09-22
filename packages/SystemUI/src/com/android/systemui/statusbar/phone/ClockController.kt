/*
 * Copyright (C) 2018-2025 LineageOS
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
package com.android.systemui.statusbar.phone

import android.content.Context
import android.database.ContentObserver
import android.graphics.Rect
import android.net.Uri
import android.provider.Settings
import android.view.View
import com.android.systemui.Dependency
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.statusbar.phone.ui.StatusBarIconController.ICON_HIDE_LIST
import com.android.systemui.statusbar.policy.Clock
import lineageos.providers.LineageSettings
import lineageos.providers.LineageSettings.System.STATUS_BAR_CLOCK

class ClockController(
    private val context: Context,
    private val statusBarView: View
) : DarkIconDispatcher.DarkReceiver {

    companion object {
        private const val CLOCK_POSITION_RIGHT = 0
        private const val CLOCK_POSITION_CENTER = 1
        private const val CLOCK_POSITION_LEFT = 2
    }

    private val iconHideListUri: Uri =
        Settings.Secure.getUriFor(ICON_HIDE_LIST)

    private val statusBarClockUri: Uri =
        LineageSettings.System.getUriFor(STATUS_BAR_CLOCK)

    private val darkIconDispatcher: DarkIconDispatcher
        get() = Dependency.get(DarkIconDispatcher::class.java)

    private val clockObserver = ClockObserver()

    private var _activeClock: Clock = getClockByPosition()

    @get:JvmName("getClock")
    val activeClock: Clock
        get() = _activeClock

    var denyListed: Boolean = false
        set(value) {
            field = value
            updateActiveClock()
        }

    var clockPosition: Int = CLOCK_POSITION_LEFT
        set(value) {
            field = value
            updateActiveClock()
        }

    private var clockTextColor: Int = _activeClock.currentTextColor
        set(value) {
            field = value
            onColorsChanged()
        }

    fun onStatusBarViewAttached() {
        clockObserver.start()
        darkIconDispatcher.addDarkReceiver(this)
    }

    fun onStatusBarViewDetached() {
        clockObserver.stop()
        darkIconDispatcher.removeDarkReceiver(this)
        _activeClock.setClockVisibleByUser(false)
    }

    fun onDensityOrFontScaleChanged() {
        _activeClock.onDensityOrFontScaleChanged()
    }

    override fun onDarkChanged(areas: ArrayList<Rect>, darkIntensity: Float, tint: Int) {
        clockTextColor = DarkIconDispatcher.getTint(areas, _activeClock, tint)
    }
    
    private fun onColorsChanged() {
        _activeClock.setTextColor(clockTextColor)
    }

    private fun updateActiveClock() {
        context.mainExecutor.execute {
            _activeClock.setClockVisibleByUser(false)
            _activeClock = getClockByPosition()
            _activeClock.setClockVisibleByUser(!denyListed)
            onColorsChanged()
        }
    }

    private fun getClockByPosition(): Clock {
        return when (clockPosition) {
            CLOCK_POSITION_RIGHT -> statusBarView.findViewById(R.id.clock_right)
            CLOCK_POSITION_CENTER -> statusBarView.findViewById(R.id.clock_center)
            else -> statusBarView.findViewById(R.id.clock)
        }
    }

    inner class ClockObserver : ContentObserver(null) {
        fun start() {
            var cr = context.contentResolver
            cr.registerContentObserver(iconHideListUri, false, this)
            cr.registerContentObserver(statusBarClockUri, false, this)
            onChange(true, iconHideListUri)
            onChange(true, statusBarClockUri)
        }

        fun stop() {
            context.contentResolver.unregisterContentObserver(this)
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            when (uri) {
                iconHideListUri -> {
                    denyListed = StatusBarIconController
                        .getIconHideList(
                            context,
                            Settings.Secure.getString(context.contentResolver, ICON_HIDE_LIST)
                        )
                        .contains("clock")
                }
                statusBarClockUri -> {
                    clockPosition = LineageSettings.System.getInt(
                        context.contentResolver, STATUS_BAR_CLOCK, CLOCK_POSITION_LEFT
                    )
                }
            }
        }
    }
}
