package com.junai.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

/**
 * Custom progress bar for the splash screen.
 * Renders a stream of binary "code" glyphs flowing from start to end,
 * glowing brightest at the active edge — replaces the plain stock ProgressBar.
 */
class CodeStreamProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    private val glyphChars = "01"
    private val charSizePx = dp(11f)
    private val charSpacingPx = dp(8f)
    private val cornerRadius = dp(9f)
    private val scanSpeedPxPerSec = dp(170f)

    private var columnChars = CharArray(0)
    private var scanPos = 0f
    private val clipRect = RectF()
    private var clipPath = Path()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1A1A")
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A3A3A")
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }

    private fun glyphPaint(hex: String) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(hex)
        textSize = charSizePx
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }

    private val dimGlyphPaint = glyphPaint("#3D3D3D")
    private val activeGlyphPaint = glyphPaint("#FF5252")
    private val glowGlyphPaint = glyphPaint("#FFFFFF").apply {
        setShadowLayer(dp(6f), 0f, 0f, Color.parseColor("#FF1744"))
    }

    private val cursorGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744")
        setShadowLayer(dp(10f), 0f, 0f, Color.parseColor("#FF1744"))
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            regenerateColumns(partial = true)
            scanPos += scanSpeedPxPerSec * (TICK_MS / 1000f)
            if (scanPos > width + dp(40f)) scanPos = -dp(40f)
            invalidate()
            handler.postDelayed(this, TICK_MS.toLong())
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clipRect.set(0f, 0f, w.toFloat(), h.toFloat())
        clipPath = Path().apply {
            addRoundRect(clipRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }
        val cols = (w / charSpacingPx).toInt().coerceAtLeast(1)
        columnChars = CharArray(cols)
        regenerateColumns(partial = false)
    }

    private fun regenerateColumns(partial: Boolean) {
        if (columnChars.isEmpty()) return
        for (i in columnChars.indices) {
            if (!partial || Random.nextInt(100) < 30) {
                columnChars[i] = glyphChars[Random.nextInt(glyphChars.length)]
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        handler.postDelayed(tickRunnable, TICK_MS.toLong())
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        handler.removeCallbacks(tickRunnable)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRoundRect(clipRect, cornerRadius, cornerRadius, trackPaint)
        canvas.save()
        canvas.clipPath(clipPath)

        val progressX = w * (progress / 100f)
        val centerY = h / 2f + charSizePx / 3f

        for (i in columnChars.indices) {
            val x = i * charSpacingPx + charSpacingPx / 2f
            if (x > w) break
            val ch = columnChars[i].toString()
            val paint = when {
                x > progressX -> dimGlyphPaint
                kotlin.math.abs(x - scanPos) < dp(28f) -> glowGlyphPaint
                else -> activeGlyphPaint
            }
            canvas.drawText(ch, x, centerY, paint)
        }

        canvas.restore()

        val cursorX = progressX.coerceIn(dp(4f), w - dp(4f))
        canvas.drawCircle(cursorX, h / 2f, dp(3.2f), cursorGlowPaint)
        canvas.drawRoundRect(clipRect, cornerRadius, cornerRadius, borderPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val TICK_MS = 70
    }
}
