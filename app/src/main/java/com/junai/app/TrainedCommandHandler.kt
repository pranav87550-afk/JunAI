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
    fun handle(intent: String, target: String, text: String, recyclerView: RecyclerView) {
        when (intent) {
            "OPEN_APP" -> {
                if (target.isNotEmpty()) appCommandHandler.openApp(target)
                else appCommandHandler.openApp(text)
            }
            "CALL_CONTACT" -> {
                if (target.isNotEmpty()) appCommandHandler.makeCall(target)
            }
            "PLAY_MUSIC" -> {
                activity.startActivity(Intent(activity, MusicHomeActivity::class.java))
                reply("Music open kar rahi hun! 🎵")
            }
            "PAUSE_MUSIC" -> {
                activity.startService(Intent(activity, MusicService::class.java).apply { action = "PAUSE" })
                reply("Music pause! ⏸️")
            }
            "SET_REMINDER" -> {
                activity.startActivity(Intent(activity, ReminderActivity::class.java))
                reply("Reminder screen open! ⏰")
            }
            "CREATE_NOTE" -> {
                activity.startActivity(Intent(activity, NotesActivity::class.java))
                reply("Notes screen open! 📝")
            }
            "SEARCH_WEB" -> {
                val query = if (target.isNotEmpty()) target else text
                webSearchHelper.search(query, recyclerView)
            }
            "SHOW_SETTINGS" -> activity.startActivity(Intent(activity, SettingsActivity::class.java))
            "TELL_TIME" -> {
                val time = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())
                reply("Abhi time hai: $time ⏰")
            }
            "TELL_DATE" -> {
                val date = java.text.SimpleDateFormat("dd MMMM yyyy, EEEE", java.util.Locale.getDefault()).format(java.util.Date())
                reply("Aaj ki date hai: $date 📅")
            }
            "TELL_BATTERY" -> {
                val bm = activity.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
                val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                reply("Battery: $level% 🔋")
            }
            else -> reply("Command samajh nahi aaya! 🤔")
        }
        recyclerView.scrollToPosition(messages.size - 1)
        onSaveChat()
        onEnableSend()
    }

    private fun reply(text: String) {
        chatAdapter.addMessage(ChatMessage(text, isUser = false))
    }
}
