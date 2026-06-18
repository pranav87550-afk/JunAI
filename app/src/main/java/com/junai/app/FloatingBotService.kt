package com.junai.app

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.animation.ValueAnimator
import androidx.core.app.NotificationCompat

class FloatingBotService : Service() {

    companion object {
        const val CHANNEL_ID     = "floating_bot_channel"
        const val NOTIF_ID       = 42
        const val BOT_SIZE_DP    = 130
        const val SNAP_ANIM_MS   = 320L

        const val ACTION_SHOW        = "ACTION_SHOW"
        const val ACTION_HIDE        = "ACTION_HIDE"
        const val ACTION_EXPRESSION  = "ACTION_EXPRESSION"
        const val EXTRA_EXPRESSION   = "extra_expression"
        const val ACTION_SPEAK_START = "ACTION_SPEAK_START"
        const val ACTION_SPEAK_STOP  = "ACTION_SPEAK_STOP"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var botView: FloatingBotView

    // Full-screen transparent touch overlay
    private lateinit var touchOverlayView: View

    private lateinit var botParams: WindowManager.LayoutParams
    private lateinit var overlayParams: WindowManager.LayoutParams

    private var screenWidth  = 0
    private var screenHeight = 0

    // Drag state
    private var initialX      = 0
    private var initialY      = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging    = false

    // Bobbing
    private var bobbingAnimator: ValueAnimator? = null
    private var bobbingBaseY = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    // ──────────────────────────────────────────────────────────
    // LIFECYCLE
    // ──────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val dm = resources.displayMetrics
        screenWidth  = dm.widthPixels
        screenHeight = dm.heightPixels

        val sizePx = (BOT_SIZE_DP * dm.density).toInt()
        val startX = screenWidth  - sizePx - 24
        val startY = screenHeight - sizePx - 180

        // ── Bot view params ──
        botParams = WindowManager.LayoutParams(
            sizePx, sizePx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
        }

        // ── Full screen touch overlay params ──
        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or  // starts as non-intercepting
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        // Transparent touch catcher — passes all touches through except tracking
        touchOverlayView = View(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        botView = FloatingBotView(this)
        botView.setOnTouchListener(botTouchListener)

        // Add overlay first (below bot), then bot on top
        windowManager.addView(touchOverlayView, overlayParams)
        windowManager.addView(botView, botParams)

        bobbingBaseY = botParams.y
        startBobbing()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE        -> hideBot()
            ACTION_SHOW        -> showBot()
            ACTION_SPEAK_START -> mainHandler.post { botView.startSpeaking() }
            ACTION_SPEAK_STOP  -> mainHandler.post { botView.stopSpeaking() }
            ACTION_EXPRESSION  -> {
                val name = intent.getStringExtra(EXTRA_EXPRESSION) ?: return START_STICKY
                val expr = try { BotExpression.valueOf(name) }
                           catch (e: Exception) { BotExpression.NEURAL }
                mainHandler.post { botView.expression = expr }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        bobbingAnimator?.cancel()
        botView.destroy()
        if (botView.isAttachedToWindow)         windowManager.removeView(botView)
        if (touchOverlayView.isAttachedToWindow) windowManager.removeView(touchOverlayView)
    }

    override fun onBind(intent: Intent?) = null

    // ──────────────────────────────────────────────────────────
    // BOT TOUCH — drag + pupil tracking
    // ──────────────────────────────────────────────────────────
    private val botTouchListener = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                stopBobbing()
                initialX      = botParams.x
                initialY      = botParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging    = false

                // Enable full-screen touch tracking
                enableOverlayTouch()
                botView.updateTouchPosition(event.rawX, event.rawY)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY

                if (!isDragging && (kotlin.math.abs(dx) > 8f || kotlin.math.abs(dy) > 8f)) {
                    isDragging = true
                }

                if (isDragging) {
                    botParams.x = (initialX + dx).toInt()
                    botParams.y = (initialY + dy).toInt()
                    windowManager.updateViewLayout(botView, botParams)
                }

                botView.updateTouchPosition(event.rawX, event.rawY)
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) snapToEdge()
                isDragging = false
                // Keep overlay active for pupil tracking — disable on finger lift
                disableOverlayTouch()
                botView.clearTouchPosition()
                true
            }

