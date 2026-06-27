package com.junai.app.memory

/**
 * SemanticQueryResolver — Parses a question like "what language do I enjoy?"
 * into a (category, predicate) lookup intent, which SemanticMemoryRepository
 * (next file) resolves against stored SemanticFactEntity rows.
 *
 * Pure text parsing only — no DB access here, matching the pattern used
 * by SemanticFactExtractor.
 */
object SemanticQueryResolver {

    data class QueryIntent(
        val category: String?,        // null = search across all categories
        val predicates: List<String>   // ordered by priority, first match wins
    )

    // Checked first — category-bound questions ("what language do I like?")
    private val categoryQuestionPatterns = listOf(
        Triple(listOf("what language", "which language"), "LANGUAGE", listOf("LIKES", "PREFERS", "USES")),
        Triple(listOf("what food", "which food"), "FOOD", listOf("LIKES", "PREFERS")),
        Triple(listOf("what color", "which colour", "what colour", "which color"), "COLOR", listOf("LIKES", "PREFERS")),
        Triple(listOf("what hobby", "what hobbies", "which hobby"), "HOBBY", listOf("LIKES", "HAS")),
        Triple(listOf("what genre", "which genre"), "MOVIE_GENRE", listOf("LIKES", "PREFERS"))
    )

    // Checked second — generic questions with no category hint ("what do I like?")
    private val genericQuestionPatterns = listOf(
        listOf("what am i working on", "what's my project", "what project am i") to "WORKS_ON",
        listOf("what do i want", "what do i need") to "WANTS",
        listOf("what do i have") to "HAS",
        listOf("what do i like", "what do i enjoy", "what do i prefer") to "LIKES",
        listOf("what do i hate", "what do i dislike") to "DISLIKES"
    )

    /** Returns null if the text isn't a recognizable semantic question. */
    fun resolve(text: String): QueryIntent? {
        val lower = text.lowercase().trim()
        if (!lower.contains("?") && !isQuestionWord(lower)) return null

        for ((phrases, category, predicates) in categoryQuestionPatterns) {
            if (phrases.any { lower.contains(it) }) return QueryIntent(category, predicates)
        }

        for ((phrases, predicate) in genericQuestionPatterns) {
            if (phrases.any { lower.contains(it) }) return QueryIntent(null, listOf(predicate))
        }

        return null
    }

    private fun isQuestionWord(text: String): Boolean {
        return text.startsWith("what") || text.startsWith("which") ||
               text.startsWith("kya") || text.startsWith("konsa") || text.startsWith("kaunsa")
    }
}
