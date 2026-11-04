package io.github.realkhfan.foco.ui.components.zoomdial

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import io.github.realkhfan.foco.ui.core.cameraColors

@Immutable
internal data class ZoomDialColors(
    val containerColor: Color,
    val tickColor: Color,
    val labelColor: Color,
    val valueColor: Color,
    val focusColor: Color,
) {

    companion object {
        @Composable
        @ReadOnlyComposable
        fun fromTheme(): ZoomDialColors {
            val cameraColors = MaterialTheme.cameraColors

            return ZoomDialColors(
                containerColor = cameraColors.overlayScrim,
                tickColor = cameraColors.overlay,
                labelColor = cameraColors.overlay,
                valueColor = MaterialTheme.colorScheme.primaryFixedDim,
                focusColor = cameraColors.overlay,
            )
        }
    }
}
