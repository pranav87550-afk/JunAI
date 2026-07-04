package com.junai.app

import android.app.Activity
import android.content.Intent
import androidx.recyclerview.widget.RecyclerView

/**
 * Handles commands that were trained by the user via Learning Center.
 * Delegates app/call actions to AppCommandHandler and web search to WebSearchHelper.
 */
class TrainedCommandHandler(
    private val activity: Activity,
    private val chatAdapter: ChatAdapter,
    private val messages: MutableList<ChatMessage>,
    private val appCommandHandler: AppCommandHandler,
    private val webSearchHelper: WebSearchHelper,
    private val onSaveChat: () -> Unit,
    private val onEnableSend: () -> Unit
) {
    // IMPROVEMENT (Phase 1f): return type changed from Unit to Boolean so
    // ChatIntentHandler knows whether this trained command actually
    // fulfilled the request — needed so a repeatedly-failing trained
    // command can be logged as a failure and surface in Pending. Two
    // genuine bugs fixed along the way: CALL_CONTACT with an empty target
    // used to do literally nothing (no reply at all), and the `else`
    // fallback ("Command samajh nahi aaya") was a dead end with no signal
    // back to the caller either.
    fun handle(intent: String, target: String, text: String, recyclerView: RecyclerView): Boolean {
        val handled = when (intent) {
            "OPEN_APP" -> {
                if (target.isNotEmpty()) appCommandHandler.openApp(target)
                else appCommandHandler.openApp(text)
            }
            "CALL_CONTACT" -> {
                if (target.isNotEmpty()) {
                    appCommandHandler.makeCall(target)
                } else {
                    reply("Kisko call karu? Naam batao! 🤔")
                    false
                }
            }
            "PLAY_MUSIC" -> {
                activity.startActivity(Intent(activity, MusicHomeActivity::class.java))
                reply("Music open kar rahi hun! 🎵")
                true
            }
            "PAUSE_MUSIC" -> {
                activity.startService(Intent(activity, MusicService::class.java).apply { action = "PAUSE" })
                reply("Music pause! ⏸️")
                true
            }
            "SET_REMINDER" -> {
                activity.startActivity(Intent(activity, ReminderActivity::class.java))
                reply("Reminder screen open! ⏰")
                true
            }
            "CREATE_NOTE" -> {
                activity.startActivity(Intent(activity, NotesActivity::class.java))
                reply("Notes screen open! 📝")
                true
            }
            "SEARCH_WEB" -> {
                val query = if (target.isNotEmpty()) target else text
                webSearchHelper.search(query, recyclerView)
                true
            }
            "SHOW_SETTINGS" -> {
                activity.startActivity(Intent(activity, SettingsActivity::class.java))
                true
            }
            "TELL_TIME" -> {
                val time = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())
                reply("Abhi time hai: $time ⏰")
                true
            }
            "TELL_DATE" -> {
                val date = java.text.SimpleDateFormat("dd MMMM yyyy, EEEE", java.util.Locale.getDefault()).format(java.util.Date())
                reply("Aaj ki date hai: $date 📅")
                true
            }
            "TELL_BATTERY" -> {
                val bm = activity.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
                val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                reply("Battery: $level% 🔋")
                true
            }
            else -> {
                reply("Command samajh nahi aaya! 🤔")
                false
            }
        }
        recyclerView.scrollToPosition(messages.size - 1)
        onSaveChat()
        onEnableSend()
        return handled
    }

    private fun reply(text: String) {
        chatAdapter.addMessage(ChatMessage(text, isUser = false))
    }
}
