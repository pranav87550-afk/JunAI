package com.junai.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * FeedbackLearner — Makes Jun smarter from thumbs up / thumbs down.
 *
 * Thumbs up  → confidence goes UP   → answer shown more often
 * Thumbs down → confidence goes DOWN → answer shown less, logged for retraining
 *
 * Also detects when an answer is consistently wrong and flags it
 * in the Learning Center for the user to fix.
 *
 * Wire into ChatAdapter's onThumbsUp / onThumbsDown callbacks.
 */
class FeedbackLearner(private val context: Context) {

    private val repo = LearningRepository(context)
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        // Confidence thresholds
        private const val BOOST_AMOUNT   = 0.08f   // +8% per thumbs up
        private const val PENALTY_AMOUNT = 0.15f   // -15% per thumbs down
        private const val MIN_CONFIDENCE = 0.10f   // Never go below 10%
        private const val MAX_CONFIDENCE = 1.00f   // Cap at 100%
        private const val FLAG_THRESHOLD = 0.40f   // Flag for review if below 40%
    }

    // ── Public API ────────────────────────────────────────────────

    /**
     * Call when user thumbs UP a response.
     * Boosts the confidence of the matched knowledge item.
     *
     * @param junAnswer  The response Jun gave
     * @param userQuestion  What the user originally asked
     */
    fun onThumbsUp(junAnswer: String, userQuestion: String) {
        scope.launch {
            val item = findKnowledgeByAnswer(junAnswer)
            if (item != null) {
                val newConfidence = (item.confidence + BOOST_AMOUNT).coerceAtMost(MAX_CONFIDENCE)
                repo.updateKnowledgeConfidence(item.id, newConfidence, incrementCorrect = true)
            }
            // Also record positive signal in user prefs
            UserPreferenceManager(context).recordIntent("FEEDBACK_POSITIVE")
        }
    }

    /**
     * Call when user thumbs DOWN a response.
     * Lowers confidence and logs it for retraining if needed.
     *
     * @param junAnswer  The response Jun gave
     * @param userQuestion  What the user originally asked
     */
    fun onThumbsDown(junAnswer: String, userQuestion: String) {
        scope.launch {
            val item = findKnowledgeByAnswer(junAnswer)
            if (item != null) {
                val newConfidence = (item.confidence - PENALTY_AMOUNT).coerceAtLeast(MIN_CONFIDENCE)
                repo.updateKnowledgeConfidence(item.id, newConfidence, incrementCorrect = false)

                // If confidence dropped too low → flag for retraining
                if (newConfidence <= FLAG_THRESHOLD) {
                    repo.logFailure(
                        question      = userQuestion,
                        detectedIntent = "FEEDBACK_DOWNVOTE",
                        confidence    = newConfidence,
                        failureReason = "LOW_CONFIDENCE_AFTER_FEEDBACK"
                    )
                }
            } else {
                // No knowledge item found — just log the failure
                repo.logFailure(
                    question      = userQuestion,
                    detectedIntent = "FEEDBACK_DOWNVOTE",
                    confidence    = 0f,
                    failureReason = "ANSWER_NOT_IN_KNOWLEDGE_BASE"
                )
            }
            UserPreferenceManager(context).recordIntent("FEEDBACK_NEGATIVE")
        }
    }

    /**
     * Returns a summary of how well Jun is doing based on feedback.
     * Show this in LearningCenter or Settings.
     */
    suspend fun getHealthReport(): HealthReport {
        val allKnowledge = repo.getAllKnowledge()
        if (allKnowledge.isEmpty()) return HealthReport(0, 0, 0, 0f, emptyList())

        val total     = allKnowledge.size
        val healthy   = allKnowledge.count { it.confidence >= 0.75f }
        val needsWork = allKnowledge.count { it.confidence < FLAG_THRESHOLD }
        val avgConf   = allKnowledge.map { it.confidence }.average().toFloat()
        val flagged   = allKnowledge
            .filter { it.confidence < FLAG_THRESHOLD }
            .sortedBy { it.confidence }
            .take(5)
            .map { it.question }

        return HealthReport(total, healthy, needsWork, avgConf, flagged)
    }

    // ── Private Helpers ───────────────────────────────────────────

    private suspend fun findKnowledgeByAnswer(answer: String): KnowledgeItem? {
        return repo.getAllKnowledge().firstOrNull { item ->
            item.answer.trim().equals(answer.trim(), ignoreCase = true) ||
            answer.contains(item.answer.take(40), ignoreCase = true)
        }
    }

    // ── Data Classes ──────────────────────────────────────────────

    data class HealthReport(
        val totalKnowledge: Int,
        val healthyItems: Int,
        val needsWorkItems: Int,
        val averageConfidence: Float,
        val flaggedQuestions: List<String>  // Questions to retrain
    ) {
        fun getHealthPercent(): Int = if (totalKnowledge == 0) 0
            else ((healthyItems * 100f) / totalKnowledge).toInt()

        fun getSummary(): String = when {
            totalKnowledge == 0 -> "Jun has no knowledge yet. Start teaching!"
            getHealthPercent() >= 80 -> "Jun is in great shape! 💪 (${getHealthPercent()}% healthy)"
            getHealthPercent() >= 50 -> "Jun is doing okay. Some answers need work. (${getHealthPercent()}% healthy)"
            else -> "Jun needs more training! Many answers are unreliable. (${getHealthPercent()}% healthy)"
        }
    }
}
