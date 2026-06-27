package com.junai.app.reasoning

/**
 * ReasoningCondition — Building block for IF/AND/OR/NOT rule logic.
 *
 * Conditions are evaluated against a ReasoningContext (the current
 * snapshot of facts: battery%, charging, hour, semantic facts, etc).
 *
 * Example: Battery < 15% AND NOT Charging
 *   Condition.And(
 *       Condition.LessThan("battery", 15f),
 *       Condition.Not(Condition.Equals("charging", "true"))
 *   )
 */
sealed class ReasoningCondition {
    data class Equals(val key: String, val value: String) : ReasoningCondition()
    data class LessThan(val key: String, val threshold: Float) : ReasoningCondition()
    data class GreaterThan(val key: String, val threshold: Float) : ReasoningCondition()
    data class HourBetween(val startHour: Int, val endHour: Int) : ReasoningCondition()
    data class And(val left: ReasoningCondition, val right: ReasoningCondition) : ReasoningCondition()
    data class Or(val left: ReasoningCondition, val right: ReasoningCondition) : ReasoningCondition()
    data class Not(val condition: ReasoningCondition) : ReasoningCondition()

    fun evaluate(ctx: ReasoningContext): Boolean = when (this) {
        is Equals -> ctx.getString(key)?.equals(value, ignoreCase = true) == true
        is LessThan -> (ctx.getNumber(key) ?: Float.MAX_VALUE) < threshold
        is GreaterThan -> (ctx.getNumber(key) ?: Float.MIN_VALUE) > threshold
        is HourBetween -> {
            val hour = ctx.getNumber("hour")?.toInt() ?: -1
            if (startHour <= endHour) hour in startHour..endHour
            else hour >= startHour || hour <= endHour  // wraps past midnight
        }
        is And -> left.evaluate(ctx) && right.evaluate(ctx)
        is Or -> left.evaluate(ctx) || right.evaluate(ctx)
        is Not -> !condition.evaluate(ctx)
    }
}
