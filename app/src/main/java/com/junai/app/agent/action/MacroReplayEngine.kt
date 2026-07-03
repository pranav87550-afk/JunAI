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
                delay(500L) // let the app/panel settle before the next action targets it
            }

            // Prefer the most stable identifier first, same priority order
            // used at capture time: resourceId > text > contentDescription.
            val target = step.resourceId ?: step.text ?: step.contentDescription
            if (target.isNullOrBlank()) {
                return "$stepLabel pe ruk gaya — is step ka koi identifier hi save nahi hua tha " +
                    "(resource-id, text, aur description sab khaali hain). Ye macro corrupt lag raha hai, dobara record karna hoga."
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
                "TAP" -> ActionEngine.tapStep(step.resourceId, step.text, step.contentDescription)
                "TYPE" -> {
                    val typed = step.typedText
                    if (typed.isNullOrBlank()) {
                        ActionResult(false, "record kiya gaya text khaali hai")
                    } else {
                        ActionEngine.typeText(target, typed)
                    }
                }
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
