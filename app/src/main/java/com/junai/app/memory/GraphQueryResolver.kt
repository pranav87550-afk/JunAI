package com.junai.app.memory

/**
 * GraphQueryResolver — Parses graph-style questions into a query type,
 * mirroring the SemanticQueryResolver pattern from Phase 4. Pure text
 * parsing, no DB access — KnowledgeGraphRepository does the actual lookup.
 */
object GraphQueryResolver {

    sealed class GraphQuery {
        data class RelatedTo(val nodeA: String, val nodeB: String) : GraphQuery()
        data class UsedFor(val node: String) : GraphQuery()
        data class AboutConcept(val node: String) : GraphQuery()
    }

    private val relatedToPatterns = listOf(
        Regex("how (?:is|are) (.+?) (?:and )?related to (.+?)[?.]?$"),
        Regex("how (?:is|are) (.+?) and (.+?) related[?.]?$")
    )

    private val usedForPatterns = listOf(
        Regex("what is (.+?) used for[?.]?$"),
        Regex("what's (.+?) used for[?.]?$")
    )

    private val aboutPatterns = listOf(
        Regex("tell me about (.+?)[?.]?$"),
        Regex("what do you know about (.+?)[?.]?$")
    )

    /** Returns null if the text doesn't match any graph-question shape. */
    fun resolve(text: String): GraphQuery? {
        val lower = text.lowercase().trim()

        for (pattern in relatedToPatterns) {
            val match = pattern.find(lower)
            if (match != null && match.groupValues.size >= 3) {
                val a = clean(match.groupValues[1])
                val b = clean(match.groupValues[2])
                if (a.isNotBlank() && b.isNotBlank()) return GraphQuery.RelatedTo(a, b)
            }
        }

        for (pattern in usedForPatterns) {
            val match = pattern.find(lower)
            if (match != null) {
                val node = clean(match.groupValues[1])
                if (node.isNotBlank()) return GraphQuery.UsedFor(node)
            }
        }

        for (pattern in aboutPatterns) {
            val match = pattern.find(lower)
            if (match != null) {
                val node = clean(match.groupValues[1])
                if (node.isNotBlank()) return GraphQuery.AboutConcept(node)
            }
        }

        return null
    }

    private fun clean(text: String): String {
        return text.trim()
            .removePrefix("a ").removePrefix("an ").removePrefix("the ")
            .trim(' ', '?', '.', '!')
    }
}