            else -> false
        }
    }

    // ──────────────────────────────────────────────────────────
    // FULL SCREEN TOUCH OVERLAY — for pupil tracking anywhere
    // ──────────────────────────────────────────────────────────
    private fun enableOverlayTouch() {
        overlayParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                              WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        touchOverlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_DOWN -> {
                    botView.updateTouchPosition(event.rawX, event.rawY)
                    false // pass touch through to apps below
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    disableOverlayTouch()
                    botView.clearTouchPosition()
                    false
                }
                else -> false
            }
        }
        try { windowManager.updateViewLayout(touchOverlayView, overlayParams) }
        catch (e: Exception) { /* ignore */ }
    }

    private fun disableOverlayTouch() {
        overlayParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                              WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                              WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        touchOverlayView.setOnTouchListener(null)
        try { windowManager.updateViewLayout(touchOverlayView, overlayParams) }
        catch (e: Exception) { /* ignore */ }
    }

    // ──────────────────────────────────────────────────────────
    // EDGE SNAP
    // ──────────────────────────────────────────────────────────
    private fun snapToEdge() {
        val botSize = botParams.width
        val midX    = botParams.x + botSize / 2
        val targetX = if (midX < screenWidth / 2) 0 else screenWidth - botSize
        val startX  = botParams.x

        ValueAnimator.ofInt(startX, targetX).apply {
            duration     = SNAP_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                botParams.x = it.animatedValue as Int
                windowManager.updateViewLayout(botView, botParams)
            }
            doOnEnd {
                bobbingBaseY = botParams.y
                startBobbing()
            }
            start()
        }
    }

    // ──────────────────────────────────────────────────────────
    // BOBBING
    // ──────────────────────────────────────────────────────────
    private fun startBobbing() {
        bobbingAnimator?.cancel()
        bobbingAnimator = ValueAnimator.ofFloat(-12f, 12f).apply {
            duration     = 1800
            repeatCount  = ValueAnimator.INFINITE
            repeatMode   = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                if (!isDragging) {
                    botParams.y = bobbingBaseY + (it.animatedValue as Float).toInt()
                    windowManager.updateViewLayout(botView, botParams)
                }
            }
            start()
        }
    }

    private fun stopBobbing() {
        bobbingAnimator?.cancel()
        bobbingAnimator = null
        bobbingBaseY = botParams.y
    }

    // ──────────────────────────────────────────────────────────
    // SHOW / HIDE
    // ──────────────────────────────────────────────────────────
    private fun showBot() {
        mainHandler.post {
            if (!botView.isAttachedToWindow) {
                windowManager.addView(touchOverlayView, overlayParams)
                windowManager.addView(botView, botParams)
                startBobbing()
            }
        }
    }

    private fun hideBot() {
        mainHandler.post {
            stopBobbing()
            if (botView.isAttachedToWindow)          windowManager.removeView(botView)
            if (touchOverlayView.isAttachedToWindow)  windowManager.removeView(touchOverlayView)
        }
    }

    // ──────────────────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────────────────
    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    private fun ValueAnimator.doOnEnd(action: () -> Unit) {
        addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationEnd(a: android.animation.Animator)    = action()
            override fun onAnimationStart(a: android.animation.Animator)  {}
            override fun onAnimationCancel(a: android.animation.Animator) {}
            override fun onAnimationRepeat(a: android.animation.Animator) {}
        })
    }

    // ──────────────────────────────────────────────────────────
    // NOTIFICATION
    // ──────────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Jun Floating Bot",
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingBotService::class.java).apply { action = ACTION_HIDE },
            PendingIntent.FLAG_IMMUTABLE
        )
        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jun is active")
            .setContentText("Tap to hide")
            .setSmallIcon(R.drawable.ic_mic)
            .addAction(0, "Hide", stopIntent)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
