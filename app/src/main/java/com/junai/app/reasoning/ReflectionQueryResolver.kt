package com.junai.app.reasoning

/**
 * ReflectionQueryResolver — Detects if the user is asking Jun to show its
 * self-reflection ("what did you learn today?", "show reflection", etc).
 * Pure text matching, same pattern as SemanticQueryResolver/GraphQueryResolver.
 */
object ReflectionQueryResolver {

    private val patterns = listOf(
        "what did you learn", "what did you learn today", "show reflection",
        "daily reflection", "self reflection", "where did you fail",
        "what should you improve", "show your progress",
        "aaj kya seekha", "reflection dikhao", "kya improve karna hai"
    )

    fun isReflectionQuery(text: String): Boolean {
        val lower = text.lowercase().trim()
        return patterns.any { lower.contains(it) }
    }
}
