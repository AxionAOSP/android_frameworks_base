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

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.android.systemui.res.R

class WifiStandardImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ImageView(context, attrs) {

    private var controller: WifiStandardViewController? = null
    private lateinit var factory: WifiStandardViewController.Factory

    fun setFactory(factory: WifiStandardViewController.Factory) {
        this.factory = factory
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        controller = factory.create(this).apply { init() }
    }

    override fun onDetachedFromWindow() {
        controller?.destroy()
        controller = null
        super.onDetachedFromWindow()
    }

    fun updateView(wifiStandard: Int, enabled: Boolean) {
        val layoutParams = layoutParams as? ViewGroup.MarginLayoutParams
        if (!enabled || wifiStandard < 4) {
            setHidden(layoutParams)
            return
        }

        val iconRes = when (wifiStandard) {
            4 -> R.drawable.ic_wifi_standard_4
            5 -> R.drawable.ic_wifi_standard_5
            6 -> R.drawable.ic_wifi_standard_6
            7 -> R.drawable.ic_wifi_standard_7
            else -> 0
        }

        if (iconRes != 0) {
            setImageResource(iconRes)
            visibility = View.VISIBLE
            layoutParams?.let {
                it.marginEnd = resources.getDimensionPixelSize(
                    R.dimen.status_bar_airplane_spacer_width
                )
                this.layoutParams = it
            }
        } else {
            setHidden(layoutParams)
        }
    }

    private fun setHidden(layoutParams: ViewGroup.MarginLayoutParams?) {
        visibility = View.GONE
        layoutParams?.let {
            it.marginEnd = 0
            this.layoutParams = it
        }
    }
}
