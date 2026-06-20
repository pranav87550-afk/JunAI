package com.junai.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

class ListeningOverlayView(context: Context) : View(context) {

    private var pulseScale = 1f
    private var pulseAnimator: ValueAnimator? = null

    // Wave bars
    private val barCount = 5
    private var barHeights = FloatArray(barCount) { 0.3f }
    private var waveAnimator: ValueAnimator? = null
    private var wavePhase = 0f

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A0A0A")
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4444")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.OUTER)
    }
    private val ringSharpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6666")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val micPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFAAAA")
        style = Paint.Style.FILL
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6666")
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        alpha = 0f
    }

    fun start() {
        animate().alpha(1f).setDuration(180).start()

        // Pulse ring
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.25f, 1f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                pulseScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        // Wave bars — simulated voice activity
        waveAnimator?.cancel()
        waveAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                wavePhase = it.animatedValue as Float
                for (i in 0 until barCount) {
                    val offset = i * 0.6f
                    barHeights[i] = (0.3f + 0.7f * ((sin(wavePhase + offset) + 1f) / 2f))
                }
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        pulseAnimator?.cancel()
        waveAnimator?.cancel()
        animate().alpha(0f).setDuration(150).start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val cx = w / 2f
        val cy = h * 0.38f
        val baseRadius = h * 0.22f
        val radius = baseRadius * pulseScale

        // Outer pulse ring
        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.drawCircle(cx, cy, baseRadius, circlePaint)
        canvas.drawCircle(cx, cy, baseRadius, ringSharpPaint)

        // Mic icon — simple capsule + arc
        val micW = baseRadius * 0.35f
        val micH = baseRadius * 0.55f
        canvas.drawRoundRect(
            RectF(cx - micW / 2, cy - micH / 2, cx + micW / 2, cy + micH / 2),
            micW / 2, micW / 2, micPaint
        )

        // Wave bars below
        val barAreaTop = cy + baseRadius + h * 0.08f
        val barWidth = w * 0.04f
        val barSpacing = w * 0.03f
        val totalBarsWidth = barCount * barWidth + (barCount - 1) * barSpacing
        var barX = cx - totalBarsWidth / 2f
        val maxBarHeight = h * 0.18f

        for (i in 0 until barCount) {
            val barH = maxBarHeight * barHeights[i]
            canvas.drawRoundRect(
                RectF(barX, barAreaTop + (maxBarHeight - barH), barX + barWidth, barAreaTop + maxBarHeight),
                barWidth / 2, barWidth / 2, barPaint
            )
            barX += barWidth + barSpacing
        }
    }

    fun destroy() {
        pulseAnimator?.cancel()
        waveAnimator?.cancel()
    }
}
