package com.junai.app.planning

import android.content.Context
import com.junai.app.AppDatabase

/**
 * PlanRepository — Android-aware bridge between GoalDecomposer (pure logic)
 * and PlanDao (storage). "Next step" / "mark done" operate on the MOST
 * RECENTLY UPDATED active plan by default — simplest sensible behavior
 * for a single-user assistant without a "which plan?" disambiguation UI.
 */
class PlanRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).planDao()

    suspend fun createPlan(goalText: String): String {
        val steps = GoalDecomposer.decompose(goalText)
        val planId = dao.insertPlan(PlanEntity(goalText = goalText)).toInt()
        val stepEntities = steps.mapIndexed { index, stepText ->
            PlanStepEntity(planId = planId, stepText = stepText, stepOrder = index)
        }
        dao.insertSteps(stepEntities)

        return if (steps.size > 1) {
            "Plan ban gaya \u2705 (${steps.size} steps):\n" +
                    steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
        } else {
            "Plan ban gaya \u2705 — \"$goalText\" ko single step ke roop mein save kiya, " +
                    "kyunki bina structured steps ke main khud se goal todh nahi sakta. " +
                    "Steps comma se separate karke batao to unhe alag track karunga."
        }
    }

    suspend fun getActivePlansSummary(): String {
        val plans = dao.getActivePlans()
        if (plans.isEmpty()) return "Abhi koi active plan nahi hai."

        val lines = plans.map { plan ->
            val remaining = dao.getIncompleteStepCount(plan.id)
            val total = dao.getStepsForPlan(plan.id).size
            "\u2022 ${plan.goalText} (${total - remaining}/$total steps done)"
        }
        return "Tumhare active plans:\n" + lines.joinToString("\n")
    }

    suspend fun getNextStepText(): String {
        val plan = getMostRecentActivePlan() ?: return "Abhi koi active plan nahi hai."
        val nextStep = dao.getNextIncompleteStep(plan.id)
            ?: run {
                dao.updatePlanStatus(plan.id, "COMPLETED")
                return "\"${plan.goalText}\" ke saare steps complete ho gaye! \uD83C\uDF89"
            }
        return "Next step (\"${plan.goalText}\"): ${nextStep.stepText}"
    }

    suspend fun markCurrentStepDone(): String {
        val plan = getMostRecentActivePlan() ?: return "Abhi koi active plan nahi hai jisme step mark karna ho."
        val step = dao.getNextIncompleteStep(plan.id)
            ?: return "\"${plan.goalText}\" already complete hai \uD83C\uDF89"

        dao.updateStep(step.copy(isCompleted = true, completedAt = System.currentTimeMillis()))
        dao.updatePlan(plan.copy(updatedAt = System.currentTimeMillis()))

        val remaining = dao.getIncompleteStepCount(plan.id)
        return if (remaining == 0) {
            dao.updatePlanStatus(plan.id, "COMPLETED")
            "\"${step.stepText}\" done \u2705 — aur \"${plan.goalText}\" ka pura plan complete ho gaya! \uD83C\uDF89"
        } else {
            "\"${step.stepText}\" done \u2705 — $remaining step(s) baaki hain."
        }
    }

    private suspend fun getMostRecentActivePlan(): PlanEntity? {
        return dao.getActivePlans().maxByOrNull { it.updatedAt }
    }
}
