package com.junai.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private val PREFS = "settings_prefs"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        tts = TextToSpeech(this, this)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        // Voice Switch
        val voiceSwitch = findViewById<Switch>(R.id.voiceSwitch)
        voiceSwitch.isChecked = prefs.getBoolean("voice_enabled", false)
        voiceSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("voice_enabled", isChecked).apply()
        }

        // Pitch SeekBar
        val pitchSeekBar = findViewById<SeekBar>(R.id.pitchSeekBar)
        pitchSeekBar.max = 100
        pitchSeekBar.progress = (prefs.getFloat("voice_pitch", 1.0f) * 50).toInt()
        pitchSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pitch = progress / 50f
                prefs.edit().putFloat("voice_pitch", pitch).apply()
                if (ttsReady) tts.setPitch(pitch)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Speed SeekBar
        val speedSeekBar = findViewById<SeekBar>(R.id.speedSeekBar)
        speedSeekBar.max = 100
        speedSeekBar.progress = (prefs.getFloat("voice_speed", 1.0f) * 50).toInt()
        speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = progress / 50f
                prefs.edit().putFloat("voice_speed", speed).apply()
                if (ttsReady) tts.setSpeechRate(speed)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Preview Voice
        findViewById<View>(R.id.btnPreviewVoice).setOnClickListener {
            if (ttsReady) {
                tts.speak("Hello! I am Jun, your AI assistant.", TextToSpeech.QUEUE_FLUSH, null, "PREVIEW")
            } else {
                Toast.makeText(this, "TTS not ready!", Toast.LENGTH_SHORT).show()
            }
        }

    // Clear chat
        findViewById<View>(R.id.btnClearChat).setOnClickListener {
            AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("Clear Chat")
                .setMessage("Are you sure? All chat history will be deleted!")
                .setPositiveButton("Yes") { _, _ ->
                    getSharedPreferences("chat_prefs", MODE_PRIVATE)
                        .edit()
                        .putString("chat_list", "[]")
                        .apply()
                    sendBroadcast(Intent("com.junai.app.CLEAR_CHAT"))
                    Toast.makeText(this, "Chat cleared!", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("No", null)
                .show()
        }

        // Export chat
        findViewById<View>(R.id.btnExportChat).setOnClickListener {
            Toast.makeText(this, "Export coming soon!", Toast.LENGTH_SHORT).show()
        }

        // Clear notes
        findViewById<View>(R.id.btnClearNotes).setOnClickListener {
            getSharedPreferences("notes_prefs", MODE_PRIVATE).edit().clear().apply()
            Toast.makeText(this, "Notes cleared!", Toast.LENGTH_SHORT).show()
        }

        // Clear todo
        findViewById<View>(R.id.btnClearTodo).setOnClickListener {
            getSharedPreferences("todo_prefs", MODE_PRIVATE).edit().clear().apply()
            Toast.makeText(this, "To-do lists cleared!", Toast.LENGTH_SHORT).show()
        }

        // Clear reminders
        findViewById<View>(R.id.btnClearReminder).setOnClickListener {
            clearAllReminders()
            Toast.makeText(this, "Reminders cleared!", Toast.LENGTH_SHORT).show()
        }

        // Factory reset
        findViewById<View>(R.id.btnFactoryReset).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Factory Reset")
                .setMessage("Are you sure? All data will be deleted!")
                .setPositiveButton("Yes") { _, _ -> factoryReset() }
                .setNegativeButton("No", null)
                .show()
        }

        // Save changes
        findViewById<View>(R.id.btnSaveChanges).setOnClickListener {
            Toast.makeText(this, "Changes saved!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
            ttsReady = true
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            tts.setPitch(prefs.getFloat("voice_pitch", 1.0f))
            tts.setSpeechRate(prefs.getFloat("voice_speed", 1.0f))
        }
    }

    private fun clearAllReminders() {
        val prefs = getSharedPreferences("reminders", MODE_PRIVATE)
        val json = prefs.getString("reminder_list", "[]") ?: "[]"
        val array = JSONArray(json)
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val id = obj.getInt("id")
            val intent = Intent(this, AlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                this, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }
        prefs.edit().clear().apply()
    }

    private fun factoryReset() {
    getSharedPreferences("chat_prefs", MODE_PRIVATE).edit().clear().apply()
    getSharedPreferences("notes_prefs", MODE_PRIVATE).edit().clear().apply()
    getSharedPreferences("todo_prefs", MODE_PRIVATE).edit().clear().apply()
    getSharedPreferences("memory_prefs", MODE_PRIVATE).edit().clear().apply()
    getSharedPreferences("unanswered_prefs", MODE_PRIVATE).edit().clear().apply()
    getSharedPreferences("settings_prefs", MODE_PRIVATE).edit().clear().apply()
    getSharedPreferences("draw_prefs", MODE_PRIVATE).edit().clear().apply()
    clearAllReminders()
    CoroutineScope(Dispatchers.IO).launch {
        try {
            AppDatabase.getInstance(applicationContext).clearAllTables()
        } catch (e: Exception) {
            // Database may not exist yet — safe to ignore
        }
    }
    sendBroadcast(Intent("com.junai.app.CLEAR_CHAT"))
    Toast.makeText(this, "Factory reset complete! All data wiped.", Toast.LENGTH_LONG).show()
    finish()
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}
