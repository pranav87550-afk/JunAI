package com.junai.app.passive

import android.content.Context
import com.junai.app.AppDatabase
import java.util.concurrent.ConcurrentHashMap

/**
 * Passive Learning — Phase 8: low-knowledge disclosure.
 *
 * Like Phase 5, this is infrastructure ready for the chat layer to call —
 * nothing calls it yet, since it needs to sit at the point where a user's
 * message resolves to "this is a new task attempt targeting app X,"
 * which is a chat-integration decision this file deliberately doesn't
 * make itself (see [isNewTaskAttempt] param below).
 *
 * Debounce choice: the spec left "a session, or a fixed time window" as
 * an implementation-time call. Went with a fixed 24-hour window per app,
 * in-memory (not persisted) — simpler than a new table/migration, and a
 * process restart re-disclosing once more is a harmless edge case, not
 * the "repeated, message-by-message annoyance" the spec is actually
 * guarding against.
 */
object PassiveLowKnowledgeAdvisor {

    /** Fewer confirmed screens than this counts as "little or no usable learned graph" for the app. */
    private const val MIN_CONFIRMED_SCREENS = 2
    private const val COOLDOWN_MS = 24 * 60 * 60 * 1000L

    private val lastDisclosedAt = ConcurrentHashMap<String, Long>()

    /**
     * @param isNewTaskAttempt Caller's call, not this function's — "a new
     *   task attempt" vs. "a follow-up message in the same thread" is a
     *   conversation-level distinction that belongs to whatever's driving
     *   the chat flow (ChatIntentHandler et al.), not to this file.
     * @return the disclosure line to show, or null if nothing should be said.
     */
    suspend fun maybeDisclose(
        context: Context,
        packageName: String,
        appDisplayName: String,
        isNewTaskAttempt: Boolean
    ): String? {
        if (!isNewTaskAttempt) return null

        val now = System.currentTimeMillis()
        val last = lastDisclosedAt[packageName]
        if (last != null && now - last < COOLDOWN_MS) return null

        val confirmedCount = AppDatabase.getInstance(context).passiveScreenDao().countConfirmedForApp(packageName)
        if (confirmedCount >= MIN_CONFIRMED_SCREENS) return null

        lastDisclosedAt[packageName] = now
        return "$appDisplayName ke baare mein abhi mujhe zyada pata nahi hai — thoda time lagega seekhne mein."
    }

    /** Lets a freshly-forgotten app disclose again right away, instead of waiting out a cooldown that no longer makes sense — see ManageLearningActivity's forget flows. */
    fun resetDebounce(packageName: String) {
        lastDisclosedAt.remove(packageName)
    }

    fun resetAllDebounces() {
        lastDisclosedAt.clear()
    }
}
