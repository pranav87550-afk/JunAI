package com.junai.app

import android.app.*
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.animation.ValueAnimator
import androidx.core.app.NotificationCompat
import kotlin.math.*
import kotlin.random.Random

class FloatingBotService : Service() {

    companion object {
        const val CHANNEL_ID      = "floating_bot_channel"
        const val NOTIF_ID        = 42
        const val BOT_SIZE_DP     = 210
        const val SNAP_ANIM_MS    = 320L
        const val MENU_CENTER_ANIM_MS = 260L
        const val TAP_MAX_MS      = 200L
        const val TAP_MAX_MOVE    = 12f
        const val DOUBLE_TAP_MS   = 300L

        const val ACTION_SHOW         = "ACTION_SHOW"
        const val ACTION_HIDE         = "ACTION_HIDE"
        const val ACTION_EXPRESSION   = "ACTION_EXPRESSION"
        const val EXTRA_EXPRESSION    = "extra_expression"
        const val ACTION_SPEAK_START  = "ACTION_SPEAK_START"
        const val ACTION_SPEAK_STOP   = "ACTION_SPEAK_STOP"
        const val ACTION_RELOAD_PREFS = "ACTION_RELOAD_PREFS"

        const val PREFS_NAME          = "mini_jun_prefs"
        const val KEY_RANDOM_EYE      = "random_eye_enabled"
        const val KEY_TOUCH_EYE       = "touch_eye_enabled"
        const val KEY_ROAMING         = "roaming_enabled"
        const val KEY_APP_SENSE       = "app_sense_enabled"

        const val SLEEPY_THRESHOLD_MS     = 60_000L
        const val ANGRY_THRESHOLD_MS      = 600_000L
        const val DIZZY_DRAG_THRESHOLD_MS = 30_000L

        const val BATTERY_WARN_INTERVAL   = 300_000L  // 5 min between battery warnings
        const val APP_SENSE_INTERVAL_MS   = 4_000L    // check foreground app every 4 sec
    }

    private lateinit var windowManager: WindowManager
    private lateinit var botView: FloatingBotView
    private lateinit var botParams: WindowManager.LayoutParams
    private var botAttached = false

    private lateinit var menuView: FloatingMenuView
    private lateinit var menuParams: WindowManager.LayoutParams
    private var menuAttached = false

    private lateinit var inputView: FloatingInputView
    private lateinit var inputParams: WindowManager.LayoutParams
    private var inputAttached = false

    private lateinit var bubbleView: BotBubbleView
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var bubbleAttached = false

    private lateinit var listeningView: ListeningOverlayView
    private lateinit var listeningParams: WindowManager.LayoutParams
    private var listeningAttached = false

    private var speechRecognizer: SpeechRecognizer? = null
    private var botHidden = false

    private var screenWidth  = 0
    private var screenHeight = 0
    private var botSizePx    = 0

    // Drag + tap state
    private var initialX           = 0
    private var initialY           = 0
    private var initialTouchX      = 0f
    private var initialTouchY      = 0f
    private var isDragging         = false
    private var touchDownTime      = 0L
    private var touchStartedOnVisor = false
    private var lastTapTime        = 0L  // for double tap detection

    // Menu center-move state
    private var preMenuBotX        = 0
    private var preMenuBotY        = 0
    private var centerMoveAnimator: ValueAnimator? = null

    // Bobbing
    private var bobbingAnimator: ValueAnimator? = null
    private var bobbingBaseY = 0

    // Roaming
    private var roamingEnabled = false
    private var roamAnimX: ValueAnimator? = null
    private var roamAnimY: ValueAnimator? = null
    private var roamHandler   = Handler(Looper.getMainLooper())
    private var roamRunnable: Runnable? = null
    private var currentRoamX = 0f
    private var currentRoamY = 0f
    private var targetRoamX  = 0f
    private var targetRoamY  = 0f

    // Mood
    private var currentMood           = BotMood.SMILE
    private var lastInteractionTime   = System.currentTimeMillis()
    private val moodHandler           = Handler(Looper.getMainLooper())
    private var moodCheckRunnable: Runnable? = null
    private var dizzyCheckRunnable: Runnable? = null
    private var isDizzyTriggered      = false

