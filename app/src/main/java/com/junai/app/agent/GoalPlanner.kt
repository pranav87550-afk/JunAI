package com.junai.app.agent

import com.junai.app.IntentDetector

enum class StepType { SEARCH, OPEN, READ, TAP, TYPE, COMPARE, RESPOND, SYSTEM_ACTION }
enum class StepStatus { PENDING, RUNNING, DONE, FAILED }

data class AgentStep(
    val stepNumber: Int,
    val type: StepType,
    val description: String,
    val target: String,
    val fallback: String?,
    val status: StepStatus = StepStatus.PENDING
)

/**
 * GoalPlanner — turns an IntentDetector.AgentTaskParams into an ordered list
 * of AgentStep, each with a fallback. Pure function, no DB access, no side
 * effects — AgentEngine (last file) is responsible for persisting the
 * returned plan into MultiStepTaskManager so it can be resumed if interrupted.
 */
object GoalPlanner {

    private val connectorWords = listOf("and then", "and", "then", "aur", "phir", "uske baad", "ke baad")

    fun createPlan(params: IntentDetector.AgentTaskParams): List<AgentStep> {
        return when (params.actionType) {
            IntentDetector.AgentActionType.RESEARCH -> researchPlan(params)
            IntentDetector.AgentActionType.SYSTEM_CONTROL -> systemControlPlan(params)
            IntentDetector.AgentActionType.SCREEN_READ -> screenReadPlan(params)
            IntentDetector.AgentActionType.MULTI_STEP -> multiStepPlan(params)
            IntentDetector.AgentActionType.RESUME ->
                // Nothing to plan here — AgentEngine should fetch the existing
                // incomplete plan from MultiStepTaskManager instead of calling this.
                emptyList()
        }
    }

    // ── RESEARCH ──────────────────────────────────────────────
    // Mirrors the canonical 7-step example from the spec exactly.
    private fun researchPlan(params: IntentDetector.AgentTaskParams): List<AgentStep> {
        val query = params.searchQuery ?: params.rawGoal
        return listOf(
            AgentStep(1, StepType.SEARCH, "Search the web for \"$query\"", query,
                "If search returns nothing, retry with a simplified query"),
            AgentStep(2, StepType.OPEN, "Open multiple results", query,
                "If a result won't open, skip it and try the next one"),
            AgentStep(3, StepType.READ, "Extract specifications / key details", query,
                "If a page has no readable content, skip and note it"),
            AgentStep(4, StepType.COMPARE, "Compare results against each other", query,
                "If fewer than 2 results loaded, present what's available with a caveat"),
            AgentStep(5, StepType.COMPARE, "Remove duplicates and contradictions", query,
                "If sources conflict and can't be resolved, mention the disagreement"),
            AgentStep(6, StepType.COMPARE, "Generate final recommendation", query,
                "If no clear winner, present top 2-3 options instead of one"),
            AgentStep(7, StepType.RESPOND, "Present result with sources", query,
                "If sources are unavailable, present the answer without source links")
        )
    }

    // ── SYSTEM_CONTROL ────────────────────────────────────────
    private fun systemControlPlan(params: IntentDetector.AgentTaskParams): List<AgentStep> {
        val setting = params.targetSetting ?: params.rawGoal
        return listOf(
            AgentStep(1, StepType.SYSTEM_ACTION, "Change system setting: $setting", setting,
                "If the setting can't be changed (e.g. missing permission), tell the user exactly what's blocking it"),
            AgentStep(2, StepType.RESPOND, "Confirm the change to the user", setting, null)
        )
    }

    // ── SCREEN_READ ───────────────────────────────────────────
    private fun screenReadPlan(params: IntentDetector.AgentTaskParams): List<AgentStep> {
        val app = params.targetApp ?: "the relevant app"
        val target = params.targetContact ?: "the screen"
        val steps = mutableListOf<AgentStep>()
        var n = 1
        if (params.targetApp != null) {
            steps.add(AgentStep(n++, StepType.OPEN, "Open $app", app,
                "If $app isn't installed or won't open, tell the user"))
        }
        steps.add(AgentStep(n++, StepType.READ, "Read content for $target", target,
            "If nothing relevant is visible on screen, say so instead of guessing"))
        steps.add(AgentStep(n, StepType.RESPOND, "Tell the user what was found", target,
            "If the content includes anything sensitive, summarize without repeating exact private details"))
        return steps
    }

    // ── MULTI_STEP ────────────────────────────────────────────
    // Splits the raw goal text on connector words into clauses, then infers
    // one step type per clause from its dominant verb.
    private fun multiStepPlan(params: IntentDetector.AgentTaskParams): List<AgentStep> {
        val clauses = splitIntoClauses(params.rawGoal)
        val steps = mutableListOf<AgentStep>()
        var n = 1
        for (clause in clauses) {
            val (type, fallback) = inferStepType(clause)
            steps.add(AgentStep(n++, type, clause.trim(), clause.trim(), fallback))
        }
        if (steps.lastOrNull()?.type != StepType.RESPOND) {
            steps.add(AgentStep(n, StepType.RESPOND, "Report what was done", params.rawGoal,
                "If any earlier step failed, mention which one instead of claiming full success"))
        }
        return steps
    }

    private fun splitIntoClauses(text: String): List<String> {
        var working = text
        for (connector in connectorWords) {
            working = working.replace(Regex("(?i)\\b${Regex.escape(connector)}\\b"), "||")
        }
        return working.split("||").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun inferStepType(clause: String): Pair<StepType, String?> {
        val lower = clause.lowercase()
        return when {
            containsAny(lower, "open", "kholo", "kholna") ->
                StepType.OPEN to "If the app isn't installed or won't open, tell the user"
            containsAny(lower, "read", "padho", "check", "dekho") ->
                StepType.READ to "If nothing relevant is visible, say so instead of guessing"
            containsAny(lower, "search", "find", "dhundo", "khojo") ->
                StepType.SEARCH to "If search returns nothing, retry with a simplified query"
            containsAny(lower, "compare") ->
                StepType.COMPARE to "If there isn't enough data to compare, present what's available"
            containsAny(lower, "tell", "batao") ->
                StepType.RESPOND to "If the result isn't ready yet, say so rather than guessing"
            containsAny(lower, "call", "phone") ->
                StepType.SYSTEM_ACTION to "Calling always needs explicit confirmation first"
            containsAny(lower, "send", "bhejo") ->
                StepType.TYPE to "Message content is always shown before sending — never sent silently"
            containsAny(lower, "play", "chalao", "chala", "bajao", "lagao") ->
                StepType.TAP to "If playback can't start, tell the user instead of retrying silently forever"
            containsAny(lower, "turn", "set", "connect", "on", "off") ->
                StepType.SYSTEM_ACTION to "If the setting can't be changed, tell the user what's blocking it"
            else -> StepType.OPEN to "If this step's intent is unclear, ask the user to rephrase"
        }
    }

    private fun containsAny(text: String, vararg words: String): Boolean =
        words.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(text) }
}
