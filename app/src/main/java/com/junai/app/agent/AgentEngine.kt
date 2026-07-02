package com.junai.app.agent

import android.content.Context
import android.media.AudioManager
import com.junai.app.AppDatabase
import com.junai.app.IntentDetector
import com.junai.app.LearningRepository
import com.junai.app.agent.action.ActionEngine
import com.junai.app.agent.action.ActionResult
import com.junai.app.agent.memory.AgentMemoryEntity
import com.junai.app.agent.research.ResearchAgent
import com.junai.app.agent.research.ResearchResult
import com.junai.app.agent.safety.SafetyConfirmationOverlay
import com.junai.app.agent.screen.ScreenContextEngine
import com.junai.app.memory.MemoryRepository
import org.json.JSONArray

/**
 * AgentEngine — the single entry point for all agent operations. Every
 * other Phase 15 module is called from here; nothing else calls them
 * directly. ChatIntentHandler calls [runTask] when intent == AGENT_TASK
 * and relays the returned summary straight to the user — this class never
 * touches RecyclerView/ChatAdapter itself, keeping it UI-agnostic per spec
 * ("Report final result back through ChatIntentHandler").
 *
 * One AgentEngine instance is expected per ChatIntentHandler lifecycle,
 * same pattern as the other repositories it holds.
 *
 * BUGFIX (post-Phase-15 debugging pass): [runTask] now calls
 * [SafetyConfirmationOverlay.ensureStarted] before doing anything else.
 * Previously, DecisionEngine → SafetyLayer would suspend for up to 30s
 * waiting for a UI to call SafetyLayer.respond() — but nothing was ever
 * observing SafetyLayer.activeRequest, so every MEDIUM/HIGH/CRITICAL step
 * (TYPE, TAP, SYSTEM_ACTION, and anything matching a SafetyConcern) silently
 * timed out and got BLOCKED. This is why multi-step and even most
 * single-step commands appeared to "do nothing."
 */
class AgentEngine(private val context: Context) {

    private val memoryRepository = MemoryRepository(context)
    private val learningRepository = LearningRepository(context)
    private val multiStepTaskManager = MultiStepTaskManager(context)
    private val agentMemoryDao = AppDatabase.getInstance(context).agentMemoryDao()

    @Volatile private var currentTaskId: Int? = null
    @Volatile private var cancelRequested = false

    companion object {
        // Exposed so ChatIntentHandler can intercept "stop"/"cancel" BEFORE
        // normal IntentDetector dispatch (same pattern as the existing
        // curiosity-pending-question check) — IntentDetector alone might
        // classify "stop" as STOP_MUSIC or UNKNOWN, not as "cancel my task".
        private val stopWords = setOf("stop", "cancel", "ruk jao", "rok do", "band karo", "rukh jao")

        /** Matches single- or double-quoted text, used to pull the actual
         * message body out of a clause like Mom ko message bhejo 'kal milte hain'. */
        private val QUOTED_TEXT_REGEX = Regex("['\"]([^'\"]+)['\"]")

        /**
         * Extracts the contact name from a TYPE-step clause.
         * Matches patterns like:
         *   "Papa ko message bhejo 'text'"    → "Papa"
         *   "Mummy ko bhej do 'text'"         → "Mummy"
         *   "Ravi bhai ko send karo 'text'"   → "Ravi bhai"
         * Stops at "ko" which is the universal Hinglish indirect-object marker.
         */
        private val CONTACT_BEFORE_KO_REGEX = Regex("(?i)^(.+?)\\s+ko\\s+(?:message|msg|send|bhejo|bhej)")
        // BUGFIX: only handled Hinglish "<contact> ko message" phrasing.
        // English-style "message <contact> '<text>'" (contact comes AFTER
        // the verb, no "ko" at all) never matched, so contact stayed null
        // and Jun asked "Kisko message karun?" even with a clear command.
        private val MESSAGE_THEN_CONTACT_REGEX =
            Regex("(?i)(?:message|msg|send)\\s+(?:to\\s+)?([A-Za-z]+(?:\\s+[A-Za-z]+)?)\\s*['\"]")

        fun looksLikeStopCommand(text: String): Boolean {
            val lower = text.lowercase().trim()
            return stopWords.any { lower == it || lower.startsWith("$it ") || lower.startsWith("$it,") }
        }
    }

