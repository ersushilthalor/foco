package io.github.realkhfan.foco.ui.components.zoom

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.unit.dp
import io.github.realkhfan.foco.ui.components.motion.LocalContentRotation
import io.github.realkhfan.foco.ui.components.motion.animateEnabledAlpha
import io.github.realkhfan.foco.ui.components.ruler.RULER_SIZE
import io.github.realkhfan.foco.ui.components.ruler.RulerMetrics
import io.github.realkhfan.foco.ui.components.ruler.rememberRulerBindings
import io.github.realkhfan.foco.ui.components.ruler.rememberRulerMetrics
import io.github.realkhfan.foco.ui.components.ruler.rulerInput
import io.github.realkhfan.foco.ui.components.zoom.gesture.ZoomBarState
import io.github.realkhfan.foco.ui.components.zoom.gesture.rememberZoomBarState
import io.github.realkhfan.foco.ui.core.TABULAR_FIGURES

private val LABEL_SIZE = 14.dp

/**
 * VibeCam's logarithmic ruler: the indicator stays in the center and the scale moves under it.
 * Stops are major marks and labels; dragging does not snap until a stop detent is crossed.
 */
@Composable
internal fun ZoomBar(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    stops: List<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: () -> Unit = {},
    colors: ZoomBarColors = ZoomBarColors.fromTheme(),
    interactionSource: MutableInteractionSource? = null,
) {
    val resolvedInteractionSource = interactionSource ?: androidx.compose.runtime.remember { MutableInteractionSource() }
    val scale = androidx.compose.runtime.remember(valueRange, stops) {
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
    val alpha by animateEnabledAlpha(enabled = enabled)
    ZoomBarLayers(
        state = state,
        scale = scale,
        metrics = metrics,
        colors = colors,
        interactionSource = resolvedInteractionSource,
        modifier = modifier
            .size(size = RULER_SIZE)
            .graphicsLayer { this.alpha = alpha }
            .rulerInput(
                state = state,
                rangeInfo = ProgressBarRangeInfo(current = value, range = valueRange),
                enabled = enabled,
                layoutDirection = LocalLayoutDirection.current,
                interactionSource = resolvedInteractionSource,
            ),
    )
}

@Composable
internal fun ZoomBarLayers(
    state: ZoomBarState,
    scale: ZoomBarScale,
    metrics: RulerMetrics,
    colors: ZoomBarColors,
    interactionSource: InteractionSource,
    modifier: Modifier = Modifier,
) {
    val rotation = LocalContentRotation.current
    val isFocused by interactionSource.collectIsFocusedAsState()
    val labels = rememberZoomBarLabels(
        marks = scale.marks,
        format = rememberZoomFormat(),
        style = MaterialTheme.typography.labelLarge.copy(
            fontSize = with(LocalDensity.current) { LABEL_SIZE.toSp() },
            fontFeatureSettings = TABULAR_FIGURES,
        ),
    )
    Spacer(
        modifier = modifier.drawWithCache {
            val window = zoomBarWindow(height = size.height)
            onDrawBehind {
                drawZoomBar(
                    position = state.position,
                    scale = scale,
                    labels = labels,
                    labelRotation = rotation(),
                    window = window,
                    isFocused = isFocused,
                    metrics = metrics,
                    colors = colors,
                )
            }
        },
    )
}
