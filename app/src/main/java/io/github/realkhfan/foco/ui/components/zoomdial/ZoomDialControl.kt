package io.github.realkhfan.foco.ui.components.zoomdial

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.realkhfan.foco.ui.components.motion.FADE_IN
import io.github.realkhfan.foco.ui.components.motion.FADE_OUT
import io.github.realkhfan.foco.ui.components.motion.MORPH_INT_OFFSET_SPEC
import io.github.realkhfan.foco.ui.components.motion.SETTLE_SIZE_SPEC
import io.github.realkhfan.foco.ui.components.motion.animateEnabledAlpha
import io.github.realkhfan.foco.ui.components.pillselector.PillSelectorColors
import io.github.realkhfan.foco.ui.components.ruler.rememberRulerBindings
import io.github.realkhfan.foco.ui.components.ruler.rulerControls
import io.github.realkhfan.foco.ui.components.zoom.ZoomBarScale
import io.github.realkhfan.foco.ui.components.zoom.ZoomStops
import io.github.realkhfan.foco.ui.components.zoom.gesture.ZoomBarState
import io.github.realkhfan.foco.ui.components.zoom.gesture.rememberZoomBarState
import io.github.realkhfan.foco.ui.components.zoom.gesture.rememberZoomControlExpander
import io.github.realkhfan.foco.ui.components.zoom.gesture.zoomControlInput
import io.github.realkhfan.foco.ui.components.zoom.rememberZoomFormat

private val STOPS_LIFT = 12.dp

/**
 * The zoom control with a dial in place of the bar, and the same contract: collapsed, it shows
 * [stops], and a tap on one reports it through [onStopClick]. A horizontal swipe or a long press
 * on the stops calls [onExpand], and the same touch goes on turning the dial, which rises from the
 * bottom edge over the full width it is given. [expanded] belongs to the caller: it expands on
 * [onExpand] and collapses whenever the screen decides to. [interactionSource] holds a press for
 * as long as a finger is on the control, so a screen that collapses on a timer can wait for the
 * release. Only the stops, or the dial once it is up, take touches; the space beside the stops
 * stays the viewfinder's.
 */
@Composable
internal fun ZoomDialControl(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    stops: List<Float>,
    valueSuffix: String,
    expanded: Boolean,
    onExpand: () -> Unit,
    onStopClick: (Float) -> Unit,
    expandLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: () -> Unit = {},
    dialColors: ZoomDialColors = ZoomDialColors.fromTheme(),
    stopsColors: PillSelectorColors = PillSelectorColors.fromTheme(),
    interactionSource: MutableInteractionSource? = null,
) {
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val scale = remember(valueRange, stops) {
        ZoomBarScale(
            valueRange = valueRange,
            stops = stops,
        )
    }
    val metrics = rememberZoomDialMetrics()
    val state = rememberZoomBarState(
        value = value,
        scale = scale,
        bindings = rememberRulerBindings(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            tickSpacing = metrics.tickArc,
        ),
    )
    val expander = rememberZoomControlExpander(
        enabled = enabled,
        expanded = expanded,
        onExpand = onExpand,
    )
    val alpha by animateEnabledAlpha(enabled = enabled)
    val valueDescription = rememberZoomFormat().format(value = value) + valueSuffix

    ZoomDialControlLayers(
        value = value,
        valueDescription = valueDescription,
        valueRange = valueRange,
        stops = stops,
        valueSuffix = valueSuffix,
        expanded = expanded,
        onExpand = expander::expand,
        onStopClick = { stop ->
            if (expander.isCollapsed()) {
                onStopClick(stop)
            }
        },
        expandLabel = expandLabel,
        enabled = enabled,
        alpha = { alpha },
        state = state,
        scale = scale,
        metrics = metrics,
        dialColors = dialColors,
        stopsColors = stopsColors,
        interactionSource = resolvedInteractionSource,
        modifier = modifier.zoomControlInput(
            state = state,
            expander = expander,
            enabled = enabled,
            layoutDirection = LocalLayoutDirection.current,
            interactionSource = resolvedInteractionSource,
        ),
    )
}

@Composable
private fun ZoomDialControlLayers(
    value: Float,
    valueDescription: String,
    valueRange: ClosedFloatingPointRange<Float>,
    stops: List<Float>,
    valueSuffix: String,
    expanded: Boolean,
    onExpand: () -> Unit,
    onStopClick: (Float) -> Unit,
    expandLabel: String,
    enabled: Boolean,
    alpha: () -> Float,
    state: ZoomBarState,
    scale: ZoomBarScale,
    metrics: ZoomDialMetrics,
    dialColors: ZoomDialColors,
    stopsColors: PillSelectorColors,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedContent(
            targetState = expanded,
            transitionSpec = { riseFromTheEdge() },
            contentAlignment = Alignment.BottomCenter,
        ) { isExpanded ->
            when {
                isExpanded -> ZoomDialLayers(
                    state = state,
                    scale = scale,
                    valueSuffix = valueSuffix,
                    metrics = metrics,
                    colors = dialColors,
                    interactionSource = interactionSource,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height = ZOOM_DIAL_HEIGHT)
                        .graphicsLayer { this.alpha = alpha() }
                        .rulerControls(
                            state = state,
                            rangeInfo = ProgressBarRangeInfo(
                                current = value,
                                range = valueRange,
                            ),
                            enabled = enabled,
                            layoutDirection = LocalLayoutDirection.current,
                            interactionSource = interactionSource,
                        )
                        .semantics { stateDescription = valueDescription },
                )

                else -> ZoomDialStops(
                    value = value,
                    stops = stops,
                    valueSuffix = valueSuffix,
                    onExpand = onExpand,
                    onStopClick = onStopClick,
                    expandLabel = expandLabel,
                    enabled = enabled,
                    colors = stopsColors,
                )
            }
        }
    }
}

@Composable
private fun ZoomDialStops(
    value: Float,
    stops: List<Float>,
    valueSuffix: String,
    onExpand: () -> Unit,
    onStopClick: (Float) -> Unit,
    expandLabel: String,
    enabled: Boolean,
    colors: PillSelectorColors,
) {
    ZoomStops(
        value = value,
        stops = stops,
        valueSuffix = valueSuffix,
        onStopClick = onStopClick,
        modifier = Modifier.padding(bottom = STOPS_LIFT),
        enabled = enabled,
        customActions = listOf(
            CustomAccessibilityAction(label = expandLabel) {
                onExpand()
                true
            },
        ),
        colors = colors,
    )
}

private fun AnimatedContentTransitionScope<Boolean>.riseFromTheEdge(): ContentTransform {
    val isRising = targetState
    val transform = when {
        isRising -> FADE_IN + slideInVertically(
            animationSpec = MORPH_INT_OFFSET_SPEC,
            initialOffsetY = { height -> height / 2 },
        ) togetherWith fadeOut(animationSpec = snap())

        else -> FADE_IN togetherWith FADE_OUT + slideOutVertically(
            animationSpec = MORPH_INT_OFFSET_SPEC,
            targetOffsetY = { height -> height / 2 },
        )
    }

    return transform using SizeTransform(clip = false) { _, _ -> SETTLE_SIZE_SPEC }
}
