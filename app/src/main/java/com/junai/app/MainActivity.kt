package com.junai.app

import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var chatAdapter: ChatAdapter
    private val messages = mutableListOf<ChatMessage>()
    private val PREFS = "chat_prefs"
    private val KEY = "chat_list"

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var voiceEnabled = false

    private lateinit var speakingIndicator: LinearLayout
    private lateinit var speakDot1: View
    private lateinit var speakDot2: View
    private lateinit var speakDot3: View

    private lateinit var learningRepo: LearningRepository

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
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tts = TextToSpeech(this, this)
        learningRepo = LearningRepository(this)
        voiceEnabled = getSharedPreferences("settings_prefs", MODE_PRIVATE)
            .getBoolean("voice_enabled", false)

        drawerLayout = findViewById(R.id.drawerLayout)
        speakingIndicator = findViewById(R.id.speakingIndicator)
        speakDot1 = findViewById(R.id.speakDot1)
        speakDot2 = findViewById(R.id.speakDot2)
        speakDot3 = findViewById(R.id.speakDot3)

        loadChat()

        findViewById<ImageButton>(R.id.menuButton).setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        findViewById<TextView>(R.id.menuSettings).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.menuMiniJun).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, MiniJunSettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.menuCalculator).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, CalculatorActivity::class.java))
        }
        findViewById<TextView>(R.id.menuNotes).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, NotesActivity::class.java))
        }
        findViewById<TextView>(R.id.menuTodo).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, TodoActivity::class.java))
        }
        findViewById<TextView>(R.id.menuDraw).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, DrawActivity::class.java))
        }
        findViewById<TextView>(R.id.menuTranslator).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, TranslatorActivity::class.java))
        }
        findViewById<TextView>(R.id.menuReminder).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, ReminderActivity::class.java))
        }
        findViewById<TextView>(R.id.menuSongs).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, MusicHomeActivity::class.java))
        }
        findViewById<TextView>(R.id.menuNegative).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, NegativeResponsesActivity::class.java))
        }
        findViewById<TextView>(R.id.menuVoiceCommands).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, VoiceCommandsActivity::class.java))
        }
        findViewById<TextView>(R.id.menuDataManagement).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, DataManagementActivity::class.java))
        }
        findViewById<TextView>(R.id.menuLearningCenter).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, LearningCenterActivity::class.java))
        }

        val recyclerView = findViewById<RecyclerView>(R.id.chatRecyclerView)
        chatAdapter = ChatAdapter(messages, object : ChatActionListener {
            override fun onSpeak(text: String) {
                if (ttsReady) speakText(text)
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
        recyclerView.adapter = chatAdapter
        recyclerView.layoutManager = LinearLayoutManager(this).also {
            it.stackFromEnd = true
        }
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

        val messageInput = findViewById<EditText>(R.id.messageInput)
        findViewById<ImageButton>(R.id.sendButton).setOnClickListener {
            val text = messageInput.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener

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
                    if (voiceEnabled && ttsReady) speakText(response)
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
                    if (voiceEnabled && ttsReady) speakText(response)
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
                    if (voiceEnabled && ttsReady) speakText(response)
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
                            if (voiceEnabled && ttsReady) speakText(response)
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
                    if (target.isNotEmpty()) openApp(target)
                    else chatAdapter.addMessage(ChatMessage("Konsa app open karun? 🤔", isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    saveChat()
                }

                IntentDetector.Intent.CALL_CONTACT -> {
                    if (target.isNotEmpty()) makeCall(target)
                    else chatAdapter.addMessage(ChatMessage("Kisko call karun? 📞", isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    saveChat()
                }

                IntentDetector.Intent.SEARCH_WEB -> {
                    val query = if (target.isNotEmpty()) target else text.replace("search", "").trim()
                    if (query.isNotEmpty()) searchAndRespond(query, recyclerView)
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
                    if (voiceEnabled && ttsReady) speakText(response)
                }

                IntentDetector.Intent.TELL_DATE -> {
                    val date = java.text.SimpleDateFormat("dd MMMM yyyy, EEEE", java.util.Locale.getDefault()).format(java.util.Date())
                    val response = "Aaj ki date hai: $date 📅"
                    chatAdapter.addMessage(ChatMessage(response, isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    saveChat()
                    if (voiceEnabled && ttsReady) speakText(response)
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
                    if (voiceEnabled && ttsReady) speakText(response)
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
                    if (voiceEnabled && ttsReady) speakText(response)
                }

                IntentDetector.Intent.FLIP_COIN -> {
                    val result = if ((0..1).random() == 0) "Heads! 🪙" else "Tails! 🪙"
                    val response = "Coin toss result: $result"
                    chatAdapter.addMessage(ChatMessage(response, isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    saveChat()
                    if (voiceEnabled && ttsReady) speakText(response)
                }

                IntentDetector.Intent.ROLL_DICE -> {
                    val result = (1..6).random()
                    val response = "Dice result: $result 🎲"
                    chatAdapter.addMessage(ChatMessage(response, isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    saveChat()
                    if (voiceEnabled && ttsReady) speakText(response)
                }

                IntentDetector.Intent.UNKNOWN -> {
                    typingIndicator.visibility = View.VISIBLE
                    animateDot(dot1, 0)
                    animateDot(dot2, 150)
                    animateDot(dot3, 300)

                    CoroutineScope(Dispatchers.IO).launch {
                        // Pehle trained commands check karo
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
                                handleTrainedCommand(matchedCmd.intent, matchedCmd.target, text, recyclerView)
                            }
                            return@launch
                        }

                        // Knowledge search
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
                                    if (voiceEnabled && ttsReady) speakText(response)
                                }
                            }, 1500)
                        }
                    }
                }

                else -> {}
            }
        }
    }

    private fun handleTrainedCommand(intent: String, target: String, text: String, recyclerView: RecyclerView) {
        when (intent) {
            "OPEN_APP" -> {
                if (target.isNotEmpty()) openApp(target)
                else openApp(text)
            }
            "CALL_CONTACT" -> {
                if (target.isNotEmpty()) makeCall(target)
            }
            "PLAY_MUSIC" -> {
                startActivity(Intent(this, MusicHomeActivity::class.java))
                chatAdapter.addMessage(ChatMessage("Music open kar rahi hun! 🎵", isUser = false))
            }
            "PAUSE_MUSIC" -> {
                val si = Intent(this, MusicService::class.java)
                si.action = "PAUSE"
                startService(si)
                chatAdapter.addMessage(ChatMessage("Music pause! ⏸️", isUser = false))
            }
            "SET_REMINDER" -> {
                startActivity(Intent(this, ReminderActivity::class.java))
                chatAdapter.addMessage(ChatMessage("Reminder screen open! ⏰", isUser = false))
            }
            "CREATE_NOTE" -> {
                startActivity(Intent(this, NotesActivity::class.java))
                chatAdapter.addMessage(ChatMessage("Notes screen open! 📝", isUser = false))
            }
            "SEARCH_WEB" -> {
                val query = if (target.isNotEmpty()) target else text
                searchAndRespond(query, recyclerView)
            }
            "SHOW_SETTINGS" -> startActivity(Intent(this, SettingsActivity::class.java))
            "TELL_TIME" -> {
                val time = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())
                chatAdapter.addMessage(ChatMessage("Abhi time hai: $time ⏰", isUser = false))
            }
            "TELL_DATE" -> {
                val date = java.text.SimpleDateFormat("dd MMMM yyyy, EEEE", java.util.Locale.getDefault()).format(java.util.Date())
                chatAdapter.addMessage(ChatMessage("Aaj ki date hai: $date 📅", isUser = false))
            }
            "TELL_BATTERY" -> {
                val bm = getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
                val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                chatAdapter.addMessage(ChatMessage("Battery: $level% 🔋", isUser = false))
            }
            else -> {
                chatAdapter.addMessage(ChatMessage("Command samajh nahi aaya! 🤔", isUser = false))
            }
        }
        recyclerView.scrollToPosition(messages.size - 1)
        saveChat()
    }

    override fun onResume() {
        super.onResume()
        voiceEnabled = getSharedPreferences("settings_prefs", MODE_PRIVATE)
            .getBoolean("voice_enabled", false)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
            ttsReady = true
            updateTtsSettings()
        }
    }

    private fun updateTtsSettings() {
        val prefs = getSharedPreferences("settings_prefs", MODE_PRIVATE)
        val pitch = prefs.getFloat("voice_pitch", 1.0f)
        val speed = prefs.getFloat("voice_speed", 1.0f)
        tts.setPitch(pitch)
        tts.setSpeechRate(speed)
    }

    private fun speakText(text: String) {
        if (!ttsReady) return
        updateTtsSettings()

        speakingIndicator.visibility = View.VISIBLE
        animateDot(speakDot1, 0)
        animateDot(speakDot2, 150)
        animateDot(speakDot3, 300)

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    speakingIndicator.visibility = View.GONE
                    speakDot1.clearAnimation()
                    speakDot2.clearAnimation()
                    speakDot3.clearAnimation()
                }
            }
            override fun onError(utteranceId: String?) {
                runOnUiThread { speakingIndicator.visibility = View.GONE }
            }
        })

        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JUN_TTS")
    }

    private fun makeCall(name: String) {
        val permission = android.Manifest.permission.CALL_PHONE
        val contactPermission = android.Manifest.permission.READ_CONTACTS

        if (androidx.core.content.ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(this, contactPermission) != PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(permission, contactPermission), 200)
            chatAdapter.addMessage(ChatMessage("Please grant call & contacts permission!", isUser = false))
            saveChat()
            return
        }

        val cursor = contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )

        var number: String? = null
        cursor?.use {
            while (it.moveToNext()) {
                val contactName = it.getString(0) ?: continue
                if (contactName.lowercase().contains(name.lowercase())) {
                    number = it.getString(1)
                    break
                }
            }
        }

        if (number != null) {
            chatAdapter.addMessage(ChatMessage("Calling $name... 📞", isUser = false))
            saveChat()
            val callIntent = Intent(Intent.ACTION_CALL)
            callIntent.data = android.net.Uri.parse("tel:$number")
            startActivity(callIntent)
        } else {
            chatAdapter.addMessage(ChatMessage("Contact '$name' nahi mila! 😕", isUser = false))
            saveChat()
        }
    }

    private fun openApp(appName: String) {
        val lower = appName.lowercase().trim()
        val pm = packageManager

        val packageMap = mapOf(
            "youtube" to "com.google.android.youtube",
            "instagram" to "com.instagram.android",
            "whatsapp" to "com.whatsapp",
            "facebook" to "com.facebook.katana",
            "snapchat" to "com.snapchat.android",
            "telegram" to "org.telegram.messenger",
            "chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "netflix" to "com.netflix.mediaclient",
            "discord" to "com.discord",
            "amazon" to "com.amazon.mShop.android.shopping",
            "flipkart" to "com.flipkart.android",
            "paytm" to "net.one97.paytm",
            "gpay" to "com.google.android.apps.nbu.paisa.user",
            "hotspot" to "in.startv.hotstar",
            "jiohotstar" to "in.startv.hotstar",
            "drive" to "com.google.android.apps.docs",
            "meet" to "com.google.android.apps.tachyon",
            "threads" to "com.instagram.barcelona",
            "truecaller" to "com.truecaller",
            "deepseek" to "com.deepseek.app",
            "chatgpt" to "com.openai.chatgpt",
            "perplexity" to "ai.perplexity.app.android",
            "meesho" to "com.meesho.supply",
            "mx player" to "com.mxtech.videoplayer.ad",
            "vidmate" to "com.vidmate.yt",
            "claude" to "com.anthropic.claude",
            "maps" to "com.google.android.apps.maps",
            "photos" to "com.google.android.apps.photos",
            "play store" to "com.android.vending",
            "spotify" to "com.spotify.music"
        )

        val packageName = packageMap[lower]
        if (packageName != null) {
            try {
                val launchIntent = pm.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    chatAdapter.addMessage(ChatMessage("Opening $appName ✅", isUser = false))
                    saveChat()
                    return
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val matched = installedApps.firstOrNull {
            pm.getApplicationLabel(it).toString().lowercase().contains(lower)
        }
        if (matched != null) {
            val launchIntent = pm.getLaunchIntentForPackage(matched.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                chatAdapter.addMessage(ChatMessage("Opening $appName ✅", isUser = false))
                saveChat()
                return
            }
        }

        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://search?q=$appName"))
            startActivity(marketIntent)
            chatAdapter.addMessage(ChatMessage("'$appName' not found. Opening Play Store... 🔍", isUser = false))
        } catch (e: Exception) {
            chatAdapter.addMessage(ChatMessage("App '$appName' not found.", isUser = false))
        }
        saveChat()
    }

    private fun searchAndRespond(query: String, recyclerView: RecyclerView) {
        val typingIndicator = findViewById<LinearLayout>(R.id.typingIndicator)
        val dot1 = findViewById<View>(R.id.dot1)
        val dot2 = findViewById<View>(R.id.dot2)
        val dot3 = findViewById<View>(R.id.dot3)

        typingIndicator.visibility = View.VISIBLE
        animateDot(dot1, 0)
        animateDot(dot2, 150)
        animateDot(dot3, 300)

        CoroutineScope(Dispatchers.IO).launch {
            val storedAnswer = AppDatabase.getInstance(this@MainActivity)
                .knowledgeDao()
                .getAnswer(query.lowercase().trim())

            if (storedAnswer != null) {
                withContext(Dispatchers.Main) {
                    typingIndicator.visibility = View.GONE
                    dot1.clearAnimation()
                    dot2.clearAnimation()
                    dot3.clearAnimation()
                    chatAdapter.addMessage(ChatMessage(storedAnswer, isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    saveChat()
                    if (voiceEnabled && ttsReady) speakText(storedAnswer)
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                val client = okhttp3.OkHttpClient()
                val wikiUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/${java.net.URLEncoder.encode(query, "UTF-8")}"
                val wikiRequest = okhttp3.Request.Builder().url(wikiUrl).build()

                client.newCall(wikiRequest).enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        fetchFromDuckDuckGo(query, recyclerView, client, typingIndicator, dot1, dot2, dot3)
                    }
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        val body = response.body?.string()
                        runOnUiThread {
                            try {
                                val json = org.json.JSONObject(body ?: "")
                                val extract = json.optString("extract", "")
                                if (extract.isNotEmpty() && extract.length > 20) {
                                    val sentences = extract.split(". ")
                                    val shortAnswer = sentences.take(3).joinToString(". ").trim()
                                    val finalAnswer = if (!shortAnswer.endsWith(".")) "$shortAnswer." else shortAnswer
                                    storeAndRespond(query, finalAnswer, recyclerView, typingIndicator, dot1, dot2, dot3)
                                } else {
                                    fetchFromDuckDuckGo(query, recyclerView, client, typingIndicator, dot1, dot2, dot3)
                                }
                            } catch (e: Exception) {
                                fetchFromDuckDuckGo(query, recyclerView, client, typingIndicator, dot1, dot2, dot3)
                            }
                        }
                    }
                })
            }
        }
    }

    private fun fetchFromDuckDuckGo(
        query: String,
        recyclerView: RecyclerView,
        client: okhttp3.OkHttpClient,
        typingIndicator: android.view.View,
        dot1: android.view.View,
        dot2: android.view.View,
        dot3: android.view.View
    ) {
        val url = "https://api.duckduckgo.com/?q=${java.net.URLEncoder.encode(query, "UTF-8")}&format=json&no_html=1&skip_disambig=1"
        val request = okhttp3.Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                runOnUiThread {
                    typingIndicator.visibility = android.view.View.GONE
                    dot1.clearAnimation()
                    dot2.clearAnimation()
                    dot3.clearAnimation()
                    chatAdapter.addMessage(ChatMessage("No internet. Can't search right now.", isUser = false))
                    recyclerView.scrollToPosition(messages.size - 1)
                    saveChat()
                }
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string()
                runOnUiThread {
                    try {
                        val json = org.json.JSONObject(body ?: "")
                        val abstractText = json.optString("AbstractText", "")
                        val answer = json.optString("Answer", "")
                        val definition = json.optString("Definition", "")
                        val related = try {
                            json.optJSONArray("RelatedTopics")?.optJSONObject(0)?.optString("Text", "") ?: ""
                        } catch (e: Exception) { "" }

                        val finalAnswer = when {
                            answer.isNotEmpty() -> answer
                            abstractText.isNotEmpty() -> abstractText.split(". ").take(3).joinToString(". ").trim()
                            definition.isNotEmpty() -> definition
                            related.isNotEmpty() -> related.split(". ").take(2).joinToString(". ").trim()
                            else -> "I couldn't find an answer for \"$query\". Try rephrasing!"
                        }
                        storeAndRespond(query, finalAnswer, recyclerView, typingIndicator, dot1, dot2, dot3)
                    } catch (e: Exception) {
                        typingIndicator.visibility = android.view.View.GONE
                        dot1.clearAnimation()
                        dot2.clearAnimation()
                        dot3.clearAnimation()
                        chatAdapter.addMessage(ChatMessage("Search failed. Try again!", isUser = false))
                        recyclerView.scrollToPosition(messages.size - 1)
                        saveChat()
                    }
                }
            }
        })
    }

    private fun storeAndRespond(
        query: String,
        answer: String,
        recyclerView: RecyclerView,
        typingIndicator: android.view.View,
        dot1: android.view.View,
        dot2: android.view.View,
        dot3: android.view.View
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            learningRepo.trainKnowledge(query, answer, "Search")
        }
        typingIndicator.visibility = android.view.View.GONE
        dot1.clearAnimation()
        dot2.clearAnimation()
        dot3.clearAnimation()
        chatAdapter.addMessage(ChatMessage(answer, isUser = false))
        recyclerView.scrollToPosition(messages.size - 1)
        saveChat()
        if (voiceEnabled && ttsReady) speakText(answer)
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
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}
