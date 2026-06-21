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

    private var currentMood: BotMood = BotMood.SMILE

    // ─── Visor ────────────────────────────────────────────────
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

    private var roamDirX = 0f
    private var roamDirY = 0f
    private var isRoaming = false

    var randomEyeEnabled = false
        set(value) { field = value; if (value) startIdleEyes() else stopIdleEyes() }
    var touchEyeEnabled = false

    // ─── Animators ────────────────────────────────────────────
    private var blinkAnimator:  ValueAnimator? = null
    private var mouthAnimator:  ValueAnimator? = null
    private var blinkScheduler: android.os.Handler? = null
    private var blinkRunnable:  Runnable? = null

    private var smoothRoamX = 0f
    private var smoothRoamY = 0f
    private var roamSmoothAnimator: ValueAnimator? = null

    // Dizzy spin
    private var dizzySpinAngle = 0f
    private var dizzyAnimator: ValueAnimator? = null

    // Mood mouth animation (sleepy idle loop / speaking loop)
    private var moodMouthAnimator: ValueAnimator? = null
    private var moodMouthFrame = 0f // 0..3 cycles through shapes

    // ─── Paints ───────────────────────────────────────────────
    private val eyeballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
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
        color      = Color.WHITE
        style      = Paint.Style.FILL
        maskFilter = BlurMaskFilter(2f, BlurMaskFilter.Blur.NORMAL)
    }
    private val mouthStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.WHITE
        style       = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap   = Paint.Cap.ROUND
    }
    private val angryEyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3333")
        style = Paint.Style.FILL
    }
    private val angryBrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#FF4444")
        style       = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap   = Paint.Cap.ROUND
    }

    // ─── Bot image ────────────────────────────────────────────
    private var botBitmap: Bitmap? = null
    private var destRect  = RectF()

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
    // VISOR-ONLY HIT TEST — used by service to ignore transparent padding
    // ──────────────────────────────────────────────────────────
    fun isTouchOnVisor(x: Float, y: Float): Boolean {
        if (destRect.isEmpty) return true // fallback before first draw
        // Expand visor rect slightly for easier tapping
        val expand = visorRect.width() * 0.15f
        val expanded = RectF(
            visorRect.left - expand,
            visorRect.top - expand,
            visorRect.right + expand,
            visorRect.bottom + expand
        )
        return expanded.contains(x, y)
    }

    // ──────────────────────────────────────────────────────────
    // MOOD
    // ──────────────────────────────────────────────────────────
    fun setMood(newMood: BotMood) {
        if (currentMood == newMood) return
        currentMood = newMood
        moodMouthAnimator?.cancel()

        when (newMood) {
            BotMood.DIZZY -> startDizzySpin()
            else -> stopDizzySpin()
        }

        if (newMood == BotMood.SLEEPY) {
            startSleepyMouthLoop()
        }

        invalidate()
    }

    fun getMood(): BotMood = currentMood

    private fun startDizzySpin() {
        dizzyAnimator?.cancel()
        dizzyAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                dizzySpinAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopDizzySpin() {
        dizzyAnimator?.cancel()
        dizzySpinAngle = 0f
    }

    // Sleepy mouth — loops through o, -, o, - shapes continuously
    private fun startSleepyMouthLoop() {
        moodMouthAnimator?.cancel()
        moodMouthAnimator = ValueAnimator.ofFloat(0f, 4f).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                moodMouthFrame = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // ──────────────────────────────────────────────────────────
    // ROAM DIRECTION
    // ──────────────────────────────────────────────────────────
    fun setRoamDirection(dx: Float, dy: Float) {
        isRoaming = true
        val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        val targetX = (dx / dist).coerceIn(-1f, 1f)
        val targetY = (dy / dist).coerceIn(-1f, 1f)

        roamSmoothAnimator?.cancel()
        val fromX = smoothRoamX
        val fromY = smoothRoamY

        roamSmoothAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = 400
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener {
                val t       = it.animatedValue as Float
                smoothRoamX = fromX + (targetX - fromX) * t
                smoothRoamY = fromY + (targetY - fromY) * t
                invalidate()
            }
            start()
        }
    }

    fun clearRoamDirection() {
        isRoaming = false
        roamSmoothAnimator?.cancel()
        val fromX = smoothRoamX
        val fromY = smoothRoamY
        roamSmoothAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = 600
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                val t       = it.animatedValue as Float
                smoothRoamX = fromX * (1f - t)
                smoothRoamY = fromY * (1f - t)
                invalidate()
            }
            start()
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

        botBitmap?.let { bmp ->
            val bmpW  = bmp.width.toFloat()
            val bmpH  = bmp.height.toFloat()
            val scale = min(w / bmpW, h / bmpH)
            val drawW = bmpW * scale
            val drawH = bmpH * scale
            val left  = (w - drawW) / 2f
            val top   = (h - drawH) / 2f
            destRect.set(left, top, left + drawW, top + drawH)
            canvas.drawBitmap(bmp, null, destRect, null)

            visorRect.set(
                left + drawW * visorLeftF,
                top  + drawH * visorTopF,
                left + drawW * visorRightF,
                top  + drawH * visorBottomF
            )
        }

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

        val eyeW = vw * 0.09f
        val eyeH = vh * 0.20f
        val eyeY = vcy - vh * 0.08f

        val leftEyeCx  = vcx - vw * 0.15f
        val rightEyeCx = vcx + vw * 0.22f

        if (currentMood == BotMood.DIZZY) {
            canvas.save()
            canvas.rotate(dizzySpinAngle, vcx, vcy)
        }

        if (currentMood == BotMood.ANGRY) {
            drawAngryFace(canvas, vcx, vcy, vw, vh)
        } else {
            drawEye(canvas, leftEyeCx,  eyeY, eyeW, eyeH)
            drawEye(canvas, rightEyeCx, eyeY, eyeW, eyeH)
            drawMouth(canvas, vcx + vw * 0.03f, eyeY + vh * 0.28f, vw * 0.12f, vh * 0.08f)
        }

        if (currentMood == BotMood.DIZZY) canvas.restore()
    }

    // ──────────────────────────────────────────────────────────
    // ANGRY FACE — simple clean 😠 style (>< eyes + flat angry mouth)
    // ──────────────────────────────────────────────────────────
    private fun drawAngryFace(canvas: Canvas, vcx: Float, vcy: Float, vw: Float, vh: Float) {
        val eyeY = vcy - vh * 0.05f
        val leftEyeCx  = vcx - vw * 0.15f
        val rightEyeCx = vcx + vw * 0.22f
        val eyeSize = vw * 0.10f

        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = Color.parseColor("#FF4444")
            style       = Paint.Style.STROKE
            strokeWidth = 7f
            strokeCap   = Paint.Cap.ROUND
        }

        // Left eye '>' shape (angled lines forming a wedge, angry look)
        canvas.drawLine(leftEyeCx - eyeSize, eyeY - eyeSize * 0.7f, leftEyeCx + eyeSize * 0.3f, eyeY, p)
        canvas.drawLine(leftEyeCx + eyeSize * 0.3f, eyeY, leftEyeCx - eyeSize, eyeY + eyeSize * 0.7f, p)

        // Right eye '<' shape mirrored
        canvas.drawLine(rightEyeCx + eyeSize, eyeY - eyeSize * 0.7f, rightEyeCx - eyeSize * 0.3f, eyeY, p)
        canvas.drawLine(rightEyeCx - eyeSize * 0.3f, eyeY, rightEyeCx + eyeSize, eyeY + eyeSize * 0.7f, p)

        // Angled angry eyebrows above
        canvas.drawLine(leftEyeCx - eyeSize * 1.2f, eyeY - eyeSize * 1.6f, leftEyeCx + eyeSize * 0.6f, eyeY - eyeSize * 2.1f, angryBrowPaint)
        canvas.drawLine(rightEyeCx - eyeSize * 0.6f, eyeY - eyeSize * 2.1f, rightEyeCx + eyeSize * 1.2f, eyeY - eyeSize * 1.6f, angryBrowPaint)

        // Flat angry mouth — straight line with slight frown dip at center
        val mouthCx = vcx + vw * 0.03f
        val mouthCy = eyeY + vh * 0.30f
        val hw = vw * 0.12f
        val path = Path().apply {
            moveTo(mouthCx - hw, mouthCy)
            lineTo(mouthCx - hw * 0.3f, mouthCy + hw * 0.15f)
            lineTo(mouthCx + hw * 0.3f, mouthCy + hw * 0.15f)
            lineTo(mouthCx + hw, mouthCy)
        }
        canvas.drawPath(path, mouthStrokePaint)
    }

    // ──────────────────────────────────────────────────────────
    // EYE
    // ──────────────────────────────────────────────────────────
    private fun drawEye(canvas: Canvas, cx: Float, cy: Float, ew: Float, eh: Float) {
        val scaleY = when {
            currentMood == BotMood.SLEEPY -> 0.15f
            expression == BotExpression.SLEEPING -> 0.06f
            expression == BotExpression.THINKING -> if (cx < width / 2f) 0.4f else 1f
            else -> 1f - (blinkProgress * 0.95f)
        }

        canvas.save()
        canvas.clipRect(cx - ew - 2f, cy - eh - 2f, cx + ew + 2f, cy + eh + 2f)
        canvas.scale(1f, scaleY, cx, cy)

        canvas.drawOval(RectF(cx - ew, cy - eh, cx + ew, cy + eh), eyeballPaint)

        val pupilW  = ew * 0.42f
        val pupilH  = eh * 0.42f
        val maxOffX = ew * 0.35f
        val maxOffY = eh * 0.28f
        val (px, py) = getPupilOffset(cx, cy, maxOffX, maxOffY)

        canvas.drawOval(
            RectF(cx + px - pupilW, cy + py - pupilH,
                  cx + px + pupilW, cy + py + pupilH),
            pupilPaint
        )

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
        maxX: Float,  maxY: Float
    ): Pair<Float, Float> {

        if (currentMood == BotMood.DIZZY) {
            val angle = Math.toRadians((dizzySpinAngle * 2.5f).toDouble())
            return Pair((cos(angle) * maxX * 0.6f).toFloat(), (sin(angle) * maxY * 0.6f).toFloat())
        }

        if (touchEyeEnabled && touchScreenX >= 0f) {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val dx   = touchScreenX - (loc[0] + eyeCx)
            val dy   = touchScreenY - (loc[1] + eyeCy)
            val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
            val move = min(dist * 0.35f, maxX)
            return Pair((dx / dist) * move, (dy / dist) * min(dist * 0.35f, maxY))
        }

        if (isRoaming || smoothRoamX != 0f || smoothRoamY != 0f) {
            return Pair(smoothRoamX * maxX, smoothRoamY * maxY)
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
        when {
            currentMood == BotMood.SLEEPY -> drawSleepyAnimatedMouth(canvas, cx, cy, hw, hh)
            expression == BotExpression.SLEEPING -> drawSleepMouth(canvas, cx, cy, hw)
            expression == BotExpression.SPEAKING -> drawSpeakingWaveMouth(canvas, cx, cy, hw, hh)
            else -> drawCupSmile(canvas, cx, cy, hw, hh)
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
        canvas.drawLine(cx - hw * 0.5f, cy, cx + hw * 0.5f, cy, mouthStrokePaint)
    }

    // Sleepy: continuously cycles o -> - -> o -> - ...
    private fun drawSleepyAnimatedMouth(canvas: Canvas, cx: Float, cy: Float, hw: Float, hh: Float) {
        val cyclePos = moodMouthFrame % 2f // 0..2, 0-1 = 'o', 1-2 = '-'
        if (cyclePos < 1f) {
            // 'o' shape — small circle, size pulses in
            val t = cyclePos // 0 to 1
            val radius = hh * 0.55f * sin(t * Math.PI).toFloat().coerceAtLeast(0.15f)
            canvas.drawOval(
                RectF(cx - radius, cy - radius, cx + radius, cy + radius),
                mouthPaint
            )
        } else {
            // '-' shape — flat line
            canvas.drawLine(cx - hw * 0.45f, cy, cx + hw * 0.45f, cy, mouthStrokePaint)
        }
    }

    // Speaking: continuously cycles o ~ o ~ o (wave-like open/close)
    private fun drawSpeakingWaveMouth(canvas: Canvas, cx: Float, cy: Float, hw: Float, hh: Float) {
        val openH = hh * (0.35f + mouthOpenAmount * 0.65f)
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
            if (expression != BotExpression.SLEEPING && currentMood != BotMood.SLEEPY) performBlink()
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

    private fun animateMouth() {
        mouthAnimator?.cancel()
        mouthAnimator = ValueAnimator.ofFloat(0f, 1f, 0.2f, 0.9f, 0.1f, 0.7f, 0f).apply {
            duration    = 700
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
        roamSmoothAnimator?.cancel()
        dizzyAnimator?.cancel()
        moodMouthAnimator?.cancel()
        blinkRunnable?.let { blinkScheduler?.removeCallbacks(it) }
        blinkScheduler = null
    }
}
