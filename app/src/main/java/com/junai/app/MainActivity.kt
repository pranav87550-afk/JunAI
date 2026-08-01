package com.junai.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import android.graphics.drawable.GradientDrawable
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var chatAdapter: ChatAdapter
    private val messages = mutableListOf<ChatMessage>()
    private val PREFS = "chat_prefs"
    private val KEY = "chat_list"

    private lateinit var ttsHelper: TtsHelper
    private var voiceEnabled = false

    private lateinit var speakingIndicator: LinearLayout
    private lateinit var speakDot1: View
    private lateinit var speakDot2: View
    private lateinit var speakDot3: View

    private lateinit var learningRepo: LearningRepository
    private lateinit var webSearchHelper: WebSearchHelper
    private lateinit var appCommandHandler: AppCommandHandler
    private lateinit var trainedCommandHandler: TrainedCommandHandler
    private lateinit var chatIntentHandler: ChatIntentHandler

    private lateinit var typingIndicator: LinearLayout
    private lateinit var dot1: View
    private lateinit var dot2: View
    private lateinit var dot3: View

    // --- Send button "thinking" state ---
    private lateinit var sendButton: ImageButton
    private lateinit var sendButtonBg: android.graphics.drawable.GradientDrawable
    private var isSendEnabled = true
    // True while Jun is generating a reply — button shows the square stop
    // icon and stays TAPPABLE (unlike isSendEnabled=false, which is used
    // elsewhere for a fully-locked-out state). Tapping while true interrupts
    // generation instead of sending a new message.
    private var isGenerating = false
    private var sendButtonAnimator: android.animation.ValueAnimator? = null
    private var currentCornerRadiusPx = 0f
    private var currentSendColor = 0
    private val sendButtonSafety = Handler(Looper.getMainLooper())
    private var safetyRunnable: Runnable? = null

    // --- Network status ---
    private lateinit var networkMonitor: NetworkStatusMonitor

    private val speechLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    if (result.resultCode == RESULT_OK) {
        val data = result.data
        val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        if (!results.isNullOrEmpty()) {
            val spokenText = results[0]
            val messageInput = findViewById<EditText>(R.id.messageInput)
            messageInput.setText(spokenText)
            // Auto-trigger send immediately after STT
            findViewById<ImageButton>(R.id.sendButton).performClick()
        }
    }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        learningRepo = LearningRepository(this)
        warmUpEnginesInBackground()

        setupNetworkMonitor()
        voiceEnabled = getSharedPreferences("settings_prefs", MODE_PRIVATE)
            .getBoolean("voice_enabled", false)

        drawerLayout = findViewById(R.id.drawerLayout)
        speakingIndicator = findViewById(R.id.speakingIndicator)
        speakDot1 = findViewById(R.id.speakDot1)
        speakDot2 = findViewById(R.id.speakDot2)
        speakDot3 = findViewById(R.id.speakDot3)

        ttsHelper = TtsHelper(this, speakingIndicator, speakDot1, speakDot2, speakDot3,
            onReady = {})  // onInit already sets isReady; nothing extra needed here

        loadChat()

        setupDrawerMenu()

        setupChatUi()

        webSearchHelper = WebSearchHelper(
            activity     = this,
            chatAdapter  = chatAdapter,
            messages     = messages,
            learningRepo = learningRepo,
            onSaveChat   = { saveChat() },
            onSpeak      = { text -> if (voiceEnabled && ttsHelper.isReady) ttsHelper.speak(text) }
        )
        appCommandHandler = AppCommandHandler(
            activity    = this,
            chatAdapter = chatAdapter,
            messages    = messages,
            onSaveChat  = { saveChat() }
        )
        trainedCommandHandler = TrainedCommandHandler(
            activity            = this,
            chatAdapter         = chatAdapter,
            messages            = messages,
            appCommandHandler   = appCommandHandler,
            webSearchHelper     = webSearchHelper,
            onSaveChat          = { saveChat() },
            onEnableSend        = { enableSendButton() }
        )
        chatIntentHandler = ChatIntentHandler(
            activity              = this,
            chatAdapter           = chatAdapter,
            messages              = messages,
            learningRepo          = learningRepo,
            webSearchHelper       = webSearchHelper,
            appCommandHandler     = appCommandHandler,
            trainedCommandHandler = trainedCommandHandler,
            typingIndicator       = typingIndicator,
            dot1                  = dot1,
            dot2                  = dot2,
            dot3                  = dot3,
            speak                 = { text -> if (voiceEnabled && ttsHelper.isReady) ttsHelper.speak(text) },
            onSaveChat            = { saveChat() },
            onEnableSend          = { enableSendButton() }
        )
    }

    // ── Network monitor setup ─────────────────────────────────────────────────
    private fun setupNetworkMonitor() {
        networkMonitor = NetworkStatusMonitor(
            activity = this,
            networkDot = findViewById(R.id.networkDot),
            networkStatusText = findViewById(R.id.networkStatusText)
        )
        networkMonitor.start()
    }

    // ── Drawer menu wiring ────────────────────────────────────────────────────
    private fun setupDrawerMenu() {
        fun drawerNav(id: Int, dest: Class<*>) {
            findViewById<LinearLayout>(id).setOnClickListener {
                drawerLayout.closeDrawer(GravityCompat.START)
                startActivity(Intent(this, dest))
            }
        }

        findViewById<ImageButton>(R.id.menuButton).setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.START))
                drawerLayout.closeDrawer(GravityCompat.START)
            else
                drawerLayout.openDrawer(GravityCompat.START)
        }

        drawerNav(R.id.menuSettings,          SettingsActivity::class.java)
        drawerNav(R.id.menuMiniJun,           MiniJunSettingsActivity::class.java)
        drawerNav(R.id.menuLearningCenter,    LearningCenterActivity::class.java)
        drawerNav(R.id.menuModels,            ModelManagerActivity::class.java)
        drawerNav(R.id.menuNegative,          NegativeResponsesActivity::class.java)
        drawerNav(R.id.menuNotes,             NotesActivity::class.java)
        drawerNav(R.id.menuTodo,              TodoActivity::class.java)
        drawerNav(R.id.menuReminder,          ReminderActivity::class.java)
        drawerNav(R.id.menuTranslator,        TranslatorActivity::class.java)
        drawerNav(R.id.menuCalculator,        CalculatorActivity::class.java)
        drawerNav(R.id.menuDraw,              DrawActivity::class.java)
        drawerNav(R.id.menuSongs,             MusicHomeActivity::class.java)
        drawerNav(R.id.menuPermissionCentre,  PermissionCentreActivity::class.java)
        drawerNav(R.id.menuScreenReading,     com.junai.app.passive.ScreenReadingActivity::class.java)
        drawerNav(R.id.menuManageLearning,    com.junai.app.passive.ManageLearningActivity::class.java)
        drawerNav(R.id.menuDataManagement,    DataManagementActivity::class.java)
        drawerNav(R.id.menuVoiceCommands,     VoiceCommandsActivity::class.java)
    }

    // ── Chat RecyclerView + adapter + mic setup ───────────────────────────────
    private fun setupChatUi() {
        val recyclerView = findViewById<RecyclerView>(R.id.chatRecyclerView)
        chatAdapter = ChatAdapter(messages, object : ChatActionListener {
            override fun onSpeak(text: String) {
                if (ttsHelper.isReady) ttsHelper.speak(text)
            }
            override fun onThumbsUp(text: String, question: String) {
                FeedbackLearner(this@MainActivity).onThumbsUp(text, question)
                android.widget.Toast.makeText(this@MainActivity, "👍 Great!", android.widget.Toast.LENGTH_SHORT).show()
            }
            override fun onThumbsDown(text: String, question: String) {
                FeedbackLearner(this@MainActivity).onThumbsDown(text, question)
                if (question.isNotEmpty()) {
                    NegativeResponsesActivity.addNegative(this@MainActivity, question, text)
                    android.widget.Toast.makeText(this@MainActivity, "👎 Added to Negative Responses!", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        })
        chatAdapter.onBotMessageAdded = {
            // BUGFIX: this used to unconditionally re-enable the send
            // button on every bot message — including the interim "kar
            // rahi hoon..." reply macro replay posts BEFORE it actually
            // starts running. That let the user send another message (or
            // the same trigger again) while the macro/agent task was
            // still mid-flight, and two of them driving the same
            // accessibility gesture channel at once caused replay to
            // sometimes do extra actions and sometimes do nothing (see
            // ChatIntentHandler.isBusy()/MacroReplayEngine.isReplaying).
            // Only auto re-enable when nothing is actually still running;
            // isBusy() is checked again once the real result comes in.
            if (!chatIntentHandler.isBusy()) enableSendButton()
        }
        recyclerView.adapter = chatAdapter
        recyclerView.layoutManager = LinearLayoutManager(this).also { it.stackFromEnd = true }
        if (messages.isNotEmpty()) recyclerView.scrollToPosition(messages.size - 1)

        typingIndicator = findViewById(R.id.typingIndicator)
        dot1 = findViewById(R.id.dot1)
        dot2 = findViewById(R.id.dot2)
        dot3 = findViewById(R.id.dot3)

        findViewById<ImageButton>(R.id.micButton).setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Jun...")
            }
            try {
                speechLauncher.launch(intent)
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "Speech not supported!", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        sendButton = findViewById(R.id.sendButton)
        setupSendButtonMorph()

        val messageInput = findViewById<EditText>(R.id.messageInput)

        sendButton.setOnClickListener {
            // Button is showing the square stop icon — tap interrupts
            // the in-flight response instead of sending a new message.
            if (isGenerating) {
                chatIntentHandler.interruptGeneration()
                enableSendButton()
                return@setOnClickListener
            }

            if (!isSendEnabled) return@setOnClickListener
            val text = messageInput.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener

            disableSendButton()

            chatAdapter.addMessage(ChatMessage(text, isUser = true))
            messageInput.setText("")
            recyclerView.scrollToPosition(messages.size - 1)
            saveChat()

            val intentResult = IntentDetector.detect(text)
            val target = intentResult.params["target"] ?: ""

            chatIntentHandler.handle(intentResult, target, text, recyclerView)
        }
    }

    /** Sets up the GradientDrawable background so we can smoothly morph its shape/color in code. */
    private fun setupSendButtonMorph() {
        val density = resources.displayMetrics.density
        currentCornerRadiusPx = 22f * density
        currentSendColor = android.graphics.Color.parseColor("#E53935")
        sendButtonBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = currentCornerRadiusPx
            setColor(currentSendColor)
        }
        sendButton.background = sendButtonBg
    }

    /**
     * Called when Jun starts generating a reply — morphs the round button
     * into a square stop icon. Stays ENABLED/tappable (unlike the old
     * behavior) so the user can tap it mid-response to interrupt.
     */
    private fun disableSendButton() {
        if (isGenerating) return
        isSendEnabled = false
        isGenerating = true
        sendButton.isEnabled = true
        sendButton.setImageResource(R.drawable.ic_stop)
        morphSendButton(toSquare = true)

        // Safety net: if some rare branch never sends a reply, don't lock the user out forever
        safetyRunnable?.let { sendButtonSafety.removeCallbacks(it) }
        safetyRunnable = Runnable { enableSendButton() }
        sendButtonSafety.postDelayed(safetyRunnable!!, 22000)
    }

    /** Re-enables sending — morphs the button back to its round, active send-arrow state. */
    private fun enableSendButton() {
        if (isSendEnabled) return
        isSendEnabled = true
        isGenerating = false
        sendButton.isEnabled = true
        sendButton.setImageResource(R.drawable.ic_send)
        morphSendButton(toSquare = false)
        safetyRunnable?.let { sendButtonSafety.removeCallbacks(it) }
    }

    private fun morphSendButton(toSquare: Boolean) {
        val density = resources.displayMetrics.density
        val fromRadius = currentCornerRadiusPx
        val toRadius = if (toSquare) 10f * density else 22f * density
        val fromColor = currentSendColor
        val toColor = android.graphics.Color.parseColor(if (toSquare) "#5A2E2C" else "#E53935")

        sendButtonAnimator?.cancel()
        sendButtonAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180 // fast + snappy, not sluggish
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val radius = fromRadius + (toRadius - fromRadius) * f
                val color = android.animation.ArgbEvaluator().evaluate(f, fromColor, toColor) as Int
                sendButtonBg.cornerRadius = radius
                sendButtonBg.setColor(color)
                currentCornerRadiusPx = radius
                currentSendColor = color
            }
            start()
        }
    }

    override fun onResume() {
    super.onResume()
    voiceEnabled = getSharedPreferences("settings_prefs", MODE_PRIVATE)
        .getBoolean("voice_enabled", false)
    if (ttsHelper.isReady) ttsHelper.applySettings()

    // Sync chat with SharedPrefs — handles clear chat from Settings
    //
    // BUGFIX: this resync used to run unconditionally on every
    // onResume(), including while ChatIntentHandler had a generation
    // in flight. That coroutine holds a captured thinkingMsgIndex into
    // this SAME shared `messages` list (ChatAdapter was constructed
    // with this exact list, not a copy) and calls
    // chatAdapter.updateMessageAt(thinkingMsgIndex, ...) once the
    // reply is ready. If the user stepped away mid-generation (e.g. to
    // Learning Center) and this resync fired first — messages.clear()
    // + loadChat() replaces the list's contents in place — that
    // captured index no longer points at the "thinking…" placeholder
    // by the time the background coroutine finishes, so the finished
    // reply silently landed on the wrong message or nowhere visible,
    // even though it still saved correctly to chat_prefs/Knowledge
    // (those don't go through this index). Skipping the resync while
    // busy just defers it to the next onResume() after generation
    // finishes — the in-flight coroutine safely updates the list
    // itself when done, so nothing is lost either way.
    if (!chatIntentHandler.isBusy()) {
        val savedJson = getSharedPreferences("chat_prefs", MODE_PRIVATE)
            .getString("chat_list", "[]") ?: "[]"
        val savedCount = org.json.JSONArray(savedJson).length()
        if (savedCount != messages.size) {
            messages.clear()
            loadChat()
            chatAdapter.notifyDataSetChanged()
        }
        // BUGFIX: this was unconditional before — onResume() fires on
        // ANY return to foreground (screen lock/unlock, notification
        // shade pulled down and released, switching apps and back),
        // not just app launch. If that happened while Qwen3 was still
        // streaming, this unconditionally flipped the button back to
        // the round send-arrow state even though nothing was pressed
        // and generation was still running in the background — exactly
        // what Pranav saw. Now it only re-enables when nothing is
        // actually still busy; isBusy() (qwenStreaming/macro/agent)
        // already exists for the resync check right above, reused here.
        enableSendButton()
    }
    }

    private fun saveChat() {
        val array = JSONArray()
        messages.forEach {
            val obj = JSONObject()
            obj.put("text", it.text)
            obj.put("isUser", it.isUser)
            obj.put("timestamp", it.timestamp)
            // BUGFIX: thinkingText was never saved, so every "Show
            // thinking" dropdown vanished the moment the app restarted
            // — loadChat() below had no data to restore it from. Not
            // persisting isThinking on purpose — a message mid-stream
            // when the app closes should just come back as a finished
            // message with whatever thinking was captured, not stuck
            // showing "Jun is thinking…" forever.
            obj.put("thinkingText", it.thinkingText)
            array.put(obj)
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    private fun loadChat() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val json = prefs.getString(KEY, "[]") ?: "[]"
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            messages.add(ChatMessage(
                text = obj.getString("text"),
                isUser = obj.getBoolean("isUser"),
                timestamp = obj.getLong("timestamp"),
                isThinking = false,
                thinkingText = obj.optString("thinkingText", "")
            ))
        }
    }

    override fun onDestroy() {
        chatIntentHandler.cancelScope()
        webSearchHelper.cancel()
        networkMonitor.stop()
        ttsHelper.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
override fun onBackPressed() {
    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
        drawerLayout.closeDrawer(GravityCompat.START)
    } else {
        super.onBackPressed()
    }
}

    /**
     * Loads all 3 on-device models as soon as the chat screen opens,
     * instead of only lazily on first use (the previous behavior — see
     * ChatIntentHandler.kt/WebSearchHelper.kt's own init() call sites,
     * which still exist as a safety net in case this warm-up hasn't
     * finished yet by the time the user sends a message).
     *
     * WHY: Engine.initialize() "can take up to ~10s per Google's own
     * docs" (ChatEngine's own comment) — with lazy-only loading, that
     * cost lands entirely on whatever message the user happens to send
     * first, making a normal-speed model feel like it takes 10 seconds
     * to answer 3 lines. PocketPal loads its model the moment the app
     * opens, before the user has even started typing, so none of that
     * load time is perceived as part of a response. This matches that.
     *
     * Each engine independently no-ops if its model isn't downloaded
     * yet (see isDownloaded() checks inside each init()), so this is
     * safe to call unconditionally even before the user has visited the
     * Models screen.
     */
    private fun warmUpEnginesInBackground() {
        lifecycleScope.launch(Dispatchers.IO) {
            // Sequential, not parallel (launch{} per engine) — LiteRT-LM's
            // own docs warn model init is a heavy operation, and running
            // all 3 native loads at once would spike memory/CPU contention
            // right at app-open, which is worse for perceived startup
            // smoothness than a few extra seconds of staggered loading.
            com.junai.app.ml.EmbeddingEngine.init(this@MainActivity)
            com.junai.app.ml.FunctionCallEngine.init(this@MainActivity)
            com.junai.app.ml.GGUFChatEngine.init(this@MainActivity)
        }
    }
}
