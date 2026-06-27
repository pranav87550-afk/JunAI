package com.junai.app.reasoning

/**
 * CuriosityEngine — Pure logic for two things:
 *  1. Deciding if unanswered text is actually a question worth being
 *     curious about (vs random chit-chat Jun shouldn't interrogate).
 *  2. Generating a natural follow-up question template based on the
 *     shape of what was asked ("what is X" vs "who is X" vs generic).
 */
object CuriosityEngine {

    private val whatIsPatterns = listOf("what is", "what's", "what are", "kya hai", "kya hota hai")
    private val whoIsPatterns = listOf("who is", "who's", "kaun hai", "kaun hota hai")
    private val howToPatterns = listOf("how to", "how do i", "how can i", "kaise")

    private val questionStarters = listOf(
        "what", "who", "how", "why", "when", "where",
        "kya", "kaun", "kaise", "kab", "kahan"
    )

    /** Only ask a follow-up for things that actually look like real questions. */
    fun isQuestionLike(text: String): Boolean {
        val lower = text.lowercase().trim()
        if (lower.endsWith("?")) return true
        return questionStarters.any { lower.startsWith(it) }
    }

    fun generateFollowUp(failedQuestion: String): String {
        val lower = failedQuestion.lowercase().trim()
        return when {
            whatIsPatterns.any { lower.contains(it) } -> {
                val subject = extractSubject(lower, whatIsPatterns)
                "Mujhe iske baare mein pata nahi — tum bata sakte ho \"$subject\" kya hota hai? Main yaad rakh lunga! \uD83E\uDD14"
            }
            whoIsPatterns.any { lower.contains(it) } ->
                "Mujhe nahi pata ye kaun hai — tum bata do? Main seekh lunga! \uD83E\uDD14"
            howToPatterns.any { lower.contains(it) } ->
                "Mujhe iska tarika nahi pata — tum jaante ho? Bata do, yaad rakh lunga! \uD83E\uDD14"
            else ->
                "Mujhe iska jawab nahi pata. Tum bata sakte ho? Main seekh lunga! \uD83E\uDD14"
        }
    }

    private fun extractSubject(lower: String, patterns: List<String>): String {
        for (p in patterns) {
            if (lower.contains(p)) {
                return lower.substringAfter(p).trim().trim('?', '.', '!').ifBlank { "ye" }
            }
        }
        return "ye"
    }
}
