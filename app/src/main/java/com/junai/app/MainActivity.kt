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
        findViewById<TextView>(R.id.menuUnanswered).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, UnansweredActivity::class.java))
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

        val recyclerView = findViewById<RecyclerView>(R.id.chatRecyclerView)
        chatAdapter = ChatAdapter(messages, object : ChatActionListener {
            override fun onSpeak(text: String) {
                if (ttsReady) speakText(text)
            }

            override fun onThumbsUp(text: String, question: String) {
                // Boost confidence — already stored, just toast
                android.widget.Toast.makeText(this@MainActivity, "👍 Great!", android.widget.Toast.LENGTH_SHORT).show()
            }

            override fun onThumbsDown(text: String, question: String) {
                // Save to negative responses
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

        // Mic button - Speech to Text
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

            val lower = text.lowercase().trim()
            when {
                lower == "clear chat" -> {
                    messages.clear()
                    chatAdapter.notifyDataSetChanged()
                    saveChat()
                    return@setOnClickListener
                }
                lower == "show notes" -> {
                    startActivity(Intent(this, NotesActivity::class.java))
                    return@setOnClickListener
                }
                lower == "show todo" -> {
                    startActivity(Intent(this, TodoActivity::class.java))
                    return@setOnClickListener
                }
                lower == "show translator" -> {
                    startActivity(Intent(this, TranslatorActivity::class.java))
                    return@setOnClickListener
                }
                lower == "show settings" -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    return@setOnClickListener
                }
                lower == "show mini jun settings" -> {
                    startActivity(Intent(this, MiniJunSettingsActivity::class.java))
                    return@setOnClickListener
                }
                lower == "show calculator" -> {
                    startActivity(Intent(this, CalculatorActivity::class.java))
                    return@setOnClickListener
                }
                lower == "show draw" -> {
                    startActivity(Intent(this, DrawActivity::class.java))
                    return@setOnClickListener
                }
                lower == "show reminder" -> {
                    startActivity(Intent(this, ReminderActivity::class.java))
                    return@setOnClickListener
                }
                // Q=A Learning
                lower.contains("=") -> {
                    val parts = text.split("=", limit = 2)
                    if (parts.size == 2) {
                        val question = parts[0].trim()
                        val answer = parts[1].trim()
                        if (question.isNotEmpty() && answer.isNotEmpty()) {
                            // Save to Room DB
                    CoroutineScope(Dispatchers.IO).launch {
                        AppDatabase.getInstance(this@MainActivity)
                            .knowledgeDao()
                            .insert(KnowledgeEntity(question.lowercase().trim(), answer))
                    }

                            val response = "Got it! I'll remember: \"$question\" = \"$answer\" ✅"
                            chatAdapter.addMessage(ChatMessage(response, isUser = false))
                            recyclerView.scrollToPosition(messages.size - 1)
                            saveChat()
                            if (voiceEnabled && ttsReady) speakText(response)
                            return@setOnClickListener
                        }
                    }
                    // Normal flow agar = sirf symbol hai
                    typingIndicator.visibility = View.VISIBLE
                    animateDot(dot1, 0)
                    animateDot(dot2, 150)
                    animateDot(dot3, 300)
                    Handler(Looper.getMainLooper()).postDelayed({
                        typingIndicator.visibility = View.GONE
                        dot1.clearAnimation()
                        dot2.clearAnimation()
                        dot3.clearAnimation()
                        val knownAnswer = UnansweredActivity.getAnswer(this, text)
                        val response = if (knownAnswer != null) knownAnswer
                        else {
                            UnansweredActivity.addQuestion(this, text)
                            "I don't know the answer yet. I've added this to my Unanswered Questions. Please teach me!"
                        }
                        chatAdapter.addMessage(ChatMessage(response, isUser = false))
                        recyclerView.scrollToPosition(messages.size - 1)
                        saveChat()
                        if (voiceEnabled && ttsReady) speakText(response)
                    }, 1500)
                    return@setOnClickListener
                }
                lower.startsWith("search ") -> {
                    val query = text.substring(7).trim()
                    if (query.isNotEmpty()) {
                        searchAndRespond(query, recyclerView)
                    }
                    return@setOnClickListener
                }
                lower.startsWith("open ") -> {
                    val appName = lower.substring(5).trim()
                    openApp(appName)
                    return@setOnClickListener
                }
                else -> {
                    typingIndicator.visibility = View.VISIBLE
                    animateDot(dot1, 0)
                    animateDot(dot2, 150)
                    animateDot(dot3, 300)

                    Handler(Looper.getMainLooper()).postDelayed({
                        typingIndicator.visibility = View.GONE
                        dot1.clearAnimation()
                        dot2.clearAnimation()
                        dot3.clearAnimation()

                        // Check Room DB first
                        var knownAnswer: String? = null
                        val latch = java.util.concurrent.CountDownLatch(1)
                        CoroutineScope(Dispatchers.IO).launch {
                            knownAnswer = AppDatabase.getInstance(this@MainActivity)
                                .knowledgeDao()
                                .getAnswer(text.lowercase().trim())
                            latch.countDown()
                        }
                        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
                        val knownAnswerFinal = knownAnswer
                        val response = if (knownAnswerFinal != null) {
                            knownAnswerFinal
                        } else {
                            // Add to unanswered questions
                            UnansweredActivity.addQuestion(this, text)
                            "I don't know the answer yet. I've added this to my Unanswered Questions. Please teach me!"
                        }

                        chatAdapter.addMessage(ChatMessage(response, isUser = false))
                        recyclerView.scrollToPosition(messages.size - 1)
                        saveChat()

                        if (voiceEnabled && ttsReady) {
                            speakText(response)
                        }
                    }, 1500)
                }
            }
        }
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
                runOnUiThread {
                    speakingIndicator.visibility = View.GONE
                }
            }
        })

        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JUN_TTS")
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
            "hotstar" to "in.startv.hotstar",
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
        // Check Room DB first
        var stored: String? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        CoroutineScope(Dispatchers.IO).launch {
            stored = AppDatabase.getInstance(this@MainActivity)
                .knowledgeDao()
                .getAnswer(query.lowercase().trim())
            latch.countDown()
        }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        val storedAnswer = stored
        if (storedAnswer != null) {
            chatAdapter.addMessage(ChatMessage(storedAnswer, isUser = false))
            recyclerView.scrollToPosition(messages.size - 1)
            saveChat()
            if (voiceEnabled && ttsReady) speakText(storedAnswer)
            return
        }

        val typingIndicator = findViewById<LinearLayout>(R.id.typingIndicator)
        val dot1 = findViewById<View>(R.id.dot1)
        val dot2 = findViewById<View>(R.id.dot2)
        val dot3 = findViewById<View>(R.id.dot3)

        typingIndicator.visibility = View.VISIBLE
        animateDot(dot1, 0)
        animateDot(dot2, 150)
        animateDot(dot3, 300)

        val client = okhttp3.OkHttpClient()

        // Wikipedia API
        val wikiUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/${java.net.URLEncoder.encode(query, "UTF-8")}"
        val wikiRequest = okhttp3.Request.Builder().url(wikiUrl).build()

        client.newCall(wikiRequest).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                // Try DuckDuckGo as fallback
                fetchFromDuckDuckGo(query, recyclerView, client, typingIndicator, dot1, dot2, dot3)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string()
                runOnUiThread {
                    try {
                        val json = org.json.JSONObject(body ?: "")
                        val extract = json.optString("extract", "")

                        if (extract.isNotEmpty() && extract.length > 20) {
                            // Shorten to 2-3 sentences
                            val sentences = extract.split(". ")
                            val shortAnswer = sentences.take(3).joinToString(". ").trim()
                            val finalAnswer = if (!shortAnswer.endsWith(".")) "$shortAnswer." else shortAnswer

                            storeAndRespond(query, finalAnswer, recyclerView, typingIndicator, dot1, dot2, dot3)
                        } else {
                            // Fallback to DuckDuckGo
                            fetchFromDuckDuckGo(query, recyclerView, client, typingIndicator, dot1, dot2, dot3)
                        }
                    } catch (e: Exception) {
                        fetchFromDuckDuckGo(query, recyclerView, client, typingIndicator, dot1, dot2, dot3)
                    }
                }
            }
        })
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
                        val abstract = json.optString("AbstractText", "")
                        val answer = json.optString("Answer", "")
                        val definition = json.optString("Definition", "")
                        val related = try {
                            json.optJSONArray("RelatedTopics")?.optJSONObject(0)?.optString("Text", "") ?: ""
                        } catch (e: Exception) { "" }

                        val finalAnswer = when {
                            answer.isNotEmpty() -> answer
                            abstract.isNotEmpty() -> abstract.split(". ").take(3).joinToString(". ").trim()
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
        // Store in Room DB
        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.getInstance(this@MainActivity)
                .knowledgeDao()
                .insert(KnowledgeEntity(query.lowercase().trim(), answer))
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
