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
import com.android.systemui.ax.AxPlatformServiceImpl
import com.android.systemui.axdynamicbar.domain.AxDynamicBarChipsRefiner
import com.android.systemui.axdynamicbar.ui.AxDynamicBarManager
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsRefiner
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet

@Module
abstract class AxionStartableModule {
    @Binds
    @IntoMap
    @ClassKey(AxPlatformServiceImpl::class)
    abstract fun bindAxPlatformService(impl: AxPlatformServiceImpl): CoreStartable
    @Binds
    @IntoMap
    @ClassKey(AxDynamicBarManager::class)
    abstract fun bindAxDynamicBarManager(impl: AxDynamicBarManager): CoreStartable
    @Binds
    @IntoSet
    abstract fun bindDynamicBarChipsRefiner(impl: AxDynamicBarChipsRefiner): OngoingActivityChipsRefiner
}
