package com.junai.app.agent.action

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.junai.app.AppDatabase
import com.junai.app.agent.MultiStepTaskManager
import com.junai.app.agent.screen.ScreenContextEngine
import com.junai.app.learning.LearnedElementEntity
import com.junai.app.learning.RecordedMacroEntity
import com.junai.app.learning.RecordedStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    /**
     * BUGFIX (multi-device): JunAI ships to users on every phone brand, and
     * each one's launcher is a DIFFERENT real package — Samsung's is
     * "com.sec.android.app.launcher", Xiaomi's "com.miui.home", Pixel's
     * "com.google.android.apps.nexuslauncher", and so on. A macro step
     * captured on one device used to store that device's literal launcher
     * package string as packageName — which then could never match on any
     * OTHER device (or even occasionally the same device, depending on how
     * that specific view reports its hosting package vs what
     * PackageManager resolves as the default home activity). Every macro
     * whose first step was "tap an app icon on the home screen" was
     * effectively device-locked. Recording now stores this sentinel
     * instead whenever the tapped node's package matches THIS device's own
     * resolved launcher (see MacroReplayEngine.getDefaultLauncherPackage())
     * — replay then resolves the CURRENT device's launcher fresh, at
     * replay time, instead of ever comparing literal strings across
     * devices. See ScreenContextEngine.isAppInForeground()'s context-aware
     * overload and MacroReplayEngine.navigateToApp() for the replay side.
     */
    const val HOME_SCREEN_SENTINEL = "<home_screen>"

    /**
     * Record-time substitution: returns HOME_SCREEN_SENTINEL if `rawPackage`
     * is this device's own current default launcher, else returns
     * `rawPackage` unchanged (a normal app's package name is still exactly
     * as portable as it ever was — only the launcher is brand-specific).
     */
    private fun packageNameForStep(rawPackage: String?, context: Context): String? {
        if (rawPackage.isNullOrBlank()) return rawPackage
        val devicesLauncher = MacroReplayEngine.getDefaultLauncherPackage(context)
        return if (devicesLauncher != null && rawPackage == devicesLauncher) HOME_SCREEN_SENTINEL else rawPackage
    }

    /**
     * Outcome of stopAndSave — Empty when nothing usable was captured,
     * Preview when there's a cleaned step list ready for the user to
     * review (Phase 2). Nothing is written to Room at this point — that
     * only happens if the user taps Save (see confirmSave()).
     */
    sealed class RecordResult {
        object Empty : RecordResult()
        data class Preview(val summary: List<String>, val duplicatesRemoved: Int) : RecordResult()
    }

    /** Outcome of confirmSave() — the actual DB write, after the user approves the preview. */
    sealed class SaveResult {
        data class Done(val macro: RecordedMacroEntity, val sourcePendingItemId: Int?) : SaveResult()
        object NothingPending : SaveResult()
    }

    private var recordingActive = false
    private var triggerPhrase: String = ""
    private var displayPhrase: String = ""
    private val steps = mutableListOf<RecordedStep>()

    /**
     * BUGFIX (accurate step counting): parallel to `steps` — records when
     * each step was actually captured. RecordedStep itself deliberately
     * carries no timestamp (would mean a JSON schema change + migration
     * for something only ever needed transiently, during THIS recording
     * session), so this lives here instead, cleared alongside `steps` at
     * the same points (start/cancel/stopAndSave). Only ever read by
     * normalize() below. Always kept in lockstep with `steps` — every
     * addition or in-place overwrite of `steps` goes through addStep()/
     * overwriteLastStep() so the two lists can never drift out of index
     * alignment.
     */
    private val stepCapturedAt = mutableListOf<Long>()

    private fun addStep(step: RecordedStep) {
        steps.add(step)
        stepCapturedAt.add(System.currentTimeMillis())
    }

    private fun overwriteLastStep(step: RecordedStep) {
        if (steps.isEmpty()) return
        steps[steps.lastIndex] = step
        stepCapturedAt[stepCapturedAt.lastIndex] = System.currentTimeMillis()
    }

    /** Set when re-demonstrating an existing macro (Execute tab redo button), so the freshly recorded steps overwrite that row instead of inserting a new one. Null for a brand-new "sikhao". */
    private var existingMacroId: Int? = null

    /**
     * BUGFIX: set when this recording was started from the Learning Center's
     * Pending tab ("Sikhao" on an unresolved command). Previously
     * LearningCenterActivity flipped that item's status to TRAINED_EXECUTE
     * the instant recording STARTED, not when it was actually saved — so a
     * Discard (Phase 2) left the item permanently gone from Pending even
     * though nothing was ever learned, and logFailure()'s dedup check
     * (getAllLearningItems() is status-agnostic) then refused to re-add it
     * next time the same phrase failed, since a row for it already
     * existed. Now the status flip only happens in confirmSave() — see
     * SaveResult.Done.sourcePendingItemId — so Discard leaves the Pending
     * item exactly as it was.
     */
    private var sourcePendingItemId: Int? = null

    /**
     * PHASE 2 (step preview before saving): stopAndSave() no longer writes
     * to Room directly. It normalizes the raw capture and parks the result
     * here, along with the trigger/display phrase and existingMacroId that
     * were active for this recording session — confirmSave() reads from
     * these exact fields, not the live session fields above, so a NEW
     * recording started before the user responds to a pending preview
     * (blocked today by busyReasonBeforeStart, but defensive here too)
     * can't clobber what's waiting for confirmation.
     */
    private var pendingSteps: List<RecordedStep>? = null
    private var pendingTriggerPhrase: String = ""
    private var pendingDisplayPhrase: String = ""
    private var pendingExistingMacroId: Int? = null
    private var pendingSourceItemId: Int? = null

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

    /**
     * BUGFIX (Phase 1c): start() had NO guard against a macro replay or a
     * live agent task already running in the background. If "sikhao" was
     * started while either was mid-flight, the new recording would capture
     * THAT task's own automated taps/types as if the user had performed
     * them — silently corrupting the new macro — on top of both fighting
     * over the same accessibility gesture-dispatch channel. Mirrors the
     * existing ChatIntentHandler.isBusy() pattern, but centralized here
     * (not duplicated at each start() call site) so any future caller
     * gets this protection automatically, not just today's two dialogs in
     * LearningCenterActivity.
     *
     * MacroReplayEngine.isReplaying is a plain object property, safe to
     * read from any Activity. AgentEngine.isTaskRunning() is NOT — it's an
     * in-memory field on whatever AgentEngine instance ChatIntentHandler
     * owns for MainActivity, unreachable from LearningCenterActivity. So
     * this checks MultiStepTaskManager.isAnyTaskRunning() instead, which
     * reads the DB-backed agent_tasks table — the actual persisted source
     * of truth, reachable from anywhere in the app (see that function's
     * comment for the recency-window reasoning).
     *
     * Returns null when it's safe to start recording, or a Hinglish
     * message to show the user when it isn't — caller decides how to
     * surface it (Toast today; nothing stronger needed since the user can
     * just retry once the other task finishes).
     */
    suspend fun busyReasonBeforeStart(context: Context): String? {
        if (MacroReplayEngine.isReplaying) {
            return "Ek pehle se seekha hua kaam chal raha hai abhi — pehle wo complete hone do, phir sikhao."
        }
        if (MultiStepTaskManager(context).isAnyTaskRunning()) {
            return "Main abhi ek doosra task kar rahi hoon background mein — thodi der ruk ke phir sikhana try karo."
        }
        return null
    }

    fun start(triggerPhrase: String, displayPhrase: String, existingMacroId: Int? = null, sourcePendingItemId: Int? = null) {
        steps.clear()
        stepCapturedAt.clear()
        this.existingMacroId = existingMacroId
        this.sourcePendingItemId = sourcePendingItemId
        this.triggerPhrase = triggerPhrase.lowercase().trim()
        this.displayPhrase = displayPhrase
        recordingStartedAt = System.currentTimeMillis()
        recordingActive = true
    }

    fun cancel() {
        recordingActive = false
        steps.clear()
        stepCapturedAt.clear()
        existingMacroId = null
        sourcePendingItemId = null
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
    internal fun labelOf(node: AccessibilityNodeInfo): Pair<String?, String?> {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 5) {
            val text = n.text?.toString()?.takeIf { it.isNotBlank() }
            // BUGFIX ("always taps Location" persisting even after the
            // ancestor+sibling walk below): toggle-style widgets — Quick
            // Settings tiles specifically, but also plain Switches —
            // commonly carry their distinguishing label via
            // AccessibilityNodeInfo.stateDescription (API 30+, e.g. "Wi-Fi,
            // On"), NOT contentDescription or text. Every QS tile shares
            // the same resourceId AND (on this device) the same blank
            // text/contentDescription at every level the walk below
            // checks — so every tile looked identical at capture time and
            // findBestMatchingNode had nothing but bounds left to break
            // the tie, consistently landing on whichever tile that
            // happened to favor. Falls back to contentDescription when
            // stateDescription isn't set, so this changes nothing for
            // ordinary buttons/icons that never had this problem.
            val desc = n.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                ?: (if (android.os.Build.VERSION.SDK_INT >= 30) n.stateDescription?.toString()?.takeIf { it.isNotBlank() } else null)
            if (text != null || desc != null) return text to desc

            // BUGFIX (Quick Settings "always taps Location" persisting even
            // after the ancestor walk above): some OEM SystemUI layouts
            // (this device included) put a tile's icon and its label as
            // SIBLINGS under a shared row container, not one above the
            // other — so walking straight up through icon -> icon's own
            // parents never crosses the label at all. At each level, also
            // check that level's siblings (the icon's "row-mates") before
            // going up further, so a label sitting next to the tapped
            // element is found, not just one directly above it.
            val parent = n.parent
            if (parent != null) {
                for (i in 0 until parent.childCount) {
                    val sibling = parent.getChild(i) ?: continue
                    if (sibling == n) { sibling.recycle(); continue }
                    val sibText = sibling.text?.toString()?.takeIf { it.isNotBlank() }
                    val sibDesc = sibling.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                        ?: (if (android.os.Build.VERSION.SDK_INT >= 30) sibling.stateDescription?.toString()?.takeIf { it.isNotBlank() } else null)
                    sibling.recycle()
                    if (sibText != null || sibDesc != null) return sibText to sibDesc
                }
            }
            n = parent
            depth++
        }
        return null to null
    }

    /** Called by JunAccessibilityService on TYPE_VIEW_CLICKED while recording. */
    fun captureTap(node: AccessibilityNodeInfo, context: Context) {
        if (!recordingActive) return
        if (isSensitive(node)) return
        lastTapAt = System.currentTimeMillis()
        val rect = boundsOf(node)
        val (label, desc) = labelOf(node)
        if (label == null && desc == null) {
            // DIAGNOSTIC: this step will have NOTHING to disambiguate it
            // from other elements sharing the same resourceId (e.g. Quick
            // Settings tiles, which all share a template id) except its
            // recorded screen position. If a macro keeps tapping the wrong
            // tile despite the stateDescription fix above, checking logcat
            // for this line at the moment of recording tells us the label
            // genuinely isn't exposed through ANY of the accessibility
            // fields this checks — a fourth fallback would be needed.
            android.util.Log.w("RecordingEngine", "captureTap: no label/desc found at all for resourceId=${node.viewIdResourceName} class=${node.className}")
        }
        addStep(
            RecordedStep(
                actionType = "TAP",
                packageName = packageNameForStep(node.packageName?.toString(), context),
                resourceId = node.viewIdResourceName,
                text = label,
                contentDescription = desc,
                className = node.className?.toString(),
                boundsLeft = rect.left,
                boundsTop = rect.top,
                boundsRight = rect.right,
                boundsBottom = rect.bottom,
                idDistinctive = node.viewIdResourceName?.let { idSiblingCount(node) <= 1 }
            )
        )
        rememberElement(context, node.packageName?.toString(), node.viewIdResourceName, node.className?.toString(), label ?: desc, rect)
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
    fun captureLongPress(node: AccessibilityNodeInfo, context: Context) {
        if (!recordingActive) return
        if (isSensitive(node)) return
        val rect = boundsOf(node)
        val (label, desc) = labelOf(node)
        addStep(
            RecordedStep(
                actionType = "LONG_PRESS",
                packageName = packageNameForStep(node.packageName?.toString(), context),
                resourceId = node.viewIdResourceName,
                text = label,
                contentDescription = desc,
                className = node.className?.toString(),
                boundsLeft = rect.left,
                boundsTop = rect.top,
                boundsRight = rect.right,
                boundsBottom = rect.bottom,
                idDistinctive = node.viewIdResourceName?.let { idSiblingCount(node) <= 1 }
            )
        )
        rememberElement(context, node.packageName?.toString(), node.viewIdResourceName, node.className?.toString(), label ?: desc, rect)
    }

    /**
     * Upserts this observation into the persistent learned_elements table
     * (see LearnedElementEntity doc) — fire-and-forget on a background
     * coroutine so it never adds latency to live event capture. Matches an
     * existing row by (app, resourceId, approximate position); if found,
     * bumps its interaction count and refreshes its label/size instead of
     * creating a duplicate row for the same real-world element.
     */
    private fun rememberElement(
        context: Context,
        packageName: String?,
        resourceId: String?,
        className: String?,
        label: String?,
        rect: android.graphics.Rect
    ) {
        if (packageName == null) return
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.getInstance(appContext).learnedElementDao()
            val now = System.currentTimeMillis()
            val existing = dao.findMatching(packageName, resourceId, rect.left, rect.top)
            if (existing != null) {
                dao.update(
                    existing.copy(
                        label = label ?: existing.label, // keep the previous label if this observation had none
                        boundsLeft = rect.left, boundsTop = rect.top,
                        boundsRight = rect.right, boundsBottom = rect.bottom,
                        width = rect.width(), height = rect.height(),
                        interactionCount = existing.interactionCount + 1,
                        lastSeenAt = now
                    )
                )
            } else {
                dao.insert(
                    LearnedElementEntity(
                        packageName = packageName,
                        resourceId = resourceId,
                        className = className,
                        label = label,
                        boundsLeft = rect.left, boundsTop = rect.top,
                        boundsRight = rect.right, boundsBottom = rect.bottom,
                        width = rect.width(), height = rect.height(),
                        interactionCount = 1,
                        firstSeenAt = now,
                        lastSeenAt = now
                    )
                )
            }
        }
    }

    /** Called by JunAccessibilityService on TYPE_VIEW_TEXT_CHANGED while recording. */
    fun captureType(node: AccessibilityNodeInfo, typedText: String, context: Context) {
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
            overwriteLastStep(last!!.copy(typedText = typedText))
        } else {
            addStep(
                RecordedStep(
                    actionType = "TYPE",
                    packageName = packageNameForStep(node.packageName?.toString(), context),
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
     *
     * BUGFIX (Phase 1h): [horizontal] is new — see RecordedStep.scrollHorizontal's
     * doc comment for why capturing forward/backward alone wasn't enough.
     */
    fun captureScroll(node: AccessibilityNodeInfo, forward: Boolean, horizontal: Boolean, context: Context) {
        if (!recordingActive) return
        if (isSensitive(node)) return

        // BUGFIX: RecyclerViews that refresh their content in response to a
        // tap elsewhere (classic example: the dialer's predictive-contact
        // search_recycler_view re-filtering after each digit typed) fire
        // their own TYPE_VIEW_SCROLLED as the list settles into its new
        // size/position — even though the user never touched that list.
        // These were getting captured as real SWIPE steps, one per digit,
        // producing macros bloated with swipes nobody actually performed
        // (and which don't even correspond to anything replayable, since
        // there's no real gesture behind them). Reusing the same
        // lastTapAt/TAP_ECHO_WINDOW_MS debounce captureType() already uses
        // for the analogous dialer TAP+TYPE echo problem — a scroll event
        // this soon after a tap is far more likely to be a passive
        // side-effect of that tap than a deliberate, separate swipe.
        if (System.currentTimeMillis() - lastTapAt < TAP_ECHO_WINDOW_MS) return

        val rect = boundsOf(node)

        // Debounce: a single physical swipe can fire several
        // TYPE_VIEW_SCROLLED events in quick succession as the list
        // settles. Update the last step in place instead of adding a new
        // one per event, same idea as the TYPE collapse above. Now also
        // checks the axis matches, not just direction — a genuinely new
        // swipe on a different axis of the same scrollable node shouldn't
        // get silently collapsed into the previous one.
        val last = steps.lastOrNull()
        if (last != null && last.actionType == "SWIPE" &&
            last.resourceId == node.viewIdResourceName && last.scrollForward == forward &&
            last.scrollHorizontal == horizontal
        ) {
            return
        }
        addStep(
            RecordedStep(
                actionType = "SWIPE",
                packageName = packageNameForStep(node.packageName?.toString(), context),
                resourceId = node.viewIdResourceName,
                text = null,
                contentDescription = node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
                className = node.className?.toString(),
                boundsLeft = rect.left,
                boundsTop = rect.top,
                boundsRight = rect.right,
                boundsBottom = rect.bottom,
                scrollForward = forward,
                scrollHorizontal = horizontal
            )
        )
    }

    internal fun boundsOf(node: AccessibilityNodeInfo): android.graphics.Rect {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        return rect
    }

    /**
     * PHASE B (capture-side half of the matching recalibration — see
     * RecordedStep.idDistinctive's doc comment): counts how many nodes on
     * the CURRENT screen share [node]'s resourceId, at the exact moment
     * it's tapped. Best-effort — if the service instance or root window
     * isn't available for any reason, returns 1 (assume distinctive,
     * i.e. don't flag it down) rather than guessing false, since an
     * incorrect "shared" flag only costs a little matching confidence
     * later, while silently under-counting is harmless either way (the
     * replay-side matcher's own live scoring still runs regardless).
     */
    private fun idSiblingCount(node: AccessibilityNodeInfo): Int {
        val resId = node.viewIdResourceName ?: return 1
        val root = com.junai.app.agent.action.JunAccessibilityService.instance?.rootInActiveWindow ?: return 1
        return try {
            val matches = root.findAccessibilityNodeInfosByViewId(resId)
            val count = matches?.size ?: 1
            matches?.forEach { try { it.recycle() } catch (e: Exception) { /* already gone */ } }
            count.coerceAtLeast(1)
        } catch (e: Exception) {
            1
        }
    }

    internal fun isSensitive(node: AccessibilityNodeInfo): Boolean {
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
    /**
     * BUGFIX (accurate step counting): this used to collapse ANY
     * back-to-back identical TAP/LONG_PRESS purely by comparing
     * identifiers — no time check at all. That's correct for a genuine
     * OS-level echo (the same physical tap sometimes reports two
     * TYPE_VIEW_CLICKED events, near-instantaneously), but it can't tell
     * that apart from a user DELIBERATELY tapping the same button twice
     * (e.g. a "+1" counter, a repeated backspace) — which takes a finger
     * lift + a fresh tap, always measurably slower than an echo. Both
     * looked identical to normalize() before, so a real second press was
     * silently thrown away, undercounting the macro's actual steps.
     * DUPLICATE_ECHO_WINDOW_MS draws that line: collapse only if the two
     * captures landed within it, keep both otherwise.
     */
    private const val DUPLICATE_ECHO_WINDOW_MS = 150L

    // BUGFIX (dial-pad extra-digit bug): the check below used to compare
    // a TAP/LONG_PRESS only against raw[lastKeptIndex] — the literal
    // immediately-previous KEPT step, regardless of its type. That works
    // when two echoed TAPs land back-to-back with nothing between them,
    // but a numeric keypad key typically fires BOTH a click event AND a
    // text-changed event on the digit display for the SAME physical
    // press — so the real sequence is often TAP, TYPE, [echo]TAP, TYPE,
    // not TAP, [echo]TAP. That TYPE step sitting in between broke the
    // adjacency this check relied on: the second TAP was being compared
    // against the TYPE step (different actionType, comparison always
    // false), never against the first TAP — so the echo was NEVER
    // caught, no matter the timing window. This is what produced the
    // extra digit: a genuine single tap on a key got recorded as two TAP
    // steps, with a completely correct TYPE step from the real press
    // sitting between them, all three getting kept.
    //
    // Fix: track the most recently kept TAP/LONG_PRESS step separately
    // from the general "last kept step", so an interleaved TYPE (or any
    // other step type) no longer breaks tap-vs-tap echo comparison. The
    // real-timestamp-based DUPLICATE_ECHO_WINDOW_MS check is unchanged —
    // this only fixes WHICH prior step gets compared against, not the
    // timing logic that decides echo-vs-deliberate-repeat.
    private fun normalize(raw: List<RecordedStep>, capturedAt: List<Long>): List<RecordedStep> {
        if (raw.isEmpty()) return raw
        val out = mutableListOf<RecordedStep>()
        var lastTapLikeKeptIndex = -1
        for (i in raw.indices) {
            val step = raw[i]
            val isTapLike = step.actionType == "TAP" || step.actionType == "LONG_PRESS"
            val isBackToBackDuplicate = isTapLike && lastTapLikeKeptIndex >= 0 && run {
                val prev = raw[lastTapLikeKeptIndex]
                prev.actionType == step.actionType &&
                    prev.resourceId == step.resourceId &&
                    prev.text == step.text &&
                    prev.contentDescription == step.contentDescription &&
                    prev.boundsLeft == step.boundsLeft &&
                    prev.boundsTop == step.boundsTop &&
                    prev.boundsRight == step.boundsRight &&
                    prev.boundsBottom == step.boundsBottom &&
                    (capturedAt.getOrElse(i) { 0L } - capturedAt.getOrElse(lastTapLikeKeptIndex) { 0L }) < DUPLICATE_ECHO_WINDOW_MS
            }
            if (isBackToBackDuplicate) continue
            out.add(step)
            if (isTapLike) lastTapLikeKeptIndex = i
        }
        return out
    }

    /**
     * BUGFIX (structural — replaces two earlier narrower patches):
     * "86260 43662" → "86260 436622" (extra trailing digit), then
     * "86260 43662" → "86260 436662" (extra digit mid-sequence). Two
     * different-looking bugs, same actual cause, and each earlier patch
     * only caught the specific ordering it was written for before the
     * next variant showed up.
     *
     * The real picture: a numeric keypad key fires BOTH a click event
     * (captured as "Tap N,LETTERS") AND a text-changed event on the
     * digits field (captured as "Type '...' in digits") for the SAME
     * physical press — and Android doesn't guarantee which one arrives
     * first, or that they even land next to each other. Chasing each
     * specific ordering variant (tap-then-echo, type-before-trailing-tap,
     * type-before-mid-sequence-tap) was never going to converge, because
     * the actual fix isn't about ordering at all:
     *
     * ActionEngine.typeStep() → JunAccessibilityService.typeText() sets
     * the field's ENTIRE content in one ACTION_SET_TEXT call. It was
     * never necessary to replay the individual key taps at all — the
     * Type step alone, replayed once, reproduces the whole number
     * correctly regardless of how many taps led up to it. So instead of
     * trying to tell a "real" key tap apart from a "redundant" one by
     * timing, this drops every keypad-style tap that sits directly next
     * to a Type step anywhere in the capture — that adjacency alone is
     * enough evidence the Type step already accounts for it, no suffix-
     * matching or trailing-position restriction needed.
     *
     * Scoped narrowly by what counts as a "keypad key": a bare digit, *,
     * or # optionally followed by a letter group (",MNO", ",TUV"...) —
     * matches only literal dial-pad buttons. A real action button like
     * "Send" or "Call" never matches this pattern and is never touched,
     * even if it happens to sit next to a Type step.
     */
    private val KEYPAD_KEY_PATTERN = Regex("^[0-9*#](,[A-Za-z]+)?$")

    private fun dropKeypadTapsAdjacentToType(list: List<RecordedStep>): List<RecordedStep> {
        val out = mutableListOf<RecordedStep>()
        for (i in list.indices) {
            val step = list[i]
            val isKeypadKeyTap = step.actionType == "TAP" &&
                step.text?.trim()?.let { KEYPAD_KEY_PATTERN.matches(it) } == true
            if (isKeypadKeyTap) {
                val prevIsType = i > 0 && list[i - 1].actionType == "TYPE"
                val nextIsType = i + 1 < list.size && list[i + 1].actionType == "TYPE"
                if (prevIsType || nextIsType) continue
            }
            out.add(step)
        }
        return out
    }

    /**
     * Stops recording and normalizes whatever was captured — but does NOT
     * persist it (Phase 2). The cleaned steps are parked in pendingSteps
     * along with a plain-language summary, so the caller can show the user
     * a preview overlay with Save/Discard. Nothing touches Room until
     * confirmSave() is called.
     */
    suspend fun stopAndSave(): RecordResult {
        recordingActive = false
        if (steps.isEmpty()) return RecordResult.Empty

        val cleaned = dropKeypadTapsAdjacentToType(normalize(steps, stepCapturedAt))
        val duplicatesRemoved = steps.size - cleaned.size
        steps.clear()
        stepCapturedAt.clear()
        if (cleaned.isEmpty()) return RecordResult.Empty

        pendingSteps = cleaned
        pendingTriggerPhrase = triggerPhrase
        pendingDisplayPhrase = displayPhrase
        pendingExistingMacroId = existingMacroId
        pendingSourceItemId = sourcePendingItemId
        existingMacroId = null
        sourcePendingItemId = null

        return RecordResult.Preview(buildSummary(cleaned), duplicatesRemoved)
    }

    /**
     * Plain-language, 1-indexed summary of each step, for the preview
     * overlay — e.g. "1. Tap WhatsApp", "2. Type 'On my way'". Falls back
     * through resourceId/contentDescription/className when there's no
     * visible text, same identifier priority order the replay matcher
     * itself uses, so the summary reflects what will actually be matched.
     */
    private fun buildSummary(list: List<RecordedStep>): List<String> {
        return list.mapIndexed { index, step ->
            val label = step.text?.takeIf { it.isNotBlank() }
                ?: step.contentDescription?.takeIf { it.isNotBlank() }
                ?: step.resourceId?.substringAfterLast('/')
                ?: step.className?.substringAfterLast('.')
                ?: "element"
            val action = when (step.actionType) {
                "TAP" -> "Tap $label"
                "LONG_PRESS" -> "Long-press $label"
                "TYPE" -> "Type '${step.typedText.orEmpty()}' in $label"
                "SWIPE" -> {
                    val dir = when {
                        step.scrollHorizontal == true && step.scrollForward == true -> "left"
                        step.scrollHorizontal == true && step.scrollForward == false -> "right"
                        step.scrollHorizontal == false && step.scrollForward == true -> "down"
                        step.scrollHorizontal == false && step.scrollForward == false -> "up"
                        else -> ""
                    }
                    "Swipe $dir $label".replace("  ", " ")
                }
                else -> "${step.actionType} $label"
            }
            "${index + 1}. $action"
        }
    }

    /**
     * User approved the preview — actually write to Room now. Mirrors the
     * old stopAndSave()'s persistence logic exactly, just moved here and
     * reading from the pending* fields instead of the live session fields
     * (which have already moved on / been cleared by the time this runs).
     */
    suspend fun confirmSave(context: Context): SaveResult {
        val cleaned = pendingSteps ?: return SaveResult.NothingPending
        val stepsJson = serializeSteps(cleaned)

        val dao = AppDatabase.getInstance(context).recordedMacroDao()
        val id = pendingExistingMacroId
        val entity: RecordedMacroEntity = if (id != null) {
            dao.updateSteps(id, stepsJson, cleaned.size)
            dao.getById(id) ?: RecordedMacroEntity(
                id = id, triggerPhrase = pendingTriggerPhrase, displayPhrase = pendingDisplayPhrase,
                stepsJson = stepsJson, stepCount = cleaned.size, createdAt = System.currentTimeMillis()
            )
        } else {
            val newEntity = RecordedMacroEntity(
                triggerPhrase = pendingTriggerPhrase, displayPhrase = pendingDisplayPhrase,
                stepsJson = stepsJson, stepCount = cleaned.size, createdAt = System.currentTimeMillis()
            )
            val newId = dao.insert(newEntity)
            newEntity.copy(id = newId.toInt())
        }

        val sourceItemId = pendingSourceItemId
        clearPending()
        return SaveResult.Done(entity, sourceItemId)
    }

    /** User discarded the preview — steps vanish as if the recording never happened. */
    fun discardPending() {
        clearPending()
    }

    private fun clearPending() {
        pendingSteps = null
        pendingTriggerPhrase = ""
        pendingDisplayPhrase = ""
        pendingExistingMacroId = null
        pendingSourceItemId = null
    }

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
                put("scrollHorizontal", s.scrollHorizontal ?: JSONObject.NULL)
                put("idDistinctive", s.idDistinctive ?: JSONObject.NULL)
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
                    scrollForward = if (o.has("scrollForward") && !o.isNull("scrollForward")) o.optBoolean("scrollForward") else null,
                    // BUGFIX (Phase 1h): same .has() guard reasoning — old
                    // macros never had this field, so they'll get null here
                    // and ActionEngine.scrollStep() falls back to its old
                    // vertical-only assumption for them, same as before.
                    scrollHorizontal = if (o.has("scrollHorizontal") && !o.isNull("scrollHorizontal")) o.optBoolean("scrollHorizontal") else null,
                    // PHASE B: same .has() guard reasoning as scrollHorizontal
                    // above — old macros never had this field, so they parse
                    // as null (unknown), which findBestMatchingNode treats as
                    // "keep old full-weight id behavior" — no change for them.
                    idDistinctive = if (o.has("idDistinctive") && !o.isNull("idDistinctive")) o.optBoolean("idDistinctive") else null
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
