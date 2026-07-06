package com.junai.app.agent.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
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
import kotlinx.coroutines.delay
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

    /**
     * PHASE (screen-settle detection): timestamp of the most recent
     * accessibility event of ANY kind, stamped unconditionally at the top
     * of onAccessibilityEvent(). @Volatile since waitForQuiet() below
     * polls it from a coroutine that may run on a different thread than
     * the one delivering accessibility events.
     */
    @Volatile
    private var lastEventAt: Long = 0L

    /**
     * PHASE (screen-settle detection): waits until the screen has gone
     * quiet — no accessibility events of any kind for [quietMs] — before
     * returning true, or gives up and returns false after [maxWaitMs]
     * total. This replaces guessing a fixed delay for "has the new screen
     * finished loading": instead of hoping 400ms or 2s was enough, it
     * waits for an actual signal that things have stopped moving.
     *
     * Deliberately content-agnostic — doesn't know or care WHAT loaded,
     * just that something is still changing. That's what makes it usable
     * before the very first step of a replay too (e.g. the home screen's
     * icon grid still populating), where there's no specific expected
     * text/node to poll for yet, unlike waitForStepEvidence() in
     * MacroReplayEngine which needs to already know what it's looking for.
     *
     * The maxWaitMs cap matters just as much as the quietMs check — a
     * screen with a genuinely continuous animation (loading spinner, a
     * live clock, a "typing…" indicator) would never go quiet on its own,
     * so this always gives up and lets the caller proceed rather than
     * hanging indefinitely.
     */
    suspend fun waitForQuiet(quietMs: Long = 300L, maxWaitMs: Long = 3000L) {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            val quietFor = System.currentTimeMillis() - lastEventAt
            if (quietFor >= quietMs) return
            delay(100L)
        }
        // Timed out without ever going quiet — proceed anyway rather than
        // blocking the replay forever on a screen that's always "busy".
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
            val result = RecordingEngine.stopAndSave()
            when (result) {
                is RecordingEngine.RecordResult.Empty -> {
                    val message = "Kuch record nahi hua — koi valid step capture nahi hui (ho sakta hai sab sensitive fields the, ya kuch tap hi nahi hua). Dobara try karo."
                    // BUGFIX: must write the message BEFORE bringing MainActivity to
                    // foreground, not after. MainActivity.onResume() only syncs chat
                    // from SharedPreferences ONCE, at resume time — if openApp()
                    // finishes bringing it to foreground (triggering onResume) before
                    // this message is written, the sync already happened and this
                    // message would sit unseen in storage until some LATER resume
                    // (e.g. next time the user backgrounds and reopens the app).
                    ChatMessageInjector.postBotMessage(appContext, message)
                    withContext(Dispatchers.Main) {
                        ActionEngine.openApp(appContext, appContext.packageName)
                    }
                }
                is RecordingEngine.RecordResult.Preview -> {
                    // PHASE 2: don't persist yet — show the step preview as
                    // a system overlay (works even if the floating bot is
                    // off, same reasoning as SafetyConfirmationOverlay) and
                    // let the user approve or throw it away first.
                    withContext(Dispatchers.Main) {
                        MacroPreviewOverlay.show(
                            context = appContext,
                            summary = result.summary,
                            duplicatesRemoved = result.duplicatesRemoved,
                            onSave = { onPreviewSaved(appContext) },
                            onDiscard = { onPreviewDiscarded(appContext) }
                        )
                    }
                }
            }
        }
    }

    private fun onPreviewSaved(appContext: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val saveResult = RecordingEngine.confirmSave(appContext)
            val message = when (saveResult) {
                is RecordingEngine.SaveResult.Done -> {
                    // BUGFIX: this status flip used to happen at recording
                    // START time in LearningCenterActivity, before the user
                    // had even confirmed anything — so Discard (Phase 2)
                    // left it permanently stuck, no way back into Pending.
                    // Now it only fires here, after an actual confirmed
                    // Save, and only for recordings that came from a
                    // Pending item in the first place (sourcePendingItemId
                    // is null for a plain "sikhao" typed directly in chat).
                    saveResult.sourcePendingItemId?.let { itemId ->
                        com.junai.app.LearningRepository(appContext).updateStatus(itemId, "TRAINED_EXECUTE")
                    }
                    "Maine seekh liya! 🎉 \"${saveResult.macro.displayPhrase}\" — ab agli baar ye bolne pe main khud kar dungi (${saveResult.macro.stepCount} steps)."
                }
                is RecordingEngine.SaveResult.NothingPending ->
                    "Kuch save karne ko nahi mila — shayad pehle hi discard ho chuka tha."
            }
            ChatMessageInjector.postBotMessage(appContext, message)
            withContext(Dispatchers.Main) {
                ActionEngine.openApp(appContext, appContext.packageName)
            }
        }
    }

    private fun onPreviewDiscarded(appContext: Context) {
        // Deliberately does NOT touch the Learning item's status — see
        // onPreviewSaved()'s comment above. Discard leaves it exactly as
        // it was (still PENDING if it came from there), so it's still
        // visible in the Pending tab and can be re-attempted or re-taught.
        RecordingEngine.discardPending()
        CoroutineScope(Dispatchers.IO).launch {
            ChatMessageInjector.postBotMessage(appContext, "Thik hai, maine wo discard kar diya — jaise sikhaya hi nahi tha. Jab chaho dobara sikha dena.")
            withContext(Dispatchers.Main) {
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

        // PHASE (screen-settle detection): stamped for EVERY event that
        // arrives here, regardless of type or whether anything below
        // actually handles it — this is the raw signal waitForQuiet()
        // polls against. Deliberately not filtered by event type: a
        // window still finishing its layout tends to keep firing a mix
        // of content-changed/scrolled/focus events, and we only care
        // that SOMETHING is still happening, not what.
        lastEventAt = System.currentTimeMillis()

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
                    val node = event.source
                    if (node == null) {
                        // DIAGNOSTIC (accurate step counting): if this ever
                        // shows up in logcat, a real tap during recording
                        // silently produced NO step — Android delivered the
                        // click event but with no source node attached, so
                        // there was nothing for captureTap() to even try.
                        android.util.Log.w("JunAccessibility", "Recording: TAP event had null source — step NOT captured. pkg=${event.packageName} class=${event.className}")
                    } else {
                        try { RecordingEngine.captureTap(node, applicationContext) } finally { node.recycle() }
                    }
                }
                // BUGFIX: was merged into the TAP branch above, so a
                // long-press got replayed as a plain click — often a no-op
                // or the wrong action (e.g. opens a chat instead of
                // long-pressing it to select). Routed to its own capture
                // now so replay can use ACTION_LONG_CLICK.
                AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED -> {
                    val node = event.source
                    if (node == null) {
                        android.util.Log.w("JunAccessibility", "Recording: LONG_PRESS event had null source — step NOT captured. pkg=${event.packageName} class=${event.className}")
                    } else {
                        try { RecordingEngine.captureLongPress(node, applicationContext) } finally { node.recycle() }
                    }
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    val node = event.source
                    if (node == null) {
                        android.util.Log.w("JunAccessibility", "Recording: TYPE event had null source — step NOT captured. pkg=${event.packageName} class=${event.className}")
                    } else {
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
                //
                // BUGFIX (Phase 1h): now also derives WHICH AXIS moved
                // (horizontal vs vertical) from the same deltas, and passes
                // it through — see RecordedStep.scrollHorizontal's doc
                // comment for why this was missing before and what it fixes.
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    val node = event.source
                    if (node == null) {
                        // DIAGNOSTIC: this is the leading suspect for
                        // "home-screen swipe sometimes doesn't get
                        // recorded" — some launchers' app-drawer swipe
                        // gesture fires TYPE_VIEW_SCROLLED without a
                        // source node attached, especially on
                        // gesture-navigation devices where the system
                        // intercepts part of the gesture first. If this
                        // line shows up in logcat right when a swipe goes
                        // missing, that confirms it — the fix from there
                        // would be a best-effort fallback search on
                        // rootInActiveWindow for a scrollable node, but
                        // that's not worth guessing at until this confirms
                        // it's actually happening.
                        android.util.Log.w("JunAccessibility", "Recording: SWIPE event had null source — step NOT captured. pkg=${event.packageName} class=${event.className}")
                    } else {
                        try {
                            val horizontal: Boolean
                            val forward: Boolean
                            if (android.os.Build.VERSION.SDK_INT >= 28) {
                                when {
                                    event.scrollDeltaY != 0 -> { horizontal = false; forward = event.scrollDeltaY > 0 }
                                    event.scrollDeltaX != 0 -> { horizontal = true; forward = event.scrollDeltaX > 0 }
                                    else -> { horizontal = false; forward = true }
                                }
                            } else { horizontal = false; forward = true }
                            RecordingEngine.captureScroll(node, forward, horizontal)
                        } finally { node.recycle() }
                    }
                }
            }
        }

        // BUGFIX (Phase 1d, likely root cause of "replay badly wrong on
        // every system screen" — Quick Settings/notification shade):
        // eventPackage doesn't depend on rootInActiveWindow at all, but
        // this used to sit AFTER the `root ?: return` below — so whenever
        // rootInActiveWindow came back null (which the Quick Settings
        // shade / notification panel commonly does, since it's a separate
        // system window that many OEMs don't treat as the "active" one for
        // accessibility purposes), ScreenContextEngine.currentApp never
        // got the chance to flip to "com.android.systemui" even though
        // that surface genuinely was in front. Every downstream check that
        // reads currentApp — MacroReplayEngine.navigateToApp()'s
        // isAppInForeground("com.android.systemui"), and
        // findBestMatchingNode()'s own node search — was working off a
        // stale/wrong value specifically during Quick-Settings steps.
        // Moving this above the early return costs nothing (event.packageName
        // is always available) and fixes the ordering unconditionally.
        val eventPackage = event.packageName?.toString()
        eventPackage?.takeIf { it.isNotBlank() }?.let { ScreenContextEngine.updateCurrentAppOnly(it) }

        val root = rootInActiveWindow ?: return
        try {
            val now = System.currentTimeMillis()
            if (now - lastSnapshotAt < snapshotThrottleMs) {
                // Too soon for a full re-scan — currentApp is already kept
                // fresh above regardless of this throttle now.
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
     * Only reports .node as non-null if the best-scoring candidate is a
     * genuinely confident one; otherwise the caller falls back to a
     * position-only tap (and ONLY if we're confirmed to be in the right
     * app — see ActionEngine's safeToUseBounds checks).
     *
     * PHASE 4 (alternate-candidate retry): also returns the second-best
     * candidate (.alternate) when it's not too far behind the winner, so
     * a caller whose actual click/action on .node fails (stale node,
     * action rejected, etc — NOT the same thing as a low match score) has
     * a real second option to try before giving up entirely, instead of
     * dropping straight to a position-only tap on the very first miss.
     * .matchedVia records which signal actually won, for step-outcome
     * logging — not used for matching logic itself.
     */
    data class NodeMatchResult(
        val node: AccessibilityNodeInfo?,
        val alternate: AccessibilityNodeInfo?,
        val matchedVia: String?   // "id" | "text" | "description" | "bounds" | null (no confident match)
    )

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
    ): NodeMatchResult {
        val root = rootInActiveWindow ?: return NodeMatchResult(null, null, null)

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
        //
        // BUGFIX (Phase 1a): previously used candidates.addAll(list), which
        // silently drops nodes AccessibilityNodeInfo.equals() considers
        // duplicates (it's overridden in AOSP to compare the underlying
        // (windowId, sourceNodeId, connectionId) triple, not object
        // identity — so byViewId() and byText() returning the "same" node
        // as two distinct object instances collapses to one Set entry).
        // The instance that loses that collapse was never recycled because
        // addAll() gives us no handle on what it silently rejected — a real,
        // if minor, leak on every call that has both an id and text match.
        // addCandidate() below plugs that leak by recycling the rejected
        // duplicate immediately, and everything that DOES make it into
        // `candidates` is now provably a distinct logical node — which is
        // what makes recycling every non-best one below safe (see that
        // comment for why this used to be considered unsafe).
        val candidates = LinkedHashSet<AccessibilityNodeInfo>()
        fun addCandidate(node: AccessibilityNodeInfo) {
            if (!candidates.add(node)) {
                // Set already has an equals()-equal node (same underlying
                // element from a different lookup) — this instance is now
                // otherwise unreferenced, so recycle it defensively. Wrapped
                // in try/catch + log so we learn from real devices whether
                // Android ever throws here (would mean two "equal" nodes
                // aren't always safe to both recycle) instead of assuming.
                try {
                    node.recycle()
                } catch (e: Exception) {
                    android.util.Log.w("JunAccessibility", "recycle() threw on a duplicate candidate — double-recycle risk may be real: ${e.message}")
                }
            }
        }
        if (!resourceId.isNullOrBlank()) {
            root.findAccessibilityNodeInfosByViewId(resourceId)?.forEach { addCandidate(it) }
        }
        if (!effectiveText.isNullOrBlank()) {
            root.findAccessibilityNodeInfosByText(effectiveText)?.forEach { addCandidate(it) }
        }
        if (candidates.isEmpty()) {
            collectAllNodes(root, candidates)
        }
        if (candidates.isEmpty()) return NodeMatchResult(null, null, null)

        val recordedWidth = if (boundsLeft != null && boundsRight != null) boundsRight - boundsLeft else null
        val recordedHeight = if (boundsTop != null && boundsBottom != null) boundsBottom - boundsTop else null
        val recordedCx = if (boundsLeft != null && boundsRight != null) (boundsLeft + boundsRight) / 2f else null
        val recordedCy = if (boundsTop != null && boundsBottom != null) (boundsTop + boundsBottom) / 2f else null

        var best: AccessibilityNodeInfo? = null
        var bestScore = -1.0
        var secondBest: AccessibilityNodeInfo? = null
        var secondBestScore = -1.0
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
                // Old best demotes to second-best rather than being lost —
                // it's still a real, scored candidate, just not the winner.
                secondBest = best
                secondBestScore = bestScore
                bestScore = score
                best = node
            } else if (score > secondBestScore) {
                secondBestScore = score
                secondBest = node
            }
        }

        // Recycle every candidate that ISN'T one of the top two — see
        // Phase 1a's comment above on why this is safe (candidates is
        // already deduplicated to distinct logical nodes).
        for (node in candidates) {
            if (node !== best && node !== secondBest) {
                try {
                    node.recycle()
                } catch (e: Exception) {
                    android.util.Log.w("JunAccessibility", "recycle() threw on a non-top-2 candidate — investigate: ${e.message}")
                }
            }
        }

        // Below this threshold we don't genuinely recognize anything as
        // "probably the same element" — better to report no match (and let
        // the caller fall back to a position-only tap) than confidently act
        // on a weak, coincidental score.
        val winner = if (bestScore >= 20.0) best else null
        // Alternate held to a slightly lower bar (15.0) than the primary —
        // it only ever gets used if the primary's actual action FAILS, so
        // a somewhat-weaker-but-still-plausible second option is worth
        // having; it's never used to override a working primary match.
        val alternateCandidate = if (winner != null && secondBestScore >= 15.0) secondBest else null
        // Recycle whichever of the top-2 didn't make the final cut (e.g.
        // winner was null so secondBest was never going to be returned as
        // .alternate either, or alternateCandidate was rejected by its
        // own threshold) — otherwise it leaks, since the loop above only
        // spared the top-2 from recycling on the assumption both would be
        // used or returned.
        if (winner == null && best != null) {
            try { best.recycle() } catch (e: Exception) { /* already gone */ }
        }
        if (alternateCandidate == null && secondBest != null && secondBest !== winner) {
            try { secondBest.recycle() } catch (e: Exception) { /* already gone */ }
        }

        val matchedVia = winner?.let { determineMatchedVia(it, resourceId, effectiveText, effectiveDesc) }
        return NodeMatchResult(winner, alternateCandidate, matchedVia)
    }

    /** Which signal actually won for this node — id > text > description > bounds-only, matching the priority the scoring above gives each. Used only for step-outcome logging, not for matching itself. */
    private fun determineMatchedVia(node: AccessibilityNodeInfo, resourceId: String?, effectiveText: String?, effectiveDesc: String?): String {
        if (!resourceId.isNullOrBlank() && node.viewIdResourceName == resourceId) return "id"
        val nodeText = node.text?.toString()
        if (!effectiveText.isNullOrBlank() && nodeText != null &&
            (nodeText.equals(effectiveText, ignoreCase = true) || nodeText.contains(effectiveText, ignoreCase = true))
        ) return "text"
        val nodeDesc = node.contentDescription?.toString()
        if (!effectiveDesc.isNullOrBlank() && nodeDesc != null &&
            (nodeDesc.equals(effectiveDesc, ignoreCase = true) || nodeDesc.contains(effectiveDesc, ignoreCase = true))
        ) return "description"
        return "bounds"
    }

    /**
     * Walks the WHOLE accessibility tree collecting every node. Only used
     * by findBestMatchingNode() as a last resort when neither id nor text
     * produced any candidates at all (icon-only elements) — bounded to a
     * sane depth so a pathological/cyclical tree can't hang replay.
     *
     * UPDATE (Phase 1a): this only ever runs when `candidates` is still
     * empty at this point, so there's no cross-lookup duplication to worry
     * about here specifically — but every node it adds still goes through
     * the same recycle-on-loss path in findBestMatchingNode() once scoring
     * is done, same as the id/text-sourced candidates.
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
