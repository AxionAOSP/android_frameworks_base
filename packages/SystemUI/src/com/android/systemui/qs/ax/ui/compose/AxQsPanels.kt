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

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clipScrollableContainer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.android.compose.animation.Expandable
import com.android.compose.animation.scene.ContentScope
import com.android.compose.gesture.gesturesDisabled
import com.android.systemui.animation.Expandable as SystemUiExpandable
import com.android.systemui.common.ui.compose.Icon as SystemUiIcon
import com.android.systemui.common.ui.compose.PagerDots
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.ax.ui.model.AxQsGridItem
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsButtonViewModel
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsForegroundServicesButtonViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toolbar.ToolbarViewModel
import com.android.systemui.res.R
import com.android.systemui.shade.ui.composable.ShadeHeader
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel

private val DragHandleWidth = 56.dp
private val DragHandleHeight = 4.dp

@Composable
internal fun <T> ContentScope.AxQQS(
    toolbarViewModel: ToolbarViewModel,
    shadeHeaderViewModel: ShadeHeaderViewModel,
    controlItems: List<AxQsGridItem<T>>,
    tileItems: List<AxQsGridItem<T>>,
    controlColumns: Int,
    controlRows: Int,
    tileColumns: Int,
    tileRows: Int,
    showTileLabels: Boolean,
    rowHeight: Dp,
    spacing: Dp,
    circleCells: Boolean,
    isFullyVisible: () -> Boolean,
    editButtonProgress: () -> Float,
    separateMode: Boolean,
    landscape: Boolean,
    modifier: Modifier = Modifier,
    controlContent: @Composable (AxQsGridItem<T>) -> Unit,
    tileContent: @Composable (AxQsGridItem<T>) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().heightIn(ShadeHeader.Dimensions.StatusBarHeight),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        if (!landscape) {
            AxQsDateHeader(
                toolbarViewModel = toolbarViewModel,
                shadeHeaderViewModel = shadeHeaderViewModel,
                showEdit = false,
                isFullyVisible = isFullyVisible,
                editButtonProgress = editButtonProgress,
            )
        }
        if (!separateMode && !landscape) {
            val pagerState = rememberAxQsTilePagerState(tileItems, tileColumns, tileRows)
            if (controlItems.isNotEmpty()) {
                AxQsGrid(
                    items = controlItems,
                    columns = controlColumns,
                    rowHeight = rowHeight,
                    spacing = spacing,
                    maxRows = controlRows,
                    squareCells = circleCells,
                    modifier = Modifier.fillMaxWidth(),
                    content = controlContent,
                )
            }
            if (tileItems.isNotEmpty()) {
                AxQsTileGrid(
                    items = tileItems,
                    columns = tileColumns,
                    rows = tileRows,
                    spacing = spacing,
                    showLabels = showTileLabels,
                    circleCells = circleCells,
                    pagerState = pagerState,
                    modifier = Modifier.fillMaxWidth(),
                    content = tileContent,
                )
            }
            Box(
                Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.width(DragHandleWidth)
                        .height(DragHandleHeight)
                        .background(
                            colorResource(R.color.ax_qqs_drag_handle),
                            RoundedCornerShape(2.dp),
                        )
                )
            }
        }
    }
}

