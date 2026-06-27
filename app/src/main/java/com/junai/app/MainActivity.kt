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
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import android.graphics.drawable.GradientDrawable

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
        drawerNav(R.id.menuNegative,          NegativeResponsesActivity::class.java)
        drawerNav(R.id.menuNotes,             NotesActivity::class.java)
        drawerNav(R.id.menuTodo,              TodoActivity::class.java)
        drawerNav(R.id.menuReminder,          ReminderActivity::class.java)
        drawerNav(R.id.menuTranslator,        TranslatorActivity::class.java)
        drawerNav(R.id.menuCalculator,        CalculatorActivity::class.java)
        drawerNav(R.id.menuDraw,              DrawActivity::class.java)
        drawerNav(R.id.menuSongs,             MusicHomeActivity::class.java)
        drawerNav(R.id.menuPermissionCentre,  PermissionCentreActivity::class.java)
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
        chatAdapter.onBotMessageAdded = { enableSendButton() }
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

    /** Disables sending while Jun is "thinking" — morphs the round button into a square. */
    private fun disableSendButton() {
        if (!isSendEnabled) return
        isSendEnabled = false
        sendButton.isEnabled = false
        morphSendButton(toSquare = true)

        // Safety net: if some rare branch never sends a reply, don't lock the user out forever
        safetyRunnable?.let { sendButtonSafety.removeCallbacks(it) }
        safetyRunnable = Runnable { enableSendButton() }
        sendButtonSafety.postDelayed(safetyRunnable!!, 22000)
    }

    /** Re-enables sending — morphs the button back to its round, active state. */
    private fun enableSendButton() {
        if (isSendEnabled) return
        isSendEnabled = true
        sendButton.isEnabled = true
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
    val savedJson = getSharedPreferences("chat_prefs", MODE_PRIVATE)
        .getString("chat_list", "[]") ?: "[]"
    val savedCount = org.json.JSONArray(savedJson).length()
    if (savedCount != messages.size) {
        messages.clear()
        loadChat()
        chatAdapter.notifyDataSetChanged()
    }
    enableSendButton()
    }

    private fun saveChat() {
        val array = JSONArray()
        messages.forEach {
            val obj = JSONObject()
            obj.put("text", it.text)
            obj.put("isUser", it.isUser)
            obj.put("timestamp", it.timestamp)
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
                obj.getString("text"),
                obj.getBoolean("isUser"),
                obj.getLong("timestamp")
            ))
        }
    }

    override fun onDestroy() {
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
}
