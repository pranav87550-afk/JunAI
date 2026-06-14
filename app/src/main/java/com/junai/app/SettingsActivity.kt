package com.junai.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        // Clear chat
        findViewById<Button>(R.id.btnClearChat).setOnClickListener {
            getSharedPreferences("chat_prefs", MODE_PRIVATE).edit().clear().apply()
            Toast.makeText(this, "Chat cleared!", Toast.LENGTH_SHORT).show()
        }

        // Export chat
        findViewById<Button>(R.id.btnExportChat).setOnClickListener {
            Toast.makeText(this, "Export coming soon!", Toast.LENGTH_SHORT).show()
        }

        // Clear notes
        findViewById<Button>(R.id.btnClearNotes).setOnClickListener {
            getSharedPreferences("notes_prefs", MODE_PRIVATE).edit().clear().apply()
            Toast.makeText(this, "Notes cleared!", Toast.LENGTH_SHORT).show()
        }

        // Clear todo
        findViewById<Button>(R.id.btnClearTodo).setOnClickListener {
            getSharedPreferences("todo_prefs", MODE_PRIVATE).edit().clear().apply()
            Toast.makeText(this, "To-do lists cleared!", Toast.LENGTH_SHORT).show()
        }

        // Clear memory
        findViewById<Button>(R.id.btnClearMemory).setOnClickListener {
            getSharedPreferences("memory_prefs", MODE_PRIVATE).edit().clear().apply()
            Toast.makeText(this, "Memory cleared!", Toast.LENGTH_SHORT).show()
        }

        // Clear reminders
        findViewById<Button>(R.id.btnClearReminder).setOnClickListener {
            clearAllReminders()
            Toast.makeText(this, "Reminders cleared!", Toast.LENGTH_SHORT).show()
        }

        // Clear unanswered questions
        findViewById<Button>(R.id.btnClearUnanswered).setOnClickListener {
            getSharedPreferences("unanswered_prefs", MODE_PRIVATE).edit().clear().apply()
            Toast.makeText(this, "Unanswered questions cleared!", Toast.LENGTH_SHORT).show()
        }

        // Factory reset
        findViewById<Button>(R.id.btnFactoryReset).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Factory Reset")
                .setMessage("Are you sure? All data will be deleted!")
                .setPositiveButton("Yes") { _, _ ->
                    factoryReset()
                }
                .setNegativeButton("No", null)
                .show()
        }

        // Save changes
        findViewById<Button>(R.id.btnSaveChanges).setOnClickListener {
            Toast.makeText(this, "Changes saved!", Toast.LENGTH_SHORT).show()
            finish()
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
        clearAllReminders()
        Toast.makeText(this, "Factory reset complete!", Toast.LENGTH_SHORT).show()
        finish()
    }
}
