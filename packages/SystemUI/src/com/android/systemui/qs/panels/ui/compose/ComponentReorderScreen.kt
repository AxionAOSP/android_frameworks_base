package com.android.systemui.qs.panels.ui.compose

import android.content.ClipData
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.systemui.qs.composefragment.model.QSComponentVisibility
import com.android.systemui.qs.composefragment.model.QSPanelComponent
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults
import com.android.systemui.qs.panels.ui.compose.infinitegrid.largeTilePadding
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toIconProvider
import com.android.systemui.res.R
import com.android.systemui.qs.panels.ui.viewmodel.ComponentReorderViewModel
import com.android.systemui.util.ui.compose.SystemSliderColors

private const val COMPONENT_MIME_TYPE = "text/plain"
private const val HEADER_KEY = "reorder_header"
private const val SLIDER_PREVIEW_ACTIVE_ICON_THRESHOLD = 0.86f

private val QS_CONTENT_MAX_WIDTH = 376.dp
private val SLIDER_PREVIEW_HEIGHT = 64.dp
private val SLIDER_PREVIEW_TRACK_HEIGHT = 40.dp
private val SLIDER_PREVIEW_THUMB_HEIGHT = 52.dp
private val SLIDER_PREVIEW_THUMB_WIDTH = 4.dp
private val SLIDER_PREVIEW_TRACK_CORNER = 12.dp
private val SLIDER_PREVIEW_ICON_PADDING = 8.dp

val LocalComponentPreviews =
    compositionLocalOf<Map<QSPanelComponent, @Composable () -> Unit>> { emptyMap() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComponentReorderScreen(
    viewModel: ComponentReorderViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOrder = viewModel.componentOrder
    val brightnessSliderVisibility = viewModel.brightnessSliderVisibility
    val volumeSliderVisibility = viewModel.volumeSliderVisibility
    val orientation = viewModel.orientation
    val surfaceEffect2 = LocalAndroidColorScheme.current.surfaceEffect2
    val isLargeScreen = LocalContext.current.resources.getBoolean(
        R.bool.config_use_large_screen_shade_header
    )
    val maxContentWidth = if (isLargeScreen) Dp.Unspecified else QS_CONTENT_MAX_WIDTH

    Scaffold(
        modifier = modifier
            .consumeWindowInsets(WindowInsets.displayCutout),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                title = {
                    Text(
                        text = stringResource(R.string.qs_reorder_components_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 24.dp),
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 2,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = surfaceEffect2,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(
                                com.android.internal.R.string.action_bar_up_description
                            ),
                        )
                    }
                },
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(vertical = 8.dp),
                windowInsets = WindowInsets(0.dp),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            ComponentReorderList(
                currentOrder = currentOrder,
                onOrderChanged = viewModel::updateComponentOrder,
                brightnessSliderVisibility = brightnessSliderVisibility,
                onBrightnessSliderVisibilityChanged =
                    viewModel::updateBrightnessSliderVisibility,
                volumeSliderVisibility = volumeSliderVisibility,
                onVolumeSliderVisibilityChanged = viewModel::updateVolumeSliderVisibility,
                orientation = orientation,
                modifier = Modifier
                    .widthIn(max = maxContentWidth)
                    .padding(horizontal = 16.dp),
            )
        }
    }
}

private class ComponentDragDropState(initialOrder: List<QSPanelComponent>) {

    private val _items = mutableStateListOf(*initialOrder.toTypedArray())
    val items: List<QSPanelComponent> get() = _items

    var draggedComponent by mutableStateOf<QSPanelComponent?>(null)
        private set

    val dragInProgress: Boolean get() = draggedComponent != null

    fun isMoving(key: String): Boolean = draggedComponent?.key == key

    fun onStarted(component: QSPanelComponent) {
        draggedComponent = component
    }

    fun onTargeting(targetIndex: Int) {
        val dragged = draggedComponent ?: return
        val fromIndex = _items.indexOf(dragged)
        if (fromIndex == targetIndex || fromIndex == -1) return
        _items.removeAt(fromIndex)
        _items.add(targetIndex.coerceIn(0, _items.size), dragged)
    }

