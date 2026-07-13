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

package com.android.systemui.qs.ax.ui

import android.view.View

class AxNotificationStackVisibility {
    private var requestedVisibility: Int? = null
    private var quickQsHidden = false
    private var separateShadeCollapsing = false

    fun update(view: View, visible: Boolean) {
        requestedVisibility = if (visible) View.VISIBLE else View.INVISIBLE
        apply(view)
    }

    fun setQuickQsHidden(view: View, hidden: Boolean) {
        requestedVisibility = requestedVisibility ?: view.visibility
        quickQsHidden = hidden
        apply(view)
    }

    fun setSeparateShadeCollapsing(view: View, collapsing: Boolean) {
        requestedVisibility = requestedVisibility ?: view.visibility
        separateShadeCollapsing = collapsing
        apply(view)
    }

    private fun apply(view: View) {
        view.visibility =
            if (quickQsHidden || separateShadeCollapsing) View.INVISIBLE
            else requestedVisibility ?: view.visibility
    }
}
