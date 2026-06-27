package com.junai.app.reasoning

/**
 * ConfidenceEngine — Formalizes the confidence concept that already exists
 * scattered across the app (KnowledgeRepository's 90/70 thresholds, etc.)
 * into one normalized scale + consistent low-confidence disclaimer.
 *
 * Different parts of the app use different raw scales (0-100 for the
 * original knowledge base, 0-1 for newer memory/semantic/graph features).
 * normalize() handles that so callers don't have to think about it.
 */
object ConfidenceEngine {

    const val LOW_CONFIDENCE_THRESHOLD = 0.5f
    const val HIGH_CONFIDENCE_THRESHOLD = 0.75f

    enum class ConfidenceLevel { HIGH, MEDIUM, LOW }

    /** @param scaleMax pass 100f for old-style 0-100 confidence, 1f for 0-1 scale (default). */
    fun normalize(value: Float, scaleMax: Float = 1f): Float = (value / scaleMax).coerceIn(0f, 1f)

    fun classify(normalizedConfidence: Float): ConfidenceLevel = when {
        normalizedConfidence >= HIGH_CONFIDENCE_THRESHOLD -> ConfidenceLevel.HIGH
        normalizedConfidence >= LOW_CONFIDENCE_THRESHOLD -> ConfidenceLevel.MEDIUM
        else -> ConfidenceLevel.LOW
    }

    /** Appends a soft disclaimer only when confidence is genuinely LOW. */
    fun applyToResponse(response: String, normalizedConfidence: Float): String {
        return if (classify(normalizedConfidence) == ConfidenceLevel.LOW) {
            "$response\n\n(I'm not fully sure about this \uD83E\uDD14)"
        } else {
            response
        }
    }
}
