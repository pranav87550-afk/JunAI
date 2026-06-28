package com.junai.app

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
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

        // Show over lock screen + turn screen on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            // Dismiss keyguard so alarm is visible without unlocking
            val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            keyguard.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON   or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_alarm_ring)

        val title = intent?.getStringExtra("title") ?: "Reminder"
        val time  = intent?.getStringExtra("time")
            ?: SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())

        try {
            findViewById<TextView>(R.id.alarmTitle).text = title
            findViewById<TextView>(R.id.alarmTime).text  = time
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back button disabled — user must tap Stop
    }
}
