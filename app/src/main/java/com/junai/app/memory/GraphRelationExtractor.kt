package com.junai.app.memory

/**
 * GraphRelationExtractor — Turns a sentence into a (subject, relation, object)
 * triple for the knowledge graph.
 *
 * Covers two shapes:
 *  - First-person: "I like Python" -> (USER, LIKES, Python)
 *  - Third-person: "Python is used for Jun AI" -> (Python, USED_FOR, Jun AI)
 *
 * Chaining these together (USER-LIKES->Python, Python-USED_FOR->Jun AI)
 * is what gives the graph its connected structure.
 */
object GraphRelationExtractor {

    data class ExtractedRelation(
        val subject: String,
        val subjectType: String,
        val relation: String,
        val objectValue: String,
        val objectType: String,
        val confidence: Float
    )

    // Third-person patterns checked first (more specific, has explicit subject)
    private val thirdPersonPatterns = listOf(
        " is used for " to "USED_FOR",
        " is part of " to "PART_OF",
        " is a type of " to "IS_A",
        " is an " to "IS_A",
        " is a " to "IS_A",
        " belongs to " to "PART_OF"
    )

    // First-person patterns — subject is always USER
    private val firstPersonPatterns = listOf(
        "i like" to "LIKES",
        "i love" to "LIKES",
        "i use" to "USES",
        "i work with" to "USES",
        "i'm working on" to "WORKS_ON",
        "i am working on" to "WORKS_ON",
        "i work on" to "WORKS_ON",
        "i have" to "HAS"
    )

    private val typeKeywords = mapOf(
        "LANGUAGE" to listOf("python", "kotlin", "java", "javascript", "swift", "c++", "rust", "go", "php", "ruby", "typescript", "dart"),
        "FOOD" to listOf("pizza", "biryani", "pasta", "sushi", "burger", "chai", "coffee", "tea", "paneer", "dosa"),
        "PROJECT" to listOf("jun ai", "junai", "app", "project"),
        "HOBBY" to listOf("coding", "reading", "gaming", "music", "cricket", "football", "painting")
    )

    fun extract(text: String): ExtractedRelation? {
        val lower = text.lowercase().trim()

        // 1. Try third-person "X is used for Y" style first
        for ((phrase, relation) in thirdPersonPatterns) {
            if (lower.contains(phrase)) {
                val parts = lower.split(phrase, limit = 2)
                if (parts.size == 2) {
                    val subject = clean(parts[0])
                    val objectValue = clean(parts[1])
                    if (subject.isNotBlank() && objectValue.isNotBlank() && subject.length <= 40 && objectValue.length <= 40) {
                        return ExtractedRelation(
                            subject = subject,
                            subjectType = inferType(subject),
                            relation = relation,
                            objectValue = objectValue,
                            objectType = inferType(objectValue),
                            confidence = 0.8f
                        )
                    }
                }
            }
        }

        // 2. Fall back to first-person "I like X" style
        for ((phrase, relation) in firstPersonPatterns) {
            if (lower.contains(phrase)) {
                val objectValue = clean(lower.substringAfter(phrase))
                if (objectValue.isNotBlank() && objectValue.length <= 40) {
                    return ExtractedRelation(
                        subject = "user",
                        subjectType = "USER",
                        relation = relation,
                        objectValue = objectValue,
                        objectType = inferType(objectValue),
                        confidence = 0.85f
                    )
                }
            }
        }

        return null
    }

    private fun clean(text: String): String {
        return text.trim()
            .removePrefix("a ").removePrefix("an ").removePrefix("the ")
            .trim(' ', '.', '!', '?')
    }

    private fun inferType(value: String): String {
        for ((type, keywords) in typeKeywords) {
            if (keywords.any { value.contains(it) }) return type
        }
        return "CONCEPT"
    }
}
