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
package com.axion.systemui.statusbar.notification.collection.render

import android.content.Intent
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.notification.collection.render.NodeController
import com.android.systemui.statusbar.notification.collection.render.SectionHeaderController
import com.android.systemui.statusbar.notification.stack.SectionHeaderView
import javax.inject.Inject

@SysUISingleton
class EssentialSectionHeaderController @Inject constructor(
    @ShadeDisplayAware private val layoutInflater: LayoutInflater,
    private val activityStarter: ActivityStarter
) : NodeController, SectionHeaderController {

    override val nodeLabel: String = "essential header"

    private var _view: SectionHeaderView? = null
    private var clearAllButtonEnabled = false
    private var clearAllClickListener: View.OnClickListener? = null

    private val onHeaderClickListener = View.OnClickListener {
        activityStarter.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_SETTINGS),
            true,
            true,
            Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
    }

    override fun reinflateView(parent: ViewGroup) {
        var oldPos = -1
        _view?.let { view ->
            view.removeFromTransientContainer()
            if (view.parent === parent) {
                oldPos = parent.indexOfChild(view)
                parent.removeView(view)
            }
        }
        val inflated = layoutInflater.inflate(
            R.layout.status_bar_notification_essential_section_header,
            parent,
            false
        ) as SectionHeaderView
        inflated.setHeaderText(R.string.essential_notifications_title)
        inflated.setOnHeaderClickListener(onHeaderClickListener)
        clearAllClickListener?.let { inflated.setOnClearAllClickListener(it) }
        if (oldPos != -1) {
            parent.addView(inflated, oldPos)
        }
        _view = inflated
        _view?.setClearSectionButtonEnabled(clearAllButtonEnabled)
    }

    override val headerView: SectionHeaderView?
        get() = _view

    override fun setClearSectionEnabled(enabled: Boolean) {
        clearAllButtonEnabled = enabled
        _view?.setClearSectionButtonEnabled(enabled)
    }

    override fun setOnClearSectionClickListener(listener: View.OnClickListener) {
        clearAllClickListener = listener
        _view?.setOnClearAllClickListener(listener)
    }

    override fun onViewAdded() {
        headerView?.setContentVisibleAnimated(true)
    }

    override val view: View
        get() = _view!!

    override fun offerToKeepInParentForAnimation(): Boolean = false

    override fun removeFromParentIfKeptForAnimation(): Boolean = false

    override fun resetKeepInParentForAnimation() {}
}
