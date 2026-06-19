package com.junai.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent

class JunAccessibilityService : AccessibilityService() {

    companion object {
        var instance: JunAccessibilityService? = null
            private set

        // Direct callback — no Intent overhead
        var onTouch: ((Float, Float) -> Unit)? = null
        var onTouchClear: (() -> Unit)? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Throttle — har 32ms pe ek update (30fps enough hai)
    private var lastUpdateMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        serviceInfo = serviceInfo?.also {
            it.flags         = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            it.eventTypes    = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            it.feedbackType  = AccessibilityServiceInfo.FEEDBACK_GENERIC
            it.notificationTimeout = 0
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance     = null
        onTouch      = null
        onTouchClear = null
    }

    // ──────────────────────────────────────────────────────────
    // TOUCH — throttled, non-blocking
    // ──────────────────────────────────────────────────────────
    override fun onMotionEvent(event: MotionEvent) {
        super.onMotionEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                val now = System.currentTimeMillis()
                if (now - lastUpdateMs < 32L) return  // throttle 30fps
                lastUpdateMs = now

                val x = event.rawX
                val y = event.rawY
                mainHandler.post {
                    onTouch?.invoke(x, y)
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                mainHandler.post {
                    onTouchClear?.invoke()
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }

    override fun onInterrupt() {
        instance = null
    }
}
