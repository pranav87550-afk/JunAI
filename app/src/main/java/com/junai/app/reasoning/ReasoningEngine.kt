package com.junai.app.reasoning

/**
 * ReasoningEngine — Evaluates a list of ReasoningRule against a
 * ReasoningContext and returns the single best recommendation, if any.
 *
 * Pure logic, no Android/DB dependency — RuleRepository (next file)
 * supplies the rules and builds the context.
 */
object ReasoningEngine {

    /**
     * Evaluates all rules, returns the highest-priority one that fires.
     * Returns null if no rule's condition is true for this context.
     */
    fun evaluate(rules: List<ReasoningRule>, context: ReasoningContext): ReasoningRule? {
        return rules
            .filter { it.condition.evaluate(context) }
            .maxByOrNull { it.priority }
    }

    /** Returns ALL rules that fire, sorted by priority — useful for a future "insights" screen. */
    fun evaluateAll(rules: List<ReasoningRule>, context: ReasoningContext): List<ReasoningRule> {
        return rules
            .filter { it.condition.evaluate(context) }
            .sortedByDescending { it.priority }
    }
}
