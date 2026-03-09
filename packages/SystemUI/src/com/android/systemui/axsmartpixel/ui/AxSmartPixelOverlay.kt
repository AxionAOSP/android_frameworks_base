/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.systemui.axsmartpixel.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.RotationUtils
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import com.android.systemui.axsmartpixel.domain.AxSmartPixelSettings
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn

@SysUISingleton
class AxSmartPixelOverlay @Inject constructor(
    @Application private val context: Context,
    private val windowManager: WindowManager,
    @Application private val applicationScope: CoroutineScope,
    private val settings: AxSmartPixelSettings,
    private val deviceEntryUdfpsInteractor: DeviceEntryUdfpsInteractor,
    private val udfpsOverlayInteractor: UdfpsOverlayInteractor,
) {
    private var filterView: AxSmartPixelView? = null
    private var viewController: AxSmartPixelViewController? = null
    private var udfpsExclusion: Rect? = null

    fun init() {
        settings.init()

        combine(
            settings.isEnabled,
            settings.percent,
            deviceEntryUdfpsInteractor.isListeningForUdfps,
            udfpsOverlayInteractor.udfpsOverlayParams,
        ) { enabled, percent, listeningForUdfps, udfpsParams ->
            udfpsExclusion = if (listeningForUdfps) sensorArea(udfpsParams) else null
            if (enabled) {
                showOverlay()
                viewController?.updateConfig(percent)
                filterView?.updateExclusion(udfpsExclusion)
            } else {
                hideOverlay()
            }
        }.launchIn(applicationScope)
    }

    private fun sensorArea(params: UdfpsOverlayParams): Rect =
        Rect(params.sensorBounds).also { bounds ->
            RotationUtils.rotateBounds(
                bounds,
                params.naturalDisplayWidth,
                params.naturalDisplayHeight,
                params.rotation,
            )
        }

    private fun showOverlay() {
        if (filterView != null) return

        val view = AxSmartPixelView(context)
        val controller = AxSmartPixelViewController(view)

        val params = WindowManager.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or
                LayoutParams.FLAG_NOT_TOUCHABLE or
                LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                LayoutParams.FLAG_HARDWARE_ACCELERATED or
                LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT,
        )
        params.title = "AxSmartPixelFilter"
        params.layoutInDisplayCutoutMode =
            LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        params.privateFlags = params.privateFlags or
            LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY

        windowManager.addView(view, params)
        controller.init()
        controller.updateConfig(settings.percent.value)

        filterView = view
        viewController = controller
    }

    private fun hideOverlay() {
        viewController?.destroy()
        filterView?.let { view ->
            windowManager.removeViewImmediate(view)
        }
        filterView = null
        viewController = null
    }
}