    // App Sense
    private var appSenseEnabled       = false
    private val appSenseHandler       = Handler(Looper.getMainLooper())
    private var appSenseRunnable: Runnable? = null

    // Battery warning
    private var lastBatteryWarnTime   = 0L
    private var lastBatteryLevel      = -1

    // Time greeting — shown once per session
    private var timeGreetingShown     = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // ──────────────────────────────────────────────────────────
    // LIFECYCLE
    // ──────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        CommandExecutor.init(this)

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

        // Menu
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

        // Input
        inputView = FloatingInputView(this)
        inputParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        inputView.onSubmit = { text ->
            registerInteraction()
            showRecognizedTextThenExecute(text)
        }
        inputView.onDismiss = { detachInput() }

        // Bubble
        bubbleView = BotBubbleView(this)
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        // Listening overlay
        listeningView = ListeningOverlayView(this)
        listeningParams = WindowManager.LayoutParams(
            botSizePx, botSizePx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        loadPrefs()
        attachBot()

        currentRoamX = botParams.x.toFloat()
        currentRoamY = botParams.y.toFloat()
        bobbingBaseY = botParams.y

        if (roamingEnabled) startRoaming() else startBobbing()

        startMoodTracking()
        showTimeGreeting()
        startBatteryMonitor()
        if (appSenseEnabled) startAppSense()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE          -> hideBot()
            ACTION_SHOW          -> showBot()
            ACTION_RELOAD_PREFS  -> mainHandler.post { reloadPrefsLive() }
            ACTION_SPEAK_START   -> mainHandler.post { botView.startSpeaking() }
            ACTION_SPEAK_STOP    -> mainHandler.post { botView.stopSpeaking() }
            ACTION_EXPRESSION    -> {
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
        bubbleView.destroy()
        listeningView.destroy()
        detachBot()
        detachMenu()
        detachInput()
        detachBubble()
        detachListening()
        speechRecognizer?.destroy()
        stopMoodTracking()
        stopAppSense()
    }

    override fun onBind(intent: Intent?) = null

    // ──────────────────────────────────────────────────────────
    // PREFS
    // ──────────────────────────────────────────────────────────
    private fun loadPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        botView.randomEyeEnabled = prefs.getBoolean(KEY_RANDOM_EYE, false)
        botView.touchEyeEnabled  = false
        roamingEnabled           = prefs.getBoolean(KEY_ROAMING, false)
        appSenseEnabled          = prefs.getBoolean(KEY_APP_SENSE, false)
    }

    private fun reloadPrefsLive() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        botView.randomEyeEnabled = prefs.getBoolean(KEY_RANDOM_EYE, false)

        val newRoaming   = prefs.getBoolean(KEY_ROAMING, false)
        val newAppSense  = prefs.getBoolean(KEY_APP_SENSE, false)

        if (newRoaming != roamingEnabled) {
            roamingEnabled = newRoaming
            if (roamingEnabled) {
                stopBobbing()
                currentRoamX = botParams.x.toFloat()
                currentRoamY = botParams.y.toFloat()
                startRoaming()
            } else {
                stopRoaming()
                bobbingBaseY = botParams.y
                startBobbing()
            }
        }

