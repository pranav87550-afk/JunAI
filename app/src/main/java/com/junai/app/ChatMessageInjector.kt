package com.junai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lets background components (the accessibility service, which has no
 * direct handle on MainActivity's chat adapter) post a Jun message into the
 * chat. Appends straight to the same SharedPreferences store MainActivity
 * already persists chat to ("chat_prefs" / "chat_list") — MainActivity's
 * existing onResume() logic re-syncs from this storage whenever the saved
 * message count differs from what's in memory, so a message appended here
 * shows up automatically the moment JunAI comes back to foreground. No new
 * event bus needed.
 */
object ChatMessageInjector {

    private const val PREFS = "chat_prefs"
    private const val KEY = "chat_list"

    fun postBotMessage(context: Context, text: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY, "[]") ?: "[]"
        val array = try { JSONArray(json) } catch (e: Exception) { JSONArray() }

        val obj = JSONObject().apply {
            put("text", text)
            put("isUser", false)
            put("timestamp", System.currentTimeMillis())
        }
        array.put(obj)

        prefs.edit().putString(KEY, array.toString()).apply()
    }
}
