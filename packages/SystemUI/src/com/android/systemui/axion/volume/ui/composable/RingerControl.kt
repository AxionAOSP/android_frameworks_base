/*
 * Copyright (C) 2025 Axion OS
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

package com.android.systemui.axion.volume.ui.composable

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.axion.volume.domain.model.AxionRingerMode
import com.android.systemui.axion.volume.ui.viewmodel.AxionVolumeDialogViewModel

@Composable
fun RingerSegmentedButton(
    viewModel: AxionVolumeDialogViewModel,
    ringerMode: AxionRingerMode,
    supportedModes: List<AxionRingerMode>,
    modifier: Modifier = Modifier
) {
    val allOptions = listOf(
        AxionRingerMode.NORMAL to Icons.Filled.Notifications,
        AxionRingerMode.VIBRATE to Icons.Filled.Vibration,
        AxionRingerMode.SILENT to Icons.Filled.NotificationsOff
    )
    val options = allOptions.filter { it.first in supportedModes }
    
    SingleChoiceSegmentedButtonRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        options.forEachIndexed { index, (mode, icon) ->
            val isSelected = ringerMode == mode
            SegmentedButton(
                selected = isSelected,
                onClick = { 
                    viewModel.rescheduleTimeout()
                    viewModel.setRingerMode(mode) 
                },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primary,
                    activeContentColor = MaterialTheme.colorScheme.onPrimary,
                    inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    inactiveContentColor = MaterialTheme.colorScheme.onSurface
                ),
                icon = {}
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = mode.name,
                    modifier = Modifier.size(20.dp),
                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun RingerButton(
    ringerMode: AxionRingerMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    cornerRadius: Dp = 16.dp
) {
    val icon = when (ringerMode) {
        AxionRingerMode.NORMAL -> Icons.Filled.Notifications
        AxionRingerMode.VIBRATE -> Icons.Filled.Vibration
        AxionRingerMode.SILENT -> Icons.Filled.NotificationsOff
    }

    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = RoundedCornerShape(cornerRadius),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceBright,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "Ringer Mode",
            modifier = Modifier.size(24.dp)
        )
    }
}
