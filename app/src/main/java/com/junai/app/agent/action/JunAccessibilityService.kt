package com.junai.app.agent.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.junai.app.ChatMessageInjector
import com.junai.app.agent.screen.ScreenContextEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // ── Recording mode (Learning Center → "Execute") ─────────────────
    //
    // The default event mask (see agent_accessibility_service_config.xml)
    // deliberately excludes TYPE_VIEW_CLICKED and TYPE_VIEW_TEXT_CHANGED —
    // that was the whole point of the perf fix (those two fire constantly
    // system-wide and were the main lag source). Recording a macro NEEDS
    // them, so we widen the mask ONLY while actively recording, and narrow
    // it straight back the moment recording stops. Same reasoning for the
    // volume-key filter: FLAG_REQUEST_FILTER_KEY_EVENTS lets onKeyEvent see
    // volume presses, but leaving that on permanently would mean Jun is
    // always intercepting volume keys system-wide — only enabled for the
    // duration of a recording.

    fun enableRecordingMode() {
        val info = serviceInfo ?: return
        info.eventTypes = info.eventTypes or
            AccessibilityEvent.TYPE_VIEW_CLICKED or
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
            // Some launchers fire context-click instead of a plain click for
            // folder icons/long-press-activated elements — catching this too
            // gives recording a better shot at custom launcher UI.
            AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = info
    }

    fun disableRecordingMode() {
        val info = serviceInfo ?: return
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        info.flags = info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        serviceInfo = info
    }

    /**
     * Volume Up/Down stops an active recording. Consumed (return true) so
     * the volume itself doesn't audibly change mid-recording — the user is
     * using the key purely as a "done" signal here, not adjusting volume.
     * Only intercepts anything at all while FLAG_REQUEST_FILTER_KEY_EVENTS
     * is set, i.e. only during an active recording (see enableRecordingMode).
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (RecordingEngine.isRecording &&
            event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            finishRecording()
            return true
        }
        return super.onKeyEvent(event)
    }

    private fun finishRecording() {
        disableRecordingMode()
        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val saved = RecordingEngine.stopAndSave(appContext)
            val message = if (saved != null) {
                "Maine seekh liya! 🎉 \"${saved.displayPhrase}\" — ab agli baar ye bolne pe main khud kar dungi (${saved.stepCount} steps)."
            } else {
                "Kuch record nahi hua — koi valid step capture nahi hui (ho sakta hai sab sensitive fields the, ya kuch tap hi nahi hua). Dobara try karo."
            }
            // BUGFIX: must write the message BEFORE bringing MainActivity to
            // foreground, not after. MainActivity.onResume() only syncs chat
            // from SharedPreferences ONCE, at resume time — if openApp()
            // finishes bringing it to foreground (triggering onResume) before
            // this message is written, the sync already happened and this
            // message would sit unseen in storage until some LATER resume
            // (e.g. next time the user backgrounds and reopens the app).
            ChatMessageInjector.postBotMessage(appContext, message)
            withContext(Dispatchers.Main) {
                // Bring JunAI back to foreground so the user sees confirmation.
                ActionEngine.openApp(appContext, appContext.packageName)
            }
        }
    }

    // BUGFIX: even after narrowing accessibilityEventTypes (see the XML
    // config), typeWindowContentChanged can still fire in quick bursts on
    // busy/animated screens (e.g. a chat list updating). Debounce the
    // EXPENSIVE part (the full collectNodes tree-walk) to at most once
    // every 200ms — but currentApp still updates on every single event,
    // since that's a near-free operation and openApp()'s foreground
    // detection depends on it staying responsive.
    private var lastSnapshotAt = 0L
    private val snapshotThrottleMs = 200L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Recording capture — handled separately from the snapshot/debounce
        // path below, since we need EVERY click/text-change while recording
        // is on, not a throttled sample of them.
        if (RecordingEngine.isRecording) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED -> {
                    event.source?.let { node ->
                        try { RecordingEngine.captureTap(node) } finally { node.recycle() }
                    }
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    event.source?.let { node ->
                        try {
                            val typed = event.text?.joinToString("") ?: node.text?.toString() ?: ""
                            RecordingEngine.captureType(node, typed)
                        } finally { node.recycle() }
                    }
                }
            }
        }

        val root = rootInActiveWindow ?: return
        try {
            val eventPackage = event.packageName?.toString()
            val now = System.currentTimeMillis()
            if (now - lastSnapshotAt < snapshotThrottleMs) {
                // Too soon for a full re-scan — just keep currentApp fresh.
                eventPackage?.takeIf { it.isNotBlank() }?.let { ScreenContextEngine.updateCurrentAppOnly(it) }
                return
            }
            lastSnapshotAt = now
            publishScreenSnapshot(root, eventPackage)
        } catch (e: Exception) {
            // AccessibilityNodeInfo can go stale mid-traversal (window closed
            // while we're reading it) — never crash the service over it.
        }
    }

    // ── Screen snapshot — feeds ScreenContextEngine ──────────────────

    private fun publishScreenSnapshot(root: AccessibilityNodeInfo, eventPackage: String? = null) {
        val visibleTexts = mutableListOf<String>()
        val clickables = mutableListOf<ScreenContextEngine.ClickableElement>()
        val inputFields = mutableListOf<ScreenContextEngine.InputField>()
        val scrollables = mutableListOf<String>()

        collectNodes(root, visibleTexts, clickables, inputFields, scrollables, depth = 0)

        ScreenContextEngine.updateContext(
            currentApp = eventPackage?.takeIf { it.isNotBlank() } ?: root.packageName?.toString() ?: "unknown",
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

    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val matches = rootInActiveWindow?.findAccessibilityNodeInfosByText(text) ?: return null
        // BUGFIX: multiple nodes on screen can share the same accessible
        // text/label — e.g. in a WhatsApp chat-list row, BOTH the contact's
        // avatar (contentDescription = "Papa") and the row's name TextView
        // (text = "Papa") match. Tapping the avatar opens their Status
        // instead of the chat. .firstOrNull() picked whichever came first
        // in traversal order, which was often the avatar. Prefer an actual
        // TextView match — that's what the row label really is — and only
        // fall back to the first match of any type if no TextView matched.
        return matches.firstOrNull { it.className?.contains("TextView") == true }
            ?: matches.firstOrNull()
    }

    fun findNodeById(viewId: String): AccessibilityNodeInfo? =
        rootInActiveWindow?.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()

    /**
     * BUGFIX (macro replay picking the wrong Quick Settings tile): tiles
     * like Bluetooth/Location/WiFi in the notification shade often share
     * ONE generic resourceId across every tile (e.g. a shared "tile" or
     * "tile_label" template id) — only their text/contentDescription
     * actually differs ("Bluetooth" vs "Location"). findNodeById() just
     * grabbed .firstOrNull() from every node matching that shared id,
     * which meant it always landed on whichever tile happens to be first
     * in the grid — not necessarily the one that was actually recorded,
     * even though the resourceId "matched" correctly.
     *
     * This checks ALL nodes sharing the resourceId and, when a
     * disambiguator (the text or contentDescription captured at record
     * time) is available, picks the one whose text/contentDescription
     * actually matches it — falling back to the first match only if no
     * disambiguator was recorded or none of the candidates match it.
     */
    fun findNodeByIdDisambiguated(viewId: String, disambiguator: String?): AccessibilityNodeInfo? {
        val matches = rootInActiveWindow?.findAccessibilityNodeInfosByViewId(viewId) ?: return null
        if (matches.isEmpty()) return null
        if (matches.size == 1 || disambiguator.isNullOrBlank()) return matches.firstOrNull()

        val needle = disambiguator.lowercase()
        return matches.firstOrNull { node ->
            node.text?.toString()?.lowercase()?.contains(needle) == true ||
                node.contentDescription?.toString()?.lowercase()?.contains(needle) == true
        } ?: matches.firstOrNull()
    }

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

    /**
     * Opens the Quick Settings panel (where toggles like WiFi/location/
     * flashlight live). Unlike a normal app, this isn't something you
     * "launch" via an Intent — com.android.systemui isn't a launchable
     * app package — so this uses the dedicated global action.
     * GLOBAL_ACTION_QUICK_SETTINGS only exists from API 31 (Android 12)
     * onward; older devices fall back to expanding notifications, which at
     * least gets partway there (quick toggles are usually one tap further).
     */
    fun expandQuickSettings(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 31) {
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        } else {
            @Suppress("DEPRECATION")
            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }
    }

    /** Tap at raw screen coordinates — fallback when no node can be found by text/id. */
    fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }
}
