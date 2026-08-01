package com.junai.app.agent.action

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.junai.app.agent.screen.ScreenContextEngine
import kotlinx.coroutines.delay

data class ActionResult(val success: Boolean, val message: String)

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

enum class SettingsType {
    WIFI, BLUETOOTH, DISPLAY, SOUND, DND, AIRPLANE_MODE,
    WRITE_SETTINGS_PERMISSION, APP_DETAILS
}

/**
 * ActionEngine — executes real actions on the phone.
 *
 * TRUST BOUNDARY: this object assumes the caller (AgentEngine) already ran
 * DecisionEngine.evaluate() and received PROCEED before calling anything
 * here — it does not re-check SafetyLayer for payments/deletes/etc, that
 * gate already happened upstream. The one absolute exception is
 * [typeText]: it refuses to type into a password field no matter what,
 * since that's a flat prohibition (READ_CREDENTIAL), not a confirmable risk.
 *
 * VERIFICATION STRATEGY: for system-level toggles (Bluetooth, brightness,
 * volume, flashlight, DND, WiFi) this verifies against the *actual system
 * state* (e.g. BluetoothAdapter.isEnabled()) rather than screen text —
 * far more reliable than hoping a confirmation string appears somewhere.
 * For UI actions (tap, openApp) there's no such system-level signal, so
 * those retry the underlying call itself and, where meaningful, re-check
 * against ScreenContextEngine.
 */
@Suppress("DEPRECATION")
object ActionEngine {

    private const val TAG = "ActionEngine"
    private const val MAX_RETRIES = 2

    private fun log(action: String, target: String, result: ActionResult) {
        android.util.Log.d(TAG, "[$action] target=\"$target\" success=${result.success} msg=${result.message} t=${System.currentTimeMillis()}")
    }

    private fun service(): JunAccessibilityService? = JunAccessibilityService.instance

    private fun noServiceResult() =
        ActionResult(false, "Jun's Accessibility permission isn't on yet — turn it on in Settings to let me do this.")

    /** Generic retry wrapper — retries [check] up to [maxRetries] times if it returns false. */
    private suspend fun retryUntil(
        maxRetries: Int = MAX_RETRIES,
        delayMs: Long = 400L,
        check: suspend (attempt: Int) -> Boolean
    ): Pair<Boolean, Int> {
        var attempt = 0
        while (attempt <= maxRetries) {
            if (check(attempt)) return true to (attempt + 1)
            attempt++
            if (attempt <= maxRetries) delay(delayMs)
        }
        return false to attempt
    }

    /**
     * BUGFIX (root cause of "kabhi extra tasks ho jaate hain, kabhi kuch
     * execute nahi hota" — reproducible in a SINGLE macro run, no
     * concurrency needed): tap/long-press/scroll used to run their node
     * search AND the real action inside the same retryUntil check —
     * meaning every retry re-fired the actual click/hold/scroll, not just
     * re-searched. performAction() on Android can return false even when
     * the action DID happen (the view/window changes state right as the
     * call returns) — for a non-idempotent UI action, retrying after that
     * spurious false means the SAME element gets tapped/scrolled a second
     * real time: a toggle flips back off, a message gets sent twice, a
     * list scrolls twice as far. That's "extra tasks."
     * The flip side: if the node genuinely isn't there yet (next screen
     * still loading), the old code only got 3 total attempts at 400ms
     * apart before giving up and aborting the WHOLE macro — every step
     * after that one silently never ran. That's "kuch execute nahi hota."
     * This retries ONLY the search (cheap, side-effect-free, safe to
     * repeat while a screen settles) and performs the real action EXACTLY
     * ONCE, on whichever attempt actually finds the node.
     */
    private suspend fun retryFindNode(
        maxRetries: Int = MAX_RETRIES,
        delayMs: Long = 400L,
        find: suspend (attempt: Int) -> AccessibilityNodeInfo?
    ): AccessibilityNodeInfo? {
        var attempt = 0
        while (attempt <= maxRetries) {
            val node = find(attempt)
            if (node != null) return node
            attempt++
            if (attempt <= maxRetries) delay(delayMs)
        }
        return null
    }

    /**
     * PHASE 4: same polling shape as retryFindNode, but for
     * findBestMatchingNode()'s NodeMatchResult — keeps retrying while
     * .node is null (nothing confident found yet), same as before. Once
     * .node is non-null we stop immediately, even if the .alternate on
     * that particular attempt happens to be null — the alternate is only
     * ever a nice-to-have for the action-failed case below, never worth
     * burning a retry over on its own.
     */
    private suspend fun retryFindMatch(
        maxRetries: Int = MAX_RETRIES,
        delayMs: Long = 400L,
        find: suspend (attempt: Int) -> JunAccessibilityService.NodeMatchResult
    ): JunAccessibilityService.NodeMatchResult {
        var attempt = 0
        var last = JunAccessibilityService.NodeMatchResult(null, null, null)
        while (attempt <= maxRetries) {
            last = find(attempt)
            if (last.node != null) return last
            attempt++
            if (attempt <= maxRetries) delay(delayMs)
        }
        return last
    }

