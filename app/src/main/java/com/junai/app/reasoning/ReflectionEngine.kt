package com.junai.app.reasoning

/**
 * ReflectionEngine — Pure statistical analysis, no DB/Android dependency.
 * ReflectionRepository gathers the raw numbers/snapshots; this just turns
 * them into a readable self-reflection report.
 *
 * Honest design note: there's no LLM here, so this isn't "Jun thinking
 * about its day" in a literal sense — it's a structured summary of real
 * counters (new facts, new connections, failures, repeating patterns).
 */
object ReflectionEngine {

    data class FailureSnapshot(val question: String, val reason: String)

    data class ReflectionInput(
        val newFactsLearned: Int,
        val newRelationsLearned: Int,
        val questionsAnsweredCorrectly: Int,
        val failures: List<FailureSnapshot>
    )

    data class ReflectionResult(
        val learnedSummary: String,
        val failureSummary: String,
        val patternsSummary: String,
        val improvementSuggestion: String
    )

    fun generate(input: ReflectionInput): ReflectionResult {
        return ReflectionResult(
            learnedSummary = buildLearnedSummary(input),
            failureSummary = buildFailureSummary(input.failures),
            patternsSummary = buildPatternsSummary(input.failures),
            improvementSuggestion = buildImprovementSuggestion(input.failures)
        )
    }

    private fun buildLearnedSummary(input: ReflectionInput): String {
        if (input.newFactsLearned == 0 && input.newRelationsLearned == 0 && input.questionsAnsweredCorrectly == 0) {
            return "Kuch naya specifically nahi seekha, existing knowledge se hi questions answer kiye."
        }
        return "${input.newFactsLearned} naye facts, ${input.newRelationsLearned} naye connections seekhe, " +
                "${input.questionsAnsweredCorrectly} questions confidently answer kiye."
    }

    private fun buildFailureSummary(failures: List<FailureSnapshot>): String {
        if (failures.isEmpty()) return "Koi naya failure nahi mila — sab handle ho gaya! \uD83C\uDF89"
        val byReason = failures.groupingBy { it.reason }.eachCount()
        val topReason = byReason.maxByOrNull { it.value }
        return "${failures.size} question(s) answer nahi kar paya. Sabse common reason: " +
                "${topReason?.key ?: "UNKNOWN"} (${topReason?.value ?: 0} baar)."
    }

    private fun buildPatternsSummary(failures: List<FailureSnapshot>): String {
        val repeated = failures.groupingBy { it.question.lowercase().trim() }.eachCount().filter { it.value >= 2 }
        if (repeated.isEmpty()) return "Koi repeating failure pattern nahi mila."
        val top = repeated.entries.sortedByDescending { it.value }.take(3)
        return "Repeating pattern: " + top.joinToString(", ") { "\"${it.key}\" (${it.value}x)" }
    }

    private fun buildImprovementSuggestion(failures: List<FailureSnapshot>): String {
        val repeatedCount = failures.groupingBy { it.question.lowercase().trim() }.eachCount().count { it.value >= 2 }
        return when {
            repeatedCount > 0 -> "$repeatedCount repeating unanswered question(s) hain — Learning Center mein train karna helpful hoga."
            failures.size > 5 -> "${failures.size} naye unanswered questions hain — Learning Center check karo."
            else -> "Sab thik chal raha hai, koi urgent improvement nahi chahiye abhi."
        }
    }
}
