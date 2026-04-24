package com.android.systemui.axdynamicbar.ui

import android.content.Context
import android.graphics.Color.TRANSPARENT
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.animation.R as AnimationR
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.AxDynamicBarTheme
import com.android.systemui.axdynamicbar.shared.ExpandedMaxWidth
import com.android.systemui.axdynamicbar.ui.compose.ExpandedContentBottomScrollPadding
import com.android.systemui.axdynamicbar.ui.compose.ExpandedIslandContent
import com.android.systemui.axdynamicbar.ui.compose.screenBounds
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.shared.recents.utilities.Utilities
import com.android.systemui.statusbar.phone.ComponentSystemUIDialog
import com.android.systemui.statusbar.phone.DialogDelegate
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.create
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

private val PanelCollapsedRadius = 12.dp
private val PanelExpandedRadius = 28.dp
private const val PanelCollapsedScaleX = 0.24f
private const val PanelCollapsedScaleY = 0.16f

@SysUISingleton
class AxDynamicBarExpandedPanel
@Inject
constructor(
    @Application private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainExecutor: Executor,
    private val dialogFactory: SystemUIDialogFactory,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val viewModel: AxDynamicBarChipViewModel,
) {
    private var currentDialog: ComponentSystemUIDialog? = null

    fun init() {
        viewModel.interactor.onCollapseRequested = { viewModel.statusBarExpansion.collapse() }
        viewModel.interactor.onFocusableRequested = { focusable -> setDialogFocusable(focusable) }

        combine(viewModel.isExpanded, viewModel.isOnKeyguard) { expanded, onKeyguard ->
                expanded && !onKeyguard
            }
            .onEach { expanded ->
                if (expanded) {
                    showDialog(viewModel.statusBarExpansion.expandable.value)
                } else {
                    dismissDialog()
                }
            }
            .launchIn(applicationScope)
    }

    private fun ensureMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainExecutor.execute(action)
    }

    private fun showDialog(expandable: Expandable?) = ensureMainThread {
        if (currentDialog != null) return@ensureMainThread

        val dialog =
            dialogFactory.create(
                context = context,
                dismissOnDeviceLock = true,
                dialogDelegate = dialogDelegate(),
            ) {
                ExpandedPanelDialogContent(viewModel)
            }

        configureDialogWindow(dialog)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            currentDialog = null
            if (viewModel.isExpanded.value) {
                viewModel.statusBarExpansion.collapse()
            }
        }
        currentDialog = dialog

        expandable?.dialogTransitionController()?.let { controller ->
            dialogTransitionAnimator.show(dialog, controller, animateBackgroundBoundsChange = true)
            installDialogOutsideTouchHandler(dialog)
        }
            ?: run {
                dialog.show()
                installDialogOutsideTouchHandler(dialog)
            }
    }

    private fun dismissDialog() = ensureMainThread { currentDialog?.dismiss() }

    private fun setDialogFocusable(focusable: Boolean) = ensureMainThread {
        currentDialog?.window?.let { window ->
            if (focusable) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            } else {
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            }
        }
    }

    private fun dialogDelegate(): DialogDelegate<SystemUIDialog> =
        object : DialogDelegate<SystemUIDialog> {
            override fun onCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
                configureDialogWindow(dialog)
            }

            override fun onStart(dialog: SystemUIDialog) {
                configureDialogWindow(dialog)
            }

            override fun getWidth(dialog: SystemUIDialog): Int =
                WindowManager.LayoutParams.MATCH_PARENT

            override fun getHeight(dialog: SystemUIDialog): Int =
                WindowManager.LayoutParams.WRAP_CONTENT
        }

    private fun configureDialogWindow(dialog: SystemUIDialog) {
        val window = dialog.window ?: return
        window.setGravity(Gravity.TOP or Gravity.FILL_HORIZONTAL)
        window.setBackgroundDrawable(GradientDrawable().apply { setColor(TRANSPARENT) })
        window.decorView.background = GradientDrawable().apply { setColor(TRANSPARENT) }
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val attributes = window.attributes
        attributes.dimAmount = 0f
        attributes.gravity = Gravity.TOP or Gravity.FILL_HORIZONTAL
        attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        window.attributes = attributes
    }

    private fun installDialogOutsideTouchHandler(dialog: SystemUIDialog) {
        dialog.window?.decorView?.post {
            val decor = dialog.window?.decorView as? ViewGroup ?: return@post
            if (decor.childCount == 0) return@post
            val background = decor.getChildAt(0) as? View ?: return@post
            if (!background.hasDialogContentChild()) return@post
            background.installExpandedPanelOutsideTouchHandler(viewModel)
            (background as? ViewGroup)?.installExpandedPanelOutsideTouchChildren(viewModel)
        }
    }
}

