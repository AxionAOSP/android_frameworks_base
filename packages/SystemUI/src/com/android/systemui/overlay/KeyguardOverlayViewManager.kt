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
package com.android.systemui.overlay

import android.view.View
import android.widget.FrameLayout
import com.android.axion.compose.host.AxComposeView
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import com.android.systemui.shade.NotificationShadeWindowView
import javax.inject.Inject

@SysUISingleton
class KeyguardOverlayViewManager @Inject constructor(
    private val windowView: NotificationShadeWindowView,
) : CoreStartable {

    override fun start() {
    }

    private fun createOverlayHost(index: Int): AxComposeView {
        val host = AxComposeView(windowView.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val keyguardRoot = windowView.requireViewById<View>(R.id.keyguard_root_view)
        val index = windowView.indexOfChild(keyguardRoot)
        windowView.addView(host, index)
        return host
    }
}
