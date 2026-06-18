package com.junai.app

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class VoiceCommandsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_commands)
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        setupManual()
    }

    private fun setupManual() {
        val container = findViewById<LinearLayout>(R.id.manualContainer)

        // About Jun
        addSection(container, "🤖 JunAI Kya Hai?")
        addInfo(container, "JunAI ek personal AI assistant hai jo tumhare Android phone pe kaam karta hai. Ye tumse seekhta hai, tumhari commands samajhta hai, aur time ke saath smarter hota jaata hai. Internet ke bina bhi kaam karta hai!")

        // Features
        addSection(container, "✨ Features")
        addCommand(container, "💬 Chat", "Jun se kuch bhi pucho — wo jawab degi ya seekhne ki koshish karegi")
        addCommand(container, "🧠 Learning Center", "Jun ko train karo — Knowledge, Commands aur Skills sikhao")
        addCommand(container, "📝 Notes", "Notes banao aur save karo")
        addCommand(container, "✅ Todo Lists", "Kaam ki list banao")
        addCommand(container, "🔢 Calculator", "Calculations karo")
        addCommand(container, "🎨 Draw", "Canvas pe draw karo, drawing save hogi Gallery mein")
        addCommand(container, "🌐 Translator", "15 languages mein translate karo — result store hota hai!")
        addCommand(container, "⏰ Reminder", "Alarm set karo — phone restart pe bhi kaam karta hai")
        addCommand(container, "🎵 Jun DJ", "Phone ki songs sunao, playlists banao")
        addCommand(container, "📊 Data Management", "Bulk knowledge import karo JSON se")

        // Chat Commands
        addSection(container, "💬 Chat Commands")
        addCommand(container, "Q=A Format", "Jun ko kuch sikhao\nEx: 'Mera naam kya hai=Pranav'")
        addCommand(container, "search [query]", "Internet pe search karo\nEx: 'search what is AI'")
        addCommand(container, "open [app naam]", "Koi bhi app open karo\nEx: 'open Instagram'")
        addCommand(container, "call [naam]", "Contact ko call karo\nEx: 'call Mom'")
        addCommand(container, "time kya hai", "Current time batao")
        addCommand(container, "date kya hai", "Aaj ki date batao")
        addCommand(container, "battery", "Battery level check karo")
        addCommand(container, "clear chat", "Chat history clear karo")
        addCommand(container, "joke sunao", "Random joke suno")
        addCommand(container, "flip coin", "Heads ya tails")
        addCommand(container, "roll dice", "1-6 random number")

        // Music Commands
        addSection(container, "🎵 Music Commands")
        addCommand(container, "play music / gaana bajao", "Music app open karo")
        addCommand(container, "next song / agla gaana", "Agla song chalao")
        addCommand(container, "previous song / pichla gaana", "Pichla song chalao")
        addCommand(container, "pause music / gaana roko", "Music pause karo")

        // Show Commands
        addSection(container, "📱 Screen Open Commands")
        addCommand(container, "show notes / notes kholo", "Notes screen")
        addCommand(container, "show todo / todo list", "Todo screen")
        addCommand(container, "show calculator / hisaab", "Calculator screen")
        addCommand(container, "show draw / drawing", "Drawing screen")
        addCommand(container, "show translator / translate", "Translator screen")
        addCommand(container, "show reminder / alarm", "Reminder screen")
        addCommand(container, "show settings", "Settings screen")
        addCommand(container, "show music / jun dj", "Music screen")

        // Learning Center
        addSection(container, "🧠 Learning Center Guide")
        addInfo(container, "Learning Center mein 4 tabs hain:")
        addCommand(container, "Pending Tab", "Jo questions Jun nahi jaanti — unhe train karo as Knowledge ya Command")
        addCommand(container, "Knowledge Tab", "Saari stored knowledge dekho aur delete karo")
        addCommand(container, "Commands Tab", "Trained commands dekho aur delete karo")
        addCommand(container, "Analytics Tab", "Stats, achievements aur failure log dekho")

        // Training Guide
        addSection(container, "📚 Jun Ko Kaise Sikhayein?")
        addInfo(container, "Method 1 — Direct chat mein:")
        addCommand(container, "Q=A format", "Ex: 'Python kya hai=Python ek programming language hai'")
        addInfo(container, "Method 2 — Learning Center se:")
        addCommand(container, "Pending items train karo", "Jun jo nahi jaanti usse Knowledge ya Command ke roop mein sikhao. Aliases aur Related Questions bhi add kar sakte ho!")
        addInfo(container, "Method 3 — JSON import:")
        addCommand(container, "Data Management", "Bulk JSON file import karo. Format: {\"knowledge\": [{\"question\": \"...\", \"answer\": \"...\", \"category\": \"...\"}]}")

        // Tips
        addSection(container, "💡 Tips & Tricks")
        addCommand(container, "Hinglish support", "Jun Hindi aur English dono samajhti hai — mix karke bolo!")
        addCommand(container, "Fuzzy matching", "Exact spelling nahi chahiye — Jun similar words bhi samajhti hai")
        addCommand(container, "Offline mode", "Seekhi hui baatein internet ke bina bhi kaam karti hain")
        addCommand(container, "Auto learning", "Search results automatically save hote hain next time ke liye")
        addCommand(container, "Thumbs down", "Galat answer pe 👎 dabao — Jun improve karegi")

        // Version
        addSection(container, "ℹ️ App Info")
        addInfo(container, "JunAI v1.0 — Jun Brain V1\nDeveloper: Pranav\nBuilt with ❤️ in Kotlin for Android")
    }

    private fun addSection(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title
            setTextColor(android.graphics.Color.parseColor("#E53935"))
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 32, 0, 8)
        })
        parent.addView(android.view.View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#E53935"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also { it.setMargins(0, 0, 0, 12) }
        })
    }

    private fun addCommand(parent: LinearLayout, command: String, description: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 4, 0, 4) }
        }
        card.addView(TextView(this).apply {
            text = command
            setTextColor(android.graphics.Color.parseColor("#E53935"))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = description
            setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
            textSize = 13f
            setPadding(0, 4, 0, 0)
        })
        parent.addView(card)
    }

    private fun addInfo(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            textSize = 13f
            setPadding(8, 4, 8, 8)
        })
    }
}
