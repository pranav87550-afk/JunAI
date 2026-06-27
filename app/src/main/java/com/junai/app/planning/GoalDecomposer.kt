package com.junai.app.planning

/**
 * GoalDecomposer — Pure logic, no DB/Android dependency.
 *
 * Honest design: without an LLM, real semantic decomposition of a vague
 * one-line goal ("learn machine learning") isn't possible — anything that
 * tried would be fake/hallucinated steps. So this only splits goals that
 * are ALREADY structured by the user (numbered, comma/semicolon-separated,
 * or "then"-chained). A genuinely vague goal becomes a single-step plan
 * instead of inventing a fake breakdown.
 */
object GoalDecomposer {

    private val numberedLinePattern = Regex("""^\s*\d+[.).:-]\s*(.+)$""")

    fun decompose(goalText: String): List<String> {
        val cleaned = goalText.trim()

        // 1. Multi-line numbered list ("1. buy milk\n2. clean room")
        val lines = cleaned.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.size > 1) {
            val numbered = lines.mapNotNull { numberedLinePattern.find(it)?.groupValues?.get(1)?.trim() }
            if (numbered.size == lines.size) return numbered
        }

        // 2. Single line, comma/semicolon separated ("buy milk, clean room, call mom")
        if (cleaned.contains(",") || cleaned.contains(";")) {
            val parts = cleaned.split(",", ";").map { it.trim() }.filter { it.isNotBlank() }
            if (parts.size > 1) return parts
        }

        // 3. "then"-chained ("buy milk then clean room then call mom")
        if (cleaned.contains(" then ")) {
            val parts = cleaned.split(" then ").map { it.trim() }.filter { it.isNotBlank() }
            if (parts.size > 1) return parts
        }

        // 4. Fallback — honest single-step plan, no fake decomposition
        return listOf(cleaned)
    }
}