    fun isTaskRunning(): Boolean = currentTaskId != null

    /** Called by ChatIntentHandler when [looksLikeStopCommand] matches and a task is running. */
    suspend fun cancelCurrentTask(): String {
        val taskId = currentTaskId ?: return "There's nothing running right now to stop."
        cancelRequested = true
        multiStepTaskManager.pauseTask(taskId)
        return "Okay, stopped. Say \"resume\" anytime to pick this back up."
    }

    /**
     * Main entry point. Returns a short, honest, human-readable summary —
     * never raw internal chain-of-thought — for ChatIntentHandler to relay.
     */
    suspend fun runTask(intentResult: IntentDetector.IntentResult): String {
        // BUGFIX: must happen before any step can reach DecisionEngine/SafetyLayer,
        // otherwise approval requests have nothing observing them and silently
        // time out after 30s. Safe to call every time — no-op after the first call.
        SafetyConfirmationOverlay.ensureStarted(context)

        if (isTaskRunning()) {
            return "I'm still working on something — say \"stop\" if you'd like me to cancel it first."
        }

        val params = intentResult.agentTaskParams
            ?: return "I'm not fully sure what you'd like me to do — can you rephrase that?"

        cancelRequested = false

        // RESUME is special — fetch the existing plan instead of making a new one.
        if (params.actionType == IntentDetector.AgentActionType.RESUME) {
            val resumable = multiStepTaskManager.getResumableTask()
                ?: return "There's nothing paused right now to resume."
            currentTaskId = resumable.taskId
            return executeSteps(
                resumable.taskId, resumable.params, resumable.steps.toMutableList(),
                resumable.currentStepIndex, intentResult.confidence
            )
        }

        // Gather full situational context before planning, per spec.
        val situationalContext = ContextAwarenessEngine.gatherContext(context, memoryRepository, multiStepTaskManager)

        val steps = GoalPlanner.createPlan(params)
        if (steps.isEmpty()) {
            return "I couldn't figure out a clear plan for that — can you be more specific?"
        }

        val taskId = multiStepTaskManager.startNewTask(params, steps)
        currentTaskId = taskId

        return executeSteps(taskId, params, steps.toMutableList(), 0, intentResult.confidence, situationalContext)
    }

    // ── Step execution loop ──────────────────────────────────────────

