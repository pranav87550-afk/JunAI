package com.junai.app.reasoning

/**
 * ReasoningRule — One IF...THEN rule.
 *
 * condition is evaluated via ReasoningCondition.evaluate(context).
 * If true, the rule "fires" and recommendationText is the suggestion
 * Jun shows to the user (e.g. "Battery low — turn on Battery Saver?").
 *
 * priority: when multiple rules fire at once, higher priority wins
 * (only the top rule's recommendation is shown, to avoid spamming
 * the user with 3 suggestions at once).
 */
data class ReasoningRule(
    val id: String,
    val condition: ReasoningCondition,
    val recommendationText: String,
    val priority: Int = 0
)
