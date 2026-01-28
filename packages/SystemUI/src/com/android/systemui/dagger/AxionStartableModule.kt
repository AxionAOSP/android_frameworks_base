/*
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

package com.android.systemui.dagger

import com.android.systemui.CoreStartable
import com.android.systemui.ax.AxPlatformHooksCoreStartable
import com.android.systemui.doze.AodScheduleController
import com.android.systemui.edgelight.EdgeLightViewController
import com.android.systemui.media.MediaViewController
import com.android.systemui.mistouch.MistouchPreventionWindowController
import com.android.systemui.pulse.PulseViewController
import com.axion.applocker.AxAppLockerHelper
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

@Module
abstract class AxionStartableModule {
    /** Inject into AodScheduleController. */
    @Binds
    @IntoMap
    @ClassKey(AodScheduleController::class)
    abstract fun bindAodScheduleController(impl: AodScheduleController): CoreStartable

    /** Inject into EdgeLightViewController. */
    @Binds
    @IntoMap
    @ClassKey(EdgeLightViewController::class)
    abstract fun bindEdgeLightViewController(impl: EdgeLightViewController): CoreStartable

    /** Inject into MediaViewController. */
    @Binds
    @IntoMap
    @ClassKey(MediaViewController::class)
    abstract fun bindMediaViewController(impl: MediaViewController): CoreStartable

    /** Inject into PulseViewController. */
    @Binds
    @IntoMap
    @ClassKey(PulseViewController::class)
    abstract fun bindPulseViewController(impl: PulseViewController): CoreStartable
    
    /** Inject into MistouchPreventionWindowController. */
    @Binds
    @IntoMap
    @ClassKey(MistouchPreventionWindowController::class)
    abstract fun bindMistouchPreventionWindowController(impl: MistouchPreventionWindowController): CoreStartable
    
    /** Inject into AxAppLockerHelper. */
    @Binds
    @IntoMap
    @ClassKey(AxAppLockerHelper::class)
    abstract fun bindAxAppLockerHelper(impl: AxAppLockerHelper): CoreStartable

    /** Inject into AxPlatformHooksCoreStartable. */
    @Binds
    @IntoMap
    @ClassKey(AxPlatformHooksCoreStartable::class)
    abstract fun bindAxPlatformHooksCoreStartable(impl: AxPlatformHooksCoreStartable): CoreStartable
}