    private suspend fun executeSteps(
        taskId: Int,
        params: IntentDetector.AgentTaskParams,
        steps: MutableList<AgentStep>,
        startIndex: Int,
        agentConfidence: Int,
        precomputedContext: CurrentContext? = null
    ): String {
        val completedMessages = mutableListOf<String>()
        val toolsUsed = mutableListOf<String>()
        var failureReason: String? = null
        var index = startIndex

        try {
        var researchCache: ResearchResult? = null

        while (index < steps.size) {
            if (cancelRequested) {
                multiStepTaskManager.pauseTask(taskId)
                currentTaskId = null
                val progress = if (completedMessages.isEmpty()) "nothing yet" else completedMessages.joinToString(" ")
                return "Okay, I stopped. So far: $progress Say \"resume\" anytime to continue."
            }

            val step = steps[index].copy(status = StepStatus.RUNNING)
            steps[index] = step
            multiStepTaskManager.updateProgress(taskId, index, steps)

            // DecisionEngine gate — every single step, never skipped.
            val decision = DecisionEngine.evaluate(step, agentConfidence)
            when (decision.verdict) {
                DecisionVerdict.NEEDS_CLARIFICATION -> {
                    multiStepTaskManager.pauseTask(taskId)
                    currentTaskId = null
                    return decision.reason ?: "I need a bit more detail before continuing — can you clarify?"
                }
                DecisionVerdict.BLOCKED -> {
                    steps[index] = step.copy(status = StepStatus.FAILED)
                    multiStepTaskManager.updateProgress(taskId, index, steps)
                    failureReason = decision.reason ?: "This step was blocked for safety."
                }
                DecisionVerdict.PROCEED, DecisionVerdict.PROCEED_WITH_NOTIFICATION -> {
                    // BUGFIX: see note above researchCache — research-action
                    // tasks bypass the normal ToolOrchestratorV2 dispatch
                    // entirely, since every step in researchPlan() would
                    // otherwise reach ToolType.RESEARCH and re-run the full
                    // search independently.
                    val actionResult = if (params.actionType == IntentDetector.AgentActionType.RESEARCH) {
                        toolsUsed.add(ToolType.RESEARCH.name)
                        when (step.type) {
                            StepType.SEARCH -> {
                                if (researchCache == null) {
                                    researchCache = ResearchAgent.research(step.target, context, learningRepository)
                                }
                                // Silent — the cached result is only shown once, on RESPOND.
                                ActionResult(true, "")
                            }
                            StepType.RESPOND -> {
                                val cached = researchCache
                                if (cached != null) {
                                    ActionResult(cached.sources.isNotEmpty(), buildResearchMessage(cached))
                                } else {
                                    // SEARCH step somehow never ran or was blocked earlier — fail honestly.
                                    ActionResult(false, "I wasn't able to research that.")
                                }
                            }
                            // OPEN/READ/COMPARE in a research plan are bookkeeping
                            // markers from the spec's canonical 7-step structure —
                            // ResearchAgent.research() already does the equivalent
                            // of "open results, read, compare" internally in one
                            // shot, so these don't need (and must not trigger)
                            // their own separate tool calls.
                            else -> ActionResult(true, "")
                        }
                    } else {
                        val situational = precomputedContext
                            ?: ContextAwarenessEngine.gatherContext(this.context, memoryRepository, multiStepTaskManager)
                        val selection = ToolOrchestratorV2.selectTool(
                            intent = IntentDetector.Intent.AGENT_TASK,
                            agentTaskParams = params,
                            context = situational,
                            agentMemoryDao = agentMemoryDao,
                            decisionConfidence = agentConfidence
                        )
                        toolsUsed.add(selection.tool.name)
                        executeStep(step, selection.tool, params)
                    }

                    if (actionResult.success) {
                        // BUGFIX: RESPOND/COMPARE steps intentionally return
                        // a blank message (see executeActionStep) — skip
                        // adding those so the final summary doesn't end up
                        // with stray extra spaces or empty entries.
                        if (actionResult.message.isNotBlank()) {
                            completedMessages.add(actionResult.message)
                        }
                    } else {
                        // BUGFIX: step.fallback is an internal guidance note for
                        // AgentEngine/DecisionEngine ("if X, tell the user Y"),
                        // never meant to be shown to the user verbatim. Appending
                        // it raw (as the old code did) leaked prompt-style
                        // instruction text straight into the chat bubble. The
                        // actionResult.message alone is already the correct,
                        // human-readable failure reason — nothing more to add here.
                        failureReason = actionResult.message
                    }
                }
            }

            val finalStatus = if (failureReason == null) StepStatus.DONE else StepStatus.FAILED
            steps[index] = step.copy(status = finalStatus)
            multiStepTaskManager.updateProgress(taskId, index, steps)

            if (failureReason != null) break
            index++
        }


        val succeeded = failureReason == null && index >= steps.size
        if (succeeded) multiStepTaskManager.completeTask(taskId) else multiStepTaskManager.failTask(taskId)
        currentTaskId = null

        saveAgentMemory(params.rawGoal, succeeded, steps, toolsUsed, failureReason)

        // Return to JunAI once the task is fully done — but only on success.
        // On failure we deliberately leave the user wherever the last step
        // left them (e.g. WhatsApp with the message half-typed), since
        // that's exactly where they'd want to look to finish/fix it
        // manually. A short delay first lets the last action (e.g. the
        // WhatsApp "sent" tick) actually render before we switch away —
        // jumping back instantly would cut off that visual confirmation.
        if (succeeded) {
            kotlinx.coroutines.delay(800)
            ActionEngine.openApp(context, context.packageName)
        }

        return buildSummary(succeeded, completedMessages, failureReason)
        } finally {
            // BUGFIX: if an uncaught exception escapes (Room DB crash, OOM,
            // accessibility service dying mid-step, coroutine cancellation),
            // currentTaskId must still be cleared. Without this, the very
            // next user message (even "hi") sees isTaskRunning()==true,
            // enters the "still working" / re-run path, and produces the
            // same stale error response repeatedly until the app restarts.
            currentTaskId = null
        }
    }