    /**
     * PHASE 4: tries `action` on match.node first; if that fails (action
     * itself returns false or throws — a real action failure, distinct
     * from findBestMatchingNode already having reported no confident
     * match), falls back to match.alternate before giving up. Both nodes
     * are recycled here exactly once each, regardless of outcome — callers
     * pass a raw action lambda and never touch recycle() themselves for
     * these two nodes.
     *
     * @return (success, usedAlternate) — usedAlternate is only meaningful when success is true
     */
    private suspend fun attemptOnMatch(
        match: JunAccessibilityService.NodeMatchResult,
        action: suspend (AccessibilityNodeInfo) -> Boolean
    ): Pair<Boolean, Boolean> {
        val primary = match.node
        if (primary != null) {
            val primaryOk = try { action(primary) } catch (e: Exception) { false }
            try { primary.recycle() } catch (e: Exception) { /* already gone */ }
            if (primaryOk) {
                match.alternate?.let { try { it.recycle() } catch (e: Exception) { /* already gone */ } }
                return true to false
            }
        }
        val alternate = match.alternate
        if (alternate != null) {
            val altOk = try { action(alternate) } catch (e: Exception) { false }
            try { alternate.recycle() } catch (e: Exception) { /* already gone */ }
            if (altOk) return true to true
        }
        return false to false
    }

