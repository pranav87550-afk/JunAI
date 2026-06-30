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
 * flow before this file existed. This class is that observer: a small
 * always-on-top overlay window (same TYPE_APPLICATION_OVERLAY pattern as
 * FloatingBotService) that pops up only when activeRequest becomes
 * non-null, shows the action + what-will-happen text, and two buttons.
 *
 * Started once from FloatingBotService.onCreate() and stopped from
 * onDestroy() — see the one-line wiring added there. It does not depend on
 * FloatingBotService internals, so it can't break the existing bubble/menu
 * logic; it only ever adds/removes its own separate overlay view.
 */
class SafetyConfirmationOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var currentView: LinearLayout? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var watchJob: Job? = null

    /** Call once (e.g. from FloatingBotService.onCreate()) to start watching for approval requests. */
    fun start() {
        if (watchJob != null) return
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

    /** Call from FloatingBotService.onDestroy() to clean up. */
    fun stop() {
        watchJob?.cancel()
        watchJob = null
        hide()
    }

    private fun show(request: SafetyLayer.ConfirmationRequest) {
        hide() // remove any stale view first, defensive against double-show

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
            windowManager.addView(container, params)
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
                windowManager.removeView(it)
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