    // ── Dispatch a single step to the right tool ─────────────────────

    /**
     * BUGFIX: a step's StepType (OPEN/TAP/TYPE/SYSTEM_ACTION/etc) is what
     * actually determines what executeStep does — ToolType (chosen by
     * ToolOrchestratorV2 from the *task's* actionType, e.g. MULTI_STEP)
     * was never meant to gate per-step execution. Previously, any task
     * whose AgentActionType was MULTI_STEP got ToolType.PLANNER for every
     * single step, and the PLANNER branch below just echoed the step's
     * description back without doing anything — so multi-step commands
     * "succeeded" without ever touching ActionEngine. Now PLANNER (and
     * AGENT) fall through to the same step-type dispatch as ACTION does,
     * so multi-step tasks actually execute each underlying step.
     */
    private suspend fun executeStep(step: AgentStep, tool: ToolType, params: IntentDetector.AgentTaskParams): ActionResult {
        return when (tool) {
            ToolType.RESEARCH -> {
                val result = ResearchAgent.research(step.target, context, learningRepository)
                ActionResult(result.sources.isNotEmpty(), buildResearchMessage(result))
            }
            ToolType.SCREEN -> {
                val screenCtx = ScreenContextEngine.getCurrentContext()
                if (screenCtx.visibleTexts.isNotEmpty()) {
                    ActionResult(true, "Found on screen: ${screenCtx.visibleTexts.take(3).joinToString(" / ")}")
                } else {
                    ActionResult(false, "Nothing readable was visible on screen.")
                }
            }
            // BUGFIX: PLANNER/AGENT used to short-circuit to a no-op echo.
            // They now dispatch by the step's own type, same as ACTION,
            // since a MULTI_STEP task is just a sequence of OPEN/TAP/TYPE/
            // SYSTEM_ACTION/etc steps that genuinely need to run.
            ToolType.ACTION, ToolType.PLANNER, ToolType.AGENT -> executeActionStep(step, params)
            else -> ActionResult(true, step.description)
        }
    }

    private fun buildResearchMessage(result: ResearchResult): String {
        val sourceNote = if (result.sources.isNotEmpty()) " (sources: ${result.sources.joinToString(", ")})" else ""
        val caveatNote = result.caveat?.let { " $it" } ?: ""
        return "${result.answer}$sourceNote.$caveatNote"
    }