    fun onDrop(): List<QSPanelComponent> {
        val result = _items.toList()
        draggedComponent = null
        return result
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComponentReorderList(
    currentOrder: List<QSPanelComponent>,
    onOrderChanged: (List<QSPanelComponent>) -> Unit,
    brightnessSliderVisibility: QSComponentVisibility,
    onBrightnessSliderVisibilityChanged: (QSComponentVisibility) -> Unit,
    volumeSliderVisibility: QSComponentVisibility,
    onVolumeSliderVisibilityChanged: (QSComponentVisibility) -> Unit,
    orientation: Int,
    modifier: Modifier = Modifier,
) {
    val dragDropState = remember(currentOrder) { ComponentDragDropState(currentOrder) }
    val listState = rememberLazyListState()
    var containerOffset by remember { mutableStateOf(Offset.Zero) }
    val previews = LocalComponentPreviews.current
    val componentHeights = remember { mutableStateMapOf<QSPanelComponent, Dp>() }
    val density = LocalDensity.current
    var activeComponent by remember { mutableStateOf<QSPanelComponent?>(null) }

    val dropTarget = remember(dragDropState) {
        object : DragAndDropTarget {
            override fun onMoved(event: DragAndDropEvent) {
                val offset = event.toAndroidDragEvent().run { Offset(x, y) }
                val relativeOffset = offset - containerOffset

                val targetItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                    item.key != HEADER_KEY &&
                        relativeOffset.y >= item.offset.toFloat() &&
                        relativeOffset.y < (item.offset + item.size).toFloat()
                }
                targetItem?.let { dragDropState.onTargeting(it.index - 1) }
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                val result = dragDropState.onDrop()
                onOrderChanged(result)
                return true
            }

            override fun onEnded(event: DragAndDropEvent) {
                if (dragDropState.dragInProgress) {
                    val result = dragDropState.onDrop()
                    onOrderChanged(result)
                }
            }
        }
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                containerOffset = coords.positionInRoot()
            }
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                    event.mimeTypes().contains(COMPONENT_MIME_TYPE)
                },
                target = dropTarget,
            ),
    ) {
        item(key = HEADER_KEY) {
            Text(
                text = stringResource(R.string.qs_reorder_components_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        itemsIndexed(
            items = dragDropState.items,
            key = { _, component -> component.key },
        ) { _, component ->
            val isBeingDragged = dragDropState.isMoving(component.key)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(
                        placementSpec = spring(
                            stiffness = Spring.StiffnessMediumLow,
                            dampingRatio = Spring.DampingRatioLowBouncy,
                        ),
                    ),
            ) {
                if (isBeingDragged) {
                    val height = componentHeights[component] ?: ComponentPreviewHeight(component)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(height)
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)
                            ),
                    )
                } else {
                    val preview = previews[component]
                    val onGloballyPositioned = Modifier.onGloballyPositioned {
                        componentHeights[component] =
                            with(density) { it.size.height.toDp() }
                    }
                    val hapticFeedback = LocalHapticFeedback.current
                    val isActive = activeComponent == component
                    val hasVisibilityOptions =
                        component == QSPanelComponent.BRIGHTNESS ||
                            component == QSPanelComponent.VOLUME

                    if (component == QSPanelComponent.TILES) {
                        key(orientation) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .componentDragSource(component, dragDropState) {
                                        activeComponent = null
                                    }
                                    .then(onGloballyPositioned),
                            ) {
                                preview?.invoke()
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .border(
                                    width = 2.dp,
                                    color = if (isActive) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                    },
                                    shape = RoundedCornerShape(20.dp),
                                )
                                .combinedClickable(
                                    onClick = {
                                        if (hasVisibilityOptions) {
                                            activeComponent = if (isActive) null else component
                                        }
                                    },
                                    onLongClick = {
                                        if (hasVisibilityOptions) {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            activeComponent = component
                                        }
                                    }
                                )
                                .componentDragSource(component, dragDropState) {
                                    activeComponent = null
                                }
                                .then(onGloballyPositioned),
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                if (preview != null) {
                                    preview()
                                } else {
                                    when (component) {
                                        QSPanelComponent.BRIGHTNESS ->
                                            BrightnessSliderPreview()
                                        QSPanelComponent.VOLUME -> VolumeSliderRowPreview()
                                        QSPanelComponent.MEDIA -> MediaPreview()
                                        else -> {}
                                    }
                                }
                                if (isActive) {
                                    when (component) {
                                        QSPanelComponent.BRIGHTNESS ->
                                            ComponentVisibilityRow(
                                                visibility = brightnessSliderVisibility,
                                                onVisibilityChanged =
                                                    onBrightnessSliderVisibilityChanged,
                                            )
                                        QSPanelComponent.VOLUME ->
                                            ComponentVisibilityRow(
                                                visibility = volumeSliderVisibility,
                                                onVisibilityChanged =
                                                    onVolumeSliderVisibilityChanged,
                                            )
                                        else -> {}
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComponentPreviewHeight(component: QSPanelComponent) = when (component) {
    QSPanelComponent.BRIGHTNESS -> 80.dp
    QSPanelComponent.VOLUME -> 72.dp
    QSPanelComponent.TILES -> CommonTileDefaults.TileHeight + 24.dp
    QSPanelComponent.MEDIA -> dimensionResource(R.dimen.qs_media_session_height_expanded) + 16.dp
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.componentDragSource(
    component: QSPanelComponent,
    dragDropState: ComponentDragDropState,
    onDragStart: () -> Unit = {},
): Modifier {
    val state by rememberUpdatedState(dragDropState)
    val hapticFeedback = LocalHapticFeedback.current

    @Suppress("DEPRECATION")
    return dragAndDropSource(
        block = {
            detectDragGesturesAfterLongPress(
                onDrag = { _, _ -> },
                onDragStart = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDragStart()
                    state.onStarted(component)
                    startTransfer(
                        DragAndDropTransferData(
                            ClipData.newPlainText("component", component.key)
                        )
                    )
                },
            )
        }
    )
}

@Composable
fun BrightnessSliderPreview(
    brightness: Float = 30f,
    iconRes: Int = R.drawable.ic_brightness_full,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SliderPreview(
            progress = brightness / 100f,
            icon = { tint ->
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = tint,
                )
            },
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier =
                Modifier.size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_qs_brightness_auto_on),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
fun VolumeSliderRowPreview(volume: Float = 60f) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SliderPreview(
            progress = volume / 100f,
            icon = { tint ->
                Icon(
                    imageVector = Icons.Rounded.VolumeUp,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = tint,
                )
            },
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier =
                Modifier.size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.horizontal_ellipsis),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SliderPreview(
    progress: Float,
    icon: @Composable (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SystemSliderColors.colors()
    val fraction = progress.coerceIn(0f, 1f)
    val iconColor =
        if (fraction >= SLIDER_PREVIEW_ACTIVE_ICON_THRESHOLD) {
            SystemSliderColors.activeIconColor(colors, enabled = true)
        } else {
            SystemSliderColors.inactiveIconColor(colors, enabled = true)
        }
    Box(
        modifier =
            modifier.height(SLIDER_PREVIEW_HEIGHT),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .height(SLIDER_PREVIEW_TRACK_HEIGHT)
                    .clip(RoundedCornerShape(SLIDER_PREVIEW_TRACK_CORNER))
                    .background(colors.inactiveTrackColor)
        )
        Box(
            modifier =
                Modifier.fillMaxWidth(fraction)
                    .height(SLIDER_PREVIEW_TRACK_HEIGHT)
                    .clip(
                        RoundedCornerShape(
                            topStart = SLIDER_PREVIEW_TRACK_CORNER,
                            topEnd = 0.dp,
                            bottomEnd = 0.dp,
                            bottomStart = SLIDER_PREVIEW_TRACK_CORNER,
                        )
                    )
                    .background(colors.activeTrackColor)
        )
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .height(SLIDER_PREVIEW_TRACK_HEIGHT)
                    .padding(end = SLIDER_PREVIEW_ICON_PADDING),
            contentAlignment = Alignment.CenterEnd,
        ) {
            icon(iconColor)
        }
        Box(
            modifier =
                Modifier.fillMaxWidth(fraction)
                    .height(SLIDER_PREVIEW_THUMB_HEIGHT),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier =
                    Modifier.width(SLIDER_PREVIEW_THUMB_WIDTH)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(SLIDER_PREVIEW_THUMB_WIDTH))
                        .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
fun EditStyleTileGrid(
    tiles: List<SizedTile<TileViewModel>>,
    columns: Int,
    modifier: Modifier = Modifier,
) {
    val tileBg = LocalAndroidColorScheme.current.surfaceEffect1
    val iconColor = MaterialTheme.colorScheme.onSurface
    val labelColor = MaterialTheme.colorScheme.onSurface
    val primaryColor = MaterialTheme.colorScheme.primary
    val gridCornerRadius = 28.dp
    val gridPadding = 10.dp

    val rows = remember(tiles, columns) {
        buildList {
            var currentRow = mutableListOf<SizedTile<TileViewModel>>()
            var currentWidth = 0
            for (tile in tiles) {
                if (currentWidth + tile.width > columns) {
                    add(currentRow.toList())
                    currentRow = mutableListOf(tile)
                    currentWidth = tile.width
                } else {
                    currentRow.add(tile)
                    currentWidth += tile.width
                }
            }
            if (currentRow.isNotEmpty()) add(currentRow.toList())
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(CommonTileDefaults.TileSpacing),
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 2.dp,
                color = primaryColor,
                shape = RoundedCornerShape(gridCornerRadius),
            )
            .drawBehind {
                drawRoundRect(
                    primaryColor,
                    cornerRadius = CornerRadius(gridCornerRadius.toPx()),
                    alpha = .15f,
                )
            }
            .padding(gridPadding),
    ) {
        for (row in rows) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(CommonTileDefaults.TileSpacing),
                modifier = Modifier.fillMaxWidth().height(CommonTileDefaults.TileHeight),
            ) {
                val totalSpan = row.sumOf { it.width }
                for (sizedTile in row) {
                    EditStyleTileCell(
                        tile = sizedTile.tile,
                        isIcon = sizedTile.isIcon,
                        tileBg = tileBg,
                        iconColor = iconColor,
                        labelColor = labelColor,
                        modifier = Modifier
                            .weight(sizedTile.width.toFloat())
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(CommonTileDefaults.InactiveCornerRadius))
                            .background(tileBg),
                    )
                }
                repeat(columns - totalSpan) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun EditStyleTileCell(
    tile: TileViewModel,
    isIcon: Boolean,
    tileBg: Color,
    iconColor: Color,
    labelColor: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state = tile.currentState
    val iconProvider = state.toIconProvider()
    val drawable = remember(iconProvider) {
        val qsIcon = iconProvider.icon
        qsIcon?.getDrawable(context)
    }

    Box(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .largeTilePadding()
                .align(Alignment.CenterStart),
        ) {
            Box(
                Modifier.size(CommonTileDefaults.ToggleTargetSize),
                contentAlignment = Alignment.Center,
            ) {
                if (drawable != null) {
                    Icon(
                        painter = rememberDrawablePainter(drawable),
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(CommonTileDefaults.IconSize),
                    )
                }
            }
            if (!isIcon) {
                Text(
                    text = state.label?.toString() ?: "",
                    style = MaterialTheme.typography.titleSmall,
                    color = labelColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MediaPreview() {
    val accentColor = MaterialTheme.colorScheme.primary
    val onAccentColor = MaterialTheme.colorScheme.onPrimary
    val cardBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    val textColor = MaterialTheme.colorScheme.onSurface
    val mediaHeight = dimensionResource(R.dimen.qs_media_session_height_expanded)
    var progress by remember { mutableFloatStateOf(0.2f) }
    val animatedProgress by
        animateFloatAsState(
            targetValue = progress,
            animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
        )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(mediaHeight)
            .clip(RoundedCornerShape(16.dp))
            .background(cardBg)
            .border(
                width = 2.dp,
                color = accentColor.copy(alpha = 0.1f),
                shape = RoundedCornerShape(16.dp),
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(accentColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = onAccentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(accentColor)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PhoneAndroid,
                        contentDescription = null,
                        tint = onAccentColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "This phone",
                        style = MaterialTheme.typography.labelSmall,
                        color = onAccentColor,
                        maxLines = 1
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Song",
                        style = MaterialTheme.typography.titleLarge,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Artist",
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accentColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Pause,
                        contentDescription = null,
                        tint = onAccentColor,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(24.dp)
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    LinearWavyProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ComponentVisibilityRow(
    visibility: QSComponentVisibility,
    onVisibilityChanged: (QSComponentVisibility) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.qs_component_visibility_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QSComponentVisibility.entries.forEach { option ->
                val selected = visibility == option
                val backgroundColor = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                }
                val contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(2.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(backgroundColor)
                        .clickable { onVisibilityChanged(option) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(componentVisibilityLabelResId(option)),
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor,
                    )
                }
            }
        }
    }
}

private fun componentVisibilityLabelResId(visibility: QSComponentVisibility): Int =
    when (visibility) {
        QSComponentVisibility.ALWAYS -> R.string.qs_component_visibility_always
        QSComponentVisibility.QS_ONLY -> R.string.qs_component_visibility_qs_only
        QSComponentVisibility.HIDDEN -> R.string.qs_component_visibility_hidden
    }
