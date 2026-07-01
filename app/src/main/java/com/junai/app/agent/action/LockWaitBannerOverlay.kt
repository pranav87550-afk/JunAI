package com.junai.app.agent.action

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * LockWaitBannerOverlay — a small, non-interactive banner pinned to the top
 * of the screen (iOS-notification style) shown while ActionEngine.openApp()
 * is waiting out an app-lock/biometric prompt.
 *
 * Deliberately NOT touchable/focusable (FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCHABLE)
 * — it sits on top of the lock screen purely as a message, and must never
 * intercept the user's PIN/pattern/fingerprint input underneath it. Sits at
 * the top specifically so it doesn't overlap the PIN pad, which is normally
 * centered or lower on screen.
 *
 * Self-starting singleton, same pattern as SafetyConfirmationOverlay — only
 * needs applicationContext, works regardless of which Activity/service is
 * alive.
 */
object LockWaitBannerOverlay {

    private var windowManager: WindowManager? = null
    private var currentView: LinearLayout? = null

    fun show(context: Context, message: String) {
        val app = context.applicationContext
        if (windowManager == null) {
            windowManager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
        hide() // remove any stale banner first

        val container = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 26, 40, 26)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E61E1E1E")) // translucent dark, like an iOS banner
                cornerRadius = 32f
            }
        }

        val text = TextView(app).apply {
            this.text = message
            setTextColor(Color.WHITE)
            textSize = 14f
        }

        container.addView(text)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // Below the status bar, above where a PIN pad / fingerprint
            // prompt would normally sit.
            y = 90
        }

        try {
            windowManager?.addView(container, params)
            currentView = container
        } catch (e: Exception) {
            android.util.Log.e("LockWaitBannerOverlay", "Failed to show banner: ${e.message}")
        }
    }

    /** Updates the currently-shown banner's text without a flicker, if one is showing. */
    fun update(message: String) {
        (currentView?.getChildAt(0) as? TextView)?.text = message
    }

    fun hide() {
        currentView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                // Already detached — safe to ignore.
            }
        }
        currentView = null
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
