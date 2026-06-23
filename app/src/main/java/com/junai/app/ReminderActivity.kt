package com.junai.app

import android.app.AlarmManager
import android.provider.Settings
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.ViewGroup
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

class ReminderActivity : AppCompatActivity() {

    private val reminders = mutableListOf<JSONObject>()
    private lateinit var adapter: RecyclerView.Adapter<*>
    private val PREFS = "reminders"
    private val KEY = "reminder_list"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reminder)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        loadReminders()

        val recyclerView = findViewById<RecyclerView>(R.id.remindersRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_reminder, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val reminder = reminders[position]
                holder.itemView.findViewById<TextView>(R.id.reminderItemTitle).text = reminder.getString("title")
                holder.itemView.findViewById<TextView>(R.id.reminderItemTime).text = reminder.getString("time")
                holder.itemView.findViewById<ImageButton>(R.id.deleteReminderButton).setOnClickListener {
                    cancelAlarm(reminder.getInt("id"))
                    reminders.removeAt(position)
                    notifyItemRemoved(position)
                    notifyItemRangeChanged(position, reminders.size)
                    saveReminders()
                }
            }

            override fun getItemCount() = reminders.size
        }

        recyclerView.adapter = adapter

        // Request exact alarm permission for Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                startActivity(intent)
            }
        }

        val timePicker = findViewById<TimePicker>(R.id.timePicker)
        timePicker.setIs24HourView(false)

        // Force spinner mode to prevent invalid manual input
        timePicker.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS

        findViewById<Button>(R.id.setReminderButton).setOnClickListener {
            setReminder(timePicker)
        }    
    }

    private fun setReminder(timePicker: TimePicker) {
        val titleInput = findViewById<EditText>(R.id.reminderTitle)
        val title = titleInput.text.toString().trim()

        if (title.isEmpty()) {
            Toast.makeText(this, "Please enter a title!", Toast.LENGTH_SHORT).show()
            return
        }

        val hour = timePicker.hour.coerceIn(0, 23)
        val minute = timePicker.minute.coerceIn(0, 59)

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        val timeStr = String.format("%02d:%02d %s",
            if (hour % 12 == 0) 12 else hour % 12,
            minute,
            if (hour < 12) "AM" else "PM")

        val id = System.currentTimeMillis().toInt()

        val obj = JSONObject().apply {
            put("id", id)
            put("title", title)
            put("time", timeStr)
            put("triggerTime", calendar.timeInMillis)
        }

        reminders.add(0, obj)
        adapter.notifyItemInserted(0)
        saveReminders()
        scheduleAlarm(id, title, timeStr, calendar.timeInMillis)

        titleInput.setText("")
        Toast.makeText(this, "Reminder set for $timeStr", Toast.LENGTH_SHORT).show()
    }

    private fun scheduleAlarm(id: Int, title: String, time: String, triggerTime: Long) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            putExtra("title", title)
            putExtra("time", time)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, id, intent,
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

    private fun cancelAlarm(id: Int) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    private fun saveReminders() {
        val array = JSONArray()
        reminders.forEach { array.put(it) }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).apply()
    }

    private fun loadReminders() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY, "[]") ?: "[]"
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            reminders.add(array.getJSONObject(i))
        }
    }
}
