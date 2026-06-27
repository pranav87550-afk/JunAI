package com.junai.app.memory

object SemanticQueryResolver {

    data class QueryIntent(
        val category: String?,
        val predicates: List<String>
    )

    private val categoryQuestionPatterns = listOf(
        Triple(listOf("what language", "which language"), "LANGUAGE", listOf("LIKES", "PREFERS", "USES")),
        Triple(listOf("what food", "which food"), "FOOD", listOf("LIKES", "PREFERS")),
        Triple(listOf("what color", "which colour", "what colour", "which color"), "COLOR", listOf("LIKES", "PREFERS")),
        Triple(listOf("what hobby", "what hobbies", "which hobby"), "HOBBY", listOf("LIKES", "HAS")),
        Triple(listOf("what genre", "which genre"), "MOVIE_GENRE", listOf("LIKES", "PREFERS"))
    )

    // FIXED — added "do"-less variants ("what i like" not just "what do i like")
    private val genericQuestionPatterns = listOf(
        listOf("what am i working on", "what's my project", "what project am i") to "WORKS_ON",
        listOf("what do i want", "what i want", "what do i need", "what i need") to "WANTS",
        listOf("what do i have", "what i have") to "HAS",
        listOf("what do i like", "what i like", "what do i enjoy", "what i enjoy", "what do i prefer", "what i prefer") to "LIKES",
        listOf("what do i hate", "what i hate", "what do i dislike", "what i dislike") to "DISLIKES"
    )

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
