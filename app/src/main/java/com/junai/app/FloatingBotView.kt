package com.junai.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.*
import kotlin.random.Random

class FloatingBotView(context: Context) : View(context) {

    // ─── Expression ───────────────────────────────────────────
    var expression: BotExpression = BotExpression.NEURAL
        set(value) { field = value; invalidate() }

    // ─── Visor area ───────────────────────────────────────────
    private val visorLeftF   = 0.22f
    private val visorTopF    = 0.33f
    private val visorRightF  = 0.78f
    private val visorBottomF = 0.68f
    private val visorRect    = RectF()

    // ─── Eye state ────────────────────────────────────────────
    private var blinkProgress = 0f

    // Touch position in SCREEN coords
    private var touchScreenX = -1f
    private var touchScreenY = -1f

    // Smooth pupil position (animated)
    private var pupilOffsetX = 0f
    private var pupilOffsetY = 0f
    private var targetPupilX = 0f
    private var targetPupilY = 0f
    private var pupilAnimator: ValueAnimator? = null

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
        style  = Paint.Style.FILL
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
        color       = Color.WHITE
        style       = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap   = Paint.Cap.ROUND
    }
    private val mouthFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1A1A")
        style = Paint.Style.FILL
    }
    private val visorGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 6f
        color       = Color.parseColor("#FF69B4")
        maskFilter  = BlurMaskFilter(18f, BlurMaskFilter.Blur.OUTER)
    }
    private val neonBodyGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 8f
        color       = Color.parseColor("#FF69B4")
        maskFilter  = BlurMaskFilter(24f, BlurMaskFilter.Blur.OUTER)
    }
    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color      = Color.parseColor("#66FF69B4")
        style      = Paint.Style.FILL
        maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
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

        drawNeonGlow(canvas, w, h)

        botBitmap?.let {
            canvas.drawBitmap(it, null, RectF(0f, 0f, w, h), null)
        }

        canvas.drawRoundRect(visorRect, 60f, 60f, visorGlowPaint)
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
        val vr  = visorRect
        val vw  = vr.width()
        val vh  = vr.height()
        val vcx = vr.centerX()
        val vcy = vr.centerY()

        val eyeR       = vw * 0.13f
        val eyeSpacing = vw * 0.22f
        val eyeY       = vcy - vh * 0.05f

        val leftEyeCx  = vcx - eyeSpacing
        val rightEyeCx = vcx + eyeSpacing

        drawEye(canvas, leftEyeCx,  eyeY, eyeR)
        drawEye(canvas, rightEyeCx, eyeY, eyeR)

        // Blush — small soft dots below each eye
        drawBlush(canvas, leftEyeCx,  eyeY + eyeR * 2.0f, eyeR * 0.7f)
        drawBlush(canvas, rightEyeCx, eyeY + eyeR * 2.0f, eyeR * 0.7f)

        drawMouth(canvas, vcx, eyeY + vh * 0.40f, vw * 0.22f)
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

        // Soft glow behind eye
        canvas.drawCircle(cx, cy, radius * 1.2f, eyeGlowPaint)

        canvas.save()
        canvas.clipRect(cx - radius - 2f, cy - radius - 2f,
                        cx + radius + 2f, cy + radius + 2f)
        canvas.scale(1f, scaleY, cx, cy)

        // White eyeball
        canvas.drawCircle(cx, cy, radius, eyeWhitePaint)

        // Pupil — uses smooth animated offset
        val pupilR = radius * 0.46f
        val maxOff = radius * 0.38f
        val (px, py) = getSmoothedPupilOffset(cx, cy, maxOff)

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

    // ──────────────────────────────────────────────────────────
    // PUPIL TRACKING — smooth follow
    // ──────────────────────────────────────────────────────────
    private fun getSmoothedPupilOffset(
        eyeCx: Float, eyeCy: Float, maxOffset: Float
    ): Pair<Float, Float> {
        if (touchScreenX < 0f || touchScreenY < 0f) return Pair(0f, 0f)

        // Eye center in screen coords
        val location = IntArray(2)
        getLocationOnScreen(location)
        val eyeScreenX = location[0] + eyeCx
        val eyeScreenY = location[1] + eyeCy

        val dx   = touchScreenX - eyeScreenX
        val dy   = touchScreenY - eyeScreenY
        val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)

        // Normalize and clamp
        val factor = min(dist, maxOffset * 2.5f) / dist * 0.5f
        val rawX = dx * factor
        val rawY = dy * factor

        // Clamp within max offset
        val clampedDist = sqrt(rawX * rawX + rawY * rawY)
        return if (clampedDist > maxOffset) {
            val scale = maxOffset / clampedDist
            Pair(rawX * scale, rawY * scale)
        } else {
            Pair(rawX, rawY)
        }
    }

    // ──────────────────────────────────────────────────────────
    // TOUCH — called from service
    // ──────────────────────────────────────────────────────────
    fun updateTouchPosition(screenX: Float, screenY: Float) {
        touchScreenX = screenX
        touchScreenY = screenY
        invalidate()
    }

    fun clearTouchPosition() {
        touchScreenX = -1f
        touchScreenY = -1f
        invalidate()
    }

    // ──────────────────────────────────────────────────────────
    // MOUTH
    // ──────────────────────────────────────────────────────────
    private fun drawMouth(canvas: Canvas, cx: Float, cy: Float, halfWidth: Float) {
        when (expression) {
            BotExpression.HAPPY    -> drawSmile(canvas, cx, cy, halfWidth, 0.7f)
            BotExpression.SLEEPING -> drawSleepMouth(canvas, cx, cy, halfWidth)
            BotExpression.THINKING -> drawThinkMouth(canvas, cx, cy, halfWidth)
            BotExpression.SPEAKING -> drawSpeakMouth(canvas, cx, cy, halfWidth)
            else -> drawSmile(canvas, cx, cy, halfWidth, 0.5f)
        }
    }

    private fun drawSmile(canvas: Canvas, cx: Float, cy: Float, hw: Float, curve: Float) {
        val path = Path().apply {
            moveTo(cx - hw, cy)
            quadTo(cx, cy + hw * curve, cx + hw, cy)
        }
        canvas.drawPath(path, mouthPaint)
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
            duration     = 200
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
            duration    = 600
            repeatCount = ValueAnimator.INFINITE
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
        pupilAnimator?.cancel()
        blinkRunnable?.let { blinkScheduler?.removeCallbacks(it) }
        blinkScheduler = null
    }
}
