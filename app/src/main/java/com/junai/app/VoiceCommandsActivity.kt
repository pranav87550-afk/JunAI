package com.junai.app

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
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

        // App Info Header
        addHeader(container,
            "🤖 JunAI",
            "Your Personal AI Companion for Android",
            "Version 1.0  •  Developer: Pranav  •  Built in Kotlin"
        )

        // What is Jun
        addSection(container, "🌟", "What is Jun AI?", "#FF1744")
        addCard(container,
            "Jun AI is a personal AI assistant built for Android. It understands your questions, learns from your inputs, executes commands, and gets smarter over time. Jun works offline for stored knowledge and online for web search and translation.",
            "#FF1744"
        )

        // Core Features
        addSection(container, "✨", "Core Features", "#FF6D00")

        addFeatureCard(container, "💬", "AI Chat", "#FF6D00",
            "Chat with Jun in natural language. Ask questions, get answers, learn new things. Jun remembers what you teach her and improves with every conversation.")

        addFeatureCard(container, "🎤", "Voice Input (STT)", "#FF6D00",
            "Tap the mic button to speak instead of type. Jun converts your speech to text and automatically sends it — no need to press send.")

        addFeatureCard(container, "🔊", "Voice Output (TTS)", "#FF6D00",
            "Jun can speak her responses aloud. Enable Voice in Settings to hear Jun's answers in real time.")

        addFeatureCard(container, "🧠", "Knowledge Memory", "#FF6D00",
            "Jun stores everything you teach her in a local database. Stored knowledge works completely offline.")

        addFeatureCard(container, "🎓", "Learning Center", "#FF6D00",
            "Train Jun using 4 tabs: Pending (unanswered questions), Knowledge (stored facts), Commands (custom triggers), Analytics (performance stats).")

        addFeatureCard(container, "📝", "Notes", "#FF6D00",
            "Create, view, and delete personal notes. All notes are saved locally on your device.")

        addFeatureCard(container, "✅", "To-Do Lists", "#FF6D00",
            "Add tasks, mark them done, and clear all with confirmation. Jun tracks your pending task count.")

        addFeatureCard(container, "⏰", "Reminder & Alarm", "#FF6D00",
            "Set reminders with a title and time. Alarms fire even after phone restart. Includes lock screen notification support.")

        addFeatureCard(container, "🌐", "Translator", "#FF6D00",
            "Translate text between 15 languages using Google Translate. Results are cached locally for instant repeat translations.")

        addFeatureCard(container, "🎵", "Jun DJ (Music Player)", "#FF6D00",
            "Play songs from your device. Features: song list A-Z, favorites playlist, add to playlist, set ringtone/alarm tone, rename, delete, and next/previous controls.")

        addFeatureCard(container, "🎨", "Sketch Pad", "#FF6D00",
            "Draw on a canvas with color options, brush size, undo/redo, eraser. Auto-save toggle — drawings save to Pictures/JunAI gallery.")

        addFeatureCard(container, "🔢", "Calculator", "#FF6D00",
            "Full-featured calculator for arithmetic operations.")

        addFeatureCard(container, "🤖", "Mini-Jun (Floating Bot)", "#FF6D00",
            "A floating AI assistant overlay that stays on top of all apps. Customize its appearance and behavior in Mini-Jun Settings.")

        addFeatureCard(container, "🛡️", "Permission Centre", "#FF6D00",
            "View and manage all permissions Jun AI needs. Toggle permissions on/off with explanations for each one.")

        addFeatureCard(container, "📊", "Data Management", "#FF6D00",
            "Import knowledge in bulk via JSON. Export and manage your stored data.")

        // Chat Commands
        addSection(container, "💬", "Chat Commands", "#7B68EE")

        addCommandCard(container, "Q=A Format", "Teach Jun a fact directly in chat.\nExample: Python kya hai=Python is a programming language", "#7B68EE")
        addCommandCard(container, "search [query]", "Search the internet for any topic.\nExample: search what is AI", "#7B68EE")
        addCommandCard(container, "open [app name]", "Open any installed app by name.\nExample: open Instagram", "#7B68EE")
        addCommandCard(container, "call [name]", "Call a contact directly.\nExample: call Mom", "#7B68EE")
        addCommandCard(container, "what time is it", "Jun tells you the current time.", "#7B68EE")
        addCommandCard(container, "what is today's date", "Jun tells you today's date.", "#7B68EE")
        addCommandCard(container, "battery", "Check your current battery level.", "#7B68EE")
        addCommandCard(container, "clear chat", "Clear the entire chat history.", "#7B68EE")
        addCommandCard(container, "tell me a joke", "Jun tells you a random joke.", "#7B68EE")
        addCommandCard(container, "flip a coin", "Random heads or tails result.", "#7B68EE")
        addCommandCard(container, "roll a dice", "Random number between 1 and 6.", "#7B68EE")

        // Music Commands
        addSection(container, "🎵", "Music Commands", "#E91E63")

        addCommandCard(container, "play music / play songs", "Opens Jun DJ music player.", "#E91E63")
        addCommandCard(container, "next song", "Skip to the next song.", "#E91E63")
        addCommandCard(container, "previous song", "Go back to the previous song.", "#E91E63")
        addCommandCard(container, "pause music / stop music", "Pause the currently playing song.", "#E91E63")

        // Screen Commands
        addSection(container, "📱", "Screen Open Commands", "#00897B")

        addCommandCard(container, "show notes / open notes", "Opens the Notes screen.", "#00897B")
        addCommandCard(container, "show todo / open todo", "Opens the To-Do Lists screen.", "#00897B")
        addCommandCard(container, "show calculator / open calculator", "Opens the Calculator screen.", "#00897B")
        addCommandCard(container, "show draw / open sketch pad", "Opens the Sketch Pad screen.", "#00897B")
        addCommandCard(container, "show translator / translate", "Opens the Translator screen.", "#00897B")
        addCommandCard(container, "show reminder / set alarm", "Opens the Reminder screen.", "#00897B")
        addCommandCard(container, "show settings / open settings", "Opens the Settings screen.", "#00897B")
        addCommandCard(container, "show music / open jun dj", "Opens the Music Player screen.", "#00897B")

        // Learning Center Guide
        addSection(container, "🎓", "Learning Center Guide", "#4CAF50")

        addCommandCard(container, "Pending Tab", "Shows all questions Jun could not answer. You can train each one as a Knowledge fact or a Command trigger.", "#4CAF50")
        addCommandCard(container, "Knowledge Tab", "View all stored knowledge. Delete individual entries or clear all.", "#4CAF50")
        addCommandCard(container, "Commands Tab", "View all trained custom commands. Each command has a trigger phrase and a response.", "#4CAF50")
        addCommandCard(container, "Analytics Tab", "View Jun's performance stats — total questions, success rate, failure log, and achievements.", "#4CAF50")

        // Training Guide
        addSection(container, "📚", "How to Train Jun?", "#1565C0")

        addCommandCard(container, "Method 1 — Direct Chat", "Type in Q=A format directly in chat.\nExample: Eiffel Tower height=330 meters", "#1565C0")
        addCommandCard(container, "Method 2 — Learning Center", "Go to Learning Center → Pending Tab. Train unanswered questions as Knowledge or Commands. Add aliases and related questions.", "#1565C0")
        addCommandCard(container, "Method 3 — Bulk JSON Import", "Go to Data Management → Import JSON.\nFormat:\n{\"knowledge\": [{\"question\": \"...\", \"answer\": \"...\", \"category\": \"...\"}]}", "#1565C0")

        // Settings Guide
        addSection(container, "⚙️", "Settings Guide", "#FF9800")

        addCommandCard(container, "Voice On/Off", "Enable or disable Jun's text-to-speech voice output.", "#FF9800")
        addCommandCard(container, "Voice Pitch & Speed", "Adjust how Jun sounds using pitch and speed sliders. Preview with the Preview button.", "#FF9800")
        addCommandCard(container, "Clear Chat", "Delete all chat history from Settings.", "#FF9800")
        addCommandCard(container, "Clear Notes / To-Do / Memory", "Individually clear each data category.", "#FF9800")
        addCommandCard(container, "Clear Reminders", "Cancel and delete all active reminders.", "#FF9800")
        addCommandCard(container, "Factory Reset", "Wipe ALL app data including Room database, chat, notes, to-dos, reminders, drawings, and AI knowledge.", "#FF9800")

        // Permission Centre Guide
        addSection(container, "🛡️", "Permission Centre Guide", "#1565C0")

        addCommandCard(container, "Microphone", "Required for voice input (STT). Without this, mic button will not work.", "#1565C0")
        addCommandCard(container, "Phone Calls", "Required to make calls via Jun AI.", "#1565C0")
        addCommandCard(container, "Contacts", "Required to find contact names for calling.", "#1565C0")
        addCommandCard(container, "Notifications", "Required for reminder and alarm alerts.", "#1565C0")
        addCommandCard(container, "Storage / Media Audio", "Required to read music files from device.", "#1565C0")
        addCommandCard(container, "Overlay", "Required for Mini-Jun floating assistant.", "#1565C0")
        addCommandCard(container, "Modify System Settings", "Required to set ringtone and alarm tone.", "#1565C0")
        addCommandCard(container, "Exact Alarms", "Required for precise reminder timing.", "#1565C0")

        // Tips
        addSection(container, "💡", "Tips & Tricks", "#FF6D00")

        addCommandCard(container, "English & Hindi both work", "Jun understands both English and Hindi. Mix them freely.", "#FF6D00")
        addCommandCard(container, "Fuzzy Matching", "Jun uses smart matching — exact spelling is not required.", "#FF6D00")
        addCommandCard(container, "Offline Mode", "All stored knowledge and features work without internet.", "#FF6D00")
        addCommandCard(container, "Auto Learning", "Web search results are automatically saved for future use.", "#FF6D00")
        addCommandCard(container, "Thumbs Down to Improve", "Tap 👎 on any wrong answer — Jun will learn from it.", "#FF6D00")
        addCommandCard(container, "Draw Auto-Save", "Toggle auto-save in Sketch Pad. When OFF, Jun asks before leaving.", "#FF6D00")
        addCommandCard(container, "Music Favorites", "Tap ⋮ on any song → Add to Favorites. Favorites playlist is always available.", "#FF6D00")
        addCommandCard(container, "STT Auto-Send", "After speaking, Jun automatically sends your message.", "#FF6D00")

        // App Info
        addSection(container, "ℹ️", "App Info", "#888888")
        addCard(container,
            "JunAI v1.0\nJun Brain V1\nDeveloper: Pranav\nBuilt with ❤️ in Kotlin for Android\nMin SDK: 24  •  Target SDK: 35",
            "#888888"
        )
    }

    // Crystal header block
    private fun addHeader(parent: LinearLayout, title: String, subtitle: String, info: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.crystal_album_art)
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 0, 0, 24) }
        }
        card.addView(TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#FF1744"))
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setShadowLayer(12f, 0f, 0f, Color.parseColor("#FF1744"))
        })
        card.addView(TextView(this).apply {
            text = subtitle
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 4)
        })
        card.addView(TextView(this).apply {
            text = info
            setTextColor(Color.parseColor("#88FFFFFF"))
            textSize = 11f
            gravity = Gravity.CENTER
        })
        parent.addView(card)
    }

    // Section header with colored accent
    private fun addSection(parent: LinearLayout, emoji: String, title: String, color: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 28, 0, 10) }
        }
        // Colored left bar
        row.addView(android.view.View(this).apply {
            setBackgroundColor(Color.parseColor(color))
            layoutParams = LinearLayout.LayoutParams(4, 48).also { it.setMargins(0, 0, 12, 0) }
        })
        row.addView(TextView(this).apply {
            text = "$emoji  $title"
            setTextColor(Color.parseColor(color))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.05f
            setShadowLayer(8f, 0f, 0f, Color.parseColor(color))
        })
        parent.addView(row)

        // Divider line
        parent.addView(android.view.View(this).apply {
            setBackgroundColor(Color.parseColor(color.replace("#", "#33").take(9)))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also { it.setMargins(0, 0, 0, 8) }
        })
    }

    // Feature card — icon + title + description
    private fun addFeatureCard(parent: LinearLayout, emoji: String, title: String, accentColor: String, description: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.crystal_song_item)
            setPadding(16, 14, 16, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 0, 0, 8) }
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = emoji
            textSize = 18f
            setPadding(0, 0, 10, 0)
        })
        titleRow.addView(TextView(this).apply {
            text = title
            setTextColor(Color.parseColor(accentColor))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        })
        card.addView(titleRow)
        card.addView(TextView(this).apply {
            text = description
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 12f
            setPadding(0, 6, 0, 0)
            lineSpacingMultiplier = 1.4f
        })
        parent.addView(card)
    }

    // Command card — command + description
    private fun addCommandCard(parent: LinearLayout, command: String, description: String, accentColor: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.crystal_menu_item)
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 0, 0, 6) }
        }
        card.addView(TextView(this).apply {
            text = command
            setTextColor(Color.parseColor(accentColor))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = description
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            setPadding(0, 4, 0, 0)
            lineSpacingMultiplier = 1.4f
        })
        parent.addView(card)
    }

    // Plain info card
    private fun addCard(parent: LinearLayout, text: String, accentColor: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            background = getDrawable(R.drawable.crystal_menu_item)
            setPadding(16, 14, 16, 14)
            lineSpacingMultiplier = 1.5f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 0, 0, 8) }
        })
    }
}
