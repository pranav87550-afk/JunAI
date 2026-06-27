package com.junai.app.reasoning

import com.junai.app.LearningRepository

/**
 * ConfidenceRepository — Bridges ConfidenceEngine with the existing
 * FailureLog table so "confidence affects learning" actually does
 * something: genuinely low-confidence answers get logged with reason
 * "LOW_CONFIDENCE" (which Phase 7's ReflectionEngine already groups by
 * failureReason automatically — no new DB table needed).
 *
 * Split into two methods on purpose:
 *  - applyConfidence() is pure/synchronous, safe to call while building
 *    the response text directly (no race condition with a DB write).
 *  - logIfLow() does the DB write — call it from a coroutine separately.
 */
class ConfidenceRepository(private val learningRepo: LearningRepository) {

    /** @param scaleMax 100f for old-style knowledge base confidence, 1f for newer 0-1 scale features. */
    fun applyConfidence(response: String, rawConfidence: Float, scaleMax: Float = 1f): String {
        val normalized = ConfidenceEngine.normalize(rawConfidence, scaleMax)
        return ConfidenceEngine.applyToResponse(response, normalized)
    }

    suspend fun logIfLow(question: String, intentName: String, rawConfidence: Float, scaleMax: Float = 1f) {
        val normalized = ConfidenceEngine.normalize(rawConfidence, scaleMax)
        if (ConfidenceEngine.classify(normalized) == ConfidenceEngine.ConfidenceLevel.LOW) {
            learningRepo.logFailure(
                question = question,
                detectedIntent = intentName,
                confidence = normalized,
                failureReason = "LOW_CONFIDENCE"
            )
        }
    }
}
