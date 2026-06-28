package com.junai.app

import android.app.*
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import kotlin.math.*

class FloatingBotService : Service() {

    companion object {
        const val BOT_SIZE_DP     = 210
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
        // Note: these are kept here so BotMoodController can reference them
    }

    private lateinit var windowManager: WindowManager
    private lateinit var notificationHelper: BotNotificationHelper
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

    private lateinit var speechController: BotSpeechController

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

    private lateinit var movement: BotMovementController

    // Mood
    private lateinit var moodController: BotMoodController

    // App Sense
    private lateinit var appSenseController: BotAppSenseController

    // Battery warning
    private lateinit var batteryMonitor: BotBatteryMonitor

    // Time greeting — shown once per session
    private var timeGreetingShown     = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // ──────────────────────────────────────────────────────────
    // LIFECYCLE
    // ──────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        notificationHelper = BotNotificationHelper(this) { botHidden }
        notificationHelper.createNotificationChannel()
        startForeground(BotNotificationHelper.NOTIF_ID, notificationHelper.buildNotification())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        CommandExecutor.init(this) { text -> showBubble(text, speak = true) }

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

        movement = BotMovementController(
            context          = this,
            windowManager    = windowManager,
            botView          = botView,
            botParams        = botParams,
            screenWidth      = screenWidth,
            screenHeight     = screenHeight,
            botSizePx        = botSizePx,
            isInterrupted    = { isDragging || menuAttached || inputAttached },
            isOverlayBusy    = { menuAttached || inputAttached },
            onPositionUpdate = { updateAttachedOverlaysPosition() }
        )

        appSenseController = BotAppSenseController(
            context     = this,
            isBotHidden = { botHidden },
            onMessage   = { message -> showBubble(message, 3000, speak = false) }
        )

        moodController = BotMoodController(
            onMoodChanged = { newMood -> botView.setMood(newMood) },
            onShowBubble  = { forMood ->
                val options  = BotMoodMessages.messages[forMood] ?: return@BotMoodController
                val text     = options.random()
                val duration = if (forMood == BotMood.SLEEPY) 2600L else 3000L
                showBubble(text, duration)
            }
        )

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
            moodController.registerInteraction()
            speechController.showRecognizedTextThenExecute(text)
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

        speechController = BotSpeechController(
            service                          = this,
            botView                          = botView,
            listeningView                    = listeningView,
            listeningParams                  = listeningParams,
            windowManager                    = windowManager,
            mainHandler                      = mainHandler,
            onShowBubble                     = { text, dur, speak -> showBubble(text, dur, speak) },
            onExecuteCommand                 = { text -> CommandExecutor.execute(this, text) },
            onListeningParamsPositionChanged = { }
        )

        loadPrefs()
        attachBot()

        movement.syncRoamPosition()
        movement.syncBobbingBase()

        if (movement.roamingEnabled) movement.startRoaming() else movement.startBobbing()

