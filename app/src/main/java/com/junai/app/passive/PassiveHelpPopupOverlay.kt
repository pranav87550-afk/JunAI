package com.junai.app.passive

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Passive Learning — Phase 6: the low-confidence help popup.
 *
 * Same visual language as [com.junai.app.agent.action.MacroPreviewOverlay]
 * (dark card, accent border, system overlay window) — deliberately NOT
 * the exact same layout, though, because of one key difference: that
 * overlay is a modal blocking dialog (FLAG_DIM_BEHIND, centered, expects
 * Save/Discard taps), while THIS one must let the user keep interacting
 * with the app underneath — the "lightweight way to demonstrate" IS the
 * user's next tap in the app itself, not a button inside this overlay.
 * So: no dimming, top-aligned banner, and FLAG_NOT_TOUCH_MODAL so touches
 * outside the banner's own bounds pass straight through to the app below.
 */
object PassiveHelpPopupOverlay {

    private var windowManager: WindowManager? = null
    private var currentView: LinearLayout? = null

    fun show(context: Context, message: String, onCancel: () -> Unit) {
        val app = context.applicationContext
        if (windowManager == null) {
            windowManager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
        hide() // defensive against double-show, same as MacroPreviewOverlay

        val container = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 32, 40, 32)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadius = 24f
                setStroke(3, Color.parseColor("#1565C0"))
            }
        }

        val messageView = TextView(app).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 0, 0, 10)
        }

        val cancelRow = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val cancelButton = TextView(app).apply {
            text = "Cancel"
            setTextColor(Color.parseColor("#E53935"))
            textSize = 13f
            setPadding(20, 10, 20, 10)
            setOnClickListener {
                hide()
                onCancel()
            }
        }
        cancelRow.addView(cancelButton)

        container.addView(messageView)
        container.addView(cancelRow)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = (24 * app.resources.displayMetrics.density).toInt()
        }

        try {
            windowManager?.addView(container, params)
            currentView = container
        } catch (e: Exception) {
            android.util.Log.e("PassiveHelpPopupOverlay", "Failed to show help popup: ${e.message}")
            // Fail safe, same reasoning as MacroPreviewOverlay: if the
            // overlay can't be shown, don't leave the coordinator waiting
            // forever for a demonstration the user was never even asked for.
            onCancel()
        }
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
