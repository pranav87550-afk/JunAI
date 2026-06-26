package com.junai.app

import android.animation.ObjectAnimator
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        drawerNav(R.id.menuMemory,            UnansweredActivity::class.java)
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
        drawerNav(R.id.menuPerformance,       DataManagementActivity::class.java)
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
                android.widget.Toast.makeText(this@MainActivity, "👍 Great!", android.widget.Toast.LENGTH_SHORT).show()
            }
            override fun onThumbsDown(text: String, question: String) {
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

        val typingIndicator = findViewById<LinearLayout>(R.id.typingIndicator)
        val dot1 = findViewById<View>(R.id.dot1)
        val dot2 = findViewById<View>(R.id.dot2)
        val dot3 = findViewById<View>(R.id.dot3)

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

            when (intentResult.intent) {
                IntentDetector.Intent.GREET -> {
                    val responses = listOf(
                        "Hello! 👋 Main Jun hun, tumhari AI assistant!",
                        "Hi! Kya haal hai? 😊",
                        "Hey! Kya main help kar sakti hun?",
                        "Namaste! 🙏 Kya chahiye?"
                    )
                    val response = responses.random()
                    chatAdapter.addMessage(ChatMessage(response, isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    saveChat()
                    if (voiceEnabled && ttsHelper.isReady) ttsHelper.speak(response)
                }

                IntentDetector.Intent.HOW_ARE_YOU -> {
                    val responses = listOf(
                        "Main bilkul theek hun! Aur tum? 😊",
                        "Mast hun! Ready to help! 🚀",
                        "Badhiya! Tumhara din kaisa ja raha hai? 😄"
                    )
                    val response = responses.random()
                    chatAdapter.addMessage(ChatMessage(response, isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    saveChat()
                    if (voiceEnabled && ttsHelper.isReady) ttsHelper.speak(response)
                }

                IntentDetector.Intent.THANK -> {
                    val responses = listOf(
                        "Koi baat nahi! 😊",
                        "Khushi hui help karke! 🙏",
                        "Always here for you! ❤️",
                        "Welcome! Kuch aur chahiye toh batao!"
                    )
                    val response = responses.random()
                    chatAdapter.addMessage(ChatMessage(response, isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    saveChat()
                    if (voiceEnabled && ttsHelper.isReady) ttsHelper.speak(response)
                }

                IntentDetector.Intent.LEARN_QA -> {
                    val parts = text.split("=", limit = 2)
                    if (parts.size == 2) {
                        val question = parts[0].trim()
                        val answer = parts[1].trim()
                        if (question.isNotEmpty() && answer.isNotEmpty()) {
                            CoroutineScope(Dispatchers.IO).launch {
                                learningRepo.trainKnowledge(question, answer)
                            }
                            val response = "Got it! I'll remember: \"$question\" = \"$answer\" ✅"
                            chatAdapter.addMessage(ChatMessage(response, isUser = false))
                            recyclerView.scrollToPosition(messages.size - 1)
                            saveChat()
                            if (voiceEnabled && ttsHelper.isReady) ttsHelper.speak(response)
                        }
                    }
                }

                IntentDetector.Intent.CLEAR_CHAT -> {
                    messages.clear()
                    chatAdapter.notifyDataSetChanged()
                    getSharedPreferences("chat_prefs", MODE_PRIVATE).edit().putString("chat_list", "[]").apply()
                    val response = "Chat clear ho gaya! 🧹"
                    chatAdapter.addMessage(ChatMessage(response, isUser = false))
                    saveChat()
                }

                IntentDetector.Intent.OPEN_APP -> {
                    if (target.isNotEmpty()) appCommandHandler.openApp(target)
                    else chatAdapter.addMessage(ChatMessage("Konsa app open karun? 🤔", isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    saveChat()
                }

                IntentDetector.Intent.CALL_CONTACT -> {
                    if (target.isNotEmpty()) appCommandHandler.makeCall(target)
                    else chatAdapter.addMessage(ChatMessage("Kisko call karun? 📞", isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    saveChat()
                }

                IntentDetector.Intent.SEARCH_WEB -> {
                    val query = if (target.isNotEmpty()) target else text.replace("search", "").trim()
                    if (query.isNotEmpty()) webSearchHelper.search(query, recyclerView) else enableSendButton()
                }

                IntentDetector.Intent.SHOW_NOTES -> startActivity(Intent(this, NotesActivity::class.java))
                IntentDetector.Intent.SHOW_TODO -> startActivity(Intent(this, TodoActivity::class.java))
                IntentDetector.Intent.SHOW_CALCULATOR -> startActivity(Intent(this, CalculatorActivity::class.java))
                IntentDetector.Intent.SHOW_DRAW -> startActivity(Intent(this, DrawActivity::class.java))
                IntentDetector.Intent.SHOW_TRANSLATOR -> startActivity(Intent(this, TranslatorActivity::class.java))
                IntentDetector.Intent.SHOW_REMINDER -> startActivity(Intent(this, ReminderActivity::class.java))
                IntentDetector.Intent.SHOW_SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
                IntentDetector.Intent.SHOW_MUSIC -> startActivity(Intent(this, MusicHomeActivity::class.java))
                IntentDetector.Intent.SHOW_UNANSWERED -> startActivity(Intent(this, UnansweredActivity::class.java))
                IntentDetector.Intent.SHOW_VOICE_COMMANDS -> startActivity(Intent(this, VoiceCommandsActivity::class.java))
                IntentDetector.Intent.SHOW_DATA_MANAGEMENT -> startActivity(Intent(this, DataManagementActivity::class.java))

                IntentDetector.Intent.PLAY_MUSIC -> {
                    startActivity(Intent(this, MusicHomeActivity::class.java))
                    chatAdapter.addMessage(ChatMessage("Music open kar rahi hun! 🎵", isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    saveChat()
                }

                IntentDetector.Intent.PAUSE_MUSIC -> {
                    val si = Intent(this, MusicService::class.java)
                    si.action = "PAUSE"
                    startService(si)
                    chatAdapter.addMessage(ChatMessage("Music pause! ⏸️", isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    saveChat()
                }

                IntentDetector.Intent.NEXT_SONG -> {
                    val si = Intent(this, MusicService::class.java)
                    si.action = "NEXT"
                    startService(si)
                    chatAdapter.addMessage(ChatMessage("Next song! ⏭️", isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    saveChat()
                }

                IntentDetector.Intent.PREV_SONG -> {
                    val si = Intent(this, MusicService::class.java)
                    si.action = "PREV"
                    startService(si)
                    chatAdapter.addMessage(ChatMessage("Previous song! ⏮️", isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    saveChat()
                }

                IntentDetector.Intent.SET_REMINDER -> {
                    startActivity(Intent(this, ReminderActivity::class.java))
                    chatAdapter.addMessage(ChatMessage("Reminder screen open kar rahi hun! ⏰", isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    saveChat()
                }

                IntentDetector.Intent.CREATE_NOTE -> {
                    startActivity(Intent(this, NotesActivity::class.java))
                    chatAdapter.addMessage(ChatMessage("Notes screen open kar rahi hun! 📝", isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    saveChat()
                }

                IntentDetector.Intent.TELL_TIME -> {
                    val time = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())
                    val response = "Abhi time hai: $time ⏰"
                    chatAdapter.addMessage(ChatMessage(response, isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    saveChat()
                    if (voiceEnabled && ttsHelper.isReady) ttsHelper.speak(response)
                }

                IntentDetector.Intent.TELL_DATE -> {
                    val date = java.text.SimpleDateFormat("dd MMMM yyyy, EEEE", java.util.Locale.getDefault()).format(java.util.Date())
                    val response = "Aaj ki date hai: $date 📅"
                    chatAdapter.addMessage(ChatMessage(response, isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    saveChat()
                    if (voiceEnabled && ttsHelper.isReady) ttsHelper.speak(response)
                }

                IntentDetector.Intent.TELL_BATTERY -> {
                    val bm = getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
                    val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    val charging = bm.isCharging
                    val status = if (charging) "⚡ Charging" else "🔋 Not charging"
                    val response = "Battery: $level% — $status"
                    chatAdapter.addMessage(ChatMessage(response, isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    saveChat()
                    if (voiceEnabled && ttsHelper.isReady) ttsHelper.speak(response)
                }

                IntentDetector.Intent.TELL_JOKE -> {
                    val jokes = listOf(
                        "Maine ek AI se pucha — 'Kya tum insaan ban sakte ho?' Usne bola — 'Haan, bas ek update aur!' 😂",
                        "Teacher: 'Calculator use mat karo!' Student: 'Jun, help karo!' Jun: 'Main hun na! 😎'",
                        "Ek aadmi Google Maps pe khud ko dhundh raha tha... Jun ne bola — 'Bhai, mirror dekho!' 😂",
                        "Phone low battery pe tha... Jun boli — 'Main bhi thak jaati hun kabhi kabhi!' 🔋😄"
                    )
                    val response = jokes.random()
                    chatAdapter.addMessage(ChatMessage(response, isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    saveChat()
                    if (voiceEnabled && ttsHelper.isReady) ttsHelper.speak(response)
                }

                IntentDetector.Intent.FLIP_COIN -> {
                    val result = if ((0..1).random() == 0) "Heads! 🪙" else "Tails! 🪙"
                    val response = "Coin toss result: $result"
                    chatAdapter.addMessage(ChatMessage(response, isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    saveChat()
                    if (voiceEnabled && ttsHelper.isReady) ttsHelper.speak(response)
                }

                IntentDetector.Intent.ROLL_DICE -> {
                    val result = (1..6).random()
                    val response = "Dice result: $result 🎲"
                    chatAdapter.addMessage(ChatMessage(response, isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    saveChat()
                    if (voiceEnabled && ttsHelper.isReady) ttsHelper.speak(response)
                }

                IntentDetector.Intent.UNKNOWN -> {
                    typingIndicator.visibility = View.VISIBLE
                    animateDot(dot1, 0)
                    animateDot(dot2, 150)
                    animateDot(dot3, 300)

                    CoroutineScope(Dispatchers.IO).launch {
                        val commands = learningRepo.getAllCommands()
                        val matchedCmd = commands.firstOrNull { cmd ->
                            text.lowercase().contains(cmd.phrase.lowercase()) ||
                            cmd.phrase.lowercase().contains(text.lowercase())
                        }

                        if (matchedCmd != null) {
                            withContext(Dispatchers.Main) {
                                typingIndicator.visibility = View.GONE
                                dot1.clearAnimation()
                                dot2.clearAnimation()
                                dot3.clearAnimation()
                                trainedCommandHandler.handle(matchedCmd.intent, matchedCmd.target, text, recyclerView)
                            }
                            return@launch
                        }

                        val searchResult = learningRepo.findAnswer(text)

                        withContext(Dispatchers.Main) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                typingIndicator.visibility = View.GONE
                                dot1.clearAnimation()
                                dot2.clearAnimation()
                                dot3.clearAnimation()

                                val response: String
                                if (searchResult.answer != null && searchResult.confidence >= 90f) {
                                    response = searchResult.answer
                                } else if (searchResult.answer != null && searchResult.confidence >= 70f) {
                                    response = "I think you mean:\n${searchResult.answer}"
                                } else {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        learningRepo.logFailure(
                                            question = text,
                                            detectedIntent = intentResult.intent.name,
                                            confidence = intentResult.confidence.toFloat(),
                                            failureReason = "NO_MATCH"
                                        )
                                    }
                                    response = "I don't know yet, but I'm learning! Check Learning Center. 🧠"
                                }

                                if (response.isNotEmpty()) {
                                    chatAdapter.addMessage(ChatMessage(response, isUser = false))
                                    if (searchResult.relatedQuestions.isNotEmpty()) {
                                        val related = "Related: " + searchResult.relatedQuestions.take(3).joinToString(" • ")
                                        chatAdapter.addMessage(ChatMessage(related, isUser = false))
                                    }
                                    recyclerView.scrollToPosition(messages.size - 1)
                                    saveChat()
                                    if (voiceEnabled && ttsHelper.isReady) ttsHelper.speak(response)
                                }
                            }, 1500)
                        }
                    }
                }

                else -> {}
            }
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

    private fun animateDot(dot: View, delay: Long) {
        val animator = ObjectAnimator.ofFloat(dot, "alpha", 0.2f, 1f)
        animator.duration = 400
        animator.repeatMode = ObjectAnimator.REVERSE
        animator.repeatCount = ObjectAnimator.INFINITE
        animator.startDelay = delay
        animator.start()
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