    private suspend fun executeActionStep(step: AgentStep, params: IntentDetector.AgentTaskParams): ActionResult {
        return when (step.type) {
            StepType.OPEN -> {
                val packageName = guessPackageName(step.target)
                    ?: return ActionResult(false, "I don't know the package name for \"${step.target}\" yet.")
                ActionEngine.openApp(context, packageName)
            }
            StepType.READ -> ActionEngine.waitForScreen(step.target, 4000L).let {
                if (it.success) it else {
                    val screenCtx = ScreenContextEngine.getCurrentContext()
                    if (screenCtx.visibleTexts.isNotEmpty()) {
                        ActionResult(true, "Found on screen: ${screenCtx.visibleTexts.take(3).joinToString(" / ")}")
                    } else it
                }
            }
            StepType.TAP -> ActionEngine.tap(step.target)
            StepType.TYPE -> {
                // SafetyLayer already approved this via DecisionEngine before we got here.
                // BUGFIX: previously typed step.description verbatim — for a
                // clause like "Mom ko message bhejo 'kal milte hain'" that
                // meant the literal text got typed into the message box instead
                // of just "kal milte hain". Extract the quoted portion when present.
                val quoted = QUOTED_TEXT_REGEX.find(step.description)?.groupValues?.get(1)
                if (quoted == null) {
                    // BUGFIX: no quotes means the user never actually said
                    // what to send (e.g. "open whatsapp and message mummy").
                    // Don't guess at message content; say so plainly instead.
                    return ActionResult(
                        false,
                        "You didn't say what to send — try again with the message in quotes, like \"message mummy 'on my way'\"."
                    )
                }

                // BUGFIX: WhatsApp (and most messaging apps) open to the
                // chat list, not a specific conversation. The old code tried
                // to find "Type a message" immediately — which doesn't exist
                // on the chat list screen — so it always failed.
                // Now extract the contact name and navigate to their chat first.
                val contact = CONTACT_BEFORE_KO_REGEX.find(step.description)?.groupValues?.get(1)?.trim()
                    ?: MESSAGE_THEN_CONTACT_REGEX.find(step.description)?.groupValues?.get(1)?.trim()

                if (contact != null) {
                    // BUGFIX: modern WhatsApp can default-open to the
                    // "Updates" (Status) tab, not "Chats". If the contact
                    // recently posted a status, their name also appears
                    // there — and since findNodeByText() just grabs the
                    // FIRST node matching the text, tap(contact) could hit
                    // their Status entry instead of their chat, opening the
                    // story viewer instead of the conversation. Explicitly
                    // switch to the Chats tab first so we're searching the
                    // right screen. Best-effort — if the tab isn't found
                    // (older WhatsApp layout with no tabs), this just no-ops
                    // and falls through to the direct tap below.
                    ActionEngine.tap("Chats")
                    kotlinx.coroutines.delay(300)

                    // APPROACH 1 (fastest): contact may already be visible in the
                    // chat list — just tap their name directly. This works when
                    // WhatsApp opens to the recent-chats list and the contact has
                    // a recent conversation (the common case).
                    val directTap = ActionEngine.tap(contact)

                    if (!directTap.success) {
                        // APPROACH 2: contact not visible in list — use search.
                        // BUGFIX: old code tried tap("Search") but modern WhatsApp
                        // merged the search bar with Meta AI — the actual text is
                        // "Ask Meta AI or Search". Try several known variants.
                        val searchHints = listOf(
                            "Ask Meta AI or Search",
                            "Search…",
                            "Search",
                            "Ask Meta AI or search"
                        )
                        var searchOpened = false
                        for (hint in searchHints) {
                            if (ActionEngine.tap(hint).success) { searchOpened = true; break }
                        }

                        if (searchOpened) {
                            kotlinx.coroutines.delay(500)
                            // Type into whatever field is now focused
                            val searchFieldHints = listOf(
                                "Ask Meta AI or Search",
                                "Search…", "Search",
                                "search_src_text"
                            )
                            for (hint in searchFieldHints) {
                                if (ActionEngine.typeText(hint, contact).success) break
                            }
                            kotlinx.coroutines.delay(1200)
                        }

                        // Tap on contact in results (works for both search results
                        // and if direct tap on chat list is retried after scroll)
                        if (!ActionEngine.tap(contact).success) {
                            return ActionResult(
                                false,
                                "Opened WhatsApp but couldn't find \"$contact\" in your chats. Make sure their name is saved exactly as you said it."
                            )
                        }
                    }

                    kotlinx.coroutines.delay(1500) // wait for chat screen + keyboard animation

                    // Confirm chat is loaded by waiting for known chat-screen elements
                    ActionEngine.waitForScreen("Type a message", 2500)

                    // Explicitly tap/focus the message field before typing —
                    // accessibility typeText() needs the field to be focused first
                    // or it may find the node but fail to commit the text.
                    val focusHints = listOf(
                        "com.whatsapp:id/entry",
                        "Type a message",
                        "Type a message…"
                    )
                    for (hint in focusHints) {
                        if (ActionEngine.tap(hint).success) break
                    }
                    kotlinx.coroutines.delay(400)
                }

                // Step D: now find the message input field and type
                val fieldHints = listOf(
                    "com.whatsapp:id/entry",       // resource ID — most reliable across versions
                    "Type a message",
                    "Type a message…",
                    "Message",
                    "Type a message here"
                )
                var result: ActionResult = ActionResult(false, "Opened the chat but couldn't find the message field.")
                for (hint in fieldHints) {
                    val attempt = ActionEngine.typeText(hint, quoted)
                    if (attempt.success) { result = attempt; break }
                }

                // Step E — auto-send. Only runs if typing actually succeeded
                // above; we never want to tap Send with an empty/wrong field.
                // No separate confirmation dialog here — the "Jun wants to
                // do this: <contact> ko message bhejo '<text>'" prompt the
                // user already approved before this step ran covers the
                // full send action, not just typing it in.
                if (result.success) {
                    kotlinx.coroutines.delay(300) // let the typed text settle/render
                    val sendHints = listOf(
                        "com.whatsapp:id/send",   // resource ID — most reliable
                        "Send"                     // content-description fallback
                    )
                    var sent = false
                    for (hint in sendHints) {
                        if (ActionEngine.tap(hint).success) { sent = true; break }
                    }
                    result = if (sent) ActionResult(true, "Sent to \"${contact ?: "the open chat"}\": \"$quoted\".")
                    else ActionResult(
                        false,
                        "Typed the message but couldn't find the Send button — it's still sitting in the text box, waiting for you to send it manually."
                    )
                }
                result
            }
            StepType.SYSTEM_ACTION -> executeSystemAction(step, params)
            StepType.SEARCH -> {
                val result = ResearchAgent.research(step.target, context, learningRepository)
                ActionResult(result.sources.isNotEmpty(), buildResearchMessage(result))
            }
            // BUGFIX: RESPOND/COMPARE steps are bookkeeping markers in the
            // plan ("now confirm to the user") — their description field is
            // an internal instruction for AgentEngine/DecisionEngine, not
            // new information. The real result already came from the
            // previous step (e.g. "Brightness set to 50/255"). Echoing
            // step.description here was duplicating + polluting that
            // message with text like "Confirm the change to the user".
            // Empty message = nothing added to completedMessages below.
            StepType.COMPARE, StepType.RESPOND -> ActionResult(true, "")
        }
    }

