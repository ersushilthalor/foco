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
import androidx.appcompat.widget.AppCompatSeekBar
import io.github.realkhfan.foco.R
import java.util.Locale
import kotlin.math.roundToInt

/**
 * VibeCam's original vertical ZoomBar, with only its activity callback replaced by
 * Foco's existing ratio-based CameraActions.setZoom() boundary.
 *
 * The rotated measurement/drawing, custom bitmap thumb, 0..100 linear track and touch
 * mapping are kept in the same shape as VibeCam's ZoomBar implementation.
 */
internal class FocoZoomBar : AppCompatSeekBar {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle)

    @SuppressLint("InflateParams")
    private val thumbView: View = LayoutInflater.from(context)
        .inflate(R.layout.foco_zoom_bar_thumb, null, false)

    private data class RenderedZoom(val ratio: Float, val linear: Float)

    private var renderedZoom: RenderedZoom? = null
    private var onLinearZoomChanged: ((Float) -> Unit)? = null

    init {
        max = 100
        progressTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
    }

    fun render(
        zoomRatio: Float,
        linearZoom: Float,
        onValueChanged: (Float) -> Unit,
    ) {
        onLinearZoomChanged = onValueChanged
        val next = RenderedZoom(zoomRatio = zoomRatio, linear = linearZoom.coerceIn(0f, 1f))
        if (next == renderedZoom) return
        renderedZoom = next

        progress = (next.linear * 100f).roundToInt()

        val textView = thumbView.findViewById<TextView>(R.id.foco_zoom_progress)
        textView.text = String.format(Locale.getDefault(), "%.1fx", zoomRatio)

        thumbView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
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
                var value = max - (max * event.y / height).toInt()
                if (value < 1) value = 1
                if (value > 100) value = 100
                onLinearZoomChanged?.invoke(value / 100f)
            }
            MotionEvent.ACTION_CANCEL -> Unit
        }
        return true
    }
}
