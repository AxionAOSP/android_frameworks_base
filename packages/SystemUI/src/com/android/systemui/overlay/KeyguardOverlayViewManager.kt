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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.edgelight.EdgeLight
import com.android.systemui.edgelight.EdgeLightInteractor
import com.android.systemui.media.MediaArt
import com.android.systemui.media.MediaArtInteractor
import com.android.systemui.pulse.PulseInteractor
import com.android.systemui.pulse.PulseVisualizer
import com.android.systemui.res.R
import com.android.systemui.shade.NotificationShadeWindowView
import javax.inject.Inject
@SysUISingleton
class KeyguardOverlayViewManager @Inject constructor(
    private val windowView: NotificationShadeWindowView,
    private val mediaArtInteractor: MediaArtInteractor,
    private val pulseInteractor: PulseInteractor,
    private val edgeLightInteractor: EdgeLightInteractor,
) : CoreStartable {

    override fun start() {
        val keyguardRoot = windowView.requireViewById<View>(R.id.keyguard_root_view)

        val mediaArtView = createOverlayHost(windowView.indexOfChild(keyguardRoot))
        val pulseView = createOverlayHost(windowView.indexOfChild(keyguardRoot))
        val edgeLightView = createOverlayHost(windowView.indexOfChild(keyguardRoot))
        KeyguardOverlayViewBinder.bind(mediaArtView) {
            val state by mediaArtInteractor.uiState.collectAsStateWithLifecycle()
            MediaArt(state = state)
        }
        KeyguardOverlayViewBinder.bind(pulseView) {
            val state by pulseInteractor.uiState.collectAsStateWithLifecycle()
            PulseVisualizer(state = state)
        }
        KeyguardOverlayViewBinder.bind(edgeLightView) {
            val state by edgeLightInteractor.uiState.collectAsState()
            EdgeLight(state = state)
        }
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