    private suspend fun executeSystemAction(step: AgentStep, params: IntentDetector.AgentTaskParams): ActionResult {
        // BUGFIX: params.targetSetting is a single value computed once by
        // IntentDetector for the *entire* raw command (e.g. "wifi:off"),
        // not per-step. Previously this was checked FIRST and reused for
        // every SYSTEM_ACTION step in a multi-step plan — so "flashlight on
        // karke phir wifi off karo" silently applied "wifi:off" twice and
        // never touched the flashlight at all. Each step's own clause
        // (step.description/step.target, set by GoalPlanner.splitIntoClauses)
        // must be parsed first; params.targetSetting is now only a fallback
        // for the single-step case where there's exactly one step and
        // per-step parsing finds nothing.
        val settingStr = parseSettingFromText("${step.description} ${step.target}")
            ?.let { "${it.first}:${it.second}" }
            ?: params.targetSetting
            ?: return ActionResult(false, "Couldn't figure out which setting to change.")

        val parts = settingStr.split(":")
        if (parts.size != 2) return ActionResult(false, "Couldn't understand the setting \"$settingStr\".")
        val (setting, state) = parts
        val enabled = state == "on" || state == "increase"

        return when (setting) {
            "wifi" -> ActionEngine.setWifi(context, enabled)
            "bluetooth" -> ActionEngine.setBluetooth(context, enabled)
            "torch" -> ActionEngine.toggleFlashlight(context, enabled)
            "dnd" -> ActionEngine.toggleDND(context, enabled)
            "airplane" -> ActionEngine.toggleAirplaneMode(context)
            "brightness" -> {
                val level = state.toIntOrNull() ?: if (state == "increase") 200 else if (state == "decrease") 80 else 128
                ActionEngine.setBrightness(context, level)
            }
            "volume" -> {
                val level = state.toIntOrNull() ?: if (state == "increase") 12 else if (state == "decrease") 3 else 7
                ActionEngine.setVolume(context, AudioManager.STREAM_MUSIC, level)
            }
            else -> ActionResult(false, "Don't know how to control \"$setting\" yet.")
        }
    }

