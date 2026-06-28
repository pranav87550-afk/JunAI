package com.junai.app.learning

/**
 * LearningEngineV2 — Ties Phases 7/8/9 (Reflection, Confidence, Curiosity)
 * together into the continuous-learning behavior the spec actually asks
 * for: reinforcing knowledge that gets repeated, instead of either
 * duplicating it or leaving its confidence static forever.
 *
 * Used by SemanticMemoryRepository and KnowledgeGraphRepository so both
 * "new concepts" subsystems share one reinforcement curve — single source
 * of truth, same pattern as ConfidenceEngine/ImportanceEngine before it.
 */
object LearningEngineV2 {

    private const val REINFORCEMENT_STEP = 0.05f
    private const val MAX_REINFORCED_CONFIDENCE = 0.98f

    /** Diminishing-returns bump — confidence approaches but never quite hits 1.0. */
    fun reinforce(currentConfidence: Float): Float {
        return (currentConfidence + REINFORCEMENT_STEP).coerceAtMost(MAX_REINFORCED_CONFIDENCE)
    }
}
