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

package com.android.systemui.routines.ui.qs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.compose.PlatformButton
import com.android.compose.theme.PlatformTheme
import com.android.systemui.dialog.ui.composable.AlertDialogContent
import com.android.systemui.res.R
import com.android.systemui.routines.data.RoutinesRepository
import com.android.systemui.routines.domain.RoutinesInteractor
import com.android.systemui.routines.model.Routine
import com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractor
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.create
import javax.inject.Inject

class RoutinesTileDialogDelegate @Inject constructor(
    private val sysuiDialogFactory: SystemUIDialogFactory,
    private val repository: RoutinesRepository,
    private val interactor: RoutinesInteractor,
    private val shadeDialogContextInteractor: ShadeDialogContextInteractor,
) {

    fun createDialog(): SystemUIDialog =
        sysuiDialogFactory.create(context = shadeDialogContextInteractor.context) {
            RoutinesDialogContent(it)
        }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun RoutinesDialogContent(dialog: SystemUIDialog) {
        val isCurrentlyInDarkTheme = isSystemInDarkTheme()
        val cachedDarkTheme = remember { isCurrentlyInDarkTheme }
        PlatformTheme(isDarkTheme = cachedDarkTheme) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.quick_settings_routines_dialog_title)) },
                content = { RoutinesList(dialog) },
                positiveButton = {
                    PlatformButton(onClick = { dialog.dismiss() }) {
                        Text(stringResource(R.string.quick_settings_done))
                    }
                },
                contentBottomPadding = 8.dp,
            )
        }
    }

    @Composable
    private fun RoutinesList(dialog: SystemUIDialog) {
        val routines by repository.routines.collectAsState()
        val enabled = routines.filter { it.enabled }
        if (enabled.isEmpty()) {
            Text(
                text = stringResource(R.string.quick_settings_routines_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            return
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(enabled, key = { it.id }) { routine ->
                RoutineRow(routine) {
                    interactor.runRoutineNow(routine.id)
                    dialog.dismiss()
                }
            }
        }
    }

    @Composable
    private fun RoutineRow(routine: Routine, onClick: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp, horizontal = 4.dp),
        ) {
            Text(
                text = routine.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    R.string.quick_settings_routines_action_count,
                    routine.actions.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