        moodController.start()
        showTimeGreeting()
        batteryMonitor = BotBatteryMonitor(this) { message -> showBubble(message, 3500) }
        batteryMonitor.start()
        if (appSenseController.enabled) appSenseController.start()
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
        movement.stopRoaming()
        movement.stopBobbing()
        botView.destroy()
        bubbleView.destroy()
        listeningView.destroy()
        detachBot()
        detachMenu()
        detachInput()
        detachBubble()
        speechController.detachListening()
        speechController.destroy()
        moodController.stop()
        appSenseController.stop()
        batteryMonitor.stop()
    }

    override fun onBind(intent: Intent?) = null

    // ──────────────────────────────────────────────────────────
    // PREFS
    // ──────────────────────────────────────────────────────────
    private fun loadPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        botView.randomEyeEnabled = prefs.getBoolean(KEY_RANDOM_EYE, false)
        botView.touchEyeEnabled  = false
        movement.roamingEnabled  = prefs.getBoolean(KEY_ROAMING, false)
        appSenseController.enabled = prefs.getBoolean(KEY_APP_SENSE, false)
    }

    private fun reloadPrefsLive() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        botView.randomEyeEnabled = prefs.getBoolean(KEY_RANDOM_EYE, false)

        val newRoaming   = prefs.getBoolean(KEY_ROAMING, false)
        val newAppSense  = prefs.getBoolean(KEY_APP_SENSE, false)

        if (newRoaming != movement.roamingEnabled) {
            movement.roamingEnabled = newRoaming
            if (movement.roamingEnabled) {
                movement.stopBobbing()
                movement.syncRoamPosition()
                movement.startRoaming()
            } else {
                movement.stopRoaming()
                movement.syncBobbingBase()
                movement.startBobbing()
            }
        }

        if (newAppSense != appSenseController.enabled) {
            appSenseController.enabled = newAppSense
            if (appSenseController.enabled) appSenseController.start() else appSenseController.stop()
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

                moodController.registerInteraction()
                if (movement.roamingEnabled) movement.pauseRoaming() else movement.stopBobbing()
                initialX       = botParams.x
                initialY       = botParams.y
                initialTouchX  = event.rawX
                initialTouchY  = event.rawY
                isDragging     = false
                touchDownTime  = System.currentTimeMillis()
                moodController.isDizzyTriggered = false
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!touchStartedOnVisor) return@OnTouchListener false
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY

                if (!isDragging && (abs(dx) > TAP_MAX_MOVE || abs(dy) > TAP_MAX_MOVE)) {
                    isDragging = true
                    moodController.scheduleDizzyCheck { isDragging }
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
                moodController.cancelDizzyCheck()
                botView.clearRoamDirection()

                if (moodController.isDizzyTriggered) {
                    moodController.isDizzyTriggered = false
                    moodController.applyMood(BotMood.SMILE)
                }

                if (wasTap) {
                    val now = System.currentTimeMillis()
                    val isDoubleTap = (now - lastTapTime) < DOUBLE_TAP_MS
                    lastTapTime = now

                    if (isDoubleTap) {
                        // Double tap → STT directly
                        moodController.registerInteraction()
                        speechController.startBackgroundSTT(botParams.x, botParams.y)
                    } else {
                        if (movement.roamingEnabled) {
                            movement.syncRoamPosition()
                        } else {
                            movement.stopBobbing()
                        }
                        openMenu()
                    }
                } else {
                    if (movement.roamingEnabled) {
                        movement.syncRoamPosition()
                        movement.resumeRoaming()
                    } else {
                        movement.snapToEdge()
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
        moodController.registerInteraction()
        movement.moveToCenter {
            if (!menuAttached) {
                try { windowManager.addView(menuView, menuParams); menuAttached = true }
                catch (e: Exception) { return@moveToCenter }
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
        movement.moveBackFromCenter {
            if (!inputAttached) {
                if (movement.roamingEnabled) movement.resumeRoaming() else movement.startBobbing()
            }
        }
    }

    private fun handleMenuAction(action: FloatingMenuView.MenuAction) {
        moodController.registerInteraction()
        when (action) {
            FloatingMenuView.MenuAction.HIDE     -> hideBot()
            FloatingMenuView.MenuAction.OPEN_APP -> {
                packageManager.getLaunchIntentForPackage(packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let { startActivity(it) }
                detachMenu()
            }
            FloatingMenuView.MenuAction.MESSAGE  -> { detachMenu(); openInput() }
            FloatingMenuView.MenuAction.SPEAK    -> { detachMenu(); speechController.startBackgroundSTT(botParams.x, botParams.y) }
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

    /** Flips the roaming preference. The new movement mode actually kicks in once the bot finishes animating back from center (see BotMovementController.moveBackFromCenter). */
    private fun toggleRoaming() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        movement.roamingEnabled = !movement.roamingEnabled
        prefs.edit().putBoolean(KEY_ROAMING, movement.roamingEnabled).apply()
        movement.stopRoaming()
        movement.stopBobbing()
        showBubble(if (movement.roamingEnabled) "Roaming turned ON" else "Roaming turned OFF", speak = false)
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
        if (movement.roamingEnabled) movement.resumeRoaming() else movement.startBobbing()
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
        speechController.updateListeningPosition(botParams.x, botParams.y)
    }

    // ──────────────────────────────────────────────────────────
    // SHOW / HIDE
    // ──────────────────────────────────────────────────────────
    private fun showBot() {
        mainHandler.post {
            botHidden = false
            notificationHelper.updateNotification()
            attachBot()
            if (movement.roamingEnabled) movement.startRoaming() else movement.startBobbing()
        }
    }

    private fun hideBot() {
        mainHandler.post {
            botHidden = true
            notificationHelper.updateNotification()
            movement.stopRoaming(); movement.stopBobbing()
            detachMenu(); detachInput(); detachBubble(); speechController.detachListening()
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
}
