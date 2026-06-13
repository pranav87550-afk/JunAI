package com.junai.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "Reminder"
        val time = intent.getStringExtra("time") ?: ""

        val serviceIntent = Intent(context, AlarmService::class.java).apply {
            putExtra("title", title)
            putExtra("time", time)
        }
        context.startForegroundService(serviceIntent)

        val ringIntent = Intent(context, AlarmRingActivity::class.java).apply {
            putExtra("title", title)
            putExtra("time", time)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(ringIntent)
    }
}
