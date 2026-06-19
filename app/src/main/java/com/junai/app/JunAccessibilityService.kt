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
        var onTouch: ((Float, Float) -> Unit)? = null
        var onTouchClear: (() -> Unit)? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastUpdateMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val info = AccessibilityServiceInfo().apply {
            eventTypes          = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC
            // Sirf filter key events — touch exploration NAHI
            flags               = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 0
        }
        serviceInfo = info
    }

    override fun onMotionEvent(event: MotionEvent) {
        super.onMotionEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                val now = System.currentTimeMillis()
                if (now - lastUpdateMs < 48L) return  // ~20fps
                lastUpdateMs = now
                val x = event.rawX
                val y = event.rawY
                mainHandler.post { onTouch?.invoke(x, y) }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                mainHandler.post { onTouchClear?.invoke() }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }
    override fun onInterrupt() { instance = null }

    override fun onDestroy() {
        super.onDestroy()
        instance     = null
        onTouch      = null
        onTouchClear = null
    }
}
