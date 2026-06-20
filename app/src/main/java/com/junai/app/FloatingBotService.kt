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
import kotlin.math.*
import kotlin.random.Random

class FloatingBotService : Service() {

    companion object {
        const val CHANNEL_ID     = "floating_bot_channel"
        const val NOTIF_ID       = 42
        const val BOT_SIZE_DP    = 210
        const val SNAP_ANIM_MS   = 320L
        const val TAP_MAX_MS     = 200L
        const val TAP_MAX_MOVE   = 12f

        const val ACTION_SHOW        = "ACTION_SHOW"
        const val ACTION_HIDE        = "ACTION_HIDE"
        const val ACTION_EXPRESSION  = "ACTION_EXPRESSION"
        const val EXTRA_EXPRESSION   = "extra_expression"
        const val ACTION_SPEAK_START = "ACTION_SPEAK_START"
        const val ACTION_SPEAK_STOP  = "ACTION_SPEAK_STOP"

        const val PREFS_NAME     = "mini_jun_prefs"
        const val KEY_RANDOM_EYE = "random_eye_enabled"
        const val KEY_TOUCH_EYE  = "touch_eye_enabled"
        const val KEY_ROAMING    = "roaming_enabled"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var botView: FloatingBotView
    private lateinit var botParams: WindowManager.LayoutParams

    private lateinit var menuView: FloatingMenuView
    private lateinit var menuParams: WindowManager.LayoutParams
    private var menuAttached = false

    private var screenWidth  = 0
    private var screenHeight = 0
    private var botSizePx    = 0

    // Drag state
    private var initialX      = 0
    private var initialY      = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging    = false
    private var touchDownTime = 0L

    // Bobbing
    private var bobbingAnimator: ValueAnimator? = null
    private var bobbingBaseY = 0

    // Roaming
    private var roamingEnabled = false
    private var roamAnimX: ValueAnimator? = null
    private var roamAnimY: ValueAnimator? = null
    private var roamHandler = Handler(Looper.getMainLooper())
    private var roamRunnable: Runnable? = null
    private var currentRoamX = 0f
    private var currentRoamY = 0f
    private var targetRoamX  = 0f
    private var targetRoamY  = 0f

    private val mainHandler = Handler(Looper.getMainLooper())

    // ──────────────────────────────────────────────────────────
    // LIFECYCLE
    // ──────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val dm       = resources.displayMetrics
        screenWidth  = dm.widthPixels
        screenHeight = dm.heightPixels
        botSizePx    = (BOT_SIZE_DP * dm.density).toInt()

        val startX = screenWidth  - botSizePx - 24
        val startY = screenHeight - botSizePx - 180

        botParams = WindowManager.LayoutParams(
            botSizePx, botSizePx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
        }

        botView = FloatingBotView(this)
        botView.setOnTouchListener(botTouchListener)

        // ── Menu view — full screen, transparent, focusable when shown ──
        menuView = FloatingMenuView(this)
        menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        menuView.onActionSelected = { action -> handleMenuAction(action) }
        menuView.onDismiss = { detachMenu() }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        botView.randomEyeEnabled = prefs.getBoolean(KEY_RANDOM_EYE, false)
        botView.touchEyeEnabled  = prefs.getBoolean(KEY_TOUCH_EYE,  false)
        roamingEnabled           = prefs.getBoolean(KEY_ROAMING,    false)

        windowManager.addView(botView, botParams)

        currentRoamX = botParams.x.toFloat()
        currentRoamY = botParams.y.toFloat()
        bobbingBaseY = botParams.y

        if (roamingEnabled) startRoaming() else startBobbing()

        JunAccessibilityService.onTouch = { x, y ->
            if (botView.touchEyeEnabled) botView.updateTouchPosition(x, y)
        }
        JunAccessibilityService.onTouchClear = {
            botView.clearTouchPosition()
        }
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
        stopRoaming()
        bobbingAnimator?.cancel()
        botView.destroy()
        if (botView.isAttachedToWindow) windowManager.removeView(botView)
        detachMenu()
        JunAccessibilityService.onTouch      = null
        JunAccessibilityService.onTouchClear = null
    }

    override fun onBind(intent: Intent?) = null

    // ──────────────────────────────────────────────────────────
    // BOT TOUCH — drag + tap-to-open-menu
    // ──────────────────────────────────────────────────────────
    private val botTouchListener = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (roamingEnabled) pauseRoaming() else stopBobbing()

                initialX      = botParams.x
                initialY      = botParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging    = false
                touchDownTime = System.currentTimeMillis()
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY

                if (!isDragging && (abs(dx) > TAP_MAX_MOVE || abs(dy) > TAP_MAX_MOVE)) {
                    isDragging = true
                }

                if (isDragging) {
                    botParams.x = (initialX + dx).toInt()
                    botParams.y = (initialY + dy).toInt()
                    windowManager.updateViewLayout(botView, botParams)
                    botView.setRoamDirection(dx, dy)
                }
                true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val elapsed = System.currentTimeMillis() - touchDownTime
                val wasTap  = !isDragging && elapsed < TAP_MAX_MS

                isDragging = false
                botView.clearRoamDirection()

