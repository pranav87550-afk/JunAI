package com.junai.app

import android.app.AlarmManager
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

    private var selectedHour = 12
    private var selectedMinute = 0
    private var isAm = false // default PM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reminder)

        findViewById<LinearLayout>(R.id.backButton).setOnClickListener { finish() }

        // Init with current time
        val cal = Calendar.getInstance()
        selectedHour = cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        selectedMinute = cal.get(Calendar.MINUTE)
        isAm = cal.get(Calendar.AM_PM) == Calendar.AM

        val hourDisplay = findViewById<TextView>(R.id.hourDisplay)
        val minuteDisplay = findViewById<TextView>(R.id.minuteDisplay)
        val amPmDisplay = findViewById<TextView>(R.id.amPmDisplay)

        fun updateDisplay() {
            hourDisplay.text = String.format("%02d", selectedHour)
            minuteDisplay.text = String.format("%02d", selectedMinute)
            amPmDisplay.text = if (isAm) "AM" else "PM"
        }
        updateDisplay()

        // Hour controls
        findViewById<Button>(R.id.hourUp).setOnClickListener {
            selectedHour = if (selectedHour >= 12) 1 else selectedHour + 1
            updateDisplay()
        }
        findViewById<Button>(R.id.hourDown).setOnClickListener {
            selectedHour = if (selectedHour <= 1) 12 else selectedHour - 1
            updateDisplay()
        }

        // Minute controls
        findViewById<Button>(R.id.minuteUp).setOnClickListener {
            selectedMinute = if (selectedMinute >= 59) 0 else selectedMinute + 1
            updateDisplay()
        }
        findViewById<Button>(R.id.minuteDown).setOnClickListener {
            selectedMinute = if (selectedMinute <= 0) 59 else selectedMinute - 1
            updateDisplay()
        }

        // AM/PM toggle
        findViewById<TextView>(R.id.amPmToggle).setOnClickListener {
            isAm = !isAm
            updateDisplay()
        }
        amPmDisplay.setOnClickListener {
            isAm = !isAm
            updateDisplay()
        }

        // Quick select buttons
        fun addMinutes(mins: Int) {
            val totalMinutes = (if (isAm) 0 else 12) * 60 +
                    (if (selectedHour == 12) 0 else selectedHour) * 60 + selectedMinute + mins
            val total24 = totalMinutes % (24 * 60)
            val h24 = total24 / 60
            val m = total24 % 60
            isAm = h24 < 12
            selectedHour = when {
                h24 == 0 -> 12
                h24 > 12 -> h24 - 12
                else -> h24
            }
            selectedMinute = m
            updateDisplay()
        }

        findViewById<Button>(R.id.quick15).setOnClickListener { addMinutes(15) }
        findViewById<Button>(R.id.quick30).setOnClickListener { addMinutes(30) }
        findViewById<Button>(R.id.quick1h).setOnClickListener { addMinutes(60) }
        findViewById<Button>(R.id.quick2h).setOnClickListener { addMinutes(120) }

        // Set reminder
        findViewById<LinearLayout>(R.id.setReminderButton).setOnClickListener {
            setReminder()
        }

        // Request exact alarm permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            }
        }

        loadReminders()
        setupRecyclerView()
    }

    private fun setReminder() {
        val titleInput = findViewById<EditText>(R.id.reminderTitle)
        val title = titleInput.text.toString().trim()

        if (title.isEmpty()) {
            Toast.makeText(this, "Please enter a title!", Toast.LENGTH_SHORT).show()
            return
        }

        val hour24 = when {
            isAm && selectedHour == 12 -> 0
            !isAm && selectedHour != 12 -> selectedHour + 12
            else -> selectedHour
        }

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour24)
            set(Calendar.MINUTE, selectedMinute)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        val timeStr = String.format("%02d:%02d %s", selectedHour, selectedMinute, if (isAm) "AM" else "PM")
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
        Toast.makeText(this, "Reminder set for $timeStr ✅", Toast.LENGTH_SHORT).show()
    }

    private fun setupRecyclerView() {
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
            if (alarmManager.canScheduleExactAlarms())
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            else
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
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
