/*
 * Copyright (C) 2026 AxionOS
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
package com.android.systemui.mistouch.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

@SysUISingleton
class MistouchSettingsRepository
@Inject
constructor(
    private val secureSettingsRepository: SecureSettingsRepository,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {
    val isEnabled: Flow<Boolean> =
        secureSettingsRepository
            .intSetting(KEY_MISTOUCH_PREVENTION, 0)
            .map { it == 1 }
            .flowOn(backgroundDispatcher)

    private companion object {
        private const val KEY_MISTOUCH_PREVENTION = "nt_mistouch_prevention_enable"
    }
}
