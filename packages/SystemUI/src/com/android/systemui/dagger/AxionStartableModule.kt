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

package com.android.systemui.dagger

import com.android.systemui.CoreStartable
import com.android.systemui.ax.StartAxPlatformModule
import com.android.systemui.axdynamicbar.domain.AxDynamicBarChipsRefiner
import com.android.systemui.axdynamicbar.ui.AxDynamicBarManager
import com.android.systemui.axsmartpixel.ui.AxSmartPixelManager
import com.android.systemui.axsmartpixel.ui.AxSmartPixelTile
import com.android.systemui.doze.AodScheduleController
import com.android.systemui.mistouch.domain.startable.StartMistouchPreventionModule
import com.android.systemui.overlay.KeyguardOverlayViewManager
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfig
import com.android.systemui.qs.tiles.base.shared.model.QSTileUIConfig
import com.android.systemui.res.R
import com.android.systemui.routines.ui.RoutinesManager
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsRefiner
import com.android.systemui.statusbar.phone.PhoneStatusBarPolicyExt
import com.android.systemui.statusbar.policy.NetworkSpeedController
import com.android.systemui.usb.UsbModePickerDialogDelegate
import com.axion.applocker.AxAppLockerHelper
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet
import dagger.multibindings.StringKey

@Module(includes = [StartAxPlatformModule::class, StartMistouchPreventionModule::class])
abstract class AxionStartableModule {
    @Binds
    @IntoMap
    @ClassKey(AodScheduleController::class)
    abstract fun bindAodScheduleController(impl: AodScheduleController): CoreStartable
    @Binds
    @IntoMap
    @ClassKey(AxDynamicBarManager::class)
    abstract fun bindAxDynamicBarManager(impl: AxDynamicBarManager): CoreStartable
    @Binds
    @IntoSet
    abstract fun bindDynamicBarChipsRefiner(impl: AxDynamicBarChipsRefiner): OngoingActivityChipsRefiner
    @Binds
    @IntoMap
    @ClassKey(AxSmartPixelManager::class)
    abstract fun bindAxSmartPixelManager(impl: AxSmartPixelManager): CoreStartable
    @Binds
    @IntoMap
    @StringKey(AxSmartPixelTile.TILE_SPEC)
    abstract fun bindAxSmartPixelTile(tile: AxSmartPixelTile): QSTileImpl<*>
    @Binds
    @IntoMap
    @ClassKey(RoutinesManager::class)
    abstract fun bindRoutinesManager(impl: RoutinesManager): CoreStartable
    @Binds
    @IntoMap
    @ClassKey(NetworkSpeedController::class)
    abstract fun bindNetworkSpeedController(impl: NetworkSpeedController): CoreStartable
    @Binds
    @IntoMap
    @ClassKey(AxAppLockerHelper::class)
    abstract fun bindAxAppLockerHelper(impl: AxAppLockerHelper): CoreStartable
    @Binds
    @IntoMap
    @ClassKey(UsbModePickerDialogDelegate::class)
    abstract fun bindUsbModePickerDialogDelegate(impl: UsbModePickerDialogDelegate): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(KeyguardOverlayViewManager::class)
    abstract fun bindKeyguardOverlayViewManager(impl: KeyguardOverlayViewManager): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(PhoneStatusBarPolicyExt::class)
    abstract fun bindPhoneStatusBarPolicyExt(impl: PhoneStatusBarPolicyExt): CoreStartable

    companion object {
        @JvmStatic
        @Provides
        @IntoMap
        @StringKey(AxSmartPixelTile.TILE_SPEC)
        fun provideSmartPixelsTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(AxSmartPixelTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.qs_smart_pixels_icon_off,
                    labelRes = R.string.quick_settings_smart_pixels_label,
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.DISPLAY,
            )
    }
}
