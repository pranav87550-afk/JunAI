package com.junai.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.View

class BotBubbleView(context: Context) : View(context) {

    private var fullText = ""
    private var visibleChars = 0
    private var bubbleAlpha = 0f

    private var typeHandler: Handler? = null
    private var typeRunnable: Runnable? = null
    private var fadeAnimator: ValueAnimator? = null
    private var autoHideRunnable: Runnable? = null

    var pointsRight = true  // tail direction — true = bubble is to the right of bot, tail points left

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A0A0A")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4444")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.OUTER)
    }
    private val borderSharpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6666")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE0E0")
        textSize = 32f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val crystalShinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        style = Paint.Style.FILL
    }

    private val padding = 28f
    private val tailSize = 16f
    private val cornerRadius = 24f

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        alpha = 0f
    }

    fun showMessage(text: String, durationMs: Long = 2800) {
        cancelAll()
        fullText = text
        visibleChars = 0

        // Measure and request layout
        requestLayout()

        // Fade in
        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            addUpdateListener {
                bubbleAlpha = it.animatedValue as Float
                this@BotBubbleView.alpha = bubbleAlpha
                invalidate()
            }
            start()
        }

        // Letter by letter typing
        typeHandler = Handler(Looper.getMainLooper())
        typeRunnable = object : Runnable {
            override fun run() {
                if (visibleChars < fullText.length) {
                    visibleChars++
                    invalidate()
                    typeHandler?.postDelayed(this, 35)
                } else {
                    // Fully typed — schedule auto hide
                    autoHideRunnable = Runnable { hideMessage() }
                    typeHandler?.postDelayed(autoHideRunnable!!, durationMs)
                }
            }
        }
        typeHandler?.post(typeRunnable!!)
    }

    fun hideMessage() {
        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofFloat(bubbleAlpha, 0f).apply {
            duration = 250
            addUpdateListener {
                bubbleAlpha = it.animatedValue as Float
                this@BotBubbleView.alpha = bubbleAlpha
                invalidate()
            }
            start()
        }
    }

    private fun cancelAll() {
        typeRunnable?.let { typeHandler?.removeCallbacks(it) }
        autoHideRunnable?.let { typeHandler?.removeCallbacks(it) }
        fadeAnimator?.cancel()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val textWidth = textPaint.measureText(fullText).coerceAtMost(420f)
        val w = (textWidth + padding * 2 + tailSize).toInt().coerceAtLeast(80)
        val h = (textPaint.textSize + padding * 2).toInt().coerceAtLeast(70)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (fullText.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val bubbleLeft   = if (pointsRight) tailSize else 0f
        val bubbleRight  = if (pointsRight) w else w - tailSize
        val bubbleRect   = RectF(bubbleLeft, 0f, bubbleRight, h)

        // Bubble body
        val path = Path()
        path.addRoundRect(bubbleRect, cornerRadius, cornerRadius, Path.Direction.CW)

        // Tail
        val tailY = h / 2f
        if (pointsRight) {
            path.moveTo(bubbleLeft, tailY - tailSize * 0.7f)
            path.lineTo(0f, tailY)
            path.lineTo(bubbleLeft, tailY + tailSize * 0.7f)
        } else {
            path.moveTo(bubbleRight, tailY - tailSize * 0.7f)
            path.lineTo(w, tailY)
            path.lineTo(bubbleRight, tailY + tailSize * 0.7f)
        }
        path.close()

        canvas.drawPath(path, bubblePaint)
        canvas.drawPath(path, borderPaint)
        canvas.drawPath(path, borderSharpPaint)

        // Crystal shine — top diagonal highlight
        val shinePath = Path().apply {
            moveTo(bubbleLeft + 12f, 8f)
            lineTo(bubbleLeft + 60f, 8f)
            lineTo(bubbleLeft + 30f, h * 0.4f)
            lineTo(bubbleLeft + 12f, h * 0.4f)
            close()
        }
        canvas.drawPath(shinePath, crystalShinePaint)

        // Text — letter by letter
        val visibleText = fullText.substring(0, visibleChars.coerceAtMost(fullText.length))
        val textX = bubbleLeft + padding
        val textY = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(visibleText, textX, textY, textPaint)
    }

    fun destroy() {
        cancelAll()
    }
}
