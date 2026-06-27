package com.junai.app.memory

/**
 * MemoryCompressor — Turns a batch of raw SHORT_TERM memories into one
 * compact summary, instead of storing every conversational turn forever.
 *
 * No LLM/embedding model is available in this app, so this is an
 * extractive compressor: it ranks entries by importance, drops near-
 * duplicate text, and joins the most representative points into a
 * single readable digest.
 */
object MemoryCompressor {

    private const val MAX_POINTS = 5

    fun compress(memories: List<MemoryEntity>): String {
        if (memories.isEmpty()) return ""

        val ranked = memories.sortedByDescending { it.importance }
        val distinctPoints = mutableListOf<String>()

        for (memory in ranked) {
            val cleaned = memory.summary.trim()
            if (cleaned.isEmpty()) continue
            val isDuplicate = distinctPoints.any { isSimilar(it, cleaned) }
            if (!isDuplicate) distinctPoints.add(cleaned)
            if (distinctPoints.size >= MAX_POINTS) break
        }

        val digest = distinctPoints.joinToString(" • ")
        return "$digest (compressed from ${memories.size} entries)"
    }

    /** Cheap similarity check — same normalized text or one fully contains the other. */
    private fun isSimilar(a: String, b: String): Boolean {
        val na = a.lowercase().trim()
        val nb = b.lowercase().trim()
        if (na == nb) return true
        if (na.length > 10 && nb.contains(na)) return true
        if (nb.length > 10 && na.contains(nb)) return true
        return false
    }
}
