package io.github.realkhfan.foco.ui.components.zoomdial

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.LayoutDirection
import io.github.realkhfan.foco.ui.components.text.CenteredText
import io.github.realkhfan.foco.ui.components.text.drawCenteredText
import io.github.realkhfan.foco.ui.components.zoom.ZoomBarScale
import kotlin.math.abs

/** Marks drawn between two neighboring ticks of the scale, the tick itself included. */
private const val MARKS_PER_TICK = 3
private const val MINOR_MARK_ALPHA = 0.6f
private const val FADE_TICKS = 2f

/** A stop's label gives way to the value readout while it is this close to the pointer. */
private const val LABEL_HIDDEN_TICKS = 0.6f
private const val LABEL_SHOWN_TICKS = 1.2f

/**
 * The readout's unit sign hangs past its number, on the side its text is shifted to, so a label
 * on that side gives way sooner.
 */
private const val UNIT_SIGN_TICKS = 0.8f

private const val DEGREES_PER_RADIAN = 57.29578f

internal fun DrawScope.drawZoomDial(
    position: Float,
    scale: ZoomBarScale,
    labels: List<CenteredText>,
    valueLabel: CenteredText,
    labelRotation: Float,
    isFocused: Boolean,
    geometry: ZoomDialGeometry,
    metrics: ZoomDialMetrics,
    colors: ZoomDialColors,
) {
    clipRect {
        drawCircle(
            color = colors.containerColor,
            radius = geometry.radius,
            center = geometry.center,
        )
        drawMarks(
            position = position,
            scale = scale,
            geometry = geometry,
            metrics = metrics,
            colors = colors,
        )
        drawLabels(
            position = position,
            scale = scale,
            labels = labels,
            unitSignOffset = valueLabel.offsetX,
            labelRotation = labelRotation,
            geometry = geometry,
            metrics = metrics,
            colors = colors,
        )
        if (isFocused) {
            drawCircle(
                color = colors.focusColor,
                radius = geometry.radius - metrics.focusStroke.width / 2,
                center = geometry.center,
                style = metrics.focusStroke,
            )
        }
    }
    drawPointer(
        geometry = geometry,
        metrics = metrics,
        colors = colors,
    )
    rotate(
        degrees = labelRotation,
        pivot = geometry.point(angle = 0f, inset = metrics.labelInset),
    ) {
        drawCenteredText(
            text = valueLabel,
            center = geometry.point(angle = 0f, inset = metrics.labelInset),
            color = colors.valueColor,
        )
    }
}

private fun DrawScope.drawMarks(
    position: Float,
    scale: ZoomBarScale,
    geometry: ZoomDialGeometry,
    metrics: ZoomDialMetrics,
    colors: ZoomDialColors,
) {
    val firstMark = geometry.firstVisibleMark(
        position = position,
        marksPerTick = MARKS_PER_TICK,
    )
    val lastMark = geometry.lastVisibleMark(
        position = position,
        marksPerTick = MARKS_PER_TICK,
        lastTick = scale.lastTick,
    )

    for (mark in firstMark..lastMark) {
        val isTick = mark % MARKS_PER_TICK == 0
        val isMajor = isTick && scale.isMajor(tick = mark / MARKS_PER_TICK)
        val angle = geometry.angle(
            markPosition = mark.toFloat() / MARKS_PER_TICK,
            position = position,
            isRtl = layoutDirection == LayoutDirection.Rtl,
        )
        val length = when {
            isMajor -> metrics.majorTickLength
            isTick -> metrics.tickLength
            else -> metrics.minorTickLength
        }
        val markAlpha = when {
            isTick -> 1f
            else -> MINOR_MARK_ALPHA
        }

        drawLine(
            color = colors.tickColor,
            start = geometry.point(angle = angle, inset = metrics.tickInset),
            end = geometry.point(angle = angle, inset = metrics.tickInset + length),
            strokeWidth = when {
                isMajor -> metrics.majorTickWidth
                else -> metrics.tickWidth
            },
            cap = StrokeCap.Round,
            alpha = markAlpha * geometry.alpha(
                angle = angle,
                fadeAngle = FADE_TICKS * geometry.tickAngle,
            ),
        )
    }
}

private fun DrawScope.drawLabels(
    position: Float,
    scale: ZoomBarScale,
    labels: List<CenteredText>,
    unitSignOffset: Float,
    labelRotation: Float,
    geometry: ZoomDialGeometry,
    metrics: ZoomDialMetrics,
    colors: ZoomDialColors,
) {
    for (index in labels.indices) {
        val markPosition = scale.markTicks[index].toFloat()
        val angle = geometry.angle(
            markPosition = markPosition,
            position = position,
            isRtl = layoutDirection == LayoutDirection.Rtl,
        )
        val readoutAlpha = labelAlpha(
            distance = abs(markPosition - position),
            isOnUnitSignSide = angle * unitSignOffset > 0f,
        )
        val alpha = readoutAlpha * geometry.alpha(
            angle = angle,
            fadeAngle = FADE_TICKS * geometry.tickAngle,
        )

        if (alpha > 0f) {
            val labelCenter = geometry.point(angle = angle, inset = metrics.labelInset)

            rotate(
                degrees = angle * DEGREES_PER_RADIAN + labelRotation,
                pivot = labelCenter,
            ) {
                drawCenteredText(
                    text = labels[index],
                    center = labelCenter,
                    color = colors.labelColor,
                    alpha = alpha,
                )
            }
        }
    }
}

private fun DrawScope.drawPointer(
    geometry: ZoomDialGeometry,
    metrics: ZoomDialMetrics,
    colors: ZoomDialColors,
) {
    val tip = geometry.point(angle = 0f, inset = metrics.pointerInset + metrics.pointerSize.height)
    val halfWidth = metrics.pointerSize.width / 2
    val baseY = tip.y - metrics.pointerSize.height
    val pointer = Path().apply {
        moveTo(x = tip.x - halfWidth, y = baseY)
        lineTo(x = tip.x + halfWidth, y = baseY)
        lineTo(x = tip.x, y = tip.y)
        close()
    }

    drawPath(
        path = pointer,
        color = colors.valueColor,
    )
}

private fun labelAlpha(
    distance: Float,
    isOnUnitSignSide: Boolean,
): Float {
    val clearance = when {
        isOnUnitSignSide -> UNIT_SIGN_TICKS
        else -> 0f
    }
    val shown = (distance - clearance - LABEL_HIDDEN_TICKS) /
        (LABEL_SHOWN_TICKS - LABEL_HIDDEN_TICKS)

    return shown.coerceIn(0f, 1f)
}
