package com.junai.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.*
import kotlin.random.Random

class FloatingBotView(context: Context) : View(context) {

    // ─── Expression ───────────────────────────────────────────
    var expression: BotExpression = BotExpression.NEURAL
        set(value) { field = value; invalidate() }

    // ─── Visor area (shifted down to match Jun's actual visor) ─
    private val visorLeftF   = 0.22f
    private val visorTopF    = 0.33f   // shifted down
    private val visorRightF  = 0.78f
    private val visorBottomF = 0.68f   // shifted down

    private val visorRect = RectF()

    // ─── Eye state ────────────────────────────────────────────
    private var blinkProgress = 0f
    private var touchX = -1f
    private var touchY = -1f

    // ─── Mouth state ──────────────────────────────────────────
    var mouthOpenAmount = 0f

    // ─── Animators ────────────────────────────────────────────
    private var blinkAnimator: ValueAnimator? = null
    private var blinkScheduler: android.os.Handler? = null
    private var blinkRunnable: Runnable? = null
    var bobbingOffsetY = 0f

    // ─── Paints ───────────────────────────────────────────────
    private val eyeWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val eyeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAFFFFFF")
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
    }
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1A2E")
        style = Paint.Style.FILL
    }
    private val pupilGlintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val mouthFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1A1A")
        style = Paint.Style.FILL
    }
    private val visorGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#FF69B4")
        maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.OUTER)
    }
    private val neonBodyGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.parseColor("#FF69B4")
        maskFilter = BlurMaskFilter(24f, BlurMaskFilter.Blur.OUTER)
    }
    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88FF69B4")
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(16f, BlurMaskFilter.Blur.NORMAL)
    }

    // ─── Bot image ────────────────────────────────────────────
    private var botBitmap: Bitmap? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        loadBotImage()
        startBlinkScheduler()
    }

    private fun loadBotImage() {
        try {
            botBitmap = BitmapFactory.decodeResource(resources, R.drawable.jun_bot)
        } catch (e: Exception) {
            botBitmap = null
        }
    }

    // ──────────────────────────────────────────────────────────
    // DRAW
    // ──────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        visorRect.set(w * visorLeftF, h * visorTopF, w * visorRightF, h * visorBottomF)

        // 1. Neon glow behind bot
        drawNeonGlow(canvas, w, h)

        // 2. Bot PNG
        botBitmap?.let {
            canvas.drawBitmap(it, null, RectF(0f, 0f, w, h), null)
        }

        // 3. Visor glow
        canvas.drawRoundRect(visorRect, 60f, 60f, visorGlowPaint)

        // 4. Face
        drawFace(canvas)
    }

    private fun drawNeonGlow(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h * 0.52f
        val rx = w * 0.42f
        val ry = h * 0.40f
        canvas.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), neonBodyGlowPaint)
    }

    // ──────────────────────────────────────────────────────────
    // FACE
    // ──────────────────────────────────────────────────────────
    private fun drawFace(canvas: Canvas) {
        val vr = visorRect
        val vw = vr.width()
        val vh = vr.height()
        val vcx = vr.centerX()
        val vcy = vr.centerY()

        val eyeR       = vw * 0.13f
        val eyeSpacing = vw * 0.22f
        val eyeY       = vcy - vh * 0.05f   // near center vertically

        val leftEyeCx  = vcx - eyeSpacing
        val rightEyeCx = vcx + eyeSpacing

        // Blush — below eyes, outside
        drawBlush(canvas, leftEyeCx  - eyeR * 0.3f, eyeY + eyeR * 1.6f, eyeR * 1.1f)
        drawBlush(canvas, rightEyeCx + eyeR * 0.3f, eyeY + eyeR * 1.6f, eyeR * 1.1f)

        drawEye(canvas, leftEyeCx,  eyeY, eyeR)
        drawEye(canvas, rightEyeCx, eyeY, eyeR)
        drawMouth(canvas, vcx, eyeY + vh * 0.38f, vw * 0.26f)
    }

    private fun drawBlush(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawCircle(cx, cy, radius, blushPaint)
    }

    private fun drawEye(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val scaleY = when (expression) {
            BotExpression.SLEEPING -> 0.08f
            BotExpression.THINKING -> if (cx < width / 2f) 0.45f else 1f
            else -> 1f - (blinkProgress * 0.95f)
        }

        // Glow behind eye
        canvas.drawCircle(cx, cy, radius * 1.2f, eyeGlowPaint)

        canvas.save()
        canvas.clipRect(cx - radius - 2f, cy - radius - 2f, cx + radius + 2f, cy + radius + 2f)
        canvas.scale(1f, scaleY, cx, cy)

        // White eyeball
        canvas.drawCircle(cx, cy, radius, eyeWhitePaint)

        // Pupil
        val pupilR    = radius * 0.46f
        val maxOffset = radius * 0.36f
        val (px, py)  = getPupilOffset(cx, cy, maxOffset)
        canvas.drawCircle(cx + px, cy + py, pupilR, pupilPaint)

        // Glint
        canvas.drawCircle(
            cx + px - pupilR * 0.22f,
            cy + py - pupilR * 0.22f,
            pupilR * 0.28f,
            pupilGlintPaint
        )
        canvas.restore()
    }

    private fun getPupilOffset(eyeCx: Float, eyeCy: Float, maxOffset: Float): Pair<Float, Float> {
        if (touchX < 0f || touchY < 0f) return Pair(0f, 0f)
        val dx   = touchX - (left + eyeCx)
        val dy   = touchY - (top  + eyeCy)
        val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        val factor = min(1f, maxOffset / dist) * 0.6f
        return Pair(dx * factor, dy * factor)
    }

    // ──────────────────────────────────────────────────────────
    // MOUTH
    // ──────────────────────────────────────────────────────────
    private fun drawMouth(canvas: Canvas, cx: Float, cy: Float, halfWidth: Float) {
        when (expression) {
            BotExpression.HAPPY     -> drawSmile(canvas, cx, cy, halfWidth, 0.55f)
            BotExpression.SLEEPING  -> drawSleepMouth(canvas, cx, cy, halfWidth)
            BotExpression.THINKING  -> drawThinkMouth(canvas, cx, cy, halfWidth)
            BotExpression.SPEAKING  -> drawSpeakMouth(canvas, cx, cy, halfWidth)
            else                    -> drawSmile(canvas, cx, cy, halfWidth, 0.32f) // NEURAL = soft smile
        }
    }

    private fun drawSmile(canvas: Canvas, cx: Float, cy: Float, hw: Float, curve: Float) {
        // Top line (flat)
        val topPath = Path().apply {
            moveTo(cx - hw, cy)
            lineTo(cx + hw, cy)
        }
        // Bottom curve
        val bottomPath = Path().apply {
            moveTo(cx - hw, cy)
            quadTo(cx, cy + hw * curve, cx + hw, cy)
        }
        // Fill
        val fillPath = Path().apply {
            moveTo(cx - hw, cy)
            quadTo(cx, cy + hw * curve, cx + hw, cy)
            lineTo(cx + hw, cy)
            lineTo(cx - hw, cy)
            close()
        }
        canvas.drawPath(fillPath, mouthFillPaint)
        canvas.drawPath(topPath,    mouthPaint)
        canvas.drawPath(bottomPath, mouthPaint)
    }

    private fun drawSleepMouth(canvas: Canvas, cx: Float, cy: Float, hw: Float) {
        canvas.drawLine(cx - hw * 0.4f, cy, cx + hw * 0.4f, cy, mouthPaint)
    }

    private fun drawThinkMouth(canvas: Canvas, cx: Float, cy: Float, hw: Float) {
        val path = Path().apply {
            moveTo(cx - hw * 0.4f, cy + hw * 0.1f)
            quadTo(cx, cy - hw * 0.1f, cx + hw * 0.4f, cy + hw * 0.05f)
        }
        canvas.drawPath(path, mouthPaint)
    }

    private fun drawSpeakMouth(canvas: Canvas, cx: Float, cy: Float, hw: Float) {
        val openY    = cy + hw * 0.4f * mouthOpenAmount
        val fillPath = Path().apply {
            moveTo(cx - hw * 0.6f, cy)
            quadTo(cx, cy - hw * 0.08f, cx + hw * 0.6f, cy)
            quadTo(cx, openY + hw * 0.1f, cx - hw * 0.6f, cy)
            close()
        }
        canvas.drawPath(fillPath, mouthFillPaint)
        val outPath = Path().apply {
            moveTo(cx - hw * 0.6f, cy)
            quadTo(cx, openY, cx + hw * 0.6f, cy)
        }
        canvas.drawPath(outPath, mouthPaint)
    }

    // ──────────────────────────────────────────────────────────
    // TOUCH
    // ──────────────────────────────────────────────────────────
    fun updateTouchPosition(screenX: Float, screenY: Float) {
        touchX = screenX; touchY = screenY; invalidate()
    }

    fun clearTouchPosition() {
        touchX = -1f; touchY = -1f; invalidate()
    }

    // ──────────────────────────────────────────────────────────
    // BLINK
    // ──────────────────────────────────────────────────────────
    private fun startBlinkScheduler() {
        blinkScheduler = android.os.Handler(android.os.Looper.getMainLooper())
        scheduleNextBlink()
    }

    private fun scheduleNextBlink() {
        val delay = Random.nextLong(2500, 5500)
        blinkRunnable = Runnable {
            if (expression != BotExpression.SLEEPING) performBlink()
            scheduleNextBlink()
        }
        blinkScheduler?.postDelayed(blinkRunnable!!, delay)
    }

    private fun performBlink() {
        blinkAnimator?.cancel()
        blinkAnimator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 200
            interpolator = LinearInterpolator()
            addUpdateListener {
                blinkProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // ──────────────────────────────────────────────────────────
    // TTS HOOK
    // ──────────────────────────────────────────────────────────
    fun startSpeaking() {
        expression = BotExpression.SPEAKING
        animateMouth()
    }

    fun stopSpeaking() {
        expression = BotExpression.NEURAL
        mouthOpenAmount = 0f
        invalidate()
    }

    private var mouthAnimator: ValueAnimator? = null
    private fun animateMouth() {
        mouthAnimator?.cancel()
        mouthAnimator = ValueAnimator.ofFloat(0f, 1f, 0.3f, 0.8f, 0f).apply {
            duration     = 600
            repeatCount  = ValueAnimator.INFINITE
            addUpdateListener {
                mouthOpenAmount = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // ──────────────────────────────────────────────────────────
    // CLEANUP
    // ──────────────────────────────────────────────────────────
    fun destroy() {
        blinkAnimator?.cancel()
        mouthAnimator?.cancel()
        blinkRunnable?.let { blinkScheduler?.removeCallbacks(it) }
        blinkScheduler = null
    }
}
