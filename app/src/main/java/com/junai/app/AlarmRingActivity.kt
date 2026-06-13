package com.junai.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlarmRingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm_ring)

        val title = intent.getStringExtra("title") ?: "Reminder"
        val time = intent.getStringExtra("time") ?: ""

        findViewById<TextView>(R.id.alarmTitle).text = title
        findViewById<TextView>(R.id.alarmTime).text = time

        findViewById<Button>(R.id.stopAlarmButton).setOnClickListener {
            // Stop alarm service
            stopService(Intent(this, AlarmService::class.java))
            finish()
        }
    }

    override fun onBackPressed() {
        // Disable back button when alarm is ringing
    }
}
