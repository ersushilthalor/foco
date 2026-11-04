package io.github.realkhfan.foco.ui.components.zoom

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import io.github.realkhfan.foco.ui.components.text.CenteredText

/**
 * Pre-measures the scale's fixed labels. During a drag only the readout changes; the marks and
 * their layouts stay cached so the dial does not remeasure every label on every frame.
 */
@Composable
internal fun rememberZoomBarLabels(
    marks: List<Float>,
    format: ZoomFormat,
    style: TextStyle,
): List<CenteredText> {
    val textMeasurer = rememberTextMeasurer()
    return remember(marks, format, style, textMeasurer) {
        marks.map { mark ->
            CenteredText(
                layout = textMeasurer.measure(
                    text = format.format(mark),
                    style = style,
                ),
            )
        }
    }
}
