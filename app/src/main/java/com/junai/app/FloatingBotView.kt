package com.junai.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.*
import kotlin.random.Random

class FloatingBotView(context: Context) : View(context) {

    var expression: BotExpression = BotExpression.NEURAL
        set(value) { field = value; invalidate() }

    // ─── Visor — tuned to new PNG ─────────────────────────────
    private val visorLeftF   = 0.20f
    private val visorTopF    = 0.30f
    private val visorRightF  = 0.80f
    private val visorBottomF = 0.60f
    private val visorRect    = RectF()

    // ─── State ────────────────────────────────────────────────
    private var blinkProgress = 0f
    var mouthOpenAmount       = 0f
    var bobbingOffsetY        = 0f

    private var touchScreenX = -1f
    private var touchScreenY = -1f

    private var idlePupilX = 0f
    private var idlePupilY = 0f
    private var idleAnimator: ValueAnimator? = null

    var randomEyeEnabled = false
        set(value) { field = value; if (value) startIdleEyes() else stopIdleEyes() }
    var touchEyeEnabled = false

    // ─── Animators ────────────────────────────────────────────
    private var blinkAnimator:  ValueAnimator? = null
    private var mouthAnimator:  ValueAnimator? = null
    private var blinkScheduler: android.os.Handler? = null
    private var blinkRunnable:  Runnable? = null

