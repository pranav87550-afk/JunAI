package com.junai.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class FloatingBotService : Service() {

    companion object {
        const val CHANNEL_ID = "floating_bot_channel"
        const val NOTIF_ID   = 42
        const val BOT_SIZE_DP = 130        // medium size
        const val SNAP_ANIM_MS = 320L

        // Intent actions
        const val ACTION_SHOW       = "ACTION_SHOW"
        const val ACTION_HIDE       = "ACTION_HIDE"
        const val ACTION_EXPRESSION = "ACTION_EXPRESSION"
        const val EXTRA_EXPRESSION  = "extra_expression"
        const val ACTION_SPEAK_START = "ACTION_SPEAK_START"
        const val ACTION_SPEAK_STOP  = "ACTION_SPEAK_STOP"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var botView: FloatingBotView
    private lateinit var params: WindowManager.LayoutParams

    private var screenWidth  = 0
    private var screenHeight = 0

    // Drag state
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    // Bobbing
    private var bobbingAnimator: ValueAnimator? = null
    private var bobbingBaseY = 0          // Y position when drag ended

    // Handler for main thread UI updates
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

        // Initial position — bottom right
        val startX = screenWidth  - sizePx - 24
        val startY = screenHeight - sizePx - 180

        params = WindowManager.LayoutParams(
            sizePx, sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
        }

        botView = FloatingBotView(this)
        botView.setOnTouchListener(touchListener)
        windowManager.addView(botView, params)

        bobbingBaseY = params.y
        startBobbing()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> hideBot()
            ACTION_SHOW -> showBot()
            ACTION_SPEAK_START -> mainHandler.post { botView.startSpeaking() }
            ACTION_SPEAK_STOP  -> mainHandler.post { botView.stopSpeaking() }
            ACTION_EXPRESSION  -> {
                val name = intent.getStringExtra(EXTRA_EXPRESSION) ?: return START_STICKY
                val expr = try { BotExpression.valueOf(name) } catch (e: Exception) { BotExpression.NEURAL }
                mainHandler.post { botView.expression = expr }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        bobbingAnimator?.cancel()
        botView.destroy()
        if (botView.isAttachedToWindow) windowManager.removeView(botView)
    }

    override fun onBind(intent: Intent?) = null

    // ──────────────────────────────────────────────────────────
    // TOUCH + DRAG
    // ──────────────────────────────────────────────────────────
    private val touchListener = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                stopBobbing()
                initialX      = params.x
                initialY      = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging    = false

                // Pass touch to eyes
                botView.updateTouchPosition(event.rawX, event.rawY)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY

                if (!isDragging && (abs(dx) > 8f || abs(dy) > 8f)) {
                    isDragging = true
                }

                if (isDragging) {
                    params.x = (initialX + dx).toInt()
                    params.y = (initialY + dy).toInt()
                    windowManager.updateViewLayout(botView, params)
                }

                botView.updateTouchPosition(event.rawX, event.rawY)
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    snapToEdge()
                } else {
                    // Tap — toggle expression as demo
                    botView.clearTouchPosition()
                }
                isDragging = false
                true
            }

            else -> false
        }
    }

    // ──────────────────────────────────────────────────────────
    // EDGE SNAP
    // ──────────────────────────────────────────────────────────
    private fun snapToEdge() {
        val botSize  = params.width
        val midX     = params.x + botSize / 2
        val targetX  = if (midX < screenWidth / 2) 0 else screenWidth - botSize

        val startX   = params.x
        ValueAnimator.ofInt(startX, targetX).apply {
            duration    = SNAP_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.x = it.animatedValue as Int
                windowManager.updateViewLayout(botView, params)
            }
            doOnEnd {
                bobbingBaseY = params.y
                startBobbing()
            }
            start()
        }
    }

    // ──────────────────────────────────────────────────────────
    // BOBBING ANIMATION
    // ──────────────────────────────────────────────────────────
    private fun startBobbing() {
        bobbingAnimator?.cancel()
        val amplitude = 12   // pixels up/down
        bobbingAnimator = ValueAnimator.ofFloat(-amplitude.toFloat(), amplitude.toFloat()).apply {
            duration      = 1800
            repeatCount   = ValueAnimator.INFINITE
            repeatMode    = ValueAnimator.REVERSE
            interpolator  = AccelerateDecelerateInterpolator()
            addUpdateListener {
                if (!isDragging) {
                    params.y = bobbingBaseY + (it.animatedValue as Float).toInt()
                    windowManager.updateViewLayout(botView, params)
                }
            }
            start()
        }
    }

    private fun stopBobbing() {
        bobbingAnimator?.cancel()
        bobbingAnimator = null
        bobbingBaseY = params.y   // lock current Y as new base
    }

    // ──────────────────────────────────────────────────────────
    // SHOW / HIDE
    // ──────────────────────────────────────────────────────────
    private fun showBot() {
        mainHandler.post {
            if (!botView.isAttachedToWindow) {
                windowManager.addView(botView, params)
                startBobbing()
            }
        }
    }

    private fun hideBot() {
        mainHandler.post {
            stopBobbing()
            if (botView.isAttachedToWindow) windowManager.removeView(botView)
        }
    }

    // ──────────────────────────────────────────────────────────
    // NOTIFICATION (required for foreground service)
    // ──────────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jun Floating Bot",
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jun is active")
            .setContentText("Tap to hide")
            .setSmallIcon(R.drawable.ic_mic)
            .addAction(0, "Hide", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    // Extension — doOnEnd helper
    private fun ValueAnimator.doOnEnd(action: () -> Unit) {
        addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationEnd(a: android.animation.Animator) = action()
            override fun onAnimationStart(a: android.animation.Animator) {}
            override fun onAnimationCancel(a: android.animation.Animator) {}
            override fun onAnimationRepeat(a: android.animation.Animator) {}
        })
    }
}
