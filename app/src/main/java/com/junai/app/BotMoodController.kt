package com.junai.app

import android.os.Handler
import android.os.Looper
import kotlin.random.Random

/**
 * Extracted from FloatingBotService — manages Jun's mood state,
 * idle-time mood transitions, and dizzy-drag detection.
 *
 * Callbacks injected at construction time (same pattern as
 * BotBatteryMonitor / BotAppSenseController):
 *   onMoodChanged  — called to update botView.setMood()
 *   onShowBubble   — called to show a mood message bubble
 */
class BotMoodController(
    private val onMoodChanged: (BotMood) -> Unit,
    private val onShowBubble:  (BotMood) -> Unit
) {
    companion object {
        const val SLEEPY_THRESHOLD_MS     = FloatingBotService.SLEEPY_THRESHOLD_MS
        const val ANGRY_THRESHOLD_MS      = FloatingBotService.ANGRY_THRESHOLD_MS
        const val DIZZY_DRAG_THRESHOLD_MS = FloatingBotService.DIZZY_DRAG_THRESHOLD_MS
    }

    var currentMood: BotMood = BotMood.SMILE
        private set

    private var lastInteractionTime = System.currentTimeMillis()
    private val handler             = Handler(Looper.getMainLooper())
    private var moodCheckRunnable: Runnable? = null
    var dizzyCheckRunnable: Runnable? = null  // internal: used by touch listener
    var isDizzyTriggered = false              // internal: used by touch listener

    // ── Public API ────────────────────────────────────────────

    fun start() {
        moodCheckRunnable = object : Runnable {
            override fun run() {
                checkMoodByIdleTime()
                handler.postDelayed(this, 10_000L)
            }
        }
        handler.postDelayed(moodCheckRunnable!!, 10_000L)
    }

    fun stop() {
        moodCheckRunnable?.let { handler.removeCallbacks(it) }
        dizzyCheckRunnable?.let { handler.removeCallbacks(it) }
    }

    fun registerInteraction() {
        lastInteractionTime = System.currentTimeMillis()
        if (currentMood != BotMood.DIZZY) applyMood(BotMood.SMILE)
    }

    fun applyMood(newMood: BotMood) {
        if (currentMood == newMood) return
        currentMood = newMood
        onMoodChanged(newMood)
        showMoodBubble(newMood)
    }

    fun showMoodBubble(forMood: BotMood) {
        onShowBubble(forMood)
    }

    /** Schedule dizzy after prolonged drag. Called from touch listener. */
    fun scheduleDizzyCheck(isDragging: () -> Boolean) {
        dizzyCheckRunnable?.let { handler.removeCallbacks(it) }
        dizzyCheckRunnable = Runnable {
            if (isDragging()) {
                isDizzyTriggered = true
                applyMood(BotMood.DIZZY)
            }
        }
        handler.postDelayed(dizzyCheckRunnable!!, DIZZY_DRAG_THRESHOLD_MS)
    }

    fun cancelDizzyCheck() {
        dizzyCheckRunnable?.let { handler.removeCallbacks(it) }
    }

    // ── Private ───────────────────────────────────────────────

    private fun checkMoodByIdleTime() {
        if (currentMood == BotMood.DIZZY) return
        val idleTime = System.currentTimeMillis() - lastInteractionTime

        val newMood = when {
            idleTime >= ANGRY_THRESHOLD_MS  -> BotMood.ANGRY
            idleTime >= SLEEPY_THRESHOLD_MS -> BotMood.SLEEPY
            else                            -> BotMood.SMILE
        }

        if (newMood != currentMood) {
            applyMood(newMood)
        } else if (newMood == BotMood.SLEEPY && Random.nextFloat() < 0.15f) {
            showMoodBubble(BotMood.SLEEPY)
        }
    }
}
