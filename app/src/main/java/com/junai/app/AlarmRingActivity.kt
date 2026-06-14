package com.junai.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlarmRingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Screen on karo even if phone locked
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        setContentView(R.layout.activity_alarm_ring)

        val title = intent?.getStringExtra("title") ?: "Reminder"
        val time = intent?.getStringExtra("time") ?: SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())

        try {
            findViewById<TextView>(R.id.alarmTitle).text = title
            findViewById<TextView>(R.id.alarmTime).text = time
        } catch (e: Exception) {
            e.printStackTrace()
        }

        findViewById<Button>(R.id.stopAlarmButton).setOnClickListener {
            try {
                stopService(Intent(this, AlarmService::class.java))
            } catch (e: Exception) {
                e.printStackTrace()
            }
            finish()
        }
    }

    override fun onBackPressed() {
        // Disable back button
    }
}
