package com.junai.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import java.util.Calendar

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
            var triggerTime = obj.getLong("triggerTime")

            // Agar triggerTime past mein hai toh next day ke liye set karo
            if (triggerTime <= System.currentTimeMillis()) {
                val calendar = Calendar.getInstance()
                val savedCal = Calendar.getInstance()
                savedCal.timeInMillis = triggerTime
                calendar.set(Calendar.HOUR_OF_DAY, savedCal.get(Calendar.HOUR_OF_DAY))
                calendar.set(Calendar.MINUTE, savedCal.get(Calendar.MINUTE))
                calendar.set(Calendar.SECOND, 0)
                if (calendar.timeInMillis <= System.currentTimeMillis()) {
                    calendar.add(Calendar.DAY_OF_MONTH, 1)
                }
                triggerTime = calendar.timeInMillis
            }

            val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra("title", title)
                putExtra("time", time)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, id, alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        }
    }
}
