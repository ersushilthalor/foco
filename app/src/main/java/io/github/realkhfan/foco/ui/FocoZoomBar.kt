package io.github.realkhfan.foco.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.SeekBar
import io.github.realkhfan.foco.R
import java.util.Locale
import kotlin.math.roundToInt

/**
 * VibeCam's original vertical SeekBar, adapted to Foco's zoom-ratio action boundary.
 * The rotated measurement, canvas transform, bitmap thumb, and touch-to-linear-zoom mapping
 * intentionally follow VibeCam's ZoomBar implementation.
 */
internal class FocoZoomBar : SeekBar {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle)

    @SuppressLint("InflateParams")
    private val thumbView: View = LayoutInflater.from(context)
        .inflate(R.layout.foco_vibecam_zoom_bar_thumb, null, false)

    private var renderedZoomRatio: Float? = null
    private var onLinearZoomChanged: ((Float) -> Unit)? = null

    init {
        max = 100
        progressTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
    }

    fun renderZoom(
        linearZoom: Float,
        zoomRatio: Float,
        onValueChanged: (Float) -> Unit,
    ) {
        onLinearZoomChanged = onValueChanged
        progress = (linearZoom.coerceIn(0f, 1f) * 100f).roundToInt()

        if (renderedZoomRatio == zoomRatio) return
        renderedZoomRatio = zoomRatio

        val textView = thumbView.findViewById<TextView>(R.id.foco_zoom_progress)
        textView.text = String.format(Locale.getDefault(), "%.1fx", zoomRatio)

        thumbView.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val bitmap = Bitmap.createBitmap(
            thumbView.measuredWidth,
            thumbView.measuredHeight,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        thumbView.layout(0, 0, thumbView.measuredWidth, thumbView.measuredHeight)
        thumbView.draw(canvas)
        thumb = BitmapDrawable(resources, bitmap)
        onSizeChanged(width, height, 0, 0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(h, w, oldh, oldw)
    }

    @Synchronized
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(heightMeasureSpec, widthMeasureSpec)
        setMeasuredDimension(measuredHeight, measuredWidth)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.rotate(-90f)
        canvas.translate(-height.toFloat(), 0f)
        super.onDraw(canvas)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || height <= 0) return false

        when (event.action) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_DOWN -> {
                val progressValue = (
                    max - (max * event.y / height).toInt()
                ).coerceIn(1, max.coerceAtLeast(1))
                onLinearZoomChanged?.invoke(progressValue / max.toFloat())
            }
            MotionEvent.ACTION_UP -> performClick()
            MotionEvent.ACTION_CANCEL -> Unit
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