@Composable
internal fun <T> ContentScope.AxQS(
    toolbarViewModel: ToolbarViewModel,
    shadeHeaderViewModel: ShadeHeaderViewModel,
    isFullyVisible: () -> Boolean,
    controlItems: List<AxQsGridItem<T>>,
    tileItems: List<AxQsGridItem<T>>,
    controlColumns: Int,
    controlRows: Int,
    tileColumns: Int,
    tileRows: Int,
    showTileLabels: Boolean,
    rowHeight: Dp,
    spacing: Dp,
    circleCells: Boolean,
    editButtonProgress: () -> Float,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    controlContent: @Composable (AxQsGridItem<T>) -> Unit,
    tileContent: @Composable (AxQsGridItem<T>) -> Unit,
    tileLabel: @Composable (AxQsGridItem<T>) -> Unit,
) {
    val pagerState = rememberAxQsTilePagerState(tileItems, tileColumns, tileRows)
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(spacing)) {
        AxQsDateHeader(
            toolbarViewModel = toolbarViewModel,
            shadeHeaderViewModel = shadeHeaderViewModel,
            showEdit = true,
            isFullyVisible = isFullyVisible,
            editButtonProgress = editButtonProgress,
        )
        Column(
            modifier =
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .clipScrollableContainer(Orientation.Vertical)
                    .verticalScroll(scrollState, overscrollEffect = null),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                if (controlItems.isNotEmpty()) {
                    AxQsGrid(
                        items = controlItems,
                        columns = controlColumns,
                        rowHeight = rowHeight,
                        spacing = spacing,
                        maxRows = controlRows,
                        squareCells = circleCells,
                        modifier = Modifier.fillMaxWidth(),
                        content = controlContent,
                    )
                }
                if (tileItems.isNotEmpty()) {
                    Column {
                        AxQsTileGrid(
                            items = tileItems,
                            columns = tileColumns,
                            rows = tileRows,
                            spacing = spacing,
                            showLabels = showTileLabels,
                            circleCells = circleCells,
                            pagerState = pagerState,
                            modifier = Modifier.fillMaxWidth(),
                            content = tileContent,
                            label = tileLabel,
                        )
                        AxQsPagerIndicator(
                            pagerState = pagerState,
                            modifier = Modifier.fillMaxWidth().padding(top = spacing),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun <T> ContentScope.AxSplitShadeQS(
    toolbarViewModel: ToolbarViewModel,
    isFullyVisible: () -> Boolean,
    controlItems: List<AxQsGridItem<T>>,
    tileItems: List<AxQsGridItem<T>>,
    controlColumns: Int,
    controlRows: Int,
    tileColumns: Int,
    tileRows: Int,
    showTileLabels: Boolean,
    rowHeight: Dp,
    spacing: Dp,
    circleCells: Boolean,
    editButtonProgress: () -> Float,
    modifier: Modifier = Modifier,
    controlContent: @Composable (AxQsGridItem<T>) -> Unit,
    tileContent: @Composable (AxQsGridItem<T>) -> Unit,
    tileLabel: @Composable (AxQsGridItem<T>) -> Unit,
) {
    val pagerState = rememberAxQsTilePagerState(tileItems, tileColumns, tileRows)
    val hasControls = controlItems.isNotEmpty()
    val hasTiles = tileItems.isNotEmpty()
    val footer: @Composable () -> Unit = {
        AxSplitShadeFooter(
            viewModel = toolbarViewModel,
            isFullyVisible = isFullyVisible,
            editButtonProgress = editButtonProgress,
        )
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val sidePadding = maxWidth * AxQuickSettingsLayoutDefaults.LANDSCAPE_SIDE_PADDING_FRACTION
        Row(
            modifier =
                Modifier.fillMaxSize()
                    .padding(
                        top =
                            AxQuickSettingsLayoutDefaults.LandscapeHeaderHeight +
                                AxQuickSettingsLayoutDefaults.LandscapeHeaderContentSpacing,
                        start = sidePadding,
                        end = sidePadding,
                    ),
            verticalAlignment = Alignment.Top,
        ) {
            if (hasControls) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    AxQsGrid(
                        items = controlItems,
                        columns = controlColumns,
                        rowHeight = rowHeight,
                        spacing = spacing,
                        maxRows = controlRows,
                        squareCells = circleCells,
                        modifier = Modifier.fillMaxWidth(),
                        content = controlContent,
                    )
                    footer()
                }
            }
            if (hasTiles) {
                if (hasControls) {
                    Spacer(
                        Modifier.width(AxQuickSettingsLayoutDefaults.LandscapeSplitGridSpacing)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    AxQsTileGrid(
                        items = tileItems,
                        columns = tileColumns,
                        rows = tileRows,
                        spacing = spacing,
                        showLabels = showTileLabels,
                        circleCells = circleCells,
                        pagerState = pagerState,
                        modifier = Modifier.fillMaxWidth(),
                        content = tileContent,
                        label = tileLabel,
                    )
                    AxQsPagerIndicator(pagerState = pagerState)
                    if (!hasControls) {
                        footer()
                    }
                }
            }
            if (!hasControls && !hasTiles) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    footer()
                }
            }
        }
    }
}

@Composable
private fun <T> rememberAxQsTilePagerState(
    items: List<AxQsGridItem<T>>,
    columns: Int,
    rows: Int,
): PagerState = rememberPagerState { axQsTileGridPageCount(items.size, columns, rows) }

@Composable
private fun ContentScope.AxQsDateHeader(
    toolbarViewModel: ToolbarViewModel,
    shadeHeaderViewModel: ShadeHeaderViewModel,
    showEdit: Boolean,
    isFullyVisible: () -> Boolean,
    editButtonProgress: () -> Float,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            AxQuickSettingsDate(viewModel = shadeHeaderViewModel)
        }
        AxQsHeaderActions(
            viewModel = toolbarViewModel,
            isFullyVisible = isFullyVisible,
            editButtonProgress = editButtonProgress,
            showEdit = showEdit,
            modifier = Modifier.offset(x = 6.dp),
        )
    }
}

@Composable
internal fun ContentScope.AxQsHeaderActions(
    viewModel: ToolbarViewModel,
    isFullyVisible: () -> Boolean,
    editButtonProgress: () -> Float,
    showEdit: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (showEdit) {
            AxEditButton(viewModel, isFullyVisible, editButtonProgress)
        }
        FooterIconButton(
            model = viewModel.settingsButtonViewModel,
            containerColor = AxTileDefaults.backgroundColor(),
            modifier =
                Modifier.sysuiResTag("settings_button_container").minimumInteractiveComponentSize(),
        )
        AxFooterOverflowMenu(viewModel)
    }
}

