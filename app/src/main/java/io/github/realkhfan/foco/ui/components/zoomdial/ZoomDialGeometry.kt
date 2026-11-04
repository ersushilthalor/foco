package io.github.realkhfan.foco.ui.components.zoomdial

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The visible part of the dial is the cap of a circle: its highest point sits [rimY] below the top
 * and the circle is as large as it takes for the cap to span the whole [width] at the bottom, so a
 * wider or shallower dial is a flatter arc. Angles are radians from the pointer at the top,
 * positive toward the end of the scale. Neighboring ticks are [tickArc] apart along the rim, which
 * makes a horizontal drag of that length move the dial by one tick.
 */
@Immutable
internal data class ZoomDialGeometry(
    val width: Float,
    val height: Float,
    val rimY: Float,
    val tickArc: Float,
) {

    val radius: Float
    val center: Offset
    val tickAngle: Float
    val edgeAngle: Float

    init {
        require(width > 0f && height > rimY && rimY >= 0f) {
            "the dial needs a positive width and a cap below its rim, was $width x $height"
        }
        require(tickArc > 0f) {
            "tickArc must be positive, was $tickArc"
        }

        val halfWidth = width / 2
        val capHeight = min(height - rimY, halfWidth)

        radius = (halfWidth * halfWidth + capHeight * capHeight) / (2 * capHeight)
        center = Offset(
            x = halfWidth,
            y = rimY + radius,
        )
        tickAngle = tickArc / radius
        edgeAngle = asin(halfWidth / radius)
    }

    fun angle(
        markPosition: Float,
        position: Float,
        isRtl: Boolean = false,
    ): Float {
        val angle = (markPosition - position) * tickAngle

        return when {
            isRtl -> -angle
            else -> angle
        }
    }

    fun point(
        angle: Float,
        inset: Float = 0f,
    ): Offset {
        val distance = radius - inset

        return Offset(
            x = center.x + distance * sin(angle),
            y = center.y - distance * cos(angle),
        )
    }

    /** 1 at the pointer side of the fade, 0 where the rim leaves the dial's bounds. */
    fun alpha(
        angle: Float,
        fadeAngle: Float,
    ): Float {
        val angleToEdge = edgeAngle - abs(angle)

        return (angleToEdge / fadeAngle).coerceIn(0f, 1f)
    }

    fun firstVisibleMark(
        position: Float,
        marksPerTick: Int,
    ): Int {
        val first = ceil((position - edgeAngle / tickAngle) * marksPerTick).toInt()

        return max(0, first)
    }

    fun lastVisibleMark(
        position: Float,
        marksPerTick: Int,
        lastTick: Int,
    ): Int {
        val last = floor((position + edgeAngle / tickAngle) * marksPerTick).toInt()

        return min(lastTick * marksPerTick, last)
    }
}
