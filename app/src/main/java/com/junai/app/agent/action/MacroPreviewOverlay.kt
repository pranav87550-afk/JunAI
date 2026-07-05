package com.junai.app.agent.action

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * MacroPreviewOverlay — Phase 2 (step preview before saving).
 *
 * Shown right after a recording session stops, before anything is written
 * to Room. Displays the plain-language step summary RecordingEngine built
 * (buildSummary()) with Save/Discard buttons — Save calls
 * RecordingEngine.confirmSave() and actually persists the macro; Discard
 * calls RecordingEngine.discardPending() and the recording vanishes as if
 * it never happened.
 *
 * Deliberately NOT tied to FloatingBotService's lifecycle — same reasoning
 * as SafetyConfirmationOverlay: if the user has the floating bubble turned
 * off (or the service isn't running), teaching a macro via "sikhao" must
 * still be able to show this preview. Only needs applicationContext, so it
 * works as long as the app process is alive, independent of any
 * Activity/Service being in the foreground.
 */
object MacroPreviewOverlay {

    private var windowManager: WindowManager? = null
    private var currentView: LinearLayout? = null

    /**
     * @param summary plain-language per-step lines from RecordingEngine.buildSummary()
     * @param duplicatesRemoved count of back-to-back duplicate taps normalize() already dropped, shown as an FYI note
     * @param onSave called after the user taps Save — caller is responsible for invoking RecordingEngine.confirmSave() and reacting to the result (e.g. posting the "Maine seekh liya" chat message)
     * @param onDiscard called after the user taps Discard — caller is responsible for invoking RecordingEngine.discardPending()
     */
    fun show(
        context: Context,
        summary: List<String>,
        duplicatesRemoved: Int,
        onSave: () -> Unit,
        onDiscard: () -> Unit
    ) {
        val app = context.applicationContext
        if (windowManager == null) {
            windowManager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
        hide() // remove any stale preview first, defensive against double-show

        val container = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 40)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadius = 28f
                setStroke(3, Color.parseColor("#4CAF50"))
            }
        }

        val title = TextView(app).apply {
            text = "Maine ye seekha, check karo 👀"
            setTextColor(Color.WHITE)
            textSize = 17f
            setPadding(0, 0, 0, 16)
        }

        val stepsList = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
        }
        summary.forEach { line ->
            stepsList.addView(TextView(app).apply {
                text = line
                setTextColor(Color.parseColor("#CCCCCC"))
                textSize = 14f
                setPadding(0, 0, 0, 10)
            })
        }

        // Cap the scroll area so a long macro doesn't push buttons off-screen.
        val scrollView = ScrollView(app).apply {
            addView(stepsList)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (400 * app.resources.displayMetrics.density).toInt()
            )
        }

        val duplicatesNote: TextView? = if (duplicatesRemoved > 0) {
            TextView(app).apply {
                text = "($duplicatesRemoved duplicate step hataye maine, wo galti se do baar capture ho gaye the)"
                setTextColor(Color.parseColor("#999999"))
                textSize = 12f
                setPadding(0, 12, 0, 0)
            }
        } else null

        val buttonRow = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 24, 0, 0)
        }

        val discardButton = Button(app).apply {
            text = "Discard"
            setOnClickListener {
                hide()
                onDiscard()
            }
        }

        val saveButton = Button(app).apply {
            text = "Save"
            setOnClickListener {
                hide()
                onSave()
            }
        }

        val spacer = android.view.View(app).apply {
            layoutParams = LinearLayout.LayoutParams(24, 1)
        }

        buttonRow.addView(discardButton)
        buttonRow.addView(spacer)
        buttonRow.addView(saveButton)

        container.addView(title)
        container.addView(scrollView)
        duplicatesNote?.let { container.addView(it) }
        container.addView(buttonRow)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.5f
        }

        try {
            windowManager?.addView(container, params)
            currentView = container
        } catch (e: Exception) {
            android.util.Log.e("MacroPreviewOverlay", "Failed to show preview: ${e.message}")
            // Fail safe: if the overlay can't even be shown (e.g. overlay
            // permission revoked), discard rather than leaving the just-
            // recorded macro stuck in pendingSteps with no way to resolve it.
            onDiscard()
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
