package com.junai.app.planning

/**
 * PlanQueryResolver — Pure text matching for plan-related commands,
 * same pattern as SemanticQueryResolver/GraphQueryResolver.
 */
object PlanQueryResolver {

    sealed class PlanQuery {
        data class CreatePlan(val goalText: String) : PlanQuery()
        object ShowPlans : PlanQuery()
        object NextStep : PlanQuery()
        object MarkStepDone : PlanQuery()
    }

    private val createPrefixes = listOf("plan:", "new plan:", "create plan:")
    private val showPatterns = listOf("show my plans", "what are my plans", "my active plans", "show plans")
    private val nextStepPatterns = listOf("what's my next step", "what is my next step", "next step", "what should i do next")
    private val markDonePatterns = listOf("mark step done", "step done", "done with step", "finished step", "step complete", "i finished that")

    fun resolve(text: String): PlanQuery? {
        val lower = text.lowercase().trim()

        for (prefix in createPrefixes) {
            if (lower.startsWith(prefix)) {
                val goal = text.substring(text.indexOf(":") + 1).trim()
                if (goal.isNotBlank()) return PlanQuery.CreatePlan(goal)
            }
        }

        if (showPatterns.any { lower.contains(it) }) return PlanQuery.ShowPlans
        if (nextStepPatterns.any { lower.contains(it) }) return PlanQuery.NextStep
        if (markDonePatterns.any { lower.contains(it) }) return PlanQuery.MarkStepDone

        return null
    }
}
