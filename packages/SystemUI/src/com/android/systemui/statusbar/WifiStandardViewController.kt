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
package com.android.systemui.statusbar

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.ViewController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class WifiStandardViewController(
    view: WifiStandardImageView,
    private val repo: WifiStandardRepository,
    private val mainDispatcher: CoroutineDispatcher,
) : ViewController<WifiStandardImageView>(view) {

    private var job: Job? = null

    override fun onViewAttached() {
        job = CoroutineScope(mainDispatcher).launch {
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
        private val repo: WifiStandardRepository,
        @Main private val mainDispatcher: CoroutineDispatcher,
    ) {
        fun create(view: WifiStandardImageView): WifiStandardViewController =
            WifiStandardViewController(view, repo, mainDispatcher)
    }
}
