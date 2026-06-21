package com.junai.app

import android.app.*
import android.content.Intent
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
        const val ACTION_RELOAD_PREFS = "ACTION_RELOAD_PREFS"

        const val PREFS_NAME     = "mini_jun_prefs"
        const val KEY_RANDOM_EYE = "random_eye_enabled"
        const val KEY_TOUCH_EYE  = "touch_eye_enabled"
        const val KEY_ROAMING    = "roaming_enabled"

        const val SLEEPY_THRESHOLD_MS = 60_000L
        const val ANGRY_THRESHOLD_MS  = 600_000L
        const val DIZZY_DRAG_THRESHOLD_MS = 30_000L
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

    private var initialX      = 0
    private var initialY      = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging    = false
    private var touchDownTime = 0L
    private var touchStartedOnVisor = false

    private var bobbingAnimator: ValueAnimator? = null
    private var bobbingBaseY = 0

    private var roamingEnabled = false
    private var roamAnimX: ValueAnimator? = null
    private var roamAnimY: ValueAnimator? = null
    private var roamHandler = Handler(Looper.getMainLooper())
    private var roamRunnable: Runnable? = null
    private var currentRoamX = 0f
    private var currentRoamY = 0f
    private var targetRoamX  = 0f
    private var targetRoamY  = 0f

    private var currentMood = BotMood.SMILE
    private var lastInteractionTime = System.currentTimeMillis()
    private val moodHandler = Handler(Looper.getMainLooper())
    private var moodCheckRunnable: Runnable? = null
    private var dizzyCheckRunnable: Runnable? = null
    private var isDizzyTriggered = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // Prevent overlapping restart calls
    private var isRestarting = false

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE         -> hideBot()
            ACTION_SHOW         -> showBot()
            ACTION_RELOAD_PREFS -> mainHandler.post { reloadPrefsLive() }
            ACTION_SPEAK_START  -> mainHandler.post { botView.startSpeaking() }
            ACTION_SPEAK_STOP   -> mainHandler.post { botView.stopSpeaking() }
            ACTION_EXPRESSION   -> {
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
    }

    override fun onBind(intent: Intent?) = null

    // ──────────────────────────────────────────────────────────
    // PREFS — load once at start, and live-reload without restart
    // ──────────────────────────────────────────────────────────
    private fun loadPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        botView.randomEyeEnabled = prefs.getBoolean(KEY_RANDOM_EYE, false)
        botView.touchEyeEnabled  = false // accessibility-based touch tracking removed
        roamingEnabled           = prefs.getBoolean(KEY_ROAMING, false)
    }

    // Called when settings change — avoids killing/recreating the service
    private fun reloadPrefsLive() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val newRandomEye = prefs.getBoolean(KEY_RANDOM_EYE, false)
        val newRoaming   = prefs.getBoolean(KEY_ROAMING, false)

        botView.randomEyeEnabled = newRandomEye

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
    }

    // ──────────────────────────────────────────────────────────
    // BOT VIEW ATTACH/DETACH — guarded against double-add/remove
    // ──────────────────────────────────────────────────────────
    private fun attachBot() {
        if (botAttached) return
        try {
            windowManager.addView(botView, botParams)
            botAttached = true
        } catch (e: Exception) { /* already attached or invalid state */ }
    }

    private fun detachBot() {
        if (!botAttached) return
        try { windowManager.removeView(botView) } catch (e: Exception) { }
        botAttached = false
    }

    // ──────────────────────────────────────────────────────────
    // MOOD TRACKING
    // ──────────────────────────────────────────────────────────
    private fun registerInteraction() {
        lastInteractionTime = System.currentTimeMillis()
        if (currentMood != BotMood.DIZZY) {
            applyMood(BotMood.SMILE)
        }
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
        } else if (newMood == BotMood.SLEEPY) {
            // Re-trigger sleepy bubble occasionally while still asleep
            if (Random.nextFloat() < 0.15f) showMoodBubble(BotMood.SLEEPY)
        }
    }

    private fun applyMood(newMood: BotMood) {
        if (currentMood == newMood) return
        currentMood = newMood
        botView.setMood(newMood)
        showMoodBubble(newMood)
    }

    private fun showMoodBubble(forMood: BotMood) {
        val options = BotMoodMessages.messages[forMood] ?: return
        val text = options.random()
        val duration = if (forMood == BotMood.SLEEPY) 2600L else 3000L
        showBubble(text, duration)
    }

    // ──────────────────────────────────────────────────────────
    // BOT TOUCH — visor-only hit detection
    // ──────────────────────────────────────────────────────────
    private val botTouchListener = View.OnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val localX = event.x
                val localY = event.y
                touchStartedOnVisor = botView.isTouchOnVisor(localX, localY)

                if (!touchStartedOnVisor) {
                    return@OnTouchListener false // let touch pass through transparent area
                }

                registerInteraction()
                if (roamingEnabled) pauseRoaming() else stopBobbing()
                initialX      = botParams.x
                initialY      = botParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging    = false
                touchDownTime = System.currentTimeMillis()
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
        if (!menuAttached) {
            try {
                windowManager.addView(menuView, menuParams)
                menuAttached = true
            } catch (e: Exception) { return }
        }
        menuView.post {
            menuView.positionRelativeToBot(botParams.x, botParams.y, botSizePx, screenWidth)
            menuView.showMenu()
        }
    }

    private fun detachMenu() {
        if (menuAttached) {
            try { windowManager.removeView(menuView) } catch (e: Exception) { }
            menuAttached = false
        }
        if (!inputAttached) {
            if (roamingEnabled) resumeRoaming() else startBobbing()
        }
    }

    private fun handleMenuAction(action: FloatingMenuView.MenuAction) {
        registerInteraction()
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
                detachMenu()
                openInput()
            }
            FloatingMenuView.MenuAction.SPEAK -> {
                detachMenu()
                startBackgroundSTT()
            }
            FloatingMenuView.MenuAction.BACK -> {
                detachMenu()
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // FLOATING TEXT INPUT
    // ──────────────────────────────────────────────────────────
    private fun openInput() {
        if (!inputAttached) {
            try {
                windowManager.addView(inputView, inputParams)
                inputAttached = true
            } catch (e: Exception) { return }
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
    // MESSAGE BUBBLE — replaces all Toasts
    // ──────────────────────────────────────────────────────────
    private fun showBubble(text: String, durationMs: Long = 2800, speak: Boolean = true) {
        if (botHidden || !botAttached) return

        if (!bubbleAttached) {
            try {
                windowManager.addView(bubbleView, bubbleParams)
                bubbleAttached = true
            } catch (e: Exception) { return }
        }

        bubbleView.post {
            positionBubble()
            bubbleView.showMessage(text, durationMs, speak)
        }
    }

    private fun positionBubble() {
        val botCenterX = botParams.x + botSizePx / 2
        val pointsRight = botCenterX < screenWidth / 2
        bubbleView.pointsRight = pointsRight

        bubbleView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val bubbleW = bubbleView.measuredWidth.coerceAtLeast(80)
        val bubbleH = bubbleView.measuredHeight.coerceAtLeast(70)

        val gap = (10 * resources.displayMetrics.density).toInt()
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
            val permIntent = Intent(this, VoicePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(permIntent)
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showBubble("Voice support nahi mila 😅", 2200)
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
                        showBubble("Sun nahi paayi 😅", 2200)
                    }
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    mainHandler.post {
                        botView.expression = BotExpression.NEURAL
                        detachListening()
                        val spoken = matches?.firstOrNull()
                        if (!spoken.isNullOrEmpty()) {
                            showRecognizedTextThenExecute(spoken)
                        }
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val recIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            try { startListening(recIntent) } catch (e: Exception) {
                showBubble("STT start nahi hui 😅", 2000)
            }
        }
    }

    // Show recognized text in bubble (no TTS for echo), then execute
    private fun showRecognizedTextThenExecute(text: String) {
        showBubble("\"$text\"", 1300, speak = false)
        mainHandler.postDelayed({
            CommandExecutor.execute(this, text)
        }, 1300)
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
            stopRoaming()
            stopBobbing()
            detachMenu()
            detachInput()
            detachBubble()
            detachListening()
            detachBot()
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

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val actionLabel = if (botHidden) "Show" else "Hide"
        val actionToSend = if (botHidden) ACTION_SHOW else ACTION_HIDE

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