@Composable
private fun ExpandedPanelDialogContent(viewModel: AxDynamicBarChipViewModel) {
    AxDynamicBarTheme { ExpandedPanelDialogContentBody(viewModel) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpandedPanelDialogContentBody(viewModel: AxDynamicBarChipViewModel) {
    val density = LocalDensity.current
    val rootView = LocalView.current
    val isLargeScreen = Utilities.isLargeScreen(LocalContext.current)
    val topPad =
        with(density) { WindowInsets.statusBars.getTop(this).toDp() } +
            4.dp +
            if (isLargeScreen) 4.dp else 0.dp
    val chipState by viewModel.chipState.collectAsStateWithLifecycle()
    val isExpanded by viewModel.isExpanded.collectAsStateWithLifecycle()
    val chipX by viewModel.chipCenterXFraction.collectAsStateWithLifecycle()
    val chipBounds by viewModel.chipBounds.collectAsStateWithLifecycle()
    var rootBounds by remember { mutableStateOf<Rect?>(null) }
    var panelBounds by remember { mutableStateOf<Rect?>(null) }
    var panelHasScrollableOverflow by remember { mutableStateOf(false) }
    val motionScheme = MaterialTheme.motionScheme
    val panelProgress = remember { Animatable(0f) }
    val bottomScrollPaddingPx = with(density) { ExpandedContentBottomScrollPadding.toPx() }

    LaunchedEffect(chipState) {
        val filtered = chipState?.allEvents?.filter { it !is IslandEvent.AospChip }
        if (filtered.isNullOrEmpty()) {
            viewModel.statusBarExpansion.collapse()
        }
    }

    LaunchedEffect(isExpanded) {
        panelProgress.animateTo(
            if (isExpanded) 1f else 0f,
            if (isExpanded) motionScheme.defaultSpatialSpec<Float>()
            else motionScheme.fastSpatialSpec<Float>(),
        )
    }

    val originX = chipBounds?.centerXFraction ?: chipX
    val chipAlignment = BiasAlignment(horizontalBias = originX * 2f - 1f, verticalBias = -1f)
    val panelOriginX = panelTransformOriginX(chipBounds, panelBounds)
    val progress = panelProgress.value.coerceIn(0f, 1f)
    val panelRadius = PanelCollapsedRadius + (PanelExpandedRadius - PanelCollapsedRadius) * progress
    val panelTapBounds =
        remember(panelBounds, panelHasScrollableOverflow, bottomScrollPaddingPx) {
            if (panelHasScrollableOverflow) {
                panelBounds
            } else {
                panelBounds.withoutBottomPadding(bottomScrollPaddingPx)
            }
        }

    Box(
        modifier =
            Modifier.fillMaxWidth()
                .wrapContentHeight()
                .onGloballyPositioned { rootBounds = it.screenBounds(rootView) }
                .pointerInput(chipBounds, panelTapBounds, rootBounds, viewModel) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        var ev: PointerEvent
                        do {
                            ev = awaitPointerEvent(PointerEventPass.Final)
                        } while (!ev.changes.any { it.changedToDownIgnoreConsumed() })
                        val down = ev.changes.first { it.changedToDownIgnoreConsumed() }
                        val downPos = down.position
                        val root = rootBounds
                        val downScreenX = (root?.left ?: 0) + downPos.x
                        val downScreenY = (root?.top ?: 0) + downPos.y
                        val downConsumed = down.isConsumed
                        val downInChip =
                            chipBounds.containsWithPadding(downScreenX, downScreenY, slop * 2f)
                        val downInPanel =
                            panelTapBounds == null ||
                                panelTapBounds.containsWithPadding(downScreenX, downScreenY, slop)
                        var horizontalChipDrag = false
                        var totalDx = 0f
                        var totalDy = 0f

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            val change = event.changes.firstOrNull() ?: break
                            totalDx = change.position.x - downPos.x
                            totalDy = change.position.y - downPos.y
                            if (!change.pressed) {
                                if (downInChip) {
                                    if (horizontalChipDrag) {
                                        change.consume()
                                        viewModel.handleChipRelease(
                                            totalDx,
                                            totalDy,
                                            slop,
                                            horizontalChipDrag,
                                        )
                                    } else {
                                        viewModel.collapseIfTap(totalDx, totalDy, slop)
                                    }
                                } else if (!downInPanel && !downConsumed && !change.isConsumed) {
                                    viewModel.collapseIfTap(totalDx, totalDy, slop)
                                }
                                break
                            }
                            if (downInChip && !horizontalChipDrag) {
                                if (abs(totalDx) > slop || abs(totalDy) > slop) {
                                    horizontalChipDrag = abs(totalDx) >= abs(totalDy)
                                    if (horizontalChipDrag) {
                                        change.consume()
                                    }
                                }
                            } else if (horizontalChipDrag) {
                                change.consume()
                            }
                        }
                    }
                }
                .padding(top = topPad, bottom = 8.dp),
        contentAlignment = chipAlignment,
    ) {
        chipState?.let { state ->
            val filtered = state.allEvents.filter { it !is IslandEvent.AospChip }
            if (filtered.isEmpty()) return@let
            val pinnedEventId =
                filtered.firstOrNull { it.id == state.event.id }?.id ?: filtered.first().id
            Box(
                modifier =
                    Modifier.widthIn(max = ExpandedMaxWidth)
                        .onGloballyPositioned { panelBounds = it.screenBounds(rootView) }
                        .graphicsLayer {
                            alpha = progress
                            scaleX = PanelCollapsedScaleX + (1f - PanelCollapsedScaleX) * progress
                            scaleY = PanelCollapsedScaleY + (1f - PanelCollapsedScaleY) * progress
                            transformOrigin = TransformOrigin(panelOriginX, 0f)
                        }
                        .clip(RoundedCornerShape(panelRadius))
            ) {
                ExpandedIslandContent(
                    events = filtered,
                    interactor = viewModel.interactor,
                    onCollapse = { viewModel.statusBarExpansion.collapse() },
                    onScrollableOverflowChanged = { panelHasScrollableOverflow = it },
                    pinnedEventId = pinnedEventId,
                    hapticsViewModelFactory = viewModel.interactor.sliderHapticsViewModelFactory,
                )
            }
        }
    }
}

