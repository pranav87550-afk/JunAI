package com.junai.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import org.json.JSONArray

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs: SharedPreferences = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
        val json = prefs.getString("reminder_list", "[]") ?: "[]"
        val array = JSONArray(json)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val id = obj.getInt("id")
            val title = obj.getString("title")
            val time = obj.getString("time")
            val triggerTime = obj.getLong("triggerTime")

            val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra("title", title)
                putExtra("time", time)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, id, alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
            )
        }
    }
}
