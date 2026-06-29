package com.junai.app.agent

import com.junai.app.IntentDetector
import com.junai.app.agent.memory.AgentMemoryDao

enum class ToolType {
    MEMORY, NOTES, REMINDER, CALCULATOR, MUSIC, DRAWING, KNOWLEDGE, VOICE,
    BROWSER, AGENT, PLANNER, RESEARCH, SCREEN, ACTION, SETTINGS
}

data class ToolSelection(
    val tool: ToolType,
    val reason: String,
    val confidence: Int
)

/**
 * ToolOrchestratorV2 — decides which tool fits a task. Phase 11 (Tool
 * Orchestrator V1) was never built, so this doesn't "extend" anything —
 * it's a fresh, standalone selector (confirmed earlier in this build,
 * Phase 11 has no hard dependents).
 *
 * DESIGN NOTE — why this is selection-only, never invocation: AgentEngine
 * (the last file in this build) calls ToolOrchestratorV2 to pick a tool,
 * but the tool list also includes AGENT itself (for cases like resuming a
 * task). If this file also *called* AgentEngine directly, the two files
 * would depend on each other's concrete class at compile time — and since
 * AgentEngine doesn't exist yet at this point in the build, that would
 * break the green-build-after-every-file rule. Keeping this file as a pure
 * decision-maker (it returns a [ToolSelection], never executes one) avoids
 * the cycle entirely: AgentEngine depends on this, this depends on nothing
 * agent-execution-related.
 */
object ToolOrchestratorV2 {

    /**
     * @param intent the resolved IntentDetector.Intent (legacy or AGENT_TASK)
     * @param agentTaskParams non-null only when intent == AGENT_TASK
     * @param context current situational context (battery/network/etc)
     * @param agentMemoryDao used to check which tool worked for similar past tasks
     * @param decisionConfidence 0-100 confidence already computed upstream
     */
    suspend fun selectTool(
        intent: IntentDetector.Intent,
        agentTaskParams: IntentDetector.AgentTaskParams?,
        context: CurrentContext,
        agentMemoryDao: AgentMemoryDao,
        decisionConfidence: Int
    ): ToolSelection {

        // 1. Base selection from intent / AgentTaskParams.
        val baseTool = if (intent == IntentDetector.Intent.AGENT_TASK && agentTaskParams != null) {
            toolForAgentAction(agentTaskParams.actionType)
        } else {
            legacyToolFor(intent)
        }

        // 2. Check AgentMemory — has something similar succeeded before?
        val keyword = agentTaskParams?.rawGoal?.split(" ")?.firstOrNull { it.length > 3 }
        val pastSuccess = keyword?.let { agentMemoryDao.getLastSuccessfulFor(it) }

        val reasonBuilder = StringBuilder("Chose ${baseTool.name} based on ${if (agentTaskParams != null) "agent task type ${agentTaskParams.actionType}" else "intent $intent"}.")
        if (pastSuccess != null) {
            reasonBuilder.append(" A similar task succeeded before using: ${pastSuccess.toolsUsed}.")
        }

        // 3. Context-aware caveats — doesn't change the selection, just flags risk.
        if ((baseTool == ToolType.RESEARCH || baseTool == ToolType.BROWSER) && !context.networkAvailable) {
            reasonBuilder.append(" Warning: no network detected — this may fail.")
        }
        if (baseTool == ToolType.ACTION && context.batteryLevel in 1..10 && !context.isCharging) {
            reasonBuilder.append(" Note: battery is low (${context.batteryLevel}%).")
        }

        // 4. Confidence: start from what DecisionEngine already computed,
        // nudge down slightly if we have no prior memory to lean on.
        val confidence = if (pastSuccess != null) {
            (decisionConfidence + 5).coerceAtMost(100)
        } else {
            decisionConfidence
        }

        return ToolSelection(tool = baseTool, reason = reasonBuilder.toString(), confidence = confidence)
    }

    /** Maps a Phase 15 AgentActionType to the tool that actually handles it. */
    private fun toolForAgentAction(actionType: IntentDetector.AgentActionType): ToolType = when (actionType) {
        IntentDetector.AgentActionType.SYSTEM_CONTROL -> ToolType.ACTION
        IntentDetector.AgentActionType.SCREEN_READ -> ToolType.SCREEN
        IntentDetector.AgentActionType.RESEARCH -> ToolType.RESEARCH
        IntentDetector.AgentActionType.MULTI_STEP -> ToolType.PLANNER
        IntentDetector.AgentActionType.RESUME -> ToolType.AGENT
    }

    /** Maps every existing (pre-Phase-15) intent to the tool that already handles it. */
    private fun legacyToolFor(intent: IntentDetector.Intent): ToolType = when (intent) {
        IntentDetector.Intent.OPEN_APP,
        IntentDetector.Intent.CALL_CONTACT,
        IntentDetector.Intent.SEND_MESSAGE -> ToolType.ACTION

        IntentDetector.Intent.PLAY_MUSIC,
        IntentDetector.Intent.PAUSE_MUSIC,
        IntentDetector.Intent.NEXT_SONG,
        IntentDetector.Intent.PREV_SONG,
        IntentDetector.Intent.STOP_MUSIC,
        IntentDetector.Intent.SHOW_MUSIC -> ToolType.MUSIC

        IntentDetector.Intent.SET_REMINDER,
        IntentDetector.Intent.SHOW_REMINDER -> ToolType.REMINDER

        IntentDetector.Intent.CREATE_NOTE,
        IntentDetector.Intent.SHOW_NOTES,
        IntentDetector.Intent.SHOW_TODO -> ToolType.NOTES

        IntentDetector.Intent.SHOW_CALCULATOR -> ToolType.CALCULATOR
        IntentDetector.Intent.SHOW_DRAW -> ToolType.DRAWING
        IntentDetector.Intent.SEARCH_WEB -> ToolType.BROWSER
        IntentDetector.Intent.LEARN_QA -> ToolType.KNOWLEDGE
        IntentDetector.Intent.USER_INFO -> ToolType.MEMORY

        IntentDetector.Intent.TELL_TIME,
        IntentDetector.Intent.TELL_DATE,
        IntentDetector.Intent.TELL_BATTERY,
        IntentDetector.Intent.TELL_JOKE,
        IntentDetector.Intent.FLIP_COIN,
        IntentDetector.Intent.ROLL_DICE,
        IntentDetector.Intent.GREET,
        IntentDetector.Intent.HOW_ARE_YOU,
        IntentDetector.Intent.THANK,
        IntentDetector.Intent.WHO_ARE_YOU -> ToolType.VOICE

        IntentDetector.Intent.SHOW_TRANSLATOR,
        IntentDetector.Intent.SHOW_SETTINGS,
        IntentDetector.Intent.SHOW_UNANSWERED,
        IntentDetector.Intent.SHOW_VOICE_COMMANDS,
        IntentDetector.Intent.SHOW_DATA_MANAGEMENT,
        IntentDetector.Intent.CLEAR_CHAT -> ToolType.SETTINGS

        IntentDetector.Intent.AGENT_TASK -> ToolType.AGENT  // shouldn't reach here — selectTool() branches before this
        IntentDetector.Intent.UNKNOWN -> ToolType.VOICE      // just respond conversationally
    }
  }
                                          
