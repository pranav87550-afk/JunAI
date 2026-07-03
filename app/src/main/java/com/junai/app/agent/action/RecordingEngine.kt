package com.junai.app.agent.action

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.junai.app.AppDatabase
import com.junai.app.agent.screen.ScreenContextEngine
import com.junai.app.learning.RecordedMacroEntity
import com.junai.app.learning.RecordedStep
import org.json.JSONArray
import org.json.JSONObject

/**
 * Records a sequence of user gestures (taps + typed text) as an
 * identifier-based macro, so it can be replayed later for the same trigger
 * phrase. See RecordedStep for why identifiers (resourceId/text/
 * contentDescription) are used instead of raw coordinates.
 *
 * SECURITY: password/PIN/OTP fields are excluded at CAPTURE time, not
 * filtered afterward — isSensitive() runs before a step is ever added to
 * the in-memory list, so sensitive input never exists in this object, let
 * alone reaches the database. This is a hard rule, not a toggle.
 */
object RecordingEngine {

    private var recordingActive = false
    private var triggerPhrase: String = ""
    private var displayPhrase: String = ""
    private val steps = mutableListOf<RecordedStep>()

    /**
     * BUGFIX (root cause of an extra/wrong trailing digit on things like
     * dialer-keypad macros): a real on-screen keypad's digit buttons (phone
     * dialer, PIN pads, in-app numeric keypads) are actual clickable Views
     * — unlike a soft keyboard (Gboard etc.), which draws its own keys and
     * isn't visible to accessibility at all. So every digit press there
     * fires TWO events: a click on the button (→ captureTap, a TAP step)
     * AND a text-changed on the number display it updates (→ captureType).
     * Because a TAP step got recorded in between, captureType's "same
     * field, just update the text" collapse below never matches (the
     * *last* step is that TAP, not a TYPE) — so instead of one clean TYPE
     * step per field, it recorded a NEW partial-text TYPE step after every
     * single digit. On replay, those partial TYPE steps (each firing
     * ACTION_SET_TEXT) interleaved with the real digit-button TAPs — one
     * appends for real, the other overwrites the whole field — and
     * depending on exact ordering, a digit could end up counted twice.
     * Tracking when the last TAP was captured lets captureType recognize
     * "this text-changed event is just the on-screen echo of the button I
     * already recorded a tap for" and skip it — the TAP alone reproduces
     * the digit correctly on replay, same as pressing the real button.
     */
    private var lastTapAt = 0L
    private const val TAP_ECHO_WINDOW_MS = 400L

    /**
     * BUGFIX (safety net for the volume-key conflict described above):
     * the ONLY way to end a recording is a Volume Up/Down press — there's
     * no timeout and no other stop path. If a "teach" session is ever
     * forgotten (app backgrounded mid-demo, user gets distracted, etc.),
     * it stays active indefinitely — and the NEXT volume press for
     * literally anything else (turning down media volume, stopping an
     * unrelated screen recording) gets silently hijacked into ending it,
     * which also force-opens JunAI. recordingStartedAt + isStale() lets
     * the service auto-cancel a session that's been open too long, so a
     * forgotten one can't sit there waiting to hijack an unrelated
     * keypress hours later. This doesn't fix the underlying key conflict
     * (a dedicated in-app stop control would be the real fix) — it just
     * bounds how long a forgotten session can cause it.
     */
    private var recordingStartedAt = 0L
    private const val STALE_RECORDING_MS = 3 * 60 * 1000L
    fun isStale(): Boolean = recordingActive && System.currentTimeMillis() - recordingStartedAt > STALE_RECORDING_MS

    val isRecording: Boolean get() = recordingActive

    fun start(triggerPhrase: String, displayPhrase: String) {
        steps.clear()
        this.triggerPhrase = triggerPhrase.lowercase().trim()
        this.displayPhrase = displayPhrase
        recordingStartedAt = System.currentTimeMillis()
        recordingActive = true
    }

    fun cancel() {
        recordingActive = false
        steps.clear()
    }

    fun stepCount(): Int = steps.size