    // ─── Paints ───────────────────────────────────────────────
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1A2E")
        style = Paint.Style.FILL
    }
    private val pupilGlintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color      = Color.WHITE
        style      = Paint.Style.FILL
        maskFilter = BlurMaskFilter(2f, BlurMaskFilter.Blur.NORMAL)
    }

    // ─── Bot image ────────────────────────────────────────────
    private var botBitmap: Bitmap? = null
    private var destRect = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        loadBotImage()
        startBlinkScheduler()
    }

    private fun loadBotImage() {
        try {
            botBitmap = BitmapFactory.decodeResource(resources, R.drawable.jun_bot)
        } catch (e: Exception) { botBitmap = null }
    }

    // ──────────────────────────────────────────────────────────
    // DRAW
    // ──────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        // Draw PNG with aspect ratio preserved
        botBitmap?.let { bmp ->
            val bmpW = bmp.width.toFloat()
            val bmpH = bmp.height.toFloat()
            val scale = min(w / bmpW, h / bmpH)
            val drawW = bmpW * scale
            val drawH = bmpH * scale
            val left  = (w - drawW) / 2f
            val top   = (h - drawH) / 2f
            destRect.set(left, top, left + drawW, top + drawH)
            canvas.drawBitmap(bmp, null, destRect, null)

            // Visor rect relative to actual drawn image
            visorRect.set(
                left + drawW * visorLeftF,
                top  + drawH * visorTopF,
                left + drawW * visorRightF,
                top  + drawH * visorBottomF
            )
        }

        // Face
        drawFace(canvas)
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

        // Eye size — chota kiya
        val eyeW       = vw * 0.13f
        val eyeH       = vh * 0.30f
        val eyeY       = vcy - vh * 0.08f

        // Left eye thoda bahar, right eye thoda andar
        val leftEyeCx  = vcx - vw * 0.20f
        val rightEyeCx = vcx + vw * 0.17f  // thoda kam spacing

        drawEye(canvas, leftEyeCx,  eyeY, eyeW, eyeH)
        drawEye(canvas, rightEyeCx, eyeY, eyeW, eyeH)

        // Mouth
        drawMouth(canvas, vcx, eyeY + vh * 0.46f, vw * 0.12f, vh * 0.08f)
    }

    // ──────────────────────────────────────────────────────────
    // EYE
    // ──────────────────────────────────────────────────────────
    private fun drawEye(canvas: Canvas, cx: Float, cy: Float, ew: Float, eh: Float) {
        val scaleY = when (expression) {
            BotExpression.SLEEPING -> 0.06f
            BotExpression.THINKING -> if (cx < width / 2f) 0.4f else 1f
            else -> 1f - (blinkProgress * 0.95f)
        }

        canvas.save()
        canvas.clipRect(cx - ew - 2f, cy - eh - 2f, cx + ew + 2f, cy + eh + 2f)
        canvas.scale(1f, scaleY, cx, cy)

        val pupilW  = ew * 0.48f
        val pupilH  = eh * 0.48f
        val maxOffX = ew * 0.35f
        val maxOffY = eh * 0.28f
        val (px, py) = getPupilOffset(cx, cy, maxOffX, maxOffY)

        canvas.drawOval(
            RectF(cx + px - pupilW, cy + py - pupilH,
                  cx + px + pupilW, cy + py + pupilH),
            pupilPaint
        )

        // Glint
        canvas.drawCircle(
            cx + px - pupilW * 0.25f,
            cy + py - pupilH * 0.25f,
            pupilW * 0.20f,
            pupilGlintPaint
        )
        canvas.restore()
    }

    // ──────────────────────────────────────────────────────────
    // PUPIL OFFSET
    // ──────────────────────────────────────────────────────────
    private fun getPupilOffset(
        eyeCx: Float, eyeCy: Float,
        maxX: Float, maxY: Float
    ): Pair<Float, Float> {
        if (touchEyeEnabled && touchScreenX >= 0f) {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val dx   = touchScreenX - (loc[0] + eyeCx)
            val dy   = touchScreenY - (loc[1] + eyeCy)
            val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
            val move = min(dist * 0.35f, maxX)
            return Pair((dx / dist) * move, (dy / dist) * min(dist * 0.35f, maxY))
        }
        if (randomEyeEnabled) {
            return Pair(idlePupilX * maxX, idlePupilY * maxY)
        }
        return Pair(0f, 0f)
    }

    fun updateTouchPosition(screenX: Float, screenY: Float) {
        touchScreenX = screenX; touchScreenY = screenY; invalidate()
    }

    fun clearTouchPosition() {
        touchScreenX = -1f; touchScreenY = -1f; invalidate()
    }

    // ──────────────────────────────────────────────────────────
    // IDLE RANDOM EYE
    // ──────────────────────────────────────────────────────────
    private fun startIdleEyes() { stopIdleEyes(); animateToNextIdleTarget() }

    private fun stopIdleEyes() {
        idleAnimator?.cancel()
        idleAnimator = null
        idlePupilX   = 0f
        idlePupilY   = 0f
        invalidate()
    }

    private fun animateToNextIdleTarget() {
        if (!randomEyeEnabled) return
        val targetX = Random.nextFloat() * 2f - 1f
        val targetY = Random.nextFloat() * 2f - 1f
        val dur     = Random.nextLong(800, 2200)
        val pause   = Random.nextLong(400, 1800)
        val fromX   = idlePupilX
        val fromY   = idlePupilY

        idleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = dur
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener {
                val t      = it.animatedValue as Float
                idlePupilX = fromX + (targetX - fromX) * t
                idlePupilY = fromY + (targetY - fromY) * t
                invalidate()
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed({ animateToNextIdleTarget() }, pause)
                }
                override fun onAnimationStart(a: android.animation.Animator)  {}
                override fun onAnimationCancel(a: android.animation.Animator) {}
                override fun onAnimationRepeat(a: android.animation.Animator) {}
            })
            start()
        }
    }

    // ──────────────────────────────────────────────────────────
    // MOUTH
    // ──────────────────────────────────────────────────────────
    private fun drawMouth(canvas: Canvas, cx: Float, cy: Float, hw: Float, hh: Float) {
        when (expression) {
            BotExpression.SLEEPING -> drawSleepMouth(canvas, cx, cy, hw)
            BotExpression.SPEAKING -> drawSpeakMouth(canvas, cx, cy, hw, hh)
            else                   -> drawCupSmile(canvas, cx, cy, hw, hh)
        }
    }

    private fun drawCupSmile(canvas: Canvas, cx: Float, cy: Float, hw: Float, hh: Float) {
        val path = Path().apply {
            moveTo(cx - hw, cy)
            lineTo(cx - hw, cy + hh * 0.3f)
            quadTo(cx - hw, cy + hh, cx, cy + hh)
            quadTo(cx + hw, cy + hh, cx + hw, cy + hh * 0.3f)
            lineTo(cx + hw, cy)
            close()
        }
        canvas.drawPath(path, mouthPaint)
    }

    private fun drawSleepMouth(canvas: Canvas, cx: Float, cy: Float, hw: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = Color.WHITE
            style       = Paint.Style.STROKE
            strokeWidth = 4f
            strokeCap   = Paint.Cap.ROUND
        }
        canvas.drawLine(cx - hw * 0.5f, cy, cx + hw * 0.5f, cy, p)
    }

    private fun drawSpeakMouth(canvas: Canvas, cx: Float, cy: Float, hw: Float, hh: Float) {
        val openH = hh * (0.3f + mouthOpenAmount * 0.7f)
        val path  = Path().apply {
            moveTo(cx - hw, cy)
            lineTo(cx - hw, cy + openH * 0.3f)
            quadTo(cx - hw, cy + openH, cx, cy + openH)
            quadTo(cx + hw, cy + openH, cx + hw, cy + openH * 0.3f)
            lineTo(cx + hw, cy)
            close()
        }
        canvas.drawPath(path, mouthPaint)
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
    fun startSpeaking() { expression = BotExpression.SPEAKING; animateMouth() }

    fun stopSpeaking() {
        expression = BotExpression.NEURAL; mouthOpenAmount = 0f; invalidate()
    }

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
        idleAnimator?.cancel()
        blinkRunnable?.let { blinkScheduler?.removeCallbacks(it) }
        blinkScheduler = null
    }
}
