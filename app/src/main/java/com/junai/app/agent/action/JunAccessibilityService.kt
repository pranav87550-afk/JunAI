package com.junai.app.agent.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.junai.app.AppDatabase
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
            // gives recording a better shot at custom launcher UI. Also the
            // real signal for an actual long-press (see captureLongPress).
            AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED or
            // BUGFIX: swipes were never recorded at all. A raw finger drag
            // isn't visible to an AccessibilityService directly, but when
            // that drag actually scrolls something, the scrollable view
            // fires this — the only reliable proxy for "user swiped" without
            // turning on touch-exploration mode (which would change how
            // touch behaves system-wide, not something to do just to
            // record a macro).
            AccessibilityEvent.TYPE_VIEW_SCROLLED
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
            val result = RecordingEngine.stopAndSave(appContext)
            val message = when (result) {
                is RecordingEngine.RecordResult.Done -> {
                    val cleanupNote = if (result.duplicatesRemoved > 0)
                        " (${result.duplicatesRemoved} duplicate step hataye maine, wo galti se do baar capture ho gaye the)"
                    else ""
                    "Maine seekh liya! 🎉 \"${result.macro.displayPhrase}\" — ab agli baar ye bolne pe main khud kar dungi (${result.macro.stepCount} steps)$cleanupNote."
                }
                is RecordingEngine.RecordResult.Empty ->
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
            // BUGFIX: see RecordingEngine.isStale() doc — a forgotten
            // recording session has no other way to end itself, and would
            // otherwise sit here hijacking the next unrelated volume press
            // indefinitely. Auto-cancel it and release the key filter
            // before it can capture (or intercept) anything else.
            if (RecordingEngine.isStale()) {
                RecordingEngine.cancel()
                disableRecordingMode()
            } else when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    event.source?.let { node ->
                        try { RecordingEngine.captureTap(node, applicationContext) } finally { node.recycle() }
                    }
                }
                // BUGFIX: was merged into the TAP branch above, so a
                // long-press got replayed as a plain click — often a no-op
                // or the wrong action (e.g. opens a chat instead of
                // long-pressing it to select). Routed to its own capture
                // now so replay can use ACTION_LONG_CLICK.
                AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED -> {
                    event.source?.let { node ->
                        try { RecordingEngine.captureLongPress(node, applicationContext) } finally { node.recycle() }
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
                // BUGFIX: swipes were never captured at all — see
                // enableRecordingMode(). scrollDeltaX/Y (API 28+) tells us
                // which way content moved; below that we can't know
                // direction from the event alone, so default to "forward"
                // (matches the common case — swiping to see more content).
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    event.source?.let { node ->
                        try {
                            val forward = if (android.os.Build.VERSION.SDK_INT >= 28) {
                                when {
                                    event.scrollDeltaY != 0 -> event.scrollDeltaY > 0
                                    event.scrollDeltaX != 0 -> event.scrollDeltaX > 0
                                    else -> true
                                }
                            } else true
                            RecordingEngine.captureScroll(node, forward)
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

    /**
     * Unified element matcher used by every replay action (tap/long-press/
     * type/scroll).
     *
     * WHY THIS EXISTS: the older approach (findNodeById / findNodeByText /
     * findNodeByIdDisambiguated below) each used only ONE or TWO signals,
     * and none of them used the element's SIZE or POSITION as part of
     * matching — bounds were only ever used as a last-resort blind
     * coordinate tap. That's exactly why replay could tap the wrong thing:
     * on a shared/templated resourceId (list rows, grid tiles, notification
     * action buttons, quick-settings tiles) "first match" is very often NOT
     * the element that was actually recorded.
     *
     * This scores every candidate on screen against everything recorded
     * about the original element — id, text, description, class, size, and
     * on-screen position — the same way a person recognizes "that specific
     * button" again even when a few similar-looking ones are also visible.
     * Only returns a match if the best-scoring candidate is a genuinely
     * confident one; otherwise returns null so the caller falls back to a
     * position-only tap (and ONLY if we're confirmed to be in the right
     * app — see ActionEngine's safeToUseBounds checks).
     */
    suspend fun findBestMatchingNode(
        resourceId: String?,
        text: String?,
        contentDescription: String?,
        className: String?,
        boundsLeft: Int?,
        boundsTop: Int?,
        boundsRight: Int?,
        boundsBottom: Int?,
        packageName: String? = null
    ): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null

        // IMPROVEMENT: this is the learned_elements table actually being
        // put to use, not just collected (see LearnedElementEntity doc).
        // A step recorded with no usable text/contentDescription (icon-only
        // controls — the Quick Settings "always taps Location" case) has
        // nothing to disambiguate same-resourceId candidates with beyond
        // position, which drifts. But if THIS exact app+resourceId+position
        // combo has been seen before (in any recording, not just this
        // macro) and confidently resolved to a label back then, that
        // label is real information this step just happens not to carry
        // itself — so borrow it, same as a person recalling "oh, that's
        // the Location tile" from having used it before, even if they
        // couldn't read the icon this particular time.
        var effectiveText = text
        var effectiveDesc = contentDescription
        if (text.isNullOrBlank() && contentDescription.isNullOrBlank() &&
            !resourceId.isNullOrBlank() && !packageName.isNullOrBlank()
        ) {
            val learned = try {
                withContext(Dispatchers.IO) {
                    AppDatabase.getInstance(applicationContext).learnedElementDao()
                        .findMatching(packageName, resourceId, boundsLeft ?: 0, boundsTop ?: 0)
                }
            } catch (e: Exception) { null }
            if (learned?.label != null) {
                effectiveText = learned.label
                effectiveDesc = learned.label
            }
        }

        // Gather every plausible candidate using the cheapest Android-
        // provided lookups first; only fall back to walking the whole tree
        // (expensive, but still bounded and only happens when id/text both
        // miss — e.g. icon-only buttons with just a contentDescription).
        val candidates = LinkedHashSet<AccessibilityNodeInfo>()
        if (!resourceId.isNullOrBlank()) {
            root.findAccessibilityNodeInfosByViewId(resourceId)?.let { candidates.addAll(it) }
        }
        if (!effectiveText.isNullOrBlank()) {
            root.findAccessibilityNodeInfosByText(effectiveText)?.let { candidates.addAll(it) }
        }
        if (candidates.isEmpty()) {
            collectAllNodes(root, candidates)
        }
        if (candidates.isEmpty()) return null

        val recordedWidth = if (boundsLeft != null && boundsRight != null) boundsRight - boundsLeft else null
        val recordedHeight = if (boundsTop != null && boundsBottom != null) boundsBottom - boundsTop else null
        val recordedCx = if (boundsLeft != null && boundsRight != null) (boundsLeft + boundsRight) / 2f else null
        val recordedCy = if (boundsTop != null && boundsBottom != null) (boundsTop + boundsBottom) / 2f else null

        var best: AccessibilityNodeInfo? = null
        var bestScore = -1.0
        val rect = android.graphics.Rect()
        for (node in candidates) {
            var score = 0.0
            if (!resourceId.isNullOrBlank() && node.viewIdResourceName == resourceId) score += 40.0

            val nodeText = node.text?.toString()
            if (!effectiveText.isNullOrBlank() && nodeText != null) {
                score += if (nodeText.equals(effectiveText, ignoreCase = true)) 30.0
                else if (nodeText.contains(effectiveText, ignoreCase = true)) 15.0 else 0.0
            }

            val nodeDesc = node.contentDescription?.toString()
            if (!effectiveDesc.isNullOrBlank() && nodeDesc != null) {
                score += if (nodeDesc.equals(effectiveDesc, ignoreCase = true)) 20.0
                else if (nodeDesc.contains(effectiveDesc, ignoreCase = true)) 10.0 else 0.0
            }

            if (!className.isNullOrBlank() && node.className?.toString() == className) score += 10.0

            node.getBoundsInScreen(rect)
            // Size match — the same physical button should render at
            // near-identical width/height across replays on the same
            // device, so this is a strong signal for telling apart
            // near-duplicate rows/tiles that share an id or label.
            if (recordedWidth != null && recordedHeight != null && recordedWidth > 0 && recordedHeight > 0 &&
                rect.width() > 0 && rect.height() > 0
            ) {
                val widthRatio = minOf(rect.width(), recordedWidth).toDouble() / maxOf(rect.width(), recordedWidth)
                val heightRatio = minOf(rect.height(), recordedHeight).toDouble() / maxOf(rect.height(), recordedHeight)
                score += 15.0 * ((widthRatio + heightRatio) / 2.0)
            }
            // Position match — full credit within 40px of the recorded
            // center (normal layout jitter), tapering to zero by 400px
            // away. A small tolerance for minor drift, not a free pass for
            // "anywhere on screen".
            if (recordedCx != null && recordedCy != null) {
                val distance = kotlin.math.hypot((rect.centerX() - recordedCx).toDouble(), (rect.centerY() - recordedCy).toDouble())
                val proximityScore = (1.0 - (distance / 400.0)).coerceIn(0.0, 1.0)
                score += 25.0 * proximityScore
            }

            if (score > bestScore) {
                bestScore = score
                best = node
            }
        }

        // Below this threshold we don't genuinely recognize anything as
        // "probably the same element" — better to report no match (and let
        // the caller fall back to a position-only tap) than confidently act
        // on a weak, coincidental score.
        return if (bestScore >= 20.0) best else null
    }

    /**
     * Walks the WHOLE accessibility tree collecting every node. Only used
     * by findBestMatchingNode() as a last resort when neither id nor text
     * produced any candidates at all (icon-only elements) — bounded to a
     * sane depth so a pathological/cyclical tree can't hang replay.
     *
     * Deliberately does not recycle collected-but-unused nodes: the scoring
     * pass above needs to read all of them, and Android's recycle() is
     * strict about double-recycling — safer to let the unused ones be
     * garbage-collected than risk recycling one that's still referenced
     * elsewhere (e.g. also returned by findAccessibilityNodeInfosByText).
     */
    private fun collectAllNodes(node: AccessibilityNodeInfo, out: MutableSet<AccessibilityNodeInfo>, depth: Int = 0) {
        if (depth > 40) return
        out.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectAllNodes(it, out, depth + 1) }
        }
    }

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

    /** Long-press (hold) at raw screen coordinates — fallback when no node can be found by text/id. */
    fun longPressAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        // Android treats a hold >= ~500ms as a long-press; 600ms gives margin.
        val stroke = GestureDescription.StrokeDescription(path, 0L, 600L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Raw swipe gesture between two points — fallback when a scrollable
     * node can't be found by id/description, so ACTION_SCROLL_FORWARD/
     * BACKWARD isn't available and we replay the literal drag instead.
     */
    fun swipeAt(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Hit-tests the current screen at (x, y) and returns the deepest node
     * whose bounds contain that point. Used as the LAST-resort way to find
     * a TYPE step's target field when it was recorded with no resourceId
     * and no contentDescription (common for custom EditTexts) — without
     * this, such a step had literally nothing left to try and replay just
     * failed outright, even though the bounds were recorded fine.
     *
     * Caller owns the returned node's lifecycle (recycle when done).
     */
    fun findNodeAtPosition(x: Float, y: Float): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findNodeAtPositionRec(root, x.toInt(), y.toInt())
    }

    private fun findNodeAtPositionRec(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (!rect.contains(x, y)) return null

        // Prefer the deepest matching descendant — e.g. the actual EditText
        // inside a container, not the container itself.
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findNodeAtPositionRec(child, x, y)
            if (hit != null) return hit
            child.recycle()
        }
        return node
    }
}
