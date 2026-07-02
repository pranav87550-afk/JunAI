package com.junai.app.agent.action

import android.content.Context
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

    suspend fun replay(context: Context, macro: RecordedMacroEntity): String {
        val steps = RecordingEngine.parseSteps(macro.stepsJson)
        if (steps.isEmpty()) {
            return "Ye macro khali hai — koi steps record nahi hue the. Dobara record karo."
        }

        for ((index, step) in steps.withIndex()) {
            val stepLabel = "Step ${index + 1}/${steps.size}"

            // Make sure we're in the right app for this step before acting.
            // Covers both the very first step (app not open at all yet) and
            // any later step that expects a different app (rare, but a
            // macro COULD span apps).
            val pkg = step.packageName
            if (!pkg.isNullOrBlank() && !ScreenContextEngine.isAppInForeground(pkg)) {
                val openResult = ActionEngine.openApp(context, pkg)
                if (!openResult.success) {
                    return "$stepLabel pe ruk gaya — \"$pkg\" open nahi ho paya. " +
                        "Wajah: ${openResult.message}"
                }
                delay(500L) // let the app settle before the next action targets it
            }

            // Prefer the most stable identifier first, same priority order
            // used at capture time: resourceId > text > contentDescription.
            val target = step.resourceId ?: step.text ?: step.contentDescription
            if (target.isNullOrBlank()) {
                return "$stepLabel pe ruk gaya — is step ka koi identifier hi save nahi hua tha " +
                    "(resource-id, text, aur description sab khaali hain). Ye macro corrupt lag raha hai, dobara record karna hoga."
            }

            val result = when (step.actionType) {
                "TAP" -> ActionEngine.tap(target)
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