    /**
     * BUGFIX (root cause of "replay always taps Location no matter which
     * Quick Settings tile was demonstrated"): captureTap/captureLongPress
     * used to read text/contentDescription straight off the tapped node.
     * That's fine for normal app buttons, but icon-only controls — Quick
     * Settings tiles being the clearest example — put their actual
     * accessible name ("Location", "Wi-Fi", "Bluetooth"...) on a PARENT
     * container, not the icon you actually tap. The icon itself usually
     * has neither text nor contentDescription, and shares the same
     * generic resourceId as every other tile. So every tile got recorded
     * with no real distinguishing label — just a shared id — and replay's
     * matching had almost nothing but bounds to go on, which drifts as
     * soon as the panel's layout shifts even slightly (tile reordering,
     * a banner appearing, which of the two QS pages is open) and
     * converges on whichever tile ends up "closest" — Location here.
     * Walking a few levels up for a label when the tapped node itself has
     * none gives the step a real name to match on, the same way a person
     * would describe what they tapped ("the Location tile"), not just its
     * on-screen position.
     */
    private fun labelOf(node: AccessibilityNodeInfo): Pair<String?, String?> {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 5) {
            val text = n.text?.toString()?.takeIf { it.isNotBlank() }
            val desc = n.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            if (text != null || desc != null) return text to desc
            n = n.parent
            depth++
        }
        return null to null
    }

    /** Called by JunAccessibilityService on TYPE_VIEW_CLICKED while recording. */
    fun captureTap(node: AccessibilityNodeInfo) {
        if (!recordingActive) return
        if (isSensitive(node)) return
        lastTapAt = System.currentTimeMillis()
        val rect = boundsOf(node)
        val (label, desc) = labelOf(node)
        steps.add(
            RecordedStep(
                actionType = "TAP",
                packageName = node.packageName?.toString(),
                resourceId = node.viewIdResourceName,
                text = label,
                contentDescription = desc,
                className = node.className?.toString(),
                boundsLeft = rect.left,
                boundsTop = rect.top,
                boundsRight = rect.right,
                boundsBottom = rect.bottom
            )
        )
    }

    /**
     * Called by JunAccessibilityService on TYPE_VIEW_CONTEXT_CLICKED while
     * recording — this is the event Android fires for a long-press on most
     * views (context menus, drag handles, "hold to select" rows). Recorded
     * as its own action type ("LONG_PRESS") instead of being folded into
     * TAP, so replay can call ACTION_LONG_CLICK instead of ACTION_CLICK —
     * a plain tap where a hold was recorded often does nothing, or the
     * wrong thing (e.g. opens a chat instead of selecting it).
     */
    fun captureLongPress(node: AccessibilityNodeInfo) {
        if (!recordingActive) return
        if (isSensitive(node)) return
        val rect = boundsOf(node)
        val (label, desc) = labelOf(node)
        steps.add(
            RecordedStep(
                actionType = "LONG_PRESS",
                packageName = node.packageName?.toString(),
                resourceId = node.viewIdResourceName,
                text = label,
                contentDescription = desc,
                className = node.className?.toString(),
                boundsLeft = rect.left,
                boundsTop = rect.top,
                boundsRight = rect.right,
                boundsBottom = rect.bottom
            )
        )
    }

    /** Called by JunAccessibilityService on TYPE_VIEW_TEXT_CHANGED while recording. */
    fun captureType(node: AccessibilityNodeInfo, typedText: String) {
        if (!recordingActive) return
        if (isSensitive(node)) return
        if (typedText.isBlank()) return
        if (System.currentTimeMillis() - lastTapAt < TAP_ECHO_WINDOW_MS) return

        // BUGFIX: bounds are now captured for TYPE steps too. Previously
        // only resourceId/contentDescription were stored, and plenty of
        // real EditTexts (custom keyboards, WebView inputs, Compose fields
        // without a testTag/contentDescription) expose neither — replay
        // then had zero identifiers to work with and died immediately with
        // "koi identifier hi save nahi hua". Bounds give it a last-resort
        // position-based fallback (see ActionEngine.typeStep).
        val rect = boundsOf(node)

        // Collapse to one TYPE step per field — without this, every single
        // keystroke would append a new step (text-changed fires per
        // character), producing dozens of near-duplicate steps for one
        // typed sentence. Matched by resourceId when available, else by
        // the field's position (covers id-less fields too).
        val last = steps.lastOrNull()
        val sameField = last != null && last.actionType == "TYPE" && (
            (node.viewIdResourceName != null && last.resourceId == node.viewIdResourceName) ||
                (node.viewIdResourceName == null && last.resourceId == null &&
                    last.boundsLeft == rect.left && last.boundsTop == rect.top)
            )
        if (sameField) {
            steps[steps.lastIndex] = last!!.copy(typedText = typedText)
        } else {
            steps.add(
                RecordedStep(
                    actionType = "TYPE",
                    packageName = node.packageName?.toString(),
                    resourceId = node.viewIdResourceName,
                    text = null,
                    contentDescription = node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
                    className = node.className?.toString(),
                    typedText = typedText,
                    boundsLeft = rect.left,
                    boundsTop = rect.top,
                    boundsRight = rect.right,
                    boundsBottom = rect.bottom
                )
            )
        }
    }

    /**
     * Called by JunAccessibilityService on TYPE_VIEW_SCROLLED while
     * recording. A raw finger swipe isn't visible to an AccessibilityService
     * as a gesture (that needs touch-exploration mode, which changes how
     * touch works system-wide and isn't appropriate here) — but a swipe
     * that actually scrolls a list/page fires this event on the scrollable
     * node, which is a reliable proxy for "the user swiped here". Replayed
     * via ACTION_SCROLL_FORWARD/BACKWARD, not a literal coordinate drag, so
     * it isn't thrown off by list items reflowing between record and replay.
     */
    fun captureScroll(node: AccessibilityNodeInfo, forward: Boolean) {
        if (!recordingActive) return
        if (isSensitive(node)) return
        val rect = boundsOf(node)

        // Debounce: a single physical swipe can fire several
        // TYPE_VIEW_SCROLLED events in quick succession as the list
        // settles. Update the last step in place instead of adding a new
        // one per event, same idea as the TYPE collapse above.
        val last = steps.lastOrNull()
        if (last != null && last.actionType == "SWIPE" &&
            last.resourceId == node.viewIdResourceName && last.scrollForward == forward
        ) {
            return
        }
        steps.add(
            RecordedStep(
                actionType = "SWIPE",
                packageName = node.packageName?.toString(),
                resourceId = node.viewIdResourceName,
                text = null,
                contentDescription = node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
                className = node.className?.toString(),
                boundsLeft = rect.left,
                boundsTop = rect.top,
                boundsRight = rect.right,
                boundsBottom = rect.bottom,
                scrollForward = forward
            )
        )
    }

    private fun boundsOf(node: AccessibilityNodeInfo): android.graphics.Rect {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        return rect
    }

    private fun isSensitive(node: AccessibilityNodeInfo): Boolean {
        return node.isPassword ||
            ScreenContextEngine.looksLikeSensitiveLabel(node.hintText?.toString()) ||
            ScreenContextEngine.looksLikeSensitiveLabel(node.text?.toString()) ||
            ScreenContextEngine.looksLikeSensitiveLabel(node.contentDescription?.toString()) ||
            ScreenContextEngine.looksLikeSensitiveLabel(node.viewIdResourceName)
    }

    /**
     * BUGFIX/IMPROVEMENT — this is the actual "summarize before storing"
     * step that was missing. Live accessibility events arrive exactly
     * whenever the OS decides to deliver them: sometimes doubled,
     * sometimes interleaved unpredictably (TAP_ECHO_WINDOW_MS and
     * labelOf() above already work around two specific known cases of
     * this). But per-event fixes at capture time can only ever react to
     * one event in isolation — they can't see the full picture. This pass
     * runs ONCE, after recording stops, with the entire sequence in hand,
     * so it can catch what a live listener structurally can't: e.g. the
     * exact same tap on the exact same target, back-to-back, with nothing
     * real in between — which is the OS reporting one physical action
     * twice, not the user doing something twice. This is the difference
     * between raw capture and an actual summary: raw capture is "every
     * event that fired"; a summary is "what the person actually did."
     */
    private fun normalize(raw: List<RecordedStep>): List<RecordedStep> {
        if (raw.isEmpty()) return raw
        val out = mutableListOf<RecordedStep>()
        for (step in raw) {
            val prev = out.lastOrNull()
            val isBackToBackDuplicate = prev != null &&
                prev.actionType == step.actionType &&
                (step.actionType == "TAP" || step.actionType == "LONG_PRESS") &&
                prev.resourceId == step.resourceId &&
                prev.text == step.text &&
                prev.contentDescription == step.contentDescription &&
                prev.boundsLeft == step.boundsLeft &&
                prev.boundsTop == step.boundsTop &&
                prev.boundsRight == step.boundsRight &&
                prev.boundsBottom == step.boundsBottom
            if (isBackToBackDuplicate) continue
            out.add(step)
        }
        return out
    }

    /**
     * Stops recording and persists whatever was captured. Returns null if
     * nothing usable was recorded (e.g. every tap happened to be on a
     * sensitive field, or the user pressed volume before doing anything).
     */
    suspend fun stopAndSave(context: Context): RecordedMacroEntity? {
        recordingActive = false
        if (steps.isEmpty()) return null

        val cleaned = normalize(steps)
        if (cleaned.isEmpty()) return null
        val duplicatesRemoved = steps.size - cleaned.size

        val stepsJson = serializeSteps(cleaned)
        val entity = RecordedMacroEntity(
            triggerPhrase = triggerPhrase,
            displayPhrase = displayPhrase,
            stepsJson = stepsJson,
            stepCount = cleaned.size,
            createdAt = System.currentTimeMillis()
        )
        AppDatabase.getInstance(context).recordedMacroDao().insert(entity)
        steps.clear()
        lastDuplicatesRemoved = duplicatesRemoved
        return entity
    }

    /** Exposed so the "recording finished" message can mention cleanup, same spirit as stepCount. */
    var lastDuplicatesRemoved: Int = 0
        private set

    fun serializeSteps(list: List<RecordedStep>): String {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().apply {
                put("actionType", s.actionType)
                put("packageName", s.packageName ?: JSONObject.NULL)
                put("resourceId", s.resourceId ?: JSONObject.NULL)
                put("text", s.text ?: JSONObject.NULL)
                put("contentDescription", s.contentDescription ?: JSONObject.NULL)
                put("className", s.className ?: JSONObject.NULL)
                put("typedText", s.typedText ?: JSONObject.NULL)
                put("boundsLeft", s.boundsLeft ?: JSONObject.NULL)
                put("boundsTop", s.boundsTop ?: JSONObject.NULL)
                put("boundsRight", s.boundsRight ?: JSONObject.NULL)
                put("boundsBottom", s.boundsBottom ?: JSONObject.NULL)
                put("scrollForward", s.scrollForward ?: JSONObject.NULL)
            })
        }
        return arr.toString()
    }

    fun parseSteps(json: String): List<RecordedStep> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecordedStep(
                    actionType = o.optString("actionType", "TAP"),
                    packageName = if (o.isNull("packageName")) null else o.optString("packageName"),
                    resourceId = if (o.isNull("resourceId")) null else o.optString("resourceId"),
                    text = if (o.isNull("text")) null else o.optString("text"),
                    contentDescription = if (o.isNull("contentDescription")) null else o.optString("contentDescription"),
                    className = if (o.isNull("className")) null else o.optString("className"),
                    typedText = if (o.isNull("typedText")) null else o.optString("typedText"),
                    boundsLeft = if (o.has("boundsLeft") && !o.isNull("boundsLeft")) o.optInt("boundsLeft") else null,
                    boundsTop = if (o.has("boundsTop") && !o.isNull("boundsTop")) o.optInt("boundsTop") else null,
                    boundsRight = if (o.has("boundsRight") && !o.isNull("boundsRight")) o.optInt("boundsRight") else null,
                    boundsBottom = if (o.has("boundsBottom") && !o.isNull("boundsBottom")) o.optInt("boundsBottom") else null,
                    // .has() guard keeps old macros (recorded before this field
                    // existed) parsing fine instead of throwing.
                    scrollForward = if (o.has("scrollForward") && !o.isNull("scrollForward")) o.optBoolean("scrollForward") else null
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
