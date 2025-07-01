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
package com.android.systemui.statusbar

import com.android.systemui.util.ViewController
import com.android.systemui.dagger.SysUISingleton
import kotlinx.coroutines.*
import javax.inject.Inject

class WifiStandardViewController(
    view: WifiStandardImageView,
    private val repo: WifiStandardRepository
) : ViewController<WifiStandardImageView>(view) {

    private var job: Job? = null

    override fun onViewAttached() {
        job = CoroutineScope(Dispatchers.Main.immediate).launch {
            repo.state.collect { state ->
                mView.updateView(state.standard, state.enabled)
            }
        }
    }

    override fun onViewDetached() {
        job?.cancel()
        job = null
    }

    @SysUISingleton
    class Factory @Inject constructor(
        private val repo: WifiStandardRepository
    ) {
        fun create(view: WifiStandardImageView): WifiStandardViewController {
            return WifiStandardViewController(view, repo)
        }
    }
}
