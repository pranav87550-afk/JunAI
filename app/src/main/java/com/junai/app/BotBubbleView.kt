package com.junai.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.View
import java.util.Locale

class BotBubbleView(context: Context) : View(context) {

    private var fullText = ""
    private var visibleChars = 0
    private var bubbleAlpha = 0f

    private var typeHandler: Handler? = null
    private var typeRunnable: Runnable? = null
    private var fadeAnimator: ValueAnimator? = null
    private var autoHideRunnable: Runnable? = null

    var pointsRight = true

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speakWithTts = false

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#160A0A")
        style = Paint.Style.FILL
    }
    private val borderGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4444")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.OUTER)
    }
    private val borderSharpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF7777")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFF0F0")
        textSize = 42f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val crystalShinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3AFFFFFF")
        style = Paint.Style.FILL
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6666")
        style = Paint.Style.FILL
    }

    private val padding = 36f
    private val tailSize = 20f
    private val cornerRadius = 30f
    private val maxBubbleWidth = 640f

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        alpha = 0f
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.language = Locale.getDefault()
        }
    }

    fun showMessage(text: String, durationMs: Long = 3400, speak: Boolean = true) {
        cancelAll()
        fullText = text
        visibleChars = 0
        speakWithTts = speak
        requestLayout()

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

        if (speakWithTts && ttsReady) {
            val cleanForSpeech = text.replace(Regex("[\"\\p{So}\\p{Cn}]"), "")
            if (cleanForSpeech.isNotBlank()) {
                tts?.speak(cleanForSpeech, TextToSpeech.QUEUE_FLUSH, null, "JUN_BUBBLE_TTS")
            }
        }

        typeHandler = Handler(Looper.getMainLooper())
        typeRunnable = object : Runnable {
            override fun run() {
                if (visibleChars < fullText.length) {
                    visibleChars++
                    invalidate()
                    typeHandler?.postDelayed(this, 30)
                } else {
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
            duration = 280
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

    // ──────────────────────────────────────────────────────────
    // TEXT WRAPPING
    // ──────────────────────────────────────────────────────────
    private fun wrapText(text: String, maxWidth: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (textPaint.measureText(testLine) > maxWidth && currentLine.isNotEmpty()) {
                lines.add(currentLine.toString())
                currentLine = StringBuilder(word)
            } else {
                currentLine = StringBuilder(testLine)
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
        return lines
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (fullText.isEmpty()) {
            setMeasuredDimension(80, 70)
            return
        }
        val availableTextWidth = maxBubbleWidth - padding * 2 - tailSize
        val lines = wrapText(fullText, availableTextWidth)
        val widestLine = lines.maxOfOrNull { textPaint.measureText(it) } ?: 0f

        val w = (widestLine + padding * 2 + tailSize).toInt().coerceAtLeast(120)
        val lineHeight = textPaint.textSize * 1.3f
        val h = (lineHeight * lines.size + padding * 1.6f).toInt().coerceAtLeast(90)
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

        val path = Path()
        path.addRoundRect(bubbleRect, cornerRadius, cornerRadius, Path.Direction.CW)

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
        canvas.drawPath(path, borderGlowPaint)
        canvas.drawPath(path, borderSharpPaint)

        // Crystal shine
        val shinePath = Path().apply {
            moveTo(bubbleLeft + 16f, 10f)
            lineTo(bubbleLeft + 80f, 10f)
            lineTo(bubbleLeft + 40f, h * 0.45f)
            lineTo(bubbleLeft + 16f, h * 0.45f)
            close()
        }
        canvas.drawPath(shinePath, crystalShinePaint)

        // Text — wrapped, letter by letter reveal across lines
        val availableTextWidth = maxBubbleWidth - padding * 2 - tailSize
        val lines = wrapText(fullText, availableTextWidth)
        val lineHeight = textPaint.textSize * 1.3f
        val totalTextHeight = lineHeight * lines.size
        var startY = (h - totalTextHeight) / 2f + textPaint.textSize

        var charsLeft = visibleChars.coerceAtMost(fullText.length)
        val textX = bubbleLeft + padding

        for (line in lines) {
            if (charsLeft <= 0) break
            val visibleInLine = line.substring(0, line.length.coerceAtMost(charsLeft))
            canvas.drawText(visibleInLine, textX, startY, textPaint)
            charsLeft -= (line.length + 1) // +1 for the space/newline
            startY += lineHeight
        }
    }

    fun destroy() {
        cancelAll()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
