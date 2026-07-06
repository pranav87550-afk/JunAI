package com.junai.app.agent.action

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.junai.app.agent.AgentStep
import com.junai.app.agent.DecisionEngine
import com.junai.app.agent.DecisionVerdict
import com.junai.app.agent.StepType
import com.junai.app.agent.screen.ScreenContextEngine
import com.junai.app.learning.RecordedMacroEntity
import com.junai.app.learning.RecordedStep
import kotlinx.coroutines.delay

/**
 * Replays a RecordedMacroEntity by driving the same ActionEngine functions
 * a live GoalPlanner-generated task would use — so everything ActionEngine
 * already handles (app-lock waiting, stale-node refresh, TextView-vs-avatar
 * preference) applies automatically to replay too, with no duplicated logic.
 *
 * Every step reports specifically why it failed if it does — which step
 * number, what it was trying to do, and what ActionEngine said — instead of
 * a generic "task failed". Recorded steps are identifier-based (resourceId/
 * text/contentDescription), so small UI shifts (new chat at the top, a
 * status ring moving) don't break replay — but if the UI changes enough
 * that NONE of a step's identifiers exist anymore, replay stops cleanly and
 * says so, rather than tapping the wrong thing.
 */
object MacroReplayEngine {

    /**
     * BUGFIX (root cause of "kabhi extra tasks, kabhi kuch execute nahi
     * hota"): replay() had NO re-entrancy guard at all. ChatIntentHandler
     * posts its "kar rahi hoon... 🎬" reply BEFORE calling replay() — and
     * that reply, like any bot message, re-enables the send button
     * immediately (see ChatAdapter.onBotMessageAdded), while the actual
     * step loop below is still running in the background with no lock.
     * If the user sent anything else in that window (even the same
     * trigger phrase again), a SECOND replay (or a live AgentEngine task)
     * started concurrently — both drive the same JunAccessibilityService
     * and the same dispatchGesture() channel, which Android only lets ONE
     * gesture use at a time (a new gesture cancels whatever was in
     * flight). That's exactly "extra actions happen" (two macros' steps
     * interleaved) and "nothing happens" (one gesture cancelled the
     * other) depending on timing. This flag, checked by ChatIntentHandler
     * before it will even start a replay, closes that window.
     */
    @Volatile
    private var replaying = false
    val isReplaying: Boolean get() = replaying

    /**
     * BUGFIX: a recorded step's packageName can be a system surface —
     * the launcher/home screen (tapping an app icon happens THERE, so the
     * event's source package is the launcher, not the app about to open)
     * or com.android.systemui (Quick Settings toggles like WiFi/location/
     * flashlight live in the notification shade, which is systemui's own
     * UI). Neither is a normal installed app with a launch Intent —
     * ActionEngine.openApp() calling getLaunchIntentForPackage() on them
     * returns null/fails every time, so every macro that started with "tap
     * an app icon on the home screen" or "toggle something in Quick
     * Settings" failed at step 1 with a misleading "may not be installed"
     * message. These need actual navigation (go home / expand quick
     * settings), not a launch Intent.
     */
    /**
     * BUGFIX (multi-device): NOT private — RecordingEngine calls this too,
     * at record time, so it can substitute RecordingEngine.HOME_SCREEN_SENTINEL
     * for whatever the literal launcher package happens to be on the
     * recording device. See HOME_SCREEN_SENTINEL's doc comment for why a
     * literal package string here doesn't generalize across phone brands.
     */
    fun getDefaultLauncherPackage(context: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName
    }

    private suspend fun navigateToApp(context: Context, pkg: String): ActionResult {
        val launcherPkg = getDefaultLauncherPackage(context)
        return when {
            // BUGFIX (multi-device): HOME_SCREEN_SENTINEL check first — a
            // macro recorded on any device stores this instead of a literal
            // launcher package now (see RecordingEngine.HOME_SCREEN_SENTINEL).
            // The `pkg == launcherPkg` branch below stays for OLD macros
            // recorded before this fix, which still have the recording
            // device's literal launcher string baked in.
            pkg == RecordingEngine.HOME_SCREEN_SENTINEL || pkg == launcherPkg -> {
                val ok = JunAccessibilityService.instance?.pressHome() ?: false
                if (ok) ActionResult(true, "went home")
                else ActionResult(false, "couldn't go to the home screen — is the accessibility service connected?")
            }
            pkg == "com.android.systemui" -> {
                val ok = JunAccessibilityService.instance?.expandQuickSettings() ?: false
                if (ok) ActionResult(true, "opened quick settings")
                else ActionResult(false, "couldn't open Quick Settings — is the accessibility service connected?")
            }
            else -> ActionEngine.openApp(context, pkg)
        }
    }

