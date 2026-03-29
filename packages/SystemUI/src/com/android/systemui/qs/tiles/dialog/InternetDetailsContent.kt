/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.qs.tiles.dialog

import android.view.LayoutInflater
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.theme.PlatformTheme
import com.android.systemui.compose.ComposeInitializer
import com.android.settingslib.R as SettingsLibR
import com.android.systemui.res.R

fun setupExtrasComposeView(
    rootView: View,
    composeView: ComposeView,
    viewModel: InternetTileExtrasViewModel,
    canConfigMobileData: Boolean,
    canConfigWifi: Boolean,
) {
    ComposeInitializer.onAttachedToWindow(rootView)
    composeView.setContent {
        PlatformTheme {
            InternetDialogExtras(viewModel, canConfigMobileData, canConfigWifi)
        }
    }
}

@Composable
fun InternetDialogExtras(
    viewModel: InternetTileExtrasViewModel,
    canConfigMobileData: Boolean,
    canConfigWifi: Boolean,
) {
    DisposableEffect(viewModel) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }
    InternetTileExtras(viewModel, canConfigMobileData, canConfigWifi)
}

@Composable
fun InternetDetailsContent(viewModel: InternetDetailsViewModel) {
    val coroutineScope = rememberCoroutineScope()
    val extrasVm = viewModel.extrasViewModel

    DisposableEffect(extrasVm) {
        extrasVm.start()
        onDispose { extrasVm.stop() }
    }

    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            // Inflate with the existing dialog xml layout and bind it with the manager
            val view =
                LayoutInflater.from(context).inflate(R.layout.internet_connectivity_details, null)
            viewModel.internetDetailsContentManager.bind(view, coroutineScope)

            view.findViewById<ComposeView>(R.id.internet_extras_compose).setContent {
                PlatformTheme {
                    InternetTileExtras(
                        extrasVm,
                        viewModel.canConfigMobileData,
                        viewModel.canConfigWifi,
                    )
                }
            }

            view
            // TODO: b/377388104 - Polish the internet details view UI
        },
        onRelease = { viewModel.internetDetailsContentManager.unBind() },
    )
}

@Composable
private fun InternetTileExtras(
    viewModel: InternetTileExtrasViewModel,
    canConfigMobileData: Boolean,
    canConfigWifi: Boolean,
) {
    val hotspotAvailable by viewModel.hotspotAvailable.collectAsState()
    val hotspotEnabled by viewModel.hotspotEnabled.collectAsState()
    val fiveGAvailable by viewModel.fiveGAvailable.collectAsState()
    val fiveGEnabled by viewModel.fiveGEnabled.collectAsState()
    val mobileUsage by viewModel.mobileDataUsage.collectAsState()
    val wifiUsage by viewModel.wifiDataUsage.collectAsState()
    val mobileCarrier by viewModel.mobileCarrier.collectAsState()
    val wifiSsid by viewModel.wifiSsid.collectAsState()

    val showMobileUsage = mobileUsage != null && canConfigMobileData
    val showWifiUsage = wifiUsage != null && canConfigWifi

    val showFiveG = fiveGAvailable && canConfigMobileData
    val showHotspot = hotspotAvailable && canConfigMobileData

    val hasUsage = showMobileUsage || showWifiUsage
    val hasToggles = showFiveG || showHotspot

    if (!hasUsage && !hasToggles) return

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = MARGIN_H)) {
        if (hasUsage) {
            DataUsageSummary(
                mobileUsage = if (canConfigMobileData) mobileUsage else null,
                wifiUsage = if (canConfigWifi) wifiUsage else null,
                mobileCarrier = mobileCarrier,
                wifiSsid = wifiSsid,
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = PADDING_H),
            )
        }

        if (showFiveG) {
            ToggleRow(
                iconRes = R.drawable.ic_5g_toggle,
                label = "5G",
                checked = fiveGEnabled,
                onToggle = { viewModel.toggleFiveG() },
            )
        }

        if (showHotspot) {
            ToggleRow(
                iconRes = R.drawable.ic_hotspot,
                label = stringResource(R.string.quick_settings_hotspot_label),
                checked = hotspotEnabled,
                onToggle = { viewModel.toggleHotspot() },
            )
        }
    }
}

@Composable
private fun DataUsageSummary(
    mobileUsage: String?,
    wifiUsage: String?,
    mobileCarrier: String?,
    wifiSsid: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .padding(horizontal = PADDING_H),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mobileUsage != null) {
            DataUsageItem(
                iconRes = SettingsLibR.drawable.ic_mobile_4_4_bar,
                label = mobileCarrier ?: stringResource(R.string.quick_settings_cellular_detail_title),
                usage = mobileUsage,
                modifier = Modifier.weight(1f),
            )
        }
        if (wifiUsage != null) {
            DataUsageItem(
                iconRes = SettingsLibR.drawable.ic_wifi_3,
                label = wifiSsid ?: stringResource(R.string.quick_settings_wifi_label),
                usage = wifiUsage,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DataUsageItem(
    iconRes: Int,
    label: String,
    usage: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = usage,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    iconRes: Int,
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .padding(horizontal = PADDING_H),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(7.dp))
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(28.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
        )
    }
}

private val MARGIN_H = 16.dp
private val PADDING_H = 22.dp
private val ROW_HEIGHT = 72.dp
