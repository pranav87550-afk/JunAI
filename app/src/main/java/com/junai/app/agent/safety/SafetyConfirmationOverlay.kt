package com.junai.app.agent.safety

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * SafetyConfirmationOverlay — the missing piece that was causing every
 * MEDIUM/HIGH/CRITICAL-risk agent step to silently time out after 30s.
 *
 * DecisionEngine → SafetyLayer.guard()/requestApproval() suspends and
 * publishes a request on [SafetyLayer.activeRequest], expecting *some* UI
 * to show it and call [SafetyLayer.respond]. Nothing was observing that
 * flow before this file existed.
 *
 * IMPORTANT — this is a self-starting singleton ([ensureStarted]), NOT tied
 * to FloatingBotService's lifecycle. If the bubble is turned off / the
 * service is killed, agent tasks (triggered from chat, not just the bubble)
 * must still be able to show this confirmation — so it only ever needs
 * applicationContext, which always exists for as long as the process is
 * alive. AgentEngine calls [ensureStarted] once before running any task;
 * calling it repeatedly is a safe no-op after the first time.
 */
object SafetyConfirmationOverlay {

    private var windowManager: WindowManager? = null
    private var appContext: Context? = null
    private var currentView: LinearLayout? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var watchJob: Job? = null

    /**
     * Safe to call from anywhere, any number of times (e.g. at the top of
     * AgentEngine.runTask()). Uses applicationContext only, so it works
     * regardless of whether FloatingBotService or any Activity is alive.
     */
    fun ensureStarted(context: Context) {
        if (watchJob != null) return
        val app = context.applicationContext
        appContext = app
        windowManager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        watchJob = scope.launch {
            SafetyLayer.activeRequest.collect { request ->
                if (request == null) {
                    hide()
                } else {
                    show(request)
                }
            }
        }
    }

    private fun show(request: SafetyLayer.ConfirmationRequest) {
        hide() // remove any stale view first, defensive against double-show

        val context = appContext ?: return
        val isCritical = request.riskLevel == RiskLevel.CRITICAL
        val accentColor = if (isCritical) Color.parseColor("#D32F2F") else Color.parseColor("#F57C00")

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 40)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadius = 28f
                setStroke(3, accentColor)
            }
        }

        val title = TextView(context).apply {
            text = if (isCritical) "⚠️ Jun needs your confirmation" else "Jun wants to do this"
            setTextColor(Color.WHITE)
            textSize = 17f
            setPadding(0, 0, 0, 16)
        }

        val actionText = TextView(context).apply {
            text = request.actionDescription
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, 0, 0, 8)
        }

        val detailText = TextView(context).apply {
            text = request.whatWillHappen
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 13f
            setPadding(0, 0, 0, 24)
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val denyButton = Button(context).apply {
            text = "No"
            setOnClickListener {
                SafetyLayer.respond(request.id, approved = false)
            }
        }

        val approveButton = Button(context).apply {
            text = if (isCritical) "Yes, I'm sure" else "Yes"
            setOnClickListener {
                SafetyLayer.respond(request.id, approved = true)
            }
        }

        val spacer = android.view.View(context).apply {
            layoutParams = LinearLayout.LayoutParams(24, 1)
        }

        buttonRow.addView(denyButton)
        buttonRow.addView(spacer)
        buttonRow.addView(approveButton)

        container.addView(title)
        container.addView(actionText)
        container.addView(detailText)
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
            android.util.Log.e("SafetyConfirmationOverlay", "Failed to show overlay: ${e.message}")
            // Fail safe: if we can't even show the dialog (e.g. overlay
            // permission revoked), deny immediately rather than leaving
            // the agent stuck for 30s with no way for the user to respond.
            SafetyLayer.respond(request.id, approved = false)
        }
    }

    private fun hide() {
        currentView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                // View already removed/detached — safe to ignore.
            }
        }
        currentView = null
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
