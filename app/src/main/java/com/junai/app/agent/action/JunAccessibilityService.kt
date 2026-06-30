package com.junai.app.agent.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.junai.app.agent.screen.ScreenContextEngine

/**
 * JunAccessibilityService — the Android-framework entry point the whole
 * agent system needs to actually see and touch the screen.
 *
 * IMPORTANT: this file was NOT named anywhere in the Phase 15 prompt's
 * module list, but ActionEngine and ScreenContextEngine both assume an
 * AccessibilityService exists to drive them — flagging that gap now,
 * before creating this, per the "explain why before adding an unlisted
 * file" rule. Without this, neither module has any real way to read or
 * touch the screen.
 *
 * Design: holds a static [instance] (set in onServiceConnected, cleared in
 * onDestroy) so ActionEngine — a plain object, not a Service — can drive
 * gestures and node lookups through it. This is the standard Android
 * pattern, since only the OS can instantiate an AccessibilityService.
 *
 * Every AccessibilityEvent pushes a fresh text-only snapshot into
 * ScreenContextEngine, filtering out password/OTP fields before they're
 * ever recorded — never after.
 */
class JunAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: JunAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onInterrupt() {
        // Required override — instance cleanup already happens in onDestroy().
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val root = rootInActiveWindow ?: return
        try {
            publishScreenSnapshot(root)
        } catch (e: Exception) {
            // AccessibilityNodeInfo can go stale mid-traversal (window closed
            // while we're reading it) — never crash the service over it.
        }
    }

    // ── Screen snapshot — feeds ScreenContextEngine ──────────────────

    private fun publishScreenSnapshot(root: AccessibilityNodeInfo) {
        val visibleTexts = mutableListOf<String>()
        val clickables = mutableListOf<ScreenContextEngine.ClickableElement>()
        val inputFields = mutableListOf<ScreenContextEngine.InputField>()
        val scrollables = mutableListOf<String>()

        collectNodes(root, visibleTexts, clickables, inputFields, scrollables, depth = 0)

        ScreenContextEngine.updateContext(
            currentApp = root.packageName?.toString() ?: "unknown",
            visibleTexts = visibleTexts,
            clickableElements = clickables,
            inputFields = inputFields,
            scrollableAreas = scrollables
        )
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        visibleTexts: MutableList<String>,
        clickables: MutableList<ScreenContextEngine.ClickableElement>,
        inputFields: MutableList<ScreenContextEngine.InputField>,
        scrollables: MutableList<String>,
        depth: Int
    ) {
        // Hard depth cap — keeps a single snapshot bounded even on apps
        // with unusually deep view hierarchies.
        if (depth > 40) return

        val hint = node.hintText?.toString()
        val looksSensitive = node.isPassword ||
            ScreenContextEngine.looksLikeSensitiveLabel(hint) ||
            ScreenContextEngine.looksLikeSensitiveLabel(node.text?.toString())

        if (node.isEditable) {
            // Sensitive editable fields are skipped entirely — never even
            // recorded as "an input field exists here", per spec.
            if (!looksSensitive) {
                inputFields.add(ScreenContextEngine.InputField(viewId = node.viewIdResourceName, hint = hint))
            }
        } else if (!looksSensitive) {
            val text = node.text?.toString()
            if (!text.isNullOrBlank()) visibleTexts.add(text)
            val contentDesc = node.contentDescription?.toString()
            if (!contentDesc.isNullOrBlank() && contentDesc != text) visibleTexts.add(contentDesc)
        }

        if (node.isClickable && !looksSensitive) {
            val label = node.text?.toString() ?: node.contentDescription?.toString()
            if (!label.isNullOrBlank()) {
                clickables.add(
                    ScreenContextEngine.ClickableElement(
                        text = label,
                        viewId = node.viewIdResourceName,
                        className = node.className?.toString()
                    )
                )
            }
        }

        if (node.isScrollable) {
            scrollables.add(node.viewIdResourceName ?: node.className?.toString() ?: "scrollable area")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectNodes(child, visibleTexts, clickables, inputFields, scrollables, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    // ── Node lookup (used by ActionEngine) ───────────────────────────

    fun findNodeByText(text: String): AccessibilityNodeInfo? =
        rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull()

    fun findNodeById(viewId: String): AccessibilityNodeInfo? =
        rootInActiveWindow?.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()

    // ── Actions (used by ActionEngine) ───────────────────────────────

    fun tap(node: AccessibilityNodeInfo): Boolean {
        // BUGFIX: many system Settings screens (WiFi/Bluetooth toggle rows
        // especially) put the matched text in a plain TextView that isn't
        // itself clickable — only an ancestor ViewGroup is. Previously this
        // called performAction directly on whatever node findNodeByText
        // returned, which silently failed (returns true sometimes even
        // when nothing visibly happens) on those rows. Walk up to the
        // nearest clickable ancestor before tapping, same pattern
        // TalkBack/other accessibility services use.
        var target: AccessibilityNodeInfo? = node
        var depth = 0
        while (target != null && !target.isClickable && depth < 6) {
            target = target.parent
            depth++
        }
        val finalTarget = target ?: node
        return finalTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun longPress(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)

    fun typeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    fun scroll(node: AccessibilityNodeInfo, forward: Boolean): Boolean {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return node.performAction(action)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /** Tap at raw screen coordinates — fallback when no node can be found by text/id. */
    fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }
}
