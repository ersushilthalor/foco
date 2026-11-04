package io.github.realkhfan.foco.ui.components.zoomdial

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.realkhfan.foco.ui.components.motion.LocalContentRotation
import io.github.realkhfan.foco.ui.components.motion.animateEnabledAlpha
import io.github.realkhfan.foco.ui.components.ruler.rememberRulerBindings
import io.github.realkhfan.foco.ui.components.ruler.rulerInput
import io.github.realkhfan.foco.ui.components.text.CenteredText
import io.github.realkhfan.foco.ui.components.zoom.ZoomBarScale
import io.github.realkhfan.foco.ui.components.zoom.ZoomFormat
import io.github.realkhfan.foco.ui.components.zoom.gesture.ZoomBarState
import io.github.realkhfan.foco.ui.components.zoom.gesture.rememberZoomBarState
import io.github.realkhfan.foco.ui.components.zoom.rememberZoomBarLabels
import io.github.realkhfan.foco.ui.components.zoom.rememberZoomFormat
import io.github.realkhfan.foco.ui.core.TABULAR_FIGURES

private val LABEL_SIZE = 13.dp
private val VALUE_SIZE = 15.dp

/**
 * The zoom scale on the rim of a dial that turns under a fixed pointer, with the value under the
 * pointer. It is as wide as it is given and takes the scale, the drag, the stops and their haptics
 * from the zoom bar: [onValueChange] is called continuously during a drag, without snapping, and
 * [onValueChangeFinished] when it ends; any other change of [value] is animated to. A horizontal
 * drag turns the dial by the distance the finger travels along the rim.
 */
@Composable
internal fun ZoomDial(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    stops: List<Float>,
    valueSuffix: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: () -> Unit = {},
    colors: ZoomDialColors = ZoomDialColors.fromTheme(),
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
    val alpha by animateEnabledAlpha(enabled = enabled)

    ZoomDialLayers(
        state = state,
        scale = scale,
        valueSuffix = valueSuffix,
        metrics = metrics,
        colors = colors,
        interactionSource = resolvedInteractionSource,
        modifier = modifier
            .fillMaxWidth()
            .height(height = ZOOM_DIAL_HEIGHT)
            .graphicsLayer { this.alpha = alpha }
            .rulerInput(
                state = state,
                rangeInfo = ProgressBarRangeInfo(
                    current = value,
                    range = valueRange,
                ),
                enabled = enabled,
                layoutDirection = LocalLayoutDirection.current,
                interactionSource = resolvedInteractionSource,
            ),
    )
}

@Composable
internal fun ZoomDialLayers(
    state: ZoomBarState,
    scale: ZoomBarScale,
    valueSuffix: String,
    metrics: ZoomDialMetrics,
    colors: ZoomDialColors,
    interactionSource: InteractionSource,
    modifier: Modifier = Modifier,
) {
    val rotation = LocalContentRotation.current
    val isFocused by interactionSource.collectIsFocusedAsState()
    val format = rememberZoomFormat()
    val labelStyle = MaterialTheme.typography.labelLarge.copy(
        fontSize = with(LocalDensity.current) { LABEL_SIZE.toSp() },
        fontFeatureSettings = TABULAR_FIGURES,
    )
    val labels = rememberZoomBarLabels(
        marks = scale.marks,
        format = format,
        style = labelStyle,
    )
    val valueLabels = rememberValueLabels(
        format = format,
        valueSuffix = valueSuffix,
        style = labelStyle.copy(
            fontSize = with(LocalDensity.current) { VALUE_SIZE.toSp() },
            fontWeight = FontWeight.Bold,
        ),
    )

    Spacer(
        modifier = modifier.drawWithCache {
            val geometry = ZoomDialGeometry(
                width = size.width,
                height = size.height,
                rimY = metrics.rimY,
                tickArc = metrics.tickArc,
            )

            onDrawBehind {
                drawZoomDial(
                    position = state.position,
                    scale = scale,
                    labels = labels,
                    valueLabel = valueLabels.label(value = scale.value(position = state.position)),
                    labelRotation = rotation(),
                    isFocused = isFocused,
                    geometry = geometry,
                    metrics = metrics,
                    colors = colors,
                )
            }
        },
    )
}

/** Measures the readout once per text it shows rather than once per frame of a drag. */
@Stable
private class ValueLabels(
    private val format: ZoomFormat,
    private val valueSuffix: String,
    private val style: TextStyle,
    private val textMeasurer: TextMeasurer,
) {

    private var number: String? = null
    private var label: CenteredText? = null

    fun label(value: Float): CenteredText {
        val formatted = format.format(value = value)
        val current = label

        return when {
            current != null && formatted == number -> current
            else -> measure(formatted = formatted)
        }
    }

    private fun measure(formatted: String): CenteredText {
        val measured = CenteredText(
            layout = textMeasurer.measure(
                text = formatted + valueSuffix,
                style = style,
            ),
            centeredLength = formatted.length,
        )

        number = formatted
        label = measured

        return measured
    }
}

@Composable
private fun rememberValueLabels(
    format: ZoomFormat,
    valueSuffix: String,
    style: TextStyle,
): ValueLabels {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    return remember(format, valueSuffix, style, textMeasurer, density) {
        ValueLabels(
            format = format,
            valueSuffix = valueSuffix,
            style = style,
            textMeasurer = textMeasurer,
        )
    }
}
