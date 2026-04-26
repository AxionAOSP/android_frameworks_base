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

package com.android.systemui.qs.tiles

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.text.TextUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.internal.logging.MetricsLogger
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlinx.coroutines.launch

class DnsTile
@Inject
constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val globalSettings: GlobalSettings,
) :
    QSTileImpl<QSTile.BooleanState>(
        host,
        uiEventLogger,
        backgroundLooper,
        mainHandler,
        falsingManager,
        metricsLogger,
        statusBarStateController,
        activityStarter,
        qsLogger,
    ) {

    private var icon: QSTile.Icon? = null

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                globalSettings
                    .observerFlow(
                        Settings.Global.PRIVATE_DNS_MODE,
                        Settings.Global.PRIVATE_DNS_SPECIFIER,
                    )
                    .collect { refreshState() }
            }
        }
    }

    override fun newTileState(): QSTile.BooleanState {
        return QSTile.BooleanState().also {
            it.handlesLongClick = true
        }
    }

    override fun handleClick(expandable: Expandable?) {
        val next = when (currentMode()) {
            MODE_HOSTNAME -> MODE_OFF
            MODE_AUTO -> {
                ensureHostname()
                MODE_HOSTNAME
            }
            else -> MODE_AUTO
        }
        globalSettings.putString(Settings.Global.PRIVATE_DNS_MODE, next)
        refreshState()
    }

    override fun getLongClickIntent(): Intent = WIRELESS_SETTINGS

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.quick_settings_dns_label)

    override fun handleUpdateState(state: QSTile.BooleanState, arg: Any?) {
        if (icon == null) {
            icon = maybeLoadResourceIcon(R.drawable.ic_qs_dns)
        }
        state.icon = icon
        state.label = mContext.getString(R.string.quick_settings_dns_label)

        when (currentMode()) {
            MODE_HOSTNAME -> {
                val hostname = globalSettings.getString(Settings.Global.PRIVATE_DNS_SPECIFIER)
                val shown = if (TextUtils.isEmpty(hostname)) DEFAULT_HOSTNAME else hostname
                state.value = true
                state.secondaryLabel = shown
                state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_dns_on, shown)
                state.state = Tile.STATE_ACTIVE
            }
            MODE_AUTO -> {
                val label = mContext.getString(R.string.quick_settings_dns_auto)
                state.value = true
                state.secondaryLabel = label
                state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_dns_on, label)
                state.state = Tile.STATE_ACTIVE
            }
            else -> {
                state.value = false
                state.secondaryLabel = mContext.getString(R.string.quick_settings_dns_off)
                state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_dns_off)
                state.state = Tile.STATE_INACTIVE
            }
        }
    }

    private fun currentMode(): String? =
        globalSettings.getString(Settings.Global.PRIVATE_DNS_MODE)

    private fun ensureHostname() {
        val hostname = globalSettings.getString(Settings.Global.PRIVATE_DNS_SPECIFIER)
        if (TextUtils.isEmpty(hostname)) {
            globalSettings.putString(Settings.Global.PRIVATE_DNS_SPECIFIER, DEFAULT_HOSTNAME)
        }
    }

    companion object {
        const val TILE_SPEC: String = "dns"

        private val WIRELESS_SETTINGS = Intent(Settings.ACTION_WIRELESS_SETTINGS)
        private const val MODE_OFF = "off"
        private const val MODE_AUTO = "opportunistic"
        private const val MODE_HOSTNAME = "hostname"
        private const val DEFAULT_HOSTNAME = "one.one.one.one"
    }
}
