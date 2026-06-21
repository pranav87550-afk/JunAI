package com.junai.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "Reminder"
        val time = intent.getStringExtra("time") ?: ""

        // start service first
        val serviceIntent = Intent(context, AlarmService::class.java).apply {
            putExtra("title", title)
            putExtra("time", time)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        // Start full screen alarm activity
        val ringIntent = Intent(context, AlarmRingActivity::class.java).apply {
            putExtra("title", title)
            putExtra("time", time)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        context.startActivity(ringIntent)
    }
}
