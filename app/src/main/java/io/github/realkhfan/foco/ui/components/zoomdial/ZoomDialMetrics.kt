package io.github.realkhfan.foco.ui.components.zoomdial

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

internal val ZOOM_DIAL_HEIGHT = 132.dp

@Immutable
internal class ZoomDialMetrics(
    density: Density,
) {

    val rimY = with(density) { RIM_Y.toPx() }
    val tickArc = with(density) { TICK_ARC.toPx() }
    val tickInset = with(density) { TICK_INSET.toPx() }
    val minorTickLength = with(density) { MINOR_TICK_LENGTH.toPx() }
    val tickLength = with(density) { TICK_LENGTH.toPx() }
    val majorTickLength = with(density) { MAJOR_TICK_LENGTH.toPx() }
    val tickWidth = with(density) { TICK_WIDTH.toPx() }
    val majorTickWidth = with(density) { MAJOR_TICK_WIDTH.toPx() }
    val labelInset = with(density) { LABEL_INSET.toPx() }
    val pointerSize = with(density) { Size(POINTER_WIDTH.toPx(), POINTER_HEIGHT.toPx()) }
    val pointerInset = with(density) { POINTER_INSET.toPx() }
    val focusStroke = Stroke(width = with(density) { FOCUS_RING_WIDTH.toPx() })

    private companion object {
        private val RIM_Y = 4.dp
        private val TICK_ARC = 24.dp
        private val TICK_INSET = 12.dp
        private val MINOR_TICK_LENGTH = 6.dp
        private val TICK_LENGTH = 9.dp
        private val MAJOR_TICK_LENGTH = 14.dp
        private val TICK_WIDTH = 1.dp
        private val MAJOR_TICK_WIDTH = 2.dp
        private val LABEL_INSET = 42.dp
        private val POINTER_WIDTH = 8.dp
        private val POINTER_HEIGHT = 7.dp
        private val POINTER_INSET = 3.dp
        private val FOCUS_RING_WIDTH = 3.dp
    }
}

@Composable
internal fun rememberZoomDialMetrics(): ZoomDialMetrics {
    val density = LocalDensity.current

    return remember(density) { ZoomDialMetrics(density = density) }
}