        if (newAppSense != appSenseEnabled) {
            appSenseEnabled = newAppSense
            if (appSenseEnabled) startAppSense() else stopAppSense()
        }
    }

    // ──────────────────────────────────────────────────────────
    // ATTACH / DETACH BOT
    // ──────────────────────────────────────────────────────────
    private fun attachBot() {
        if (botAttached) return
        try { windowManager.addView(botView, botParams); botAttached = true }
        catch (e: Exception) { }
    }

    private fun detachBot() {
        if (!botAttached) return
        try { windowManager.removeView(botView) } catch (e: Exception) { }
        botAttached = false
    }

    // ──────────────────────────────────────────────────────────
    // TIME GREETING — shown once on service start
    // ──────────────────────────────────────────────────────────
    private fun showTimeGreeting() {
        if (timeGreetingShown) return
        timeGreetingShown = true

        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val greeting = when (hour) {
            in 5..11  -> "Good morning! ☀️"
            in 12..16 -> "Good afternoon! 🌤️"
            in 17..20 -> "Good evening! 🌅"
            else      -> "Working late? 🌙"
        }

        mainHandler.postDelayed({ showBubble(greeting, 3000) }, 1200)
    }

    // ──────────────────────────────────────────────────────────
    // BATTERY MONITOR
    // ──────────────────────────────────────────────────────────
    private fun startBatteryMonitor() {
        val batteryHandler = Handler(Looper.getMainLooper())
        val batteryRunnable = object : Runnable {
            override fun run() {
                checkBattery()
                batteryHandler.postDelayed(this, 60_000L) // check every 1 min
            }
        }
        batteryHandler.postDelayed(batteryRunnable, 30_000L) // first check after 30 sec
    }

    private fun checkBattery() {
        try {
            val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
            val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val isCharging = bm.isCharging
            val now = System.currentTimeMillis()

            if (isCharging) return
            if (now - lastBatteryWarnTime < BATTERY_WARN_INTERVAL) return

            val message = when {
                level <= 5  -> "Critical battery! Plug me in NOW! 🔴🔋"
                level <= 15 -> "Battery very low! $level% 🔋 Charge me please!"
                level <= 20 -> "I'm getting tired... $level% battery left 🔋"
                else        -> return
            }

            if (level != lastBatteryLevel) {
                lastBatteryLevel    = level
                lastBatteryWarnTime = now
                showBubble(message, 3500)
            }
        } catch (e: Exception) { /* ignore */ }
    }

    // ──────────────────────────────────────────────────────────
    // APP SENSE — detect foreground app and react
    // ──────────────────────────────────────────────────────────
    private fun startAppSense() {
        stopAppSense()
        appSenseRunnable = object : Runnable {
            override fun run() {
                if (!appSenseEnabled || botHidden) {
                    appSenseHandler.postDelayed(this, APP_SENSE_INTERVAL_MS)
                    return
                }
                val pkg = AppSenseManager.getForegroundApp(this@FloatingBotService)
                if (!pkg.isNullOrEmpty()) {
                    val message = AppSenseManager.getMessageForApp(pkg)
                    if (!message.isNullOrEmpty()) {
                        showBubble(message, 3000, speak = false)
                    }
                }
                appSenseHandler.postDelayed(this, APP_SENSE_INTERVAL_MS)
            }
        }
        appSenseHandler.postDelayed(appSenseRunnable!!, APP_SENSE_INTERVAL_MS)
    }

    private fun stopAppSense() {
        appSenseRunnable?.let { appSenseHandler.removeCallbacks(it) }
        appSenseRunnable = null
    }

    // ──────────────────────────────────────────────────────────
    // MOOD TRACKING
    // ──────────────────────────────────────────────────────────
    private fun registerInteraction() {
        lastInteractionTime = System.currentTimeMillis()
        if (currentMood != BotMood.DIZZY) applyMood(BotMood.SMILE)
    }

    private fun startMoodTracking() {
        moodCheckRunnable = object : Runnable {
            override fun run() {
                checkMoodByIdleTime()
                moodHandler.postDelayed(this, 10_000L)
            }
        }
        moodHandler.postDelayed(moodCheckRunnable!!, 10_000L)
    }

    private fun stopMoodTracking() {
        moodCheckRunnable?.let { moodHandler.removeCallbacks(it) }
        dizzyCheckRunnable?.let { moodHandler.removeCallbacks(it) }
    }

    private fun checkMoodByIdleTime() {
        if (currentMood == BotMood.DIZZY) return
        val idleTime = System.currentTimeMillis() - lastInteractionTime

        val newMood = when {
            idleTime >= ANGRY_THRESHOLD_MS  -> BotMood.ANGRY
            idleTime >= SLEEPY_THRESHOLD_MS -> BotMood.SLEEPY
            else -> BotMood.SMILE
        }

        if (newMood != currentMood) {
            applyMood(newMood)
        } else if (newMood == BotMood.SLEEPY && Random.nextFloat() < 0.15f) {
            showMoodBubble(BotMood.SLEEPY)
        }
    }

    private fun applyMood(newMood: BotMood) {
        if (currentMood == newMood) return
        currentMood = newMood
        botView.setMood(newMood)
        showMoodBubble(newMood)
    }

    private fun showMoodBubble(forMood: BotMood) {
        val options  = BotMoodMessages.messages[forMood] ?: return
        val text     = options.random()
        val duration = if (forMood == BotMood.SLEEPY) 2600L else 3000L
        showBubble(text, duration)
    }

    // ──────────────────────────────────────────────────────────
    // BOT TOUCH — visor-only + double tap STT
    // ──────────────────────────────────────────────────────────
    private val botTouchListener = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val localX = event.x
                val localY = event.y
                touchStartedOnVisor = botView.isTouchOnVisor(localX, localY)
                if (!touchStartedOnVisor) return@OnTouchListener false

                registerInteraction()
                if (roamingEnabled) pauseRoaming() else stopBobbing()
                initialX       = botParams.x
                initialY       = botParams.y
                initialTouchX  = event.rawX
                initialTouchY  = event.rawY
                isDragging     = false
                touchDownTime  = System.currentTimeMillis()
                isDizzyTriggered = false
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!touchStartedOnVisor) return@OnTouchListener false
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY

                if (!isDragging && (abs(dx) > TAP_MAX_MOVE || abs(dy) > TAP_MAX_MOVE)) {
                    isDragging = true
                    scheduleDizzyCheck()
                }

                if (isDragging) {
                    botParams.x = (initialX + dx).toInt()
                    botParams.y = (initialY + dy).toInt()
                    try { windowManager.updateViewLayout(botView, botParams) } catch (e: Exception) { }
                    botView.setRoamDirection(dx, dy)
                    updateAttachedOverlaysPosition()
                }
                true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (!touchStartedOnVisor) return@OnTouchListener false

                val elapsed = System.currentTimeMillis() - touchDownTime
                val wasTap  = !isDragging && elapsed < TAP_MAX_MS

                isDragging = false
                dizzyCheckRunnable?.let { moodHandler.removeCallbacks(it) }
                botView.clearRoamDirection()

                if (isDizzyTriggered) {
                    isDizzyTriggered = false
                    applyMood(BotMood.SMILE)
                }

                if (wasTap) {
                    val now = System.currentTimeMillis()
                    val isDoubleTap = (now - lastTapTime) < DOUBLE_TAP_MS
                    lastTapTime = now

                    if (isDoubleTap) {
                        // Double tap → STT directly
                        startBackgroundSTT()
                    } else {
                        if (roamingEnabled) {
                            currentRoamX = botParams.x.toFloat()
                            currentRoamY = botParams.y.toFloat()
                        } else {
                            stopBobbing()
                        }
                        openMenu()
                    }
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

    private fun scheduleDizzyCheck() {
        dizzyCheckRunnable?.let { moodHandler.removeCallbacks(it) }
        dizzyCheckRunnable = Runnable {
            if (isDragging) {
                isDizzyTriggered = true
                applyMood(BotMood.DIZZY)
            }
        }
        moodHandler.postDelayed(dizzyCheckRunnable!!, DIZZY_DRAG_THRESHOLD_MS)
    }

    // ──────────────────────────────────────────────────────────
    // MENU
    // ──────────────────────────────────────────────────────────
    private fun openMenu() {
        registerInteraction()
        preMenuBotX = botParams.x
        preMenuBotY = botParams.y

        animateBotToCenter {
            if (!menuAttached) {
                try { windowManager.addView(menuView, menuParams); menuAttached = true }
                catch (e: Exception) { return@animateBotToCenter }
            }
            menuView.post {
                menuView.positionRelativeToBot(botParams.x, botParams.y, botSizePx, screenWidth)
                menuView.showMenu()
            }
        }
    }

    private fun detachMenu() {
        if (menuAttached) {
            try { windowManager.removeView(menuView) } catch (e: Exception) { }
            menuAttached = false
        }
        if (botHidden) return
        animateBotBackFromCenter()
    }

    /** Moves the bot to the exact center of the screen so the orbit menu always has full room to open, regardless of where the bot was docked. */
    private fun animateBotToCenter(onArrived: () -> Unit) {
        centerMoveAnimator?.cancel()
        val targetX = (screenWidth  - botSizePx) / 2
        val targetY = (screenHeight - botSizePx) / 2
        val startX  = botParams.x
        val startY  = botParams.y

        centerMoveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = MENU_CENTER_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                botParams.x = (startX + (targetX - startX) * f).toInt()
                botParams.y = (startY + (targetY - startY) * f).toInt()
                try { windowManager.updateViewLayout(botView, botParams) } catch (e: Exception) { }
                updateAttachedOverlaysPosition()
            }
            doOnEnd { onArrived() }
            start()
        }
    }

    /** Returns the bot to wherever it was sitting before the menu opened, then resumes roaming/bobbing. */
    private fun animateBotBackFromCenter() {
        centerMoveAnimator?.cancel()
        val startX  = botParams.x
        val startY  = botParams.y
        val targetX = preMenuBotX
        val targetY = preMenuBotY

        centerMoveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = MENU_CENTER_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                botParams.x = (startX + (targetX - startX) * f).toInt()
                botParams.y = (startY + (targetY - startY) * f).toInt()
                try { windowManager.updateViewLayout(botView, botParams) } catch (e: Exception) { }
                updateAttachedOverlaysPosition()
            }
            doOnEnd {
                currentRoamX = botParams.x.toFloat()
                currentRoamY = botParams.y.toFloat()
                bobbingBaseY = botParams.y
                if (!inputAttached) {
                    if (roamingEnabled) resumeRoaming() else startBobbing()
                }
            }
            start()
        }
    }

    private fun handleMenuAction(action: FloatingMenuView.MenuAction) {
        registerInteraction()
        when (action) {
            FloatingMenuView.MenuAction.HIDE     -> hideBot()
            FloatingMenuView.MenuAction.OPEN_APP -> {
                packageManager.getLaunchIntentForPackage(packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let { startActivity(it) }
                detachMenu()
            }
            FloatingMenuView.MenuAction.MESSAGE  -> { detachMenu(); openInput() }
            FloatingMenuView.MenuAction.SPEAK    -> { detachMenu(); startBackgroundSTT() }
            FloatingMenuView.MenuAction.BACK     -> detachMenu()
            FloatingMenuView.MenuAction.ROAM     -> { detachMenu(); toggleRoaming() }
            FloatingMenuView.MenuAction.EYE_MOVEMENT -> { detachMenu(); toggleEyeMovement() }
            FloatingMenuView.MenuAction.HELP     -> {
                detachMenu()
                Intent(this, VoiceCommandsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .let { startActivity(it) }
            }
        }
    }

    /** Flips the roaming preference. The new movement mode actually kicks in once the bot finishes animating back from center (see animateBotBackFromCenter). */
    private fun toggleRoaming() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        roamingEnabled = !roamingEnabled
        prefs.edit().putBoolean(KEY_ROAMING, roamingEnabled).apply()
        stopRoaming()
        stopBobbing()
        showBubble(if (roamingEnabled) "Roaming turned ON" else "Roaming turned OFF", speak = false)
    }

    /** Flips random eye movement. This is a live setter on FloatingBotView, so it applies instantly. */
    private fun toggleEyeMovement() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val newValue = !botView.randomEyeEnabled
        prefs.edit().putBoolean(KEY_RANDOM_EYE, newValue).apply()
        botView.randomEyeEnabled = newValue
        showBubble(if (newValue) "Eye movement turned ON" else "Eye movement turned OFF", speak = false)
    }

    // ──────────────────────────────────────────────────────────
    // FLOATING TEXT INPUT
    // ──────────────────────────────────────────────────────────
    private fun openInput() {
        if (!inputAttached) {
            try { windowManager.addView(inputView, inputParams); inputAttached = true }
            catch (e: Exception) { return }
        }
        inputView.show()
    }

    private fun detachInput() {
        if (inputAttached) {
            try { windowManager.removeView(inputView) } catch (e: Exception) { }
            inputAttached = false
        }
        if (roamingEnabled) resumeRoaming() else startBobbing()
    }

    // ──────────────────────────────────────────────────────────
    // BUBBLE
    // ──────────────────────────────────────────────────────────
    private fun showBubble(text: String, durationMs: Long = 2800, speak: Boolean = true) {
        if (botHidden || !botAttached) return
        if (!bubbleAttached) {
            try { windowManager.addView(bubbleView, bubbleParams); bubbleAttached = true }
            catch (e: Exception) { return }
        }
        bubbleView.post {
            positionBubble()
            bubbleView.showMessage(text, durationMs, speak)
        }
    }

    private fun positionBubble() {
        val botCenterX  = botParams.x + botSizePx / 2
        val pointsRight = botCenterX < screenWidth / 2
        bubbleView.pointsRight = pointsRight

        bubbleView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val bubbleW = bubbleView.measuredWidth.coerceAtLeast(80)
        val bubbleH = bubbleView.measuredHeight.coerceAtLeast(70)
        val gap     = (10 * resources.displayMetrics.density).toInt()
        val bubbleX = if (pointsRight) botParams.x + botSizePx + gap else botParams.x - gap - bubbleW
        val bubbleY = (botParams.y + botSizePx / 2 - bubbleH / 2).coerceAtLeast(20)

        bubbleParams.x = bubbleX.coerceIn(0, (screenWidth - bubbleW).coerceAtLeast(0))
        bubbleParams.y = bubbleY
        try { windowManager.updateViewLayout(bubbleView, bubbleParams) } catch (e: Exception) { }
    }

    private fun detachBubble() {
        if (bubbleAttached) {
            try { windowManager.removeView(bubbleView) } catch (e: Exception) { }
            bubbleAttached = false
        }
    }

    private fun updateAttachedOverlaysPosition() {
        if (bubbleAttached) positionBubble()
        if (listeningAttached) {
            listeningParams.x = botParams.x
            listeningParams.y = botParams.y
            try { windowManager.updateViewLayout(listeningView, listeningParams) } catch (e: Exception) { }
        }
    }

    // ──────────────────────────────────────────────────────────
    // LISTENING OVERLAY
    // ──────────────────────────────────────────────────────────
    private fun showListeningUI() {
        if (!listeningAttached) {
            try {
                listeningParams.x = botParams.x
                listeningParams.y = botParams.y
                windowManager.addView(listeningView, listeningParams)
                listeningAttached = true
            } catch (e: Exception) { return }
        }
        listeningView.start()
    }

    private fun detachListening() {
        if (!listeningAttached) return
        listeningView.stop()
        mainHandler.postDelayed({
            if (listeningAttached) {
                try { windowManager.removeView(listeningView) } catch (e: Exception) { }
                listeningAttached = false
            }
        }, 200)
    }

    // ──────────────────────────────────────────────────────────
    // BACKGROUND STT
    // ──────────────────────────────────────────────────────────
    private fun startBackgroundSTT() {
        registerInteraction()

        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, VoicePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showBubble("Voice support not available 😅", 2200)
            return
        }

        botView.expression = BotExpression.LISTENING
        showListeningUI()

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    mainHandler.post { botView.expression = BotExpression.THINKING }
                }
                override fun onError(error: Int) {
                    mainHandler.post {
                        botView.expression = BotExpression.NEURAL
                        detachListening()
                        showBubble("Couldn't hear you 😅", 2200)
                    }
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    mainHandler.post {
                        botView.expression = BotExpression.NEURAL
                        detachListening()
                        val spoken = matches?.firstOrNull()
                        if (!spoken.isNullOrEmpty()) showRecognizedTextThenExecute(spoken)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            try {
                startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                })
            } catch (e: Exception) {
                showBubble("STT failed to start 😅", 2000)
            }
        }
    }

    private fun showRecognizedTextThenExecute(text: String) {
        showBubble("\"$text\"", 1300, speak = false)
        mainHandler.postDelayed({ CommandExecutor.execute(this, text) }, 1300)
    }

    // ──────────────────────────────────────────────────────────
    // ROAMING
    // ──────────────────────────────────────────────────────────
    private fun startRoaming() { stopBobbing(); roamToNextTarget() }
    private fun pauseRoaming() {
        roamAnimX?.cancel(); roamAnimY?.cancel()
        roamRunnable?.let { roamHandler.removeCallbacks(it) }
    }
    private fun resumeRoaming() { roamToNextTarget() }
    private fun stopRoaming() {
        roamAnimX?.cancel(); roamAnimY?.cancel()
        roamRunnable?.let { roamHandler.removeCallbacks(it) }
    }

    private fun roamToNextTarget() {
        if (!roamingEnabled || isDragging || menuAttached || inputAttached) return

        val margin = if (Random.nextFloat() < 0.3f) botSizePx * 0.05f else botSizePx * 0.15f
        targetRoamX = Random.nextFloat() * (screenWidth  - botSizePx - margin * 2) + margin
        targetRoamY = Random.nextFloat() * (screenHeight - botSizePx - margin * 2) + margin

        val dx       = targetRoamX - currentRoamX
        val dy       = targetRoamY - currentRoamY
        val distance = sqrt(dx * dx + dy * dy)
        val speed    = 280f * resources.displayMetrics.density
        val duration = ((distance / speed) * 1000f).toLong().coerceIn(1000, 4200)

        botView.setRoamDirection(dx, dy)
        val fromX = currentRoamX
        val fromY = currentRoamY

        roamAnimX = ValueAnimator.ofFloat(fromX, targetRoamX).apply {
            this.duration = duration
            interpolator  = AccelerateDecelerateInterpolator()
            addUpdateListener {
                if (!isDragging && !menuAttached && !inputAttached) {
                    currentRoamX = it.animatedValue as Float
                    botParams.x  = currentRoamX.toInt()
                    try { windowManager.updateViewLayout(botView, botParams) } catch (e: Exception) { }
                    updateAttachedOverlaysPosition()
                }
            }
            start()
        }

        roamAnimY = ValueAnimator.ofFloat(fromY, targetRoamY).apply {
            this.duration = duration
            startDelay    = 60
            interpolator  = AccelerateDecelerateInterpolator()
            addUpdateListener {
                if (!isDragging && !menuAttached && !inputAttached) {
                    currentRoamY = it.animatedValue as Float
                    botParams.y  = currentRoamY.toInt()
                    try { windowManager.updateViewLayout(botView, botParams) } catch (e: Exception) { }
                    updateAttachedOverlaysPosition()
                }
            }
            doOnEnd {
                if (!isDragging && !menuAttached && !inputAttached) {
                    botView.clearRoamDirection()
                    val pause = Random.nextLong(500, 2000)
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
                try { windowManager.updateViewLayout(botView, botParams) } catch (e: Exception) { }
                updateAttachedOverlaysPosition()
            }
            doOnEnd { bobbingBaseY = botParams.y; startBobbing() }
            start()
        }
    }

    // ──────────────────────────────────────────────────────────
    // BOBBING
    // ──────────────────────────────────────────────────────────
    private fun startBobbing() {
        if (menuAttached || inputAttached) return
        bobbingAnimator?.cancel()
        bobbingAnimator = ValueAnimator.ofFloat(-12f, 12f).apply {
            duration     = 1800
            repeatCount  = ValueAnimator.INFINITE
            repeatMode   = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                if (!isDragging && !roamingEnabled && !menuAttached && !inputAttached) {
                    botParams.y = bobbingBaseY + (it.animatedValue as Float).toInt()
                    try { windowManager.updateViewLayout(botView, botParams) } catch (e: Exception) { }
                    updateAttachedOverlaysPosition()
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
            botHidden = false
            updateNotification()
            attachBot()
            if (roamingEnabled) startRoaming() else startBobbing()
        }
    }

    private fun hideBot() {
        mainHandler.post {
            botHidden = true
            updateNotification()
            stopRoaming(); stopBobbing()
            detachMenu(); detachInput(); detachBubble(); detachListening()
            detachBot()
        }
    }

    // ──────────────────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────────────────
    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

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
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val actionToSend = if (botHidden) ACTION_SHOW else ACTION_HIDE
        val actionLabel  = if (botHidden) "Show" else "Hide"
        val actionIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingBotService::class.java).apply { action = actionToSend },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jun is active")
            .setContentText(if (botHidden) "Jun hidden — tap to show" else "Tap to hide")
            .setSmallIcon(R.drawable.ic_mic)
            .addAction(0, actionLabel, actionIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
