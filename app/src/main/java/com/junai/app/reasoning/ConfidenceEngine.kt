package com.junai.app.reasoning

/**
 * ConfidenceEngine — Formalizes the confidence concept that already exists
 * scattered across the app into one normalized scale + consistent
 * low-confidence disclaimer.
 *
 * Thresholds (0.70 / 0.90) deliberately match the existing knowledge-base
 * cutoffs already used in ChatIntentHandler's UNKNOWN branch (confidence
 * >= 90 -> direct answer, >= 70 -> "I think you mean", else -> failure) so
 * this formalizes what already existed instead of introducing a second,
 * inconsistent scale.
 */
object ConfidenceEngine {

    const val LOW_CONFIDENCE_THRESHOLD = 0.70f
    const val HIGH_CONFIDENCE_THRESHOLD = 0.90f

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
