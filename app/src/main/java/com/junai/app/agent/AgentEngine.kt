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

                    val actionResult = executeStep(step, selection.tool, params)
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

        return buildSummary(succeeded, completedMessages, failureReason)
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
                // Best-effort field lookup — GoalPlanner doesn't yet separate "which field"
                // from "what to type", so we try common message-box hints in order.
                val fieldHints = listOf("Message", "Type a message", step.target)
                var result: ActionResult = ActionResult(false, "Couldn't find a message field to type into.")
                for (hint in fieldHints) {
                    val attempt = ActionEngine.typeText(hint, step.description)
                    if (attempt.success) { result = attempt; break }
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
        val settingStr = params.targetSetting
            ?: parseSettingFromText("${step.description} ${step.target}")?.let { "${it.first}:${it.second}" }
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
