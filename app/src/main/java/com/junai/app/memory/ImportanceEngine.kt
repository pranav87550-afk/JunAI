package com.junai.app.memory

/**
 * ImportanceEngine — Decides how much a piece of information matters.
 *
 * Pure scoring logic, no DB access (testable, reusable). MemoryRepository
 * feeds it text + simple counters (repetition/frequency come from queries);
 * everything else is detected from the text itself.
 *
 * Weighted signals (per project spec):
 * - repetition         : same/similar thing already remembered
 * - emotional value    : emotionally charged language
 * - explicit preference: "I like / prefer / love / hate..."
 * - goals / projects   : "I want to / working on / my goal..."
 * - reminders          : tied to a reminder/task phrase
 * - corrections        : user correcting Jun (flag passed in by caller)
 * - frequency          : how often this category/topic comes up overall
 */
object ImportanceEngine {

    data class ScoreBreakdown(
        val repetition: Float,
        val emotional: Float,
        val explicitPreference: Float,
        val goalOrProject: Float,
        val reminder: Float,
        val correction: Float,
        val frequency: Float,
        val total: Float
    )

    // Weights sum to 1.0
    private const val W_REPETITION = 0.15f
    private const val W_EMOTIONAL   = 0.15f
    private const val W_PREFERENCE  = 0.20f
    private const val W_GOAL        = 0.20f
    private const val W_REMINDER    = 0.10f
    private const val W_CORRECTION  = 0.15f
    private const val W_FREQUENCY   = 0.05f

    private val preferencePatterns = listOf(
        "i like", "i love", "i prefer", "i hate", "i don't like", "i dislike",
        "mujhe pasand", "mujhe nahi pasand", "i enjoy", "i always", "i never"
    )

    private val goalPatterns = listOf(
        "i want to", "i'm working on", "i am working on", "my goal", "i plan to",
        "i'm trying to", "i need to", "working on a project",
        "main bana raha", "main kar raha", "mera goal"
    )

    private val emotionalPositive = listOf(
        "happy", "excited", "great", "love it", "amazing", "khush", "badhiya"
    )
    private val emotionalNegative = listOf(
        "sad", "stressed", "frustrated", "angry", "upset", "tension", "pareshan", "dukhi"
    )

    private val reminderPatterns = listOf(
        "remind me", "reminder", "don't forget", "yaad rakhna", "yaad dilana"
    )

    /**
     * Computes importance score for a piece of text.
     *
     * @param text raw user message / memory summary
     * @param isCorrection true if this memory comes from user correcting Jun
     * @param repetitionCount how many similar memories already exist (from repo)
     * @param topicFrequency how often this category has come up (from repo)
     */
    fun score(
        text: String,
        isCorrection: Boolean = false,
        repetitionCount: Int = 0,
        topicFrequency: Int = 0
    ): ScoreBreakdown {
        val lower = text.lowercase()

        val repetitionScore = repetitionCount.coerceAtMost(5) / 5f
        val emotionalScore = when {
            emotionalNegative.any { lower.contains(it) } -> 0.9f
            emotionalPositive.any { lower.contains(it) } -> 0.7f
            else -> 0f
        }
        val preferenceScore = if (preferencePatterns.any { lower.contains(it) }) 1f else 0f
        val goalScore = if (goalPatterns.any { lower.contains(it) }) 1f else 0f
        val reminderScore = if (reminderPatterns.any { lower.contains(it) }) 1f else 0f
        val correctionScore = if (isCorrection) 1f else 0f
        val frequencyScore = topicFrequency.coerceAtMost(10) / 10f

        val total = (repetitionScore * W_REPETITION) +
                (emotionalScore * W_EMOTIONAL) +
                (preferenceScore * W_PREFERENCE) +
                (goalScore * W_GOAL) +
                (reminderScore * W_REMINDER) +
                (correctionScore * W_CORRECTION) +
                (frequencyScore * W_FREQUENCY)

        return ScoreBreakdown(
            repetition = repetitionScore,
            emotional = emotionalScore,
            explicitPreference = preferenceScore,
            goalOrProject = goalScore,
            reminder = reminderScore,
            correction = correctionScore,
            frequency = frequencyScore,
            total = total.coerceIn(0f, 1f)
        )
    }
}
