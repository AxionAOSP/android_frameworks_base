/*
 * Copyright (C) 2025 AxionOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.shared.clocks

import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.android.systemui.customization.R

class NowPlayingIconBinder private constructor() {

    private var currentDrawable: Drawable? = null

    fun bindAndStart(iconView: ImageView?) {
        if (iconView == null) return
        if (USE_ANIMATED_DRAWABLE) {
            val drawable = ContextCompat.getDrawable(iconView.context, R.drawable.audio_bars_playing)
            iconView.setImageDrawable(drawable)
            currentDrawable = drawable
            if (drawable is AnimatedVectorDrawable) {
                drawable.registerAnimationCallback(object : Animatable2.AnimationCallback() {
                    override fun onAnimationEnd(drawable: Drawable?) {
                        (drawable as? AnimatedVectorDrawable)?.start()
                    }
                })
                drawable.start()
            }
        } else {
            iconView.setImageResource(R.drawable.ic_music_note)
            currentDrawable = null
        }
    }

    fun stop() {
        if (USE_ANIMATED_DRAWABLE) {
            (currentDrawable as? AnimatedVectorDrawable)?.stop()
            currentDrawable = null
        }
    }

    companion object {
        @Volatile
        private var instance: NowPlayingIconBinder? = null
        const val USE_ANIMATED_DRAWABLE = false

        fun get(): NowPlayingIconBinder {
            return instance ?: synchronized(this) {
                instance ?: NowPlayingIconBinder().also { instance = it }
            }
        }
    }
}
