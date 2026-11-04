package io.github.realkhfan.foco.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.realkhfan.foco.ui.components.motion.FADE_IN
import io.github.realkhfan.foco.ui.components.motion.FADE_OUT
import io.github.realkhfan.foco.ui.components.motion.ProvideContentRotation
import io.github.realkhfan.foco.ui.components.zoomdial.ZoomDialControl
import io.github.realkhfan.foco.ui.core.CameraColors
import io.github.realkhfan.foco.ui.core.LocalCameraColors
import kotlinx.coroutines.delay
import kotlin.math.abs

private const val COLLAPSE_DELAY_MS = 1_000L
private const val STOP_TOLERANCE = 0.01f

/**
 * VibeCam's original collapsible zoom dial, adapted only at the camera-state boundary.
 * The dial's visual, drawing, gestures, transitions and stop selector are transplanted source.
 */
@Composable
internal fun VibeCamZoomDial(
    state: AppState,
    actions: CameraActions,
    rotationDegrees: Float,
    modifier: Modifier = Modifier,
) {
    val expansion = remember { ZoomDialExpansion() }
    val snapshot = remember { LastAdjustableZoom() }.update(state)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isPanorama = state.kind == CaptureKind.PHOTO && state.photoMode == PhotoMode.PANORAMA
    val isAdjustable = state.cameraReady &&
        state.minZoom.isFinite() && state.maxZoom.isFinite() &&
        state.minZoom > 0f && state.maxZoom > state.minZoom &&
        state.zoomChips.size > 1
    val visible = isAdjustable && snapshot != null && !isPanorama

    LaunchedEffect(state.zoom) {
        expansion.onZoomChanged(zoomRatio = state.zoom)
    }
    LaunchedEffect(expansion.isExpanded, state.zoom, isPressed) {
        if (expansion.isExpanded && !isPressed) {
            delay(COLLAPSE_DELAY_MS)
            expansion.collapse()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Keep the collapsed stop pills on Foco's existing zoom-chip baseline.
            .offset(y = (-34).dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = FADE_IN,
            exit = FADE_OUT,
        ) {
            if (snapshot != null) {
                CompositionLocalProvider(LocalCameraColors provides CameraColors.DEFAULT) {
                    ProvideContentRotation(degrees = rotationDegrees) {
                        ZoomDialControl(
                            value = state.zoom.coerceIn(snapshot.minZoom, snapshot.maxZoom),
                            onValueChange = { zoomRatio ->
                                expansion.onDialTurned(zoomRatio = zoomRatio)
                                actions.setZoom(zoomRatio)
                            },
                            valueRange = snapshot.minZoom..snapshot.maxZoom,
                            stops = snapshot.stops,
                            valueSuffix = "×",
                            expanded = expansion.isExpanded,
                            onExpand = expansion::expand,
                            onStopClick = { stop ->
                                expansion.onStopClicked(zoomRatio = stop)
                                actions.setZoom(stop, fromChip = true)
                            },
                            expandLabel = "Adjust zoom",
                            interactionSource = interactionSource,
                        )
                    }
                }
            }
        }
    }
}

@Stable
private class ZoomDialExpansion {
    var isExpanded by mutableStateOf(false)
        private set

    private var expectedZoomRatio: Float? = null

    fun expand() {
        isExpanded = true
    }

    fun collapse() {
        isExpanded = false
    }

    fun onDialTurned(zoomRatio: Float) {
        expectedZoomRatio = zoomRatio
    }

    fun onStopClicked(zoomRatio: Float) {
        expectedZoomRatio = zoomRatio
    }

    fun onZoomChanged(zoomRatio: Float) {
        val expected = expectedZoomRatio
        val isOwnChange = expected == null || abs(zoomRatio - expected) <= STOP_TOLERANCE
        expectedZoomRatio = zoomRatio
        if (!isOwnChange) expand()
    }
}

private data class ZoomDialSnapshot(
    val zoomRatio: Float,
    val minZoom: Float,
    val maxZoom: Float,
    val stops: List<Float>,
)

@Stable
private class LastAdjustableZoom {
    private var last: ZoomDialSnapshot? = null

    fun update(state: AppState): ZoomDialSnapshot? {
        val min = state.minZoom
        val max = state.maxZoom
        val stops = state.zoomChips
            .filter { it.isFinite() && it >= min && it <= max }
            .distinct()
            .sorted()

        if (
            state.cameraReady &&
            min.isFinite() && max.isFinite() &&
            min > 0f && max > min &&
            stops.size > 1
        ) {
            last = ZoomDialSnapshot(
                zoomRatio = state.zoom,
                minZoom = min,
                maxZoom = max,
                stops = stops,
            )
        }
        return last
    }
}
