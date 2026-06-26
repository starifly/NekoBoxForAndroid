package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * A simple QR-scanner viewfinder overlay: dims the whole surface and punches a
 * rounded-square transparent "scan window" in the center with a tinted border.
 * Replaces the zxing-lite ViewfinderView (removed with the ZXing dependency).
 */
class ScannerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99000000.toInt() // ~60% black scrim
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(3f)
    }

    private val windowRect = RectF()
    private var cornerRadius = dp(16f)

    /** The scan window in view coordinates, for mapping ML Kit results / cropping. */
    val scanWindow: RectF get() = RectF(windowRect)

    init {
        // Needed so the CLEAR xfermode punches a hole rather than painting black.
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setBorderColor(color: Int) {
        borderPaint.color = color
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Square window ~70% of the shorter side, centered.
        val side = min(w, h) * 0.7f
        val left = (w - side) / 2f
        val top = (h - side) / 2f
        windowRect.set(left, top, left + side, top + side)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        canvas.drawRoundRect(windowRect, cornerRadius, cornerRadius, clearPaint)
        canvas.drawRoundRect(windowRect, cornerRadius, cornerRadius, borderPaint)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
