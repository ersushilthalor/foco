package io.github.realkhfan.foco.ui.components.zoom

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.realkhfan.foco.ui.components.motion.animateEnabledAlpha
import io.github.realkhfan.foco.ui.components.motion.revealResizing
import io.github.realkhfan.foco.ui.components.pillselector.PillSelectorColors
import io.github.realkhfan.foco.ui.components.ruler.RULER_SIZE
import io.github.realkhfan.foco.ui.components.ruler.RulerMetrics
import io.github.realkhfan.foco.ui.components.ruler.rememberRulerBindings
import io.github.realkhfan.foco.ui.components.ruler.rememberRulerMetrics
import io.github.realkhfan.foco.ui.components.ruler.rulerControls
import io.github.realkhfan.foco.ui.components.zoom.gesture.ZoomBarState
import io.github.realkhfan.foco.ui.components.zoom.gesture.rememberZoomBarState
import io.github.realkhfan.foco.ui.components.zoom.gesture.rememberZoomControlExpander
import io.github.realkhfan.foco.ui.components.zoom.gesture.zoomControlInput

/**
 * Collapsed, it shows lens stops; tap a stop to jump. Swipe or long-press to expand the original
 * VibeCam linear ruler. [expanded] remains controlled by the caller.
 */
@Composable
internal fun ZoomControl(
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
    barColors: ZoomBarColors = ZoomBarColors.fromTheme(),
    stopsColors: PillSelectorColors = PillSelectorColors.fromTheme(),
    interactionSource: MutableInteractionSource? = null,
) {
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val scale = remember(valueRange, stops) {
        ZoomBarScale(valueRange = valueRange, stops = stops)
    }
    val metrics = rememberRulerMetrics()
    val state = rememberZoomBarState(
        value = value,
        scale = scale,
        bindings = rememberRulerBindings(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            tickSpacing = metrics.tickSpacing,
        ),
    )
    val expander = rememberZoomControlExpander(
        enabled = enabled,
        expanded = expanded,
        onExpand = onExpand,
    )
    val alpha by animateEnabledAlpha(enabled = enabled)
    val valueDescription = rememberZoomFormat().format(value = value) + valueSuffix

    ZoomControlLayers(
        value = value,
        valueDescription = valueDescription,
        valueRange = valueRange,
        stops = stops,
        valueSuffix = valueSuffix,
        expanded = expanded,
        onExpand = expander::expand,
        onStopClick = { stop ->
            if (expander.isCollapsed()) onStopClick(stop)
        },
        expandLabel = expandLabel,
        enabled = enabled,
        alpha = { alpha },
        state = state,
        scale = scale,
        metrics = metrics,
        barColors = barColors,
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
private fun ZoomControlLayers(
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
    metrics: RulerMetrics,
    barColors: ZoomBarColors,
    stopsColors: PillSelectorColors,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Spacer(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { this.alpha = alpha() }
                .background(color = barColors.containerColor, shape = CircleShape),
        )
        AnimatedContent(
            targetState = expanded,
            transitionSpec = { revealResizing() },
            contentAlignment = Alignment.Center,
        ) { isExpanded ->
            if (isExpanded) {
                ZoomControlBar(
                    value = value,
                    valueDescription = valueDescription,
                    valueRange = valueRange,
                    state = state,
                    scale = scale,
                    metrics = metrics,
                    enabled = enabled,
                    alpha = alpha,
                    colors = barColors,
                    interactionSource = interactionSource,
                )
            } else {
                ZoomControlStops(
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
private fun ZoomControlStops(
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
        enabled = enabled,
        customActions = listOf(
            CustomAccessibilityAction(label = expandLabel) {
                onExpand()
                true
            },
        ),
        colors = colors.copy(containerColor = Color.Transparent),
    )
}

@Composable
private fun ZoomControlBar(
    value: Float,
    valueDescription: String,
    valueRange: ClosedFloatingPointRange<Float>,
    state: ZoomBarState,
    scale: ZoomBarScale,
    metrics: RulerMetrics,
    enabled: Boolean,
    alpha: () -> Float,
    colors: ZoomBarColors,
    interactionSource: MutableInteractionSource,
) {
    ZoomBarLayers(
        state = state,
        scale = scale,
        metrics = metrics,
        colors = colors.copy(containerColor = Color.Transparent),
        interactionSource = interactionSource,
        modifier = Modifier
            .size(size = RULER_SIZE)
            .graphicsLayer { this.alpha = alpha() }
            .rulerControls(
                state = state,
                rangeInfo = ProgressBarRangeInfo(current = value, range = valueRange),
                enabled = enabled,
                layoutDirection = LocalLayoutDirection.current,
                interactionSource = interactionSource,
            )
            .semantics { stateDescription = valueDescription },
    )
}