    /** Fallback parser for multi-step clauses that don't carry structured targetSetting. */
    private fun parseSettingFromText(text: String): Pair<String, String>? {
        val lower = text.lowercase()
        val settingKeywords = mapOf(
            "wifi" to "wifi", "bluetooth" to "bluetooth", "torch" to "torch", "flashlight" to "torch",
            "brightness" to "brightness", "volume" to "volume",
            "do not disturb" to "dnd", "dnd" to "dnd", "airplane" to "airplane"
        )
        for ((keyword, canonical) in settingKeywords) {
            if (lower.contains(keyword)) {
                val isOff = listOf("off", "disable", "band", "mat").any { lower.contains(it) }
                return canonical to (if (isOff) "off" else "on")
            }
        }
        return null
    }

    /** Best-effort spoken-name → package lookup. Unknown apps fail gracefully, never crash. */
    private fun guessPackageName(appName: String): String? {
        val knownPackages = mapOf(
            "whatsapp" to "com.whatsapp", "youtube" to "com.google.android.youtube",
            "instagram" to "com.instagram.android", "facebook" to "com.facebook.katana",
            "chrome" to "com.android.chrome", "gmail" to "com.google.android.gm",
            "spotify" to "com.spotify.music", "telegram" to "org.telegram.messenger",
            "maps" to "com.google.android.apps.maps", "settings" to "com.android.settings",
            "playstore" to "com.android.vending", "play store" to "com.android.vending"
        )
        val lower = appName.lowercase().trim()
        return knownPackages.entries.firstOrNull { lower.contains(it.key) }?.value
    }

    // ── Memory + summary ──────────────────────────────────────────────

    private suspend fun saveAgentMemory(
        goalText: String,
        succeeded: Boolean,
        steps: List<AgentStep>,
        toolsUsed: List<String>,
        failureReason: String?
    ) {
        try {
            val stepsJson = JSONArray(steps.map { it.description }).toString()
            val toolsJson = JSONArray(toolsUsed.distinct()).toString()
            agentMemoryDao.insert(
                AgentMemoryEntity(
                    goalText = goalText,
                    wasSuccessful = succeeded,
                    stepsUsed = stepsJson,
                    toolsUsed = toolsJson,
                    preferredApps = JSONArray().toString(),
                    failureReason = failureReason,
                    improvementNote = null
                )
            )
            // Genuinely feeds Learning Engine V2's repeated-failure nudge, which
            // reads from exactly this table (see LearningEngineV2Repository).
            if (!succeeded) {
                learningRepository.logFailure(
                    question = goalText,
                    detectedIntent = "AGENT_TASK",
                    confidence = 0f,
                    failureReason = failureReason ?: "AGENT_STEP_FAILED"
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("AgentEngine", "Failed to save agent memory: ${e.message}")
        }
    }

    /** Per spec: short, honest, human-readable — never raw chain-of-thought. */
    private fun buildSummary(succeeded: Boolean, completedMessages: List<String>, failureReason: String?): String {
        val doneText = completedMessages.joinToString(" ")
        return if (succeeded) {
            doneText.ifBlank { "Done." }
        } else {
            val prefix = if (completedMessages.isNotEmpty()) "$doneText " else ""
            "$prefix${failureReason ?: "Something didn't work as expected."}"
        }
    }
}
