package com.junai.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent

class JunAccessibilityService : AccessibilityService() {

    companion object {
        // FloatingBotService ko touch coords bhejne ke liye
        const val ACTION_TOUCH_UPDATE = "ACTION_TOUCH_UPDATE"
        const val EXTRA_TOUCH_X       = "extra_touch_x"
        const val EXTRA_TOUCH_Y       = "extra_touch_y"
        const val ACTION_TOUCH_CLEAR  = "ACTION_TOUCH_CLEAR"

        var instance: JunAccessibilityService? = null
            private set

        fun isRunning() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // Touch events enable karo
        serviceInfo = serviceInfo?.also {
            it.flags = it.flags or
                AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            it.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            it.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // ──────────────────────────────────────────────────────────
    // TOUCH EVENTS — poori screen ke touches
    // ──────────────────────────────────────────────────────────
    override fun onMotionEvent(event: MotionEvent) {
        super.onMotionEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                sendTouchToBot(event.rawX, event.rawY)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                clearTouchFromBot()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Accessibility events ki zaroorat nahi — sirf touch chahiye
    }

    override fun onInterrupt() {
        instance = null
    }

    // ──────────────────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────────────────
    private fun sendTouchToBot(x: Float, y: Float) {
        val intent = Intent(this, FloatingBotService::class.java).apply {
            action = ACTION_TOUCH_UPDATE
            putExtra(EXTRA_TOUCH_X, x)
            putExtra(EXTRA_TOUCH_Y, y)
        }
        try { startService(intent) } catch (e: Exception) { /* service not running */ }
    }

    private fun clearTouchFromBot() {
        val intent = Intent(this, FloatingBotService::class.java).apply {
            action = ACTION_TOUCH_CLEAR
        }
        try { startService(intent) } catch (e: Exception) { /* ignore */ }
    }
}
