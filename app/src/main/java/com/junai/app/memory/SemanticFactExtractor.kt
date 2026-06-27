package com.junai.app.memory

/**
 * SemanticFactExtractor — Turns a statement like "I like Python" into a
 * structured fact (predicate=LIKES, objectValue=Python, category=LANGUAGE),
 * without needing an embedding model or LLM.
 *
 * Pattern-based, not true NLU — covers the common explicit-statement
 * patterns ("I like/love/prefer/hate/use/want/have/am X") the spec calls out.
 */
object SemanticFactExtractor {

    data class ExtractedFact(
        val predicate: String,
        val objectValue: String,
        val category: String,
        val confidence: Float
    )

    // Order matters — more specific phrases checked first
    private val predicatePatterns = listOf(
        "i don't like" to "DISLIKES",
        "i do not like" to "DISLIKES",
        "i dislike" to "DISLIKES",
        "i hate" to "DISLIKES",
        "i prefer" to "PREFERS",
        "i love" to "LIKES",
        "i like" to "LIKES",
        "i enjoy" to "LIKES",
        "i work with" to "USES",
        "i use" to "USES",
        "i'm working on" to "WORKS_ON",
        "i am working on" to "WORKS_ON",
        "i work on" to "WORKS_ON",
        "i want to" to "WANTS",
        "i want" to "WANTS",
        "i need" to "WANTS",
        "i have" to "HAS",
        "i'm" to "IS",
        "i am" to "IS",
        "mujhe pasand hai" to "LIKES",
        "mujhe nahi pasand" to "DISLIKES"
    )

    private val categoryKeywords = mapOf(
        "LANGUAGE" to listOf("python", "kotlin", "java", "javascript", "swift", "c++", "rust", "go", "php", "ruby", "typescript", "dart"),
        "FOOD" to listOf("pizza", "biryani", "pasta", "sushi", "burger", "chai", "coffee", "tea", "paneer", "dosa"),
        "COLOR" to listOf("red", "blue", "green", "black", "white", "yellow", "purple", "orange", "pink"),
        "HOBBY" to listOf("coding", "reading", "gaming", "music", "cricket", "football", "painting", "traveling", "photography"),
        "MOVIE_GENRE" to listOf("action", "comedy", "horror", "thriller", "drama", "romance", "sci-fi", "animation")
    )

    /** Returns null if no statement pattern matched (most chat turns won't match). */
    fun extract(text: String): ExtractedFact? {
        val lower = text.lowercase().trim()

        val match = predicatePatterns.firstOrNull { (pattern, _) -> lower.contains(pattern) }
            ?: return null
        val (pattern, predicate) = match

        val objectValue = lower.substringAfter(pattern).trim()
            .removePrefix("a ").removePrefix("an ").removePrefix("the ")
            .trim(' ', '.', '!', '?')

        if (objectValue.isBlank() || objectValue.length > 60) return null

        val category = inferCategory(objectValue)
        val confidence = if (category != "GENERAL") 0.85f else 0.6f

        return ExtractedFact(predicate, objectValue, category, confidence)
    }

    private fun inferCategory(objectValue: String): String {
        for ((category, keywords) in categoryKeywords) {
            if (keywords.any { objectValue.contains(it) }) return category
        }
        return "GENERAL"
    }
}
