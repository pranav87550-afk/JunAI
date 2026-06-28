package com.junai.app.learning

/**
 * RepeatedFailureDetector — Pure pattern detection, no DB access.
 * Given a list of failure snapshots, finds the question that has failed
 * the most times (if it crosses REPEAT_THRESHOLD) — the literal
 * "learn from repeated requests" signal from the project spec.
 */
object RepeatedFailureDetector {

    const val REPEAT_THRESHOLD = 3

    data class FailureSnapshot(val question: String, val timestamp: Long)
    data class RepeatedFailure(val question: String, val count: Int)

    /** Returns the most-repeated unanswered question, if it crossed the threshold. */
    fun findMostRepeated(failures: List<FailureSnapshot>): RepeatedFailure? {
        if (failures.isEmpty()) return null

        // Group by normalized text, but remember one original-casing version to display.
        val normalized = failures.map { it.question.lowercase().trim() to it.question }
        val counts = normalized.groupingBy { it.first }.eachCount()
        val top = counts.entries.filter { it.value >= REPEAT_THRESHOLD }.maxByOrNull { it.value } ?: return null
        val displayQuestion = normalized.first { it.first == top.key }.second

        return RepeatedFailure(displayQuestion, top.value)
    }
}
