/*
 * Copyright 2025-2026 AxionOS
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

package com.android.systemui.qs.ax.ui.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.SceneTransitionLayoutState
import com.android.compose.animation.scene.TransitionBuilder
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.systemui.qs.composefragment.SceneKeys
import com.android.systemui.qs.panels.ui.compose.infinitegrid.squishScale
import com.android.systemui.qs.shared.ui.QuickSettings.Elements
import com.android.systemui.shade.ui.composable.ShadeHeader

fun TransitionBuilder.axFromQuickQuickSettingsToQuickSettings() {
    fractionRange(end = 0.5f) {
        fade(SceneKeys.QuickQuickSettings.rootElementKey)
        translate(SceneKeys.QuickQuickSettings.rootElementKey, y = 48.dp)
    }
    fractionRange(start = 0.43f) {
        fade(SceneKeys.QuickSettings.rootElementKey)
        translate(SceneKeys.QuickSettings.rootElementKey, y = (-48).dp)
    }
    disableAxQsSharedElements()
    sharedElement(ShadeHeader.Elements.Clock, enabled = false)
}

fun TransitionBuilder.toAxEditMode() {
    fractionRange(start = 0.5f) { fade(SceneKeys.EditMode.rootElementKey) }
    fractionRange(end = 0.5f) {
        fade(SceneKeys.QuickQuickSettings.rootElementKey)
        fade(SceneKeys.QuickSettings.rootElementKey)
    }
    disableAxQsSharedElements()
}

fun TransitionBuilder.toAxPanelSettings() {
    fractionRange(start = 0.5f) { fade(SceneKeys.PanelSettings.rootElementKey) }
    fractionRange(end = 0.5f) { fade(SceneKeys.EditMode.rootElementKey) }
    disableAxQsSharedElements()
}

private fun TransitionBuilder.disableAxQsSharedElements() {
    sharedElement(Elements.TileElementMatcher, enabled = false)
    sharedElement(Elements.BrightnessSlider, enabled = false)
    sharedElement(Elements.VolumeSlider, enabled = false)
}

fun SceneTransitionLayoutState.shouldComposeLiveAxQs(): Boolean {
    return when (val state = transitionState) {
        is TransitionState.Idle -> state.currentScene.isAxQsScene()
        is TransitionState.Transition -> {
            val fromAxQs = state.fromContent.isAxQsScene()
            val toAxQs = state.toContent.isAxQsScene()
            when {
                fromAxQs && toAxQs -> true
                fromAxQs -> state.progress < 0.5f
                toAxQs -> state.progress > 0.5f
                else -> false
            }
        }
    }
}

internal fun Modifier.axQsEntrance(progress: () -> Float): Modifier {
    return squishScale(
        squishiness = progress,
        alphaStart = AX_QS_ENTRANCE_ALPHA_START,
        transformOrigin = TransformOrigin(0.5f, 0f),
    )
}

private fun ContentKey.isAxQsScene(): Boolean {
    return this == SceneKeys.QuickSettings || this == SceneKeys.QuickQuickSettings
}

private const val AX_QS_ENTRANCE_ALPHA_START = 0.89f