                if (wasTap) {
                    // Tap detected — open menu
                    if (roamingEnabled) {
                        currentRoamX = botParams.x.toFloat()
                        currentRoamY = botParams.y.toFloat()
                    } else {
                        stopBobbing()
                    }
                    openMenu()
                } else {
                    if (roamingEnabled) {
                        currentRoamX = botParams.x.toFloat()
                        currentRoamY = botParams.y.toFloat()
                        resumeRoaming()
                    } else {
                        snapToEdge()
                    }
                }
                true
            }

            else -> false
        }
    }

    // ──────────────────────────────────────────────────────────
    // MENU
    // ──────────────────────────────────────────────────────────
    private fun openMenu() {
        if (!menuAttached) {
            try {
                windowManager.addView(menuView, menuParams)
                menuAttached = true
            } catch (e: Exception) { return }
        }
        menuView.showMenu()
    }

    private fun detachMenu() {
        if (menuAttached) {
            try { windowManager.removeView(menuView) } catch (e: Exception) { }
            menuAttached = false
        }
        // Resume idle behavior
        if (roamingEnabled) resumeRoaming() else startBobbing()
    }

    private fun handleMenuAction(action: FloatingMenuView.MenuAction) {
        when (action) {
            FloatingMenuView.MenuAction.HIDE -> {
                detachMenu()
                hideBot()
            }
            FloatingMenuView.MenuAction.OPEN_APP -> {
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent?.let { startActivity(it) }
                detachMenu()
            }
            FloatingMenuView.MenuAction.MESSAGE -> {
                // Opens main app with a flag to focus message input
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("focus_input", true)
                }
                startActivity(intent)
                detachMenu()
            }
            FloatingMenuView.MenuAction.SPEAK -> {
                // Opens main app with a flag to trigger voice input
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("trigger_voice", true)
                }
                startActivity(intent)
                detachMenu()
            }
            FloatingMenuView.MenuAction.BACK -> {
                detachMenu()
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // ROAMING
    // ──────────────────────────────────────────────────────────
    private fun startRoaming() {
        stopBobbing()
        roamToNextTarget()
    }

    private fun pauseRoaming() {
        roamAnimX?.cancel()
        roamAnimY?.cancel()
        roamRunnable?.let { roamHandler.removeCallbacks(it) }
    }

    private fun resumeRoaming() {
        roamToNextTarget()
    }

    private fun stopRoaming() {
        roamAnimX?.cancel()
        roamAnimY?.cancel()
        roamRunnable?.let { roamHandler.removeCallbacks(it) }
    }

    private fun roamToNextTarget() {
        if (!roamingEnabled || isDragging || menuAttached) return

        val margin = botSizePx * 0.3f
        targetRoamX = Random.nextFloat() * (screenWidth  - botSizePx - margin * 2) + margin
        targetRoamY = Random.nextFloat() * (screenHeight - botSizePx - margin * 2) + margin

        val dx       = targetRoamX - currentRoamX
        val dy       = targetRoamY - currentRoamY
        val distance = sqrt(dx * dx + dy * dy)

        val speed    = 280f * resources.displayMetrics.density
        val duration = ((distance / speed) * 1000f).toLong().coerceIn(1200, 3500)

        botView.setRoamDirection(dx, dy)

        val fromX = currentRoamX
        val fromY = currentRoamY

        roamAnimX = ValueAnimator.ofFloat(fromX, targetRoamX).apply {
            this.duration = duration
            interpolator  = AccelerateDecelerateInterpolator()
            addUpdateListener {
                if (!isDragging && !menuAttached) {
                    currentRoamX = it.animatedValue as Float
                    botParams.x  = currentRoamX.toInt()
                    try { windowManager.updateViewLayout(botView, botParams) } catch (e: Exception) { }
                }
            }
            start()
        }

        roamAnimY = ValueAnimator.ofFloat(fromY, targetRoamY).apply {
            this.duration = duration
            startDelay    = 80
            interpolator  = AccelerateDecelerateInterpolator()
            addUpdateListener {
                if (!isDragging && !menuAttached) {
                    currentRoamY = it.animatedValue as Float
                    botParams.y  = currentRoamY.toInt()
                    try { windowManager.updateViewLayout(botView, botParams) } catch (e: Exception) { }
                }
            }
            doOnEnd {
                if (!isDragging && !menuAttached) {
                    botView.clearRoamDirection()
                    val pause = Random.nextLong(800, 2500)
                    roamRunnable = Runnable { roamToNextTarget() }
                    roamHandler.postDelayed(roamRunnable!!, pause)
                }
            }
            start()
        }
    }

    // ──────────────────────────────────────────────────────────
    // EDGE SNAP
    // ──────────────────────────────────────────────────────────
    private fun snapToEdge() {
        val midX    = botParams.x + botSizePx / 2
        val targetX = if (midX < screenWidth / 2)
            -botSizePx / 8 else screenWidth - botSizePx + botSizePx / 8
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
        if (menuAttached) return
        bobbingAnimator?.cancel()
        bobbingAnimator = ValueAnimator.ofFloat(-12f, 12f).apply {
            duration     = 1800
            repeatCount  = ValueAnimator.INFINITE
            repeatMode   = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                if (!isDragging && !roamingEnabled && !menuAttached) {
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
        bobbingBaseY    = botParams.y
    }

    // ──────────────────────────────────────────────────────────
    // SHOW / HIDE
    // ──────────────────────────────────────────────────────────
    private fun showBot() {
        mainHandler.post {
            if (!botView.isAttachedToWindow) {
                windowManager.addView(botView, botParams)
                if (roamingEnabled) startRoaming() else startBobbing()
            }
        }
    }

    private fun hideBot() {
        mainHandler.post {
            stopRoaming()
            stopBobbing()
            detachMenu()
            if (botView.isAttachedToWindow) windowManager.removeView(botView)
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jun is active")
            .setContentText("Tap to hide")
            .setSmallIcon(R.drawable.ic_mic)
            .addAction(0, "Hide", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
