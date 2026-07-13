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

package com.android.systemui.qs.ax.ui.gesture

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.android.compose.gesture.NestedDraggable
import com.android.compose.gesture.NestedDraggableRootNode
import com.android.mechanics.DistanceGestureContext
import com.android.mechanics.MotionValue
import com.android.mechanics.debug.DebugMotionValueNode
import com.android.mechanics.effects.MagneticDetach
import com.android.mechanics.spec.InputDirection
import com.android.mechanics.spec.SemanticKey
import com.android.mechanics.spec.builder.ComposeMotionBuilderContext
import com.android.mechanics.spec.builder.fixedSpatialValueSpec
import com.android.mechanics.spec.builder.motionBuilderContext
import com.android.mechanics.spec.builder.spatialMotionSpec
import com.android.mechanics.spec.with
import com.android.mechanics.spring.SpringParameters
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

fun Modifier.mediaOverscrollToDismiss(
    enabled: Boolean = true,
    dismissAllowed: Boolean = true,
    onDismissed: () -> Unit,
) = then(MediaOverscrollToDismissElement(enabled, dismissAllowed, onDismissed))

private data class MediaOverscrollToDismissElement(
    val enabled: Boolean,
    val dismissAllowed: Boolean,
    val onDismissed: () -> Unit,
) : ModifierNodeElement<MediaOverscrollToDismissNode>() {
    override fun create() = MediaOverscrollToDismissNode(enabled, dismissAllowed, onDismissed)

    override fun update(node: MediaOverscrollToDismissNode) {
        node.update(enabled, dismissAllowed, onDismissed)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "mediaOverscrollToDismiss"
        properties["enabled"] = enabled
    }
}

private class MediaOverscrollToDismissNode(
    enabled: Boolean,
    private var dismissAllowed: Boolean,
    private var onDismissed: () -> Unit,
) :
    DelegatingNode(),
    LayoutModifierNode,
    CompositionLocalConsumerModifierNode,
    ObserverModifierNode,
    NestedDraggable,
    NestedDraggable.Controller {
    private var enabled = enabled
    private var overscrollSign = 0f
    private val gestureContext =
        DistanceGestureContext(0f, InputDirection.Max, directionChangeSlop = 1f)
    private var dragState: DragState by mutableStateOf(DragState.Idle)
    private lateinit var motionValue: MotionValue
    private lateinit var motionBuilderContext: ComposeMotionBuilderContext
    private var delegateNode =
        delegate(NestedDraggableRootNode(this, Orientation.Horizontal, null, enabled, true))
    private var contentWidth = 0
    private var motionValueJob: Job? = null

    fun update(enabled: Boolean, dismissAllowed: Boolean, onDismissed: () -> Unit) {
        this.dismissAllowed = dismissAllowed
        this.onDismissed = onDismissed
        if (this.enabled == enabled) return
        this.enabled = enabled
        delegateNode.update(this, Orientation.Horizontal, null, enabled, true)
    }

    override fun onAttach() {
        motionBuilderContext = motionBuilderContext()
        val spec = derivedStateOf {
            with(motionBuilderContext) {
                when (dragState) {
                    DragState.Idle -> fixedSpatialValueSpec(0f, SnapBackSpring)
                    DragState.Dragging -> spatialMotionSpec { after(0f, MagneticDetach()) }
                    DragState.Dismissed ->
                        fixedSpatialValueSpec(
                            contentWidth.toFloat(),
                            SnapBackSpring,
                            listOf(isDismissedState with true),
                        )
                }
            }
        }
        motionValue =
            MotionValue(
                input = { gestureContext.dragOffset },
                gestureContext = gestureContext,
                spec = spec::value,
            )
        delegate(DebugMotionValueNode(motionValue))
        onObservedReadsChanged()
        motionValueJob = coroutineScope.launch { keepRunningUntilDismissed() }
    }

    override fun onObservedReadsChanged() {
        observeReads {
            gestureContext.directionChangeSlop = currentValueOf(LocalViewConfiguration).touchSlop
        }
    }

    override fun onDetach() {
        motionValueJob?.cancel()
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        contentWidth = placeable.measuredWidth
        return layout(placeable.measuredWidth, placeable.measuredHeight) {
            placeable.place((motionValue.output * overscrollSign).toInt(), 0)
        }
    }

    override fun onDragStarted(
        position: Offset,
        sign: Float,
        pointersDown: Int,
        pointerType: PointerType?,
    ): NestedDraggable.Controller {
        overscrollSign = sign
        gestureContext.reset(dragOffset = motionValue.output, direction = InputDirection.Max)
        dragState = DragState.Dragging
        return this
    }

    override fun shouldConsumeNestedPreScroll(sign: Float): Boolean {
        return motionValue[isDismissedState] ?: false
    }

    override val autoStopNestedDrags: Boolean
        get() = true

    override fun onDrag(delta: Float): Float {
        val previousOffset = gestureContext.dragOffset
        val currentOffset = previousOffset + delta * overscrollSign
        if (currentOffset >= 0f) {
            gestureContext.dragOffset = currentOffset
            return delta
        }
        gestureContext.dragOffset = 0f
        return -previousOffset * overscrollSign
    }

    override suspend fun onDragStopped(
        velocity: Float,
        awaitFling: suspend () -> Unit,
    ): Float {
        with(requireDensity()) {
            val isFling = abs(velocity) > DismissVelocity.toPx()
            val meetsDismissThreshold =
                if (isFling) {
                    velocity.sign == overscrollSign
                } else {
                    gestureContext.dragOffset >= contentWidth / 2f
                }
            dragState =
                if (dismissAllowed && meetsDismissThreshold) DragState.Dismissed else DragState.Idle
        }
        return velocity
    }

    private suspend fun keepRunningUntilDismissed() {
        motionValue.keepRunningWhile {
            val isDismissed = get(isDismissedState) ?: false
            !(isDismissed && isStable)
        }
        onDismissed()
    }

    private enum class DragState {
        Idle,
        Dragging,
        Dismissed,
    }

    private companion object {
        val isDismissedState = SemanticKey<Boolean>("mediaDismissed")
        val DismissVelocity = 1000.dp
        val SnapBackSpring = SpringParameters(stiffness = 550f, dampingRatio = 0.95f)
    }
}