@Composable
internal fun ContentScope.AxSplitShadeFooter(
    viewModel: ToolbarViewModel,
    isFullyVisible: () -> Boolean,
    editButtonProgress: () -> Float,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AxEditButton(viewModel, isFullyVisible, editButtonProgress)
        FooterIconButton(
            model = viewModel.settingsButtonViewModel,
            containerColor = AxTileDefaults.backgroundColor(),
            modifier =
                Modifier.sysuiResTag("settings_button_container").minimumInteractiveComponentSize(),
        )
        AxFooterOverflowMenu(viewModel)
    }
}

@Composable
internal fun AxQsPagerIndicator(pagerState: PagerState, modifier: Modifier = Modifier) {
    if (pagerState.pageCount > 1) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            PagerDots(
                pagerState = pagerState,
                activeColor = MaterialTheme.colorScheme.primary,
                nonActiveColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                showArrows = false,
            )
        }
    }
}

@Composable
private fun AxEditButton(
    viewModel: ToolbarViewModel,
    isFullyVisible: () -> Boolean,
    editButtonProgress: () -> Float,
) {
    val editModeButtonViewModel =
        rememberViewModel("AxQsActions") { viewModel.editModeButtonViewModelFactory.create() }
    if (!editModeButtonViewModel.isEditButtonVisible) return
    val editAlpha = editButtonProgress().coerceIn(0f, 1f)
    val editEnabled = editAlpha >= EDIT_BUTTON_ENABLE_THRESHOLD && isFullyVisible()
    Box(
        modifier =
            Modifier.graphicsLayer { alpha = editAlpha }
                .then(
                    if (editEnabled) Modifier
                    else Modifier.gesturesDisabled().clearAndSetSemantics {}
                )
    ) {
        Expandable(
            color = AxTileDefaults.backgroundColor(),
            shape = CircleShape,
            onClick = { editModeButtonViewModel.onButtonClick() },
            modifier =
                Modifier.sysuiResTag("qs_edit_mode_button").minimumInteractiveComponentSize(),
            useModifierBasedImplementation = true,
        ) {
            Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.accessibility_quick_settings_edit),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun AxFooterOverflowMenu(viewModel: ToolbarViewModel) {
    var expanded by remember { mutableStateOf(false) }
    var expandable by remember { mutableStateOf<SystemUiExpandable?>(null) }
    val context = LocalContext.current
    val menuColor =
        AxTileDefaults.backgroundColor().compositeOver(MaterialTheme.colorScheme.surface)
    Box {
        Expandable(
            color = AxTileDefaults.backgroundColor(),
            shape = CircleShape,
            onClick = {
                expandable = it
                expanded = true
            },
            modifier = Modifier.minimumInteractiveComponentSize(),
            useModifierBasedImplementation = true,
        ) {
            Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.qs_edit_menu_content_description),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(0.dp, (-12).dp),
            shape = RoundedCornerShape(24.dp),
            containerColor = menuColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            viewModel.foregroundServicesViewModel?.let { model ->
                AxForegroundServicesMenuItem(model) {
                    val source = expandable ?: return@AxForegroundServicesMenuItem
                    expanded = false
                    model.onClick(context, source)
                }
            }
            AxPowerMenuItem(viewModel.powerButtonViewModel) {
                val source = expandable ?: return@AxPowerMenuItem
                expanded = false
                viewModel.powerButtonViewModel.onClick(source)
            }
        }
    }
}

@Composable
private fun AxForegroundServicesMenuItem(
    model: FooterActionsForegroundServicesButtonViewModel,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = stringResource(R.string.ax_qs_running_services),
                style = MaterialTheme.typography.labelLarge,
            )
        },
        leadingIcon = {
            SystemUiIcon(
                icon = model.icon,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = {
            Text(
                text = model.foregroundServicesCount.toString(),
                style = MaterialTheme.typography.labelMedium,
            )
        },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 52.dp),
    )
}

@Composable
private fun AxPowerMenuItem(model: FooterActionsButtonViewModel, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text = stringResource(R.string.accessibility_quick_settings_power_menu),
                style = MaterialTheme.typography.labelLarge,
            )
        },
        leadingIcon = {
            SystemUiIcon(
                icon = model.icon,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 52.dp),
    )
}

@Composable
private fun FooterIconButton(
    model: FooterActionsButtonViewModel?,
    containerColor: Color,
    modifier: Modifier = Modifier,
) {
    if (model == null) return
    Expandable(
        color = containerColor,
        shape = CircleShape,
        onClick = model.onClick,
        modifier = modifier,
        useModifierBasedImplementation = true,
    ) {
        Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            SystemUiIcon(
                icon = model.icon,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private const val EDIT_BUTTON_ENABLE_THRESHOLD = 0.99f
