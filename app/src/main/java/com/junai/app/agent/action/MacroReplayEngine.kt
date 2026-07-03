package com.junai.app.agent.action

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.junai.app.agent.screen.ScreenContextEngine
import com.junai.app.learning.RecordedMacroEntity
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
    private fun getDefaultLauncherPackage(context: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName
    }

    private suspend fun navigateToApp(context: Context, pkg: String): ActionResult {
        val launcherPkg = getDefaultLauncherPackage(context)
        return when {
            pkg == launcherPkg -> {
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

    private suspend fun replayInternal(context: Context, macro: RecordedMacroEntity): String {
        val steps = RecordingEngine.parseSteps(macro.stepsJson)
        if (steps.isEmpty()) {
            return "Ye macro khali hai — koi steps record nahi hue the. Dobara record karo."
        }

        for ((index, step) in steps.withIndex()) {
            val stepLabel = "Step ${index + 1}/${steps.size}"

            // Make sure we're in the right app/surface for this step before
            // acting. Covers the very first step (nothing open yet) and any
            // later step that expects a different app or system surface.
            val pkg = step.packageName
            if (!pkg.isNullOrBlank() && !ScreenContextEngine.isAppInForeground(pkg)) {
                val navResult = navigateToApp(context, pkg)
                if (!navResult.success) {
                    return "$stepLabel pe ruk gaya — \"$pkg\" tak nahi pahunch paayi. " +
                        "Wajah: ${navResult.message}"
                }
                // BUGFIX (root cause of intermittent swipe failures AND
                // wrong-element taps like lock icon instead of a folder):
                // this used to be a single fixed delay(500L) regardless of
                // how long the new screen actually took to render. The
                // home screen especially can take longer than 500ms to
                // populate its icon grid (more so under load/low battery)
                // — so the next step's node search sometimes ran against a
                // still-loading tree: either the target genuinely wasn't
                // there yet (swipe target not found → step fails) or only
                // SOME icons had rendered and the search matched whatever
                // was already there instead of the intended one (wrong
                // icon tapped). Poll for the surface to actually report
                // itself foreground — up to 2s, checking every 200ms —
                // then give it one more short beat to finish laying out,
                // instead of gambling on a single fixed wait.
                var settled = ScreenContextEngine.isAppInForeground(pkg)
                var waited = 0L
                while (!settled && waited < 2000L) {
                    delay(200L)
                    waited += 200L
                    settled = ScreenContextEngine.isAppInForeground(pkg)
                }
                delay(300L) // final beat for icons/views to finish laying out even after foreground is confirmed
            }

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

            val result = when (step.actionType) {
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
                    step.packageName, step.className
                )
                // BUGFIX: long-presses used to be recorded and replayed as
                // plain TAPs (see JunAccessibilityService/RecordingEngine),
                // which is often a no-op or the wrong action entirely (e.g.
                // opening a chat instead of long-pressing it to select).
                "LONG_PRESS" -> ActionEngine.longPressStep(
                    step.resourceId, step.text, step.contentDescription,
                    step.boundsLeft, step.boundsTop, step.boundsRight, step.boundsBottom,
                    step.packageName, step.className
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
                            step.packageName, step.className
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
                    step.boundsLeft, step.boundsTop, step.boundsRight, step.boundsBottom,
                    step.packageName, step.className
                )
                else -> ActionResult(false, "pehchana nahi gaya action type: ${step.actionType}")
            }

            if (!result.success) {
                return "$stepLabel pe atak gaya (${step.actionType} on \"$target\"). " +
                    "Wajah: ${result.message}\n\n" +
                    "Screen shayad badal gayi hai jab se ye seekha tha — is task ko dobara record karna sahi rahega."
            }

            delay(400L) // let the UI settle between steps, same pacing GoalPlanner-driven steps use
        }

        return "Ho gaya! \"${macro.displayPhrase}\" successfully complete — ${steps.size} steps chale."
    }
}