    /**
     * PHASE 4: writes one row to step_outcomes. Fire-and-forget-ish but
     * still suspend (called from within the same IO-dispatched replay
     * coroutine, no separate launch needed) — wrapped in try/catch so a
     * logging hiccup (DB briefly locked, etc) can never fail the actual
     * replay step it's just trying to record the outcome of.
     * macroId/stepIndex are nullable: ActionEngine's Step functions are
     * only ever called from macro replay today, but keeping them optional
     * means a future non-macro caller doesn't have to fabricate fake ids
     * just to compile — it simply skips logging (see the null-check below).
     */
    private suspend fun logStepOutcome(
        context: Context,
        macroId: Int?,
        stepIndex: Int?,
        actionType: String,
        success: Boolean,
        matchedVia: String?,
        usedAlternate: Boolean,
        failureReason: String?
    ) {
        if (macroId == null || stepIndex == null) return
        try {
            com.junai.app.AppDatabase.getInstance(context).stepOutcomeDao().insert(
                com.junai.app.learning.StepOutcomeEntity(
                    macroId = macroId,
                    stepIndex = stepIndex,
                    actionType = actionType,
                    success = success,
                    matchedVia = matchedVia,
                    usedAlternate = usedAlternate,
                    failureReason = failureReason,
                    timestamp = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            android.util.Log.w("ActionEngine", "Failed to log step outcome: ${e.message}")
        }
    }

    // ══════════════════ ACCESSIBILITY-BASED ACTIONS ══════════════════

    suspend fun tap(nodeId: String): ActionResult {
        val svc = service() ?: return noServiceResult().also { log("tap", nodeId, it) }
        val node = retryFindNode { svc.findNodeByText(nodeId) ?: svc.findNodeById(nodeId) }
        val ok = node?.let { val r = svc.tap(it); it.recycle(); r } ?: false
        val result = if (ok) ActionResult(true, "Tapped \"$nodeId\".")
        else ActionResult(false, "Couldn't find or tap \"$nodeId\".")
        log("tap", nodeId, result)
        return result
    }

    /**
     * Tap variant used by macro replay, which has richer per-step info than
     * a single search string. Uses findBestMatchingNode() — when a
     * resourceId is shared across several near-identical elements (Quick
     * Settings tiles being the clearest example: Bluetooth/Location/WiFi
     * can all use the same tile template id), plain tap(resourceId) always
     * landed on the first match regardless of which tile was actually
     * recorded. Scoring in text/description/size/position lets us pick the
     * RIGHT one among same-id candidates instead of just the first.
     *
     * bounds (l,t,r,b) is the LAST-resort fallback — some elements (custom
     * launcher folder icons, OEM widgets) never expose a usable resourceId/
     * text/contentDescription at all, so if identifier-based lookup finds
     * nothing whatsoever, this taps the recorded screen position directly.
     * This is inherently less reliable (breaks if the UI reflows), which is
     * exactly why it's tried last, not first.
     */
    suspend fun tapStep(
        resourceId: String?,
        text: String?,
        contentDescription: String?,
        boundsLeft: Int? = null,
        boundsTop: Int? = null,
        boundsRight: Int? = null,
        boundsBottom: Int? = null,
        packageName: String? = null,
        className: String? = null,
        macroId: Int? = null,
        stepIndex: Int? = null,
        // PHASE B: forwarded from RecordedStep.idDistinctive — see that
        // field's doc comment and findBestMatchingNode's for what this
        // changes. Default null = old behavior, unchanged.
        idDistinctive: Boolean? = null
    ): ActionResult {
        val svc = service() ?: return noServiceResult().also { log("tapStep", resourceId ?: text ?: "?", it) }
        val label = resourceId ?: text ?: contentDescription ?: "?"
        ScreenContextEngine.currentBlockingOverlay()?.let { overlay ->
            val result = ActionResult(false, "Couldn't tap \"$label\" — a $overlay is covering the screen.")
            log("tapStep", label, result)
            return result
        }
        val match = retryFindMatch {
            svc.findBestMatchingNode(resourceId, text, contentDescription, className, boundsLeft, boundsTop, boundsRight, boundsBottom, packageName, idDistinctive)
        }
        val (ok, usedAlternate) = attemptOnMatch(match) { node -> svc.tap(node) }
        if (ok) {
            val result = ActionResult(true, "Tapped \"$label\".")
            log("tapStep", label, result)
            logStepOutcome(svc.applicationContext, macroId, stepIndex, "TAP", true, match.matchedVia, usedAlternate, null)
            return result
        }

        // BUGFIX: bounds are recorded in the ORIGINAL app's coordinate space
        // at capture time. If MacroReplayEngine's app-navigation step above
        // silently didn't land us in that app (a stale/false-positive
        // isAppInForeground read, or the switch just hadn't visually
        // settled yet), blindly tapping the recorded (x,y) here would hit
        // whatever happens to be on screen NOW — a different app entirely —
        // and often still "succeeds" (taps some unrelated element), which
        // is exactly how a macro can report "Ho gaya!" while doing nothing
        // the user actually sees. Only trust bounds when we've confirmed
        // we're still in the app they were recorded in.
        val safeToUseBounds = packageName.isNullOrBlank() || ScreenContextEngine.isAppInForeground(svc.applicationContext, packageName)
        if (safeToUseBounds && boundsLeft != null && boundsTop != null && boundsRight != null && boundsBottom != null) {
            val cx = (boundsLeft + boundsRight) / 2f
            val cy = (boundsTop + boundsBottom) / 2f
            val tapped = svc.tapAt(cx, cy)
            val result = if (tapped) {
                ActionResult(true, "Tapped \"$label\" by its recorded position (element identifiers weren't found — used the fallback coordinates).")
            } else {
                ActionResult(false, "Couldn't find or tap \"$label\" — identifiers not found and the fallback position tap also failed.")
            }
            log("tapStep", label, result)
            logStepOutcome(svc.applicationContext, macroId, stepIndex, "TAP", tapped, "bounds", false, if (tapped) null else "bounds_tap_failed")
            return result
        }
        if (!safeToUseBounds) {
            val result = ActionResult(false, "\"$label\" nahi mila, aur \"$packageName\" abhi foreground me nahi hai — isliye recorded position bhi try nahi ki (galat app me tap ho jaata).")
            log("tapStep", label, result)
            logStepOutcome(svc.applicationContext, macroId, stepIndex, "TAP", false, null, false, "wrong_app_foreground")
            return result
        }

        val result = ActionResult(false, "Couldn't find or tap \"$label\".")
        log("tapStep", label, result)
        logStepOutcome(svc.applicationContext, macroId, stepIndex, "TAP", false, null, false, "no_node_found")
        return result
    }

    suspend fun longPress(nodeId: String): ActionResult {
        val svc = service() ?: return noServiceResult().also { log("longPress", nodeId, it) }
        val node = retryFindNode { svc.findNodeByText(nodeId) ?: svc.findNodeById(nodeId) }
        val ok = node?.let { val r = svc.longPress(it); it.recycle(); r } ?: false
        val result = if (ok) ActionResult(true, "Long-pressed \"$nodeId\".")
        else ActionResult(false, "Couldn't long-press \"$nodeId\".")
        log("longPress", nodeId, result)
        return result
    }

    /**
     * LONG_PRESS variant used by macro replay. Same identifier→bounds
     * priority as tapStep — see that function's doc for why. Without this,
     * every recorded long-press (hold-to-select in a chat list, drag
     * handles, "hold to see options") replayed as a plain click via
     * tapStep, which is often a no-op or does the wrong thing entirely.
     */
    suspend fun longPressStep(
        resourceId: String?,
        text: String?,
        contentDescription: String?,
        boundsLeft: Int? = null,
        boundsTop: Int? = null,
        boundsRight: Int? = null,
        boundsBottom: Int? = null,
        packageName: String? = null,
        className: String? = null,
        macroId: Int? = null,
        stepIndex: Int? = null,
        idDistinctive: Boolean? = null
    ): ActionResult {
        val svc = service() ?: return noServiceResult().also { log("longPressStep", resourceId ?: text ?: "?", it) }
        val label = resourceId ?: text ?: contentDescription ?: "?"
        val match = retryFindMatch {
            svc.findBestMatchingNode(resourceId, text, contentDescription, className, boundsLeft, boundsTop, boundsRight, boundsBottom, packageName, idDistinctive)
        }
        val (ok, usedAlternate) = attemptOnMatch(match) { node -> svc.longPress(node) }
        if (ok) {
            val result = ActionResult(true, "Long-pressed \"$label\".")
            log("longPressStep", label, result)
            logStepOutcome(svc.applicationContext, macroId, stepIndex, "LONG_PRESS", true, match.matchedVia, usedAlternate, null)
            return result
        }

        val safeToUseBounds = packageName.isNullOrBlank() || ScreenContextEngine.isAppInForeground(svc.applicationContext, packageName)
        if (safeToUseBounds && boundsLeft != null && boundsTop != null && boundsRight != null && boundsBottom != null) {
            val cx = (boundsLeft + boundsRight) / 2f
            val cy = (boundsTop + boundsBottom) / 2f
            val held = svc.longPressAt(cx, cy)
            val result = if (held) {
                ActionResult(true, "Long-pressed \"$label\" by its recorded position (element identifiers weren't found — used the fallback coordinates).")
            } else {
                ActionResult(false, "Couldn't long-press \"$label\" — identifiers not found and the fallback position hold also failed.")
            }
            log("longPressStep", label, result)
            logStepOutcome(svc.applicationContext, macroId, stepIndex, "LONG_PRESS", held, "bounds", false, if (held) null else "bounds_tap_failed")
            return result
        }
        if (!safeToUseBounds) {
            val result = ActionResult(false, "\"$label\" nahi mila, aur \"$packageName\" abhi foreground me nahi hai — isliye recorded position bhi try nahi ki.")
            log("longPressStep", label, result)
            logStepOutcome(svc.applicationContext, macroId, stepIndex, "LONG_PRESS", false, null, false, "wrong_app_foreground")
            return result
        }

        val result = ActionResult(false, "Couldn't long-press \"$label\".")
        log("longPressStep", label, result)
        logStepOutcome(svc.applicationContext, macroId, stepIndex, "LONG_PRESS", false, null, false, "no_node_found")
        return result
    }

    suspend fun scroll(direction: ScrollDirection, nodeId: String): ActionResult {
        val svc = service() ?: return noServiceResult().also { log("scroll", nodeId, it) }
        val forward = direction == ScrollDirection.DOWN || direction == ScrollDirection.RIGHT
        val node = retryFindNode { svc.findNodeById(nodeId) ?: svc.findNodeByText(nodeId) }
        val ok = node?.let { val r = svc.scroll(it, forward); it.recycle(); r } ?: false
        val result = if (ok) ActionResult(true, "Scrolled \"$nodeId\" ${direction.name.lowercase()}.")
        else ActionResult(false, "Couldn't scroll \"$nodeId\".")
        log("scroll", nodeId, result)
        return result
    }

    /**
     * SWIPE variant used by macro replay (see RecordingEngine.captureScroll
     * for why this is scroll-node-based rather than a literal coordinate
     * drag by default). Falls back to a raw gesture along the recorded
     * bounds only if no scrollable node can be found by id/description —
     * that's inherently less reliable (the swiped area may have reflowed),
     * so it's the last resort, not the first attempt.
     */
    suspend fun scrollStep(
        resourceId: String?,
        contentDescription: String?,
        forward: Boolean,
        // BUGFIX (Phase 1h): the raw-gesture fallback below used to always
        // swipe vertically (varying Y, fixed X) no matter what was actually
        // recorded — see RecordedStep.scrollHorizontal's doc comment. A
        // horizontal carousel/tab-strip swipe falling back to raw gesture
        // would swipe the wrong way entirely. Defaults to false (vertical)
        // so old macros recorded before this field existed keep their old
        // behavior exactly, rather than silently changing under them.
        horizontal: Boolean = false,
        boundsLeft: Int? = null,
        boundsTop: Int? = null,
        boundsRight: Int? = null,
        boundsBottom: Int? = null,
        packageName: String? = null,
        className: String? = null,
        macroId: Int? = null,
        stepIndex: Int? = null
    ): ActionResult {
        val svc = service() ?: return noServiceResult().also { log("scrollStep", resourceId ?: contentDescription ?: "?", it) }
        val label = resourceId ?: contentDescription ?: "swipe"
        val match = retryFindMatch(maxRetries = 1) {
            svc.findBestMatchingNode(resourceId, null, contentDescription, className, boundsLeft, boundsTop, boundsRight, boundsBottom, packageName)
        }
        // BUGFIX: unlike tapStep/typeStep, scrollStep deliberately does NOT
        // use attemptOnMatch's alternate-candidate retry. A wrong TAP
        // opens the wrong screen; a wrong SCROLL can drag an entirely
        // unrelated scrollable container — on a real device this action'd
        // on the second-best-scoring candidate (picked purely by
        // identifier similarity, with no notion of "is this even the
        // right kind of scrollable") when the primary's scroll action
        // failed, and it happened to be the home screen's own page
        // pager — swiping straight into the Google feed panel on a
        // completely different screen. That's a far worse failure mode
        // than just falling through to the position-based raw-gesture
        // fallback below, so for SWIPE specifically we only ever act on
        // the primary candidate.
        val ok = match.node?.let { node ->
            val r = try { svc.scroll(node, forward) } catch (e: Exception) { false }
            try { node.recycle() } catch (e: Exception) { /* already gone */ }
            r
        } ?: false
        match.alternate?.let { try { it.recycle() } catch (e: Exception) { /* already gone */ } }
        if (ok) {
            val result = ActionResult(true, "Scrolled \"$label\".")
            log("scrollStep", label, result)
            logStepOutcome(svc.applicationContext, macroId, stepIndex, "SWIPE", true, match.matchedVia, false, null)
            return result
        }

        // No scrollable node found — replay the literal swipe using the
        // recorded bounds as the drag path, but only if we've confirmed
        // we're still in the app it was recorded in (see tapStep's doc for
        // why an unguarded coordinate fallback is dangerous).
        val safeToUseBounds = packageName.isNullOrBlank() || ScreenContextEngine.isAppInForeground(svc.applicationContext, packageName)
        if (safeToUseBounds && boundsLeft != null && boundsTop != null && boundsRight != null && boundsBottom != null) {
            val swiped = if (horizontal) {
                // BUGFIX (Phase 1h): horizontal axis — vary X, fixed Y.
                // Mirrors the vertical case's direction convention: forward
                // means content moves left (revealing what's further along
                // a carousel/tab-strip), so the finger swipes right-to-left.
                val cy = (boundsTop + boundsBottom) / 2f
                val leftX = boundsLeft + (boundsRight - boundsLeft) * 0.25f
                val rightX = boundsLeft + (boundsRight - boundsLeft) * 0.75f
                if (forward) svc.swipeAt(rightX, cy, leftX, cy) else svc.swipeAt(leftX, cy, rightX, cy)
            } else {
                val cx = (boundsLeft + boundsRight) / 2f
                val topY = boundsTop + (boundsBottom - boundsTop) * 0.25f
                val bottomY = boundsTop + (boundsBottom - boundsTop) * 0.75f
                if (forward) svc.swipeAt(cx, bottomY, cx, topY) else svc.swipeAt(cx, topY, cx, bottomY)
            }
            val result = if (swiped) {
                ActionResult(true, "Swiped \"$label\" by its recorded position (scrollable element not found — used a raw gesture instead).")
            } else {
                ActionResult(false, "Couldn't scroll or swipe \"$label\" — element not found and the fallback gesture also failed.")
            }
            log("scrollStep", label, result)
            logStepOutcome(svc.applicationContext, macroId, stepIndex, "SWIPE", swiped, "bounds", false, if (swiped) null else "bounds_swipe_failed")
            return result
        }
        if (!safeToUseBounds) {
            val result = ActionResult(false, "\"$label\" nahi mila, aur \"$packageName\" abhi foreground me nahi hai — isliye fallback swipe bhi try nahi kiya.")
            log("scrollStep", label, result)
            logStepOutcome(svc.applicationContext, macroId, stepIndex, "SWIPE", false, null, false, "wrong_app_foreground")
            return result
        }

        val result = ActionResult(false, "Couldn't scroll \"$label\".")
        log("scrollStep", label, result)
        logStepOutcome(svc.applicationContext, macroId, stepIndex, "SWIPE", false, null, false, "no_node_found")
        return result
    }

    /** Refuses outright if the resolved field is a password field — no exceptions, ever. */
    suspend fun typeText(nodeId: String, text: String): ActionResult {
        val svc = service() ?: return noServiceResult().also { log("typeText", nodeId, it) }

        val precheck = svc.findNodeById(nodeId) ?: svc.findNodeByText(nodeId)
        if (precheck?.isPassword == true) {
            precheck.recycle()
            val result = ActionResult(false, "Jun never types into password fields — no exceptions.")
            log("typeText", nodeId, result)
            return result
        }
        precheck?.recycle()

        val (ok, attempts) = retryUntil { _ ->
            val node = svc.findNodeById(nodeId) ?: svc.findNodeByText(nodeId)
            if (node == null) return@retryUntil false
            if (node.isPassword) { node.recycle(); return@retryUntil false }
            val typed = svc.typeText(node, text)
            // BUGFIX: `node` is a point-in-time snapshot. After performAction()
            // mutates the real view, this same AccessibilityNodeInfo object
            // still reports its PRE-action text until explicitly refreshed —
            // so the old code almost always read stale (often empty/hint)
            // text here and reported "couldn't find the message field" even
            // when typing actually succeeded. refresh() re-syncs the node
            // with the live view before we read .text.
            node.refresh()
            val confirmed = typed && node.text?.toString()?.contains(text) == true
            node.recycle()
            confirmed
        }
        val result = if (ok) ActionResult(true, "Typed into \"$nodeId\".")
        else ActionResult(false, "Couldn't confirm text was typed into \"$nodeId\" after $attempts attempt(s).")
        log("typeText", nodeId, result)
        return result
    }

    /**
     * TYPE variant used by macro replay.
     *
     * THE FIX for "Step X/Y pe ruk gaya — koi identifier hi save nahi hua":
     * plenty of real EditTexts (custom keyboards, WebView inputs, Compose
     * fields with no testTag/contentDescription) expose neither a
     * resourceId nor a contentDescription. Previously a TYPE step on one of
     * those had genuinely nothing to fall back on and replay died
     * immediately. Bounds are now always recorded for TYPE steps (see
     * RecordingEngine.captureType), so this hit-tests the screen at the
     * recorded position via findNodeAtPosition() as a last resort — same
     * "identifier first, position last" priority tapStep already uses.
     *
     * Still refuses password fields outright, no exceptions — same rule as
     * typeText() above.
     */
    suspend fun typeStep(
        resourceId: String?,
        contentDescription: String?,
        typedText: String,
        boundsLeft: Int? = null,
        boundsTop: Int? = null,
        boundsRight: Int? = null,
        boundsBottom: Int? = null,
        packageName: String? = null,
        className: String? = null,
        macroId: Int? = null,
        stepIndex: Int? = null
    ): ActionResult {
        val svc = service() ?: return noServiceResult().also { log("typeStep", resourceId ?: contentDescription ?: "?", it) }
        val label = resourceId ?: contentDescription ?: "?"
        ScreenContextEngine.currentBlockingOverlay()?.let { overlay ->
            val result = ActionResult(false, "Couldn't type into \"$label\" — a $overlay is covering the screen.")
            log("typeStep", label, result)
            return result
        }
        val safeToUseBounds = packageName.isNullOrBlank() || ScreenContextEngine.isAppInForeground(svc.applicationContext, packageName)

        // Captured from inside the retryUntil lambda below so the final
        // logStepOutcome() call after the loop knows exactly which path
        // won (or that none did) on the attempt that decided the outcome.
        var lastMatchedVia: String? = null
        var lastUsedAlternate = false

        suspend fun tryType(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.isPassword) { node.recycle(); return false }
            val typed = svc.typeText(node, typedText)
            // Same staleness fix as typeText() above — re-sync before reading.
            node.refresh()
            val confirmed = typed && node.text?.toString()?.contains(typedText) == true
            node.recycle()
            return confirmed
        }

        val (ok, attempts) = retryUntil { _ ->
            val match = svc.findBestMatchingNode(resourceId, null, contentDescription, className, boundsLeft, boundsTop, boundsRight, boundsBottom, packageName)

            if (tryType(match.node)) {
                lastMatchedVia = match.matchedVia
                lastUsedAlternate = false
                match.alternate?.let { try { it.recycle() } catch (e: Exception) { /* already gone */ } }
                return@retryUntil true
            }
            if (tryType(match.alternate)) {
                lastMatchedVia = match.matchedVia
                lastUsedAlternate = true
                return@retryUntil true
            }

            // Neither identifier-based candidate worked (or none was found
            // at all) — same wrong-app risk tapStep's bounds fallback has,
            // only hit-test the recorded position if we've confirmed we're
            // actually still in the recorded app, otherwise this could
            // silently type into an unrelated field in whatever app
            // happens to be on screen.
            if (safeToUseBounds && boundsLeft != null && boundsTop != null && boundsRight != null && boundsBottom != null) {
                val cx = (boundsLeft + boundsRight) / 2f
                val cy = (boundsTop + boundsBottom) / 2f
                val boundsNode = svc.findNodeAtPosition(cx, cy)
                if (tryType(boundsNode)) {
                    lastMatchedVia = "bounds"
                    lastUsedAlternate = false
                    return@retryUntil true
                }
            }
            false
        }
        val result = if (ok) {
            ActionResult(true, "Typed into \"$label\" (attempt $attempts).")
        } else if (!safeToUseBounds) {
            ActionResult(false, "\"$label\" nahi mila, aur \"$packageName\" abhi foreground me nahi hai — isliye recorded position bhi try nahi ki.")
        } else if (resourceId.isNullOrBlank() && contentDescription.isNullOrBlank() && boundsLeft == null) {
            ActionResult(false, "Password field ya koi identifier/position nahi mila — is field me type nahi kar payi.")
        } else {
            ActionResult(false, "Couldn't find or type into \"$label\" after $attempts attempt(s).")
        }
        log("typeStep", label, result)
        val failureReason = if (ok) null else if (!safeToUseBounds) "wrong_app_foreground" else "no_node_found"
        logStepOutcome(svc.applicationContext, macroId, stepIndex, "TYPE", ok, lastMatchedVia, lastUsedAlternate, failureReason)
        return result
    }

    fun pressBack(): ActionResult {
        val svc = service() ?: return noServiceResult().also { log("pressBack", "-", it) }
        val ok = svc.pressBack()
        val result = if (ok) ActionResult(true, "Pressed back.") else ActionResult(false, "Couldn't press back.")
        log("pressBack", "-", result)
        return result
    }

    fun pressHome(): ActionResult {
        val svc = service() ?: return noServiceResult().also { log("pressHome", "-", it) }
        val ok = svc.pressHome()
        val result = if (ok) ActionResult(true, "Pressed home.") else ActionResult(false, "Couldn't press home.")
        log("pressHome", "-", result)
        return result
    }

    suspend fun openApp(context: Context, packageName: String): ActionResult {
        var launched = false
        val (ok, attempts) = retryUntil(delayMs = 500L) { _ ->
            try {
                if (ScreenContextEngine.isAppInForeground(packageName)) return@retryUntil true
                // BUGFIX: previously this re-sent the launch Intent on every
                // retry (every ~1.1s) whenever isAppInForeground still read
                // false. If the app WAS actually opening but the foreground
                // snapshot just hadn't caught up yet, re-launching mid
                // open-animation restarts/interrupts that transition —
                // which could keep currentApp from ever settling long enough
                // to be sampled as "in foreground", so confirmation failed
                // forever even though the app was genuinely open on screen.
                // Now the intent fires exactly once; remaining attempts only
                // poll the snapshot, giving it time to catch up.
                if (!launched) {
                    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                        ?: return@retryUntil false
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    launched = true
                }
                delay(500L)
                ScreenContextEngine.isAppInForeground(packageName)
            } catch (e: Exception) {
                false
            }
        }
        var result = if (ok) ActionResult(true, "Opened $packageName.")
        else if (!launched) ActionResult(false, "Couldn't find $packageName — it may not be installed.")
        else ActionResult(false, "Opened $packageName but couldn't confirm it's in the foreground after $attempts attempt(s).")

        // BUGFIX: the first version of this extended wait only kicked in if
        // looksLikeLockScreen() was ALREADY true right after the initial 3
        // quick attempts. But a slow cold-start (e.g. on low battery, where
        // Android throttles app launch) can just be genuinely slow with NO
        // lock screen at all — that case fell through with no extra wait
        // and no banner, and just failed. Now we always give a slow-to-open
        // app more time; the banner only appears if/when a lock screen is
        // actually spotted DURING that wait, and its wording adapts if one
        // shows up partway through.
        if (!ok && launched) {
            var bannerShown = false
            val unlocked = run {
                val deadline = System.currentTimeMillis() + 25_000L
                while (System.currentTimeMillis() < deadline) {
                    if (ScreenContextEngine.isAppInForeground(packageName) && !ScreenContextEngine.looksLikeLockScreen()) {
                        return@run true
                    }
                    val secondsLeft = ((deadline - System.currentTimeMillis()) / 1000L).coerceAtLeast(0)
                    if (ScreenContextEngine.looksLikeLockScreen()) {
                        bannerShown = true
                        LockWaitBannerOverlay.show(context, "🔒 Waiting for you to unlock $packageName… (${secondsLeft}s)")
                    }
                    delay(500L)
                }
                false
            }
            if (bannerShown) LockWaitBannerOverlay.hide()
            result = when {
                unlocked && bannerShown -> ActionResult(true, "Opened $packageName (waited for you to unlock it).")
                unlocked -> ActionResult(true, "Opened $packageName (took a bit longer than usual).")
                bannerShown -> ActionResult(false, "$packageName is locked and wasn't unlocked in time — unlock it and try again.")
                else -> ActionResult(false, "Opened $packageName but couldn't confirm it's in the foreground after waiting.")
            }
        }

        log("openApp", packageName, result)
        return result
    }


    /** UP/DOWN scrolls whatever's currently scrollable on screen. LEFT/RIGHT not yet supported. */
    suspend fun navigate(direction: ScrollDirection): ActionResult {
        val result = when (direction) {
            ScrollDirection.UP, ScrollDirection.DOWN -> {
                val scrollableId = ScreenContextEngine.getCurrentContext().scrollableAreas.firstOrNull()
                if (scrollableId == null) ActionResult(false, "Nothing scrollable found on this screen.")
                else scroll(direction, scrollableId)
            }
            ScrollDirection.LEFT, ScrollDirection.RIGHT ->
                ActionResult(false, "Horizontal navigation isn't supported yet — only vertical scrolling.")
        }
        log("navigate", direction.name, result)
        return result
    }

    suspend fun waitForScreen(expectedText: String, timeoutMs: Long = 5000L): ActionResult {
        val found = ScreenContextEngine.waitForText(expectedText, timeoutMs)
        val result = if (found) ActionResult(true, "\"$expectedText\" appeared.")
        else ActionResult(false, "\"$expectedText\" never appeared within ${timeoutMs}ms.")
        log("waitForScreen", expectedText, result)
        return result
    }

    // ══════════════════════ SYSTEM-LEVEL ACTIONS ══════════════════════

    /**
     * WiFi can't be toggled programmatically on modern Android without
     * being a system app. Best-effort: open settings, try to tap the
     * toggle via Accessibility, then verify against the REAL state via
     * WifiManager.isWifiEnabled() (the getter still works even though the
     * setter is restricted). Heuristic — may need on-device tuning per OEM
     * settings UI (Samsung / Xiaomi / stock Android all differ).
     */
    suspend fun setWifi(context: Context, enabled: Boolean): ActionResult {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager?.isWifiEnabled == enabled) {
            val result = ActionResult(true, "WiFi already ${if (enabled) "on" else "off"}.")
            log("setWifi", enabled.toString(), result)
            return result
        }

        openSpecificSettings(context, SettingsType.WIFI)
        delay(800L)
        val svc = service()

        val (ok, attempts) = retryUntil { _ ->
            if (svc != null) {
                val node = svc.findNodeByText("Wi-Fi") ?: svc.findNodeByText("WiFi")
                node?.let { svc.tap(it).also { _ -> it.recycle() } }
                delay(500L)
            }
            wifiManager?.isWifiEnabled == enabled
        }
        val result = if (ok) ActionResult(true, "WiFi is now ${if (enabled) "on" else "off"}.")
        else ActionResult(false, "Opened WiFi settings but couldn't confirm the toggle changed after $attempts attempt(s) — please toggle it manually.")
        log("setWifi", enabled.toString(), result)
        return result
    }