private class ExpandedPanelOutsideTouchHandler(private val viewModel: AxDynamicBarChipViewModel) :
    View.OnTouchListener {
    private var downRawX = 0f
    private var downRawY = 0f
    private var horizontalChipDrag = false
    private var tracking = false

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = false
                horizontalChipDrag = false
                if (view.isInsideDialogContent(event.x, event.y)) return false
                if (
                    !viewModel.chipBounds.value.containsWithPadding(
                        event.rawX,
                        event.rawY,
                        slop * 2f,
                    )
                ) {
                    return false
                }
                downRawX = event.rawX
                downRawY = event.rawY
                tracking = true
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                if (!horizontalChipDrag) {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > slop || abs(dy) > slop) {
                        horizontalChipDrag = abs(dx) >= abs(dy)
                    }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                tracking = false
                viewModel.handleChipRelease(dx, dy, slop, horizontalChipDrag)
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                val wasTracking = tracking
                tracking = false
                wasTracking
            }
            else -> tracking
        }
    }
}

private fun View.installExpandedPanelOutsideTouchHandler(viewModel: AxDynamicBarChipViewModel) {
    setOnClickListener { viewModel.statusBarExpansion.collapse() }
    setOnTouchListener(ExpandedPanelOutsideTouchHandler(viewModel))
}

private fun ViewGroup.installExpandedPanelOutsideTouchChildren(
    viewModel: AxDynamicBarChipViewModel
) {
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        if (child.getTag(AnimationR.id.tag_dialog_background) != true) {
            child.installExpandedPanelOutsideTouchHandler(viewModel)
        }
    }
}

private fun View.hasDialogContentChild(): Boolean {
    return dialogContentChild() != null
}

private fun View.isInsideDialogContent(x: Float, y: Float): Boolean {
    val child = dialogContentChild() ?: return false
    return x >= child.left && x <= child.right && y >= child.top && y <= child.bottom
}

private fun View.dialogContentChild(): View? {
    val group = this as? ViewGroup ?: return null
    for (i in 0 until group.childCount) {
        val child = group.getChildAt(i)
        if (child.getTag(AnimationR.id.tag_dialog_background) == true) return child
    }
    return null
}

private fun AxDynamicBarChipBounds?.containsWithPadding(
    x: Float,
    y: Float,
    padding: Float,
): Boolean =
    this != null &&
        x >= left - padding &&
        x <= right + padding &&
        y >= top - padding &&
        y <= bottom + padding

private fun Rect?.containsWithPadding(x: Float, y: Float, padding: Float): Boolean =
    this != null &&
        x >= left - padding &&
        x <= right + padding &&
        y >= top - padding &&
        y <= bottom + padding

private fun Rect?.withoutBottomPadding(padding: Float): Rect? =
    this?.let {
        Rect(it.left, it.top, it.right, (it.bottom - padding.toInt()).coerceAtLeast(it.top))
    }

private fun panelTransformOriginX(chipBounds: AxDynamicBarChipBounds?, panelBounds: Rect?): Float {
    if (chipBounds == null || panelBounds == null || panelBounds.width() <= 0) return 0.5f
    val chipCenter = (chipBounds.left + chipBounds.right) / 2f
    return ((chipCenter - panelBounds.left) / panelBounds.width()).coerceIn(0f, 1f)
}

private fun AxDynamicBarChipViewModel.handleChipRelease(
    dx: Float,
    dy: Float,
    slop: Float,
    wasHorizontalDrag: Boolean,
) {
    if (wasHorizontalDrag) {
        if (dx > 0f) cyclePrev() else cycleNext()
    } else {
        collapseIfTap(dx, dy, slop)
    }
}

private fun AxDynamicBarChipViewModel.collapseIfTap(dx: Float, dy: Float, slop: Float) {
    if (dx * dx + dy * dy <= slop * slop) {
        statusBarExpansion.collapse()
    }
}