    /**
     * BUGFIX (Phase 1b, the big one): live AgentEngine-driven tasks route
     * every single step through DecisionEngine.evaluate() → SafetyLayer
     * before ActionEngine ever touches the screen — that's what makes
     * payments/deletes/sending-messages require confirmation. Macro replay
     * called ActionEngine directly, with NO such gate — meaning a macro
     * recorded once around a payment flow, a delete, or sending a message
     * would silently re-execute it on every future trigger, forever, with
     * zero confirmation. This maps each RecordedStep into an AgentStep-
     * shaped description/target good enough for DecisionEngine's
     * keyword-based risk classification, then runs the same evaluate() gate
     * per step (see replayInternal) — matching live-task behavior exactly,
     * rather than inventing a separate, weaker safety path for replay.
     *
     * Why per-step, not once for the whole macro: matches how live tasks
     * already work, and keeps this simple/consistent — the tradeoff is a
     * macro with several payment-like steps can prompt more than once per
     * replay. That's intentionally annoying-but-safe rather than silent.
     *
     * Field mapping, and why:
     *   - actionType → StepType: TAP/LONG_PRESS/SWIPE → StepType.TAP,
     *     TYPE → StepType.TYPE (DecisionEngine's baseline risk table only
     *     cares LOW vs MEDIUM here; the real classification comes from
     *     safetyConcernFor()'s keyword scan below, not this baseline).
     *   - target/description text is built from `text` + `contentDescription`
     *     + the resourceId's last path segment with underscores/dashes
     *     turned into spaces (e.g. "btn_delete_msg" → "delete msg") — a
     *     step with no visible label can still carry a strong safety signal
     *     in its resourceId, so that's included deliberately, not just the
     *     human-readable fields.
     *   - TYPE steps also fold `typedText` into the description — what the
     *     user actually typed (e.g. "send 500 to Ramesh") is often the
     *     strongest signal of all for payment/message intent. Safe to
     *     include: RecordingEngine already never captures typedText for
     *     password/PIN/OTP fields, so nothing sensitive ever reaches here.
     *   - Falls back to a non-blank generic label when every field is null
     *     (a bounds-only step) — DecisionEngine's own clarity check treats
     *     a blank description/target as "ask the user to clarify", which
     *     would be a wrong, confusing thing to surface for a bounds-only
     *     step that's otherwise totally ordinary.
     */
    private fun stepToAgentStep(step: RecordedStep, stepNumber: Int): AgentStep {
        val type = if (step.actionType == "TYPE") StepType.TYPE else StepType.TAP
        val verb = when (step.actionType) {
            "TAP" -> "Tap"
            "LONG_PRESS" -> "Long-press"
            "SWIPE" -> "Swipe"
            "TYPE" -> "Type into"
            else -> "Do"
        }
        val idWords = step.resourceId
            ?.substringAfterLast("/")
            ?.replace('_', ' ')
            ?.replace('-', ' ')
        val label = listOfNotNull(step.text, step.contentDescription, idWords)
            .firstOrNull { it.isNotBlank() }
            ?: "this element"
        val target = listOfNotNull(step.text, step.contentDescription, idWords)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "screen position" }
        val description = if (step.actionType == "TYPE" && !step.typedText.isNullOrBlank()) {
            "$verb \"$label\": types \"${step.typedText}\""
        } else {
            "$verb \"$label\""
        }
        return AgentStep(
            stepNumber = stepNumber,
            type = type,
            description = description,
            target = target,
            fallback = null
        )
    }

    suspend fun replay(context: Context, macro: RecordedMacroEntity): String {
        // BUGFIX: see isReplaying doc above — refuse to start a second
        // replay while one is already mid-flight instead of silently
        // racing it. ChatIntentHandler also checks this before it even
        // shows the "kar rahi hoon" reply, so this is a defensive
        // second layer, not the only guard.
        if (replaying) {
            return "Ek kaam pehle se chal raha hai — pehle wo complete hone do, phir dobara try karo."
        }
        replaying = true
        try {
            return replayInternal(context, macro)
        } finally {
            replaying = false
        }
    }

    /**
     * BUGFIX (recovery jump): a step whose whole job is just navigating
     * somewhere (swipe open the app drawer, tap a home-screen icon) is the
     * most fragile part of any macro — especially the gesture-based ones,
     * which depend on OS/OEM-specific interpretation we can't fully
     * control (see the "com.oppo.quicksearchbox instead of the app
     * drawer" case this was built for). Rather than keep chasing every
     * device's exact gesture semantics, when one of these fails this
     * skips straight to the next app the macro was actually headed
     * toward, via a normal Intent launch — the same mechanism openApp()
     * already uses reliably everywhere else — instead of giving up.
     *
     * Scans forward from `fromIndex` for the first step whose packageName
     * is a genuinely different, real app: not blank, not the same package
     * as the step that just failed, and not HOME_SCREEN_SENTINEL (none of
     * those are "a real app to jump to" — they're just more of the same
     * surface we already couldn't reach). If found, launches it and
     * verifies landing before handing back the index to resume from —
     * every step in between gets skipped, since they were all steps
     * inside the surface this macro couldn't get back into anyway.
     *
     * Returns null (recovery not possible) when there's no further app
     * change left in the macro, or the jump itself didn't land — in both
     * cases the caller falls back to its normal failure message,
     * unchanged from before this existed.
     */
    private suspend fun attemptRecoveryJump(context: Context, steps: List<RecordedStep>, fromIndex: Int): Int? {
        val failedPkg = steps[fromIndex].packageName
        var target = fromIndex + 1
        while (target < steps.size) {
            val candidatePkg = steps[target].packageName
            if (!candidatePkg.isNullOrBlank() &&
                candidatePkg != failedPkg &&
                candidatePkg != RecordingEngine.HOME_SCREEN_SENTINEL
            ) {
                break
            }
            target++
        }
        if (target >= steps.size) return null // nothing further to recover to

        val targetPkg = steps[target].packageName ?: return null
        // BUGFIX: this used to call ActionEngine.openApp() directly, which
        // only knows how to launch a real app via Intent — for a system
        // surface like Quick Settings (com.android.systemui isn't
        // launchable that way at all) that call would just silently fail,
        // making recovery useless for exactly the kind of step most likely
        // to need it. navigateToApp() already has the right dispatch logic
        // (home-screen sentinel → pressHome(), systemui → Quick Settings,
        // otherwise → openApp()) — reuse it here instead of duplicating or
        // bypassing it.
        val openResult = navigateToApp(context, targetPkg)
        if (!openResult.success) return null

        JunAccessibilityService.instance?.waitForQuiet()
        return if (waitForStepEvidence(steps[target], timeoutMs = 2000L)) target else null
    }

    private suspend fun replayInternal(context: Context, macro: RecordedMacroEntity): String {
        val steps = RecordingEngine.parseSteps(macro.stepsJson)
        if (steps.isEmpty()) {
            return "Ye macro khali hai — koi steps record nahi hue the. Dobara record karo."
        }

        var index = 0
        while (index < steps.size) {
            val step = steps[index]
            val stepLabel = "Step ${index + 1}/${steps.size}"

            // Make sure we're in the right app/surface for this step before
            // acting. Covers the very first step (nothing open yet) and any
            // later step that expects a different app or system surface.
            val pkg = step.packageName
            if (!pkg.isNullOrBlank() && !ScreenContextEngine.isAppInForeground(context, pkg)) {
                val navResult = navigateToApp(context, pkg)
                if (!navResult.success) {
                    val recovered = attemptRecoveryJump(context, steps, index)
                    if (recovered != null) {
                        index = recovered
                        continue
                    }
                    return "$stepLabel pe ruk gaya — \"$pkg\" tak nahi pahunch paayi. " +
                        "Wajah: ${navResult.message}"
                }
            }

            // BUGFIX (root cause of intermittent swipe failures, wrong-
            // element taps like lock icon instead of a folder, AND — the
            // case that actually surfaced this — Step 1 landing on a
            // totally wrong screen because the FIRST step never went
            // through navigateToApp at all if we were already sitting on
            // the right app/home-screen, so it never got any settle wait
            // whatsoever before this fix): this used to be a single fixed
            // delay(500L), and only even ran for steps that triggered
            // navigateToApp — a step that starts out already on the right
            // surface (most commonly step 1, e.g. the home screen the
            // recording started from) skipped waiting entirely and went
            // straight to matching against a tree that might still be
            // mid-layout. Now EVERY step waits for the screen to actually
            // go quiet (see JunAccessibilityService.waitForQuiet()) —
            // content-agnostic, so it works before step 1 too where there's
            // no specific expected element yet to poll for — capped at 3s
            // so a screen with a genuinely continuous animation can't hang
            // the replay forever.
            JunAccessibilityService.instance?.waitForQuiet()

            // Prefer the most stable identifier first, same priority order
            // used at capture time: resourceId > text > contentDescription.
            // BUGFIX: this used to hard-require a text/id identifier for
            // TYPE steps and treat "no identifier" as a corrupt macro —
            // but bounds are now captured for every step type (see
            // RecordingEngine), so ANY step type can fall back to its
            // recorded position. Only truly bail out if NEITHER an
            // identifier NOR bounds were saved at all.
            val target = step.resourceId ?: step.text ?: step.contentDescription
            val hasBounds = step.boundsLeft != null && step.boundsTop != null &&
                step.boundsRight != null && step.boundsBottom != null
            if (target.isNullOrBlank() && !hasBounds) {
                return "$stepLabel pe ruk gaya — is step ka koi identifier hi save nahi hua tha " +
                    "(resource-id, text, description, position — sab khaali hain). Ye macro corrupt lag raha hai, dobara record karna hoga."
            }

            // BUGFIX (Phase 1b): safety gate, previously entirely missing
            // from replay — see stepToAgentStep()'s doc comment above for
            // the full reasoning. agentConfidenceScore is fixed at 100 here
            // (not IntentDetector-derived) because this isn't a "how sure
            // am I what the user meant" check — the macro was already
            // demonstrated once by the user — only the risk/SafetyConcern
            // half of evaluate() is meaningfully in play for replay.
            val decision = DecisionEngine.evaluate(stepToAgentStep(step, index + 1), agentConfidenceScore = 100)
            when (decision.verdict) {
                DecisionVerdict.BLOCKED -> {
                    return "$stepLabel pe ruk gaya — ${decision.reason ?: "safety ke liye ye step rok diya gaya."}"
                }
                DecisionVerdict.NEEDS_CLARIFICATION -> {
                    // No live chat loop exists here to actually ask a
                    // clarifying question mid-replay, so this is treated as
                    // a hard stop too, with a message pointing at re-teaching
                    // rather than a generic failure.
                    return "$stepLabel pe ruk gaya — ${decision.reason ?: "is step ka theek se pata nahi chal raha."} Behtar hoga is task ko dobara sikhao."
                }
                DecisionVerdict.PROCEED, DecisionVerdict.PROCEED_WITH_NOTIFICATION -> {
                    // Continue below — PROCEED_WITH_NOTIFICATION doesn't get
                    // special handling here, same as AgentEngine's own
                    // per-step loop (see DecisionEngine call site there).
                }
            }

            val result = executeStep(step, macro.id, index)

            if (!result.success) {
                val recovered = attemptRecoveryJump(context, steps, index)
                if (recovered != null) {
                    index = recovered
                    continue
                }
                return "$stepLabel pe atak gaya (${step.actionType} on \"$target\"). " +
                    "Wajah: ${result.message}\n\n" +
                    "Screen shayad badal gayi hai jab se ye seekha tha — is task ko dobara record karna sahi rahega."
            }

            // IMPROVEMENT (Phase 1e — closed-loop verification): this used
            // to be a single flat delay(400L) with ZERO confirmation the
            // step actually took effect — ActionEngine reporting success
            // only means "a tap/type was dispatched", not "the screen did
            // what was recorded". VerificationEngine existed for exactly
            // this but was completely unused dead code until now.
            //
            // Only checked when there IS a next step, and only when that
            // next step expects the SAME app (or doesn't specify one) —
            // cross-app transitions are deliberately left to the next
            // loop iteration's own navigateToApp() foreground-poll above,
            // which already does real verification for that case; running
            // both would just fight over the same wait.
            //
            // Evidence check has two tiers, cheapest first: if the next
            // step has a visible label (text/contentDescription), reuse
            // ScreenContextEngine.waitForText() — it's already backed by
            // the cached snapshot, no tree-walk needed. Only when a step
            // has nothing but a resourceId (icon-only element) do we fall
            // back to a real node lookup via findBestMatchingNode(), which
            // needs its own small poll loop since nothing existing does
            // this check today.
            //
            // On a miss: check for drift (lock screen / wrong app) FIRST —
            // if the screen is genuinely wrong, retrying the same tap is
            // pointless and we say so specifically instead of stalling.
            // Otherwise is ambiguous (probably just still rendering) —
            // retry the step ONCE (re-executing it — see waitForStepEvidence()'s
            // doc comment for why this isn't literally routed through
            // VerificationEngine), then check evidence one final time
            // before giving up.
            if (index + 1 < steps.size) {
                val nextStep = steps[index + 1]
                val expectsSameApp = nextStep.packageName.isNullOrBlank() || nextStep.packageName == step.packageName
                if (expectsSameApp) {
                    if (!waitForStepEvidence(nextStep, timeoutMs = 2000L)) {
                        val drift = detectDrift(context, nextStep.packageName)
                        if (drift != null) {
                            val recovered = attemptRecoveryJump(context, steps, index)
                            if (recovered != null) {
                                index = recovered
                                continue
                            }
                            return "$stepLabel ke baad ruk gaya — $drift. Isliye aage ke steps nahi chalaye — screen expected se alag hai."
                        }
                        val retryResult = executeStep(step, macro.id, index)
                        if (!retryResult.success || !waitForStepEvidence(nextStep, timeoutMs = 1500L)) {
                            val recovered = attemptRecoveryJump(context, steps, index)
                            if (recovered != null) {
                                index = recovered
                                continue
                            }
                            return "$stepLabel ke baad atak gaya — expected screen dobara try karne ke baad bhi nahi aayi. " +
                                "Screen shayad badal gayi hai jab se ye seekha tha — is task ko dobara record karna sahi rahega."
                        }
                    }
                } else {
                    delay(400L) // cross-app transition — verified by the next iteration's own navigateToApp() poll instead
                }
            } else {
                delay(400L) // last step — nothing to compare against, keep old pacing
            }
            index++
        }

        return "Ho gaya! \"${macro.displayPhrase}\" successfully complete — ${steps.size} steps chale."
    }

    /**
     * Dispatches a single recorded step's action via ActionEngine. Pulled
     * out of replayInternal's loop (Phase 1e) so the verification retry
     * path below can re-run the exact same step without duplicating this
     * whole when-block.
     */
    private suspend fun executeStep(step: RecordedStep, macroId: Int, stepIndex: Int): ActionResult {
        return when (step.actionType) {
            // BUGFIX: was ActionEngine.tap(target) using just ONE string.
            // When the target came from resourceId and that id is shared
            // across multiple near-identical elements (e.g. Quick
            // Settings tiles all using the same template id), that
            // always landed on whichever tile came first — not
            // necessarily the recorded one (Bluetooth got recorded,
            // Location got tapped). tapStep() passes text/description
            // too, so same-id candidates get disambiguated correctly.
            "TAP" -> ActionEngine.tapStep(
                step.resourceId, step.text, step.contentDescription,
                step.boundsLeft, step.boundsTop, step.boundsRight, step.boundsBottom,
                step.packageName, step.className, macroId, stepIndex
            )
            // BUGFIX: long-presses used to be recorded and replayed as
            // plain TAPs (see JunAccessibilityService/RecordingEngine),
            // which is often a no-op or the wrong action entirely (e.g.
            // opening a chat instead of long-pressing it to select).
            "LONG_PRESS" -> ActionEngine.longPressStep(
                step.resourceId, step.text, step.contentDescription,
                step.boundsLeft, step.boundsTop, step.boundsRight, step.boundsBottom,
                step.packageName, step.className, macroId, stepIndex
            )
            "TYPE" -> {
                val typed = step.typedText
                if (typed.isNullOrBlank()) {
                    ActionResult(false, "record kiya gaya text khaali hai")
                } else {
                    // BUGFIX: this is the actual fix for the "Step X/Y pe
                    // ruk gaya — koi identifier hi save nahi hua" failure.
                    // Previously required resourceId/text/contentDescription
                    // and bailed with no fallback if a field had none of
                    // those (very common for custom EditTexts). typeStep()
                    // now falls back to the recorded bounds via
                    // findNodeAtPosition() when identifiers come up empty.
                    ActionEngine.typeStep(
                        step.resourceId, step.contentDescription, typed,
                        step.boundsLeft, step.boundsTop, step.boundsRight, step.boundsBottom,
                        step.packageName, step.className, macroId, stepIndex
                    )
                }
            }
            // BUGFIX: swipes were never recorded OR replayed at all
            // before — see RecordingEngine.captureScroll. Replayed as a
            // real scroll action on the same scrollable node when
            // possible, falling back to a raw drag gesture along the
            // recorded bounds if the node can't be found anymore.
            "SWIPE" -> ActionEngine.scrollStep(
                step.resourceId, step.contentDescription, step.scrollForward ?: true,
                step.scrollHorizontal ?: false,
                step.boundsLeft, step.boundsTop, step.boundsRight, step.boundsBottom,
                step.packageName, step.className, macroId, stepIndex
            )
            else -> ActionResult(false, "pehchana nahi gaya action type: ${step.actionType}")
        }
    }

    /**
     * Evidence check for Phase 1e's closed-loop verification — is [step]'s
     * own element now findable on screen? Two tiers, cheapest first:
     *
     *   1. If the step has a visible label (text/contentDescription), reuse
     *      ScreenContextEngine.waitForText() — already backed by the
     *      cached snapshot from the last accessibility event, no fresh
     *      tree-walk needed. This covers the common case cheaply.
     *   2. Otherwise (icon-only element, only a resourceId) fall back to
     *      a real findBestMatchingNode() lookup, polled the same way.
     *      This can't go through VerificationEngine.verifyCondition()
     *      because that takes a synchronous condition lambda over the
     *      cached ScreenContext — findBestMatchingNode() needs a live,
     *      suspend call to the AccessibilityService itself, which a sync
     *      lambda over a snapshot can't do.
     *
     * The found node in tier 2 is only an existence check — it's recycled
     * immediately, never acted on.
     */
    private suspend fun waitForStepEvidence(step: RecordedStep, timeoutMs: Long): Boolean {
        val label = step.text?.takeIf { it.isNotBlank() } ?: step.contentDescription?.takeIf { it.isNotBlank() }
        if (label != null) {
            return ScreenContextEngine.waitForText(label, timeoutMs)
        }
        val svc = JunAccessibilityService.instance ?: return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val match = svc.findBestMatchingNode(
                step.resourceId, step.text, step.contentDescription, step.className,
                step.boundsLeft, step.boundsTop, step.boundsRight, step.boundsBottom,
                step.packageName
            )
            if (match.node != null) {
                match.node.recycle()
                match.alternate?.let { try { it.recycle() } catch (e: Exception) { /* already gone */ } }
                return true
            }
            match.alternate?.let { try { it.recycle() } catch (e: Exception) { /* already gone */ } }
            delay(250L)
        }
        return false
    }

    /**
     * Best-effort explanation for WHY expected evidence didn't show up —
     * checked before deciding whether a retry is even worth attempting.
     * Retrying a tap makes no sense if the screen is a lock prompt or a
     * completely different app; better to stop and say so specifically.
     */
    private fun detectDrift(context: Context, expectedPkg: String?): String? {
        if (ScreenContextEngine.looksLikeLockScreen()) {
            return "screen par ek lock/authentication prompt dikh raha hai"
        }
        if (!expectedPkg.isNullOrBlank() && !ScreenContextEngine.isAppInForeground(context, expectedPkg)) {
            val actualApp = ScreenContextEngine.getCurrentContext().currentApp
            return "expected app ki jagah ab \"$actualApp\" khula hua hai"
        }
        return null
    }
}
