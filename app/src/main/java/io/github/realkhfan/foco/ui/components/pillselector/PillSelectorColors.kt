package io.github.realkhfan.foco.ui.components.pillselector

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import io.github.realkhfan.foco.ui.core.cameraColors

@Immutable
internal data class PillSelectorColors(
    val containerColor: Color,
    val contentColor: Color,
    val selectedContainerColor: Color,
    val selectedContentColor: Color,
) {

    companion object {
        @Composable
        @ReadOnlyComposable
        fun fromTheme(): PillSelectorColors {
            val cameraColors = MaterialTheme.cameraColors
            val colorScheme = MaterialTheme.colorScheme

            return PillSelectorColors(
                containerColor = cameraColors.overlayScrim,
                contentColor = cameraColors.overlay,
                selectedContainerColor = colorScheme.primaryFixed,
                selectedContentColor = colorScheme.onPrimaryFixed,
            )
        }

        @Composable
        @ReadOnlyComposable
        fun fromSurfaceTheme(): PillSelectorColors {
            val colorScheme = MaterialTheme.colorScheme

            return PillSelectorColors(
                containerColor = colorScheme.surfaceContainerHighest,
                contentColor = colorScheme.onSurface,
                selectedContainerColor = colorScheme.primary,
                selectedContentColor = colorScheme.onPrimary,
            )
        }
    }
}
