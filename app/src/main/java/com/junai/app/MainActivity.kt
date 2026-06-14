package com.junai.app

import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var chatAdapter: ChatAdapter
    private val messages = mutableListOf<ChatMessage>()
    private val PREFS = "chat_prefs"
    private val KEY = "chat_list"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawerLayout)

        loadChat()

        findViewById<ImageButton>(R.id.menuButton).setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        // Menu items
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

        val recyclerView = findViewById<RecyclerView>(R.id.chatRecyclerView)
        chatAdapter = ChatAdapter(messages)
        recyclerView.adapter = chatAdapter
        recyclerView.layoutManager = LinearLayoutManager(this).also {
            it.stackFromEnd = true
        }
        if (messages.isNotEmpty()) recyclerView.scrollToPosition(messages.size - 1)

        val typingIndicator = findViewById<LinearLayout>(R.id.typingIndicator)
        val dot1 = findViewById<View>(R.id.dot1)
        val dot2 = findViewById<View>(R.id.dot2)
        val dot3 = findViewById<View>(R.id.dot3)

        val messageInput = findViewById<EditText>(R.id.messageInput)
        findViewById<ImageButton>(R.id.sendButton).setOnClickListener {
            val text = messageInput.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener

            chatAdapter.addMessage(ChatMessage(text, isUser = true))
            messageInput.setText("")
            recyclerView.scrollToPosition(messages.size - 1)
            saveChat()

            // Handle commands
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
                lower.startsWith("open ") -> {
                    val appName = lower.substring(5).trim()
                    openApp(appName)
                    return@setOnClickListener
                }
                else -> {
            
                    // Normal AI response
                    typingIndicator.visibility = View.VISIBLE
                    animateDot(dot1, 0)
                    animateDot(dot2, 150)
                    animateDot(dot3, 300)

                    Handler(Looper.getMainLooper()).postDelayed({
                        typingIndicator.visibility = View.GONE
                        dot1.clearAnimation()
                        dot2.clearAnimation()
                        dot3.clearAnimation()
                        chatAdapter.addMessage(ChatMessage("In development", isUser = false))
                        recyclerView.scrollToPosition(messages.size - 1)
                        saveChat()
                    }, 1500)
                }
            }
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

        // Fallback — Play Store se open karo
        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://search?q=$appName"))
            startActivity(marketIntent)
            chatAdapter.addMessage(ChatMessage("'$appName' not found. Opening Play Store... 🔍", isUser = false))
        } catch (e: Exception) {
            chatAdapter.addMessage(ChatMessage("App '$appName' not found on this phone.", isUser = false))
        }
        saveChat()
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

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}