    suspend fun setBluetooth(context: Context, enabled: Boolean): ActionResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            val result = ActionResult(false, "Bluetooth permission isn't granted — grant it in Permission Centre first.")
            log("setBluetooth", enabled.toString(), result)
            return result
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            val result = ActionResult(false, "This device doesn't seem to have Bluetooth.")
            log("setBluetooth", enabled.toString(), result)
            return result
        }
        val (ok, attempts) = retryUntil { _ ->
            try {
                if (enabled) adapter.enable() else adapter.disable()
            } catch (e: SecurityException) {
                // fall through — the state check below will reflect reality either way
            }
            delay(600L)
            adapter.isEnabled == enabled
        }
        val result = if (ok) ActionResult(true, "Bluetooth ${if (enabled) "enabled" else "disabled"}.")
        else ActionResult(false, "Couldn't confirm Bluetooth changed after $attempts attempt(s).")
        log("setBluetooth", enabled.toString(), result)
        return result
    }

    fun setBrightness(context: Context, level: Int): ActionResult {
        val clamped = level.coerceIn(0, 255)
        if (!Settings.System.canWrite(context)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            val result = ActionResult(false, "Need permission to change brightness — opened the settings page to grant it.")
            log("setBrightness", clamped.toString(), result)
            return result
        }
        val result = try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, clamped)
            val actual = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            if (actual == clamped) ActionResult(true, "Brightness set to $clamped/255.")
            else ActionResult(false, "Tried to set brightness but the system reports $actual/255 instead.")
        } catch (e: Exception) {
            ActionResult(false, "Couldn't change brightness: ${e.message}")
        }
        log("setBrightness", clamped.toString(), result)
        return result
    }

    fun setVolume(context: Context, stream: Int, level: Int): ActionResult {
        val result = try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audioManager.getStreamMaxVolume(stream)
            val clamped = level.coerceIn(0, max)
            audioManager.setStreamVolume(stream, clamped, 0)
            val actual = audioManager.getStreamVolume(stream)
            if (actual == clamped) ActionResult(true, "Volume set to $clamped/$max.")
            else ActionResult(false, "Tried to set volume but the system reports $actual/$max instead.")
        } catch (e: SecurityException) {
            ActionResult(false, "Don't have permission to change this volume stream — likely needs Do Not Disturb access.")
        } catch (e: Exception) {
            ActionResult(false, "Couldn't change volume: ${e.message}")
        }
        log("setVolume", "$stream:$level", result)
        return result
    }

    fun toggleFlashlight(context: Context, enabled: Boolean): ActionResult {
        val result = try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId == null) {
                ActionResult(false, "This device doesn't have a flashlight.")
            } else {
                cameraManager.setTorchMode(cameraId, enabled)
                ActionResult(true, "Flashlight ${if (enabled) "on" else "off"}.")
            }
        } catch (e: Exception) {
            ActionResult(false, "Couldn't change flashlight: ${e.message}")
        }
        log("toggleFlashlight", enabled.toString(), result)
        return result
    }

    fun toggleDND(context: Context, enabled: Boolean): ActionResult {
        val result = try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!notificationManager.isNotificationPolicyAccessGranted) {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ActionResult(false, "Need Do Not Disturb permission — opened the settings page to grant it.")
            } else {
                notificationManager.setInterruptionFilter(
                    if (enabled) NotificationManager.INTERRUPTION_FILTER_NONE else NotificationManager.INTERRUPTION_FILTER_ALL
                )
                val actuallyOn = notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
                if (actuallyOn == enabled) ActionResult(true, "Do Not Disturb ${if (enabled) "on" else "off"}.")
                else ActionResult(false, "Tried to change Do Not Disturb but it didn't take effect.")
            }
        } catch (e: Exception) {
            ActionResult(false, "Couldn't change Do Not Disturb: ${e.message}")
        }
        log("toggleDND", enabled.toString(), result)
        return result
    }

    /** Android doesn't allow programmatic airplane mode toggling for non-system apps — open settings instead. */
    fun toggleAirplaneMode(context: Context): ActionResult {
        val opened = openSpecificSettings(context, SettingsType.AIRPLANE_MODE)
        val result = ActionResult(
            opened.success,
            "Opened Airplane Mode settings — please toggle it manually, Android doesn't allow apps to do this directly."
        )
        log("toggleAirplaneMode", "-", result)
        return result
    }

    fun openSpecificSettings(context: Context, type: SettingsType): ActionResult {
        val action = when (type) {
            SettingsType.WIFI -> Settings.ACTION_WIFI_SETTINGS
            SettingsType.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            SettingsType.DISPLAY -> Settings.ACTION_DISPLAY_SETTINGS
            SettingsType.SOUND -> Settings.ACTION_SOUND_SETTINGS
            SettingsType.DND -> Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
            SettingsType.AIRPLANE_MODE -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            SettingsType.WRITE_SETTINGS_PERMISSION -> Settings.ACTION_MANAGE_WRITE_SETTINGS
            SettingsType.APP_DETAILS -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        }
        val result = try {
            val intent = Intent(action)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (type == SettingsType.WRITE_SETTINGS_PERMISSION || type == SettingsType.APP_DETAILS) {
                intent.data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
            ActionResult(true, "Opened settings.")
        } catch (e: Exception) {
            ActionResult(false, "Couldn't open settings: ${e.message}")
        }
        log("openSpecificSettings", type.name, result)
        return result
    }
}
