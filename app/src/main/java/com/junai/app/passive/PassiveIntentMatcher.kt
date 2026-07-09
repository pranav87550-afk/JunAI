package com.junai.app.passive

/**
 * Passive Learning — Phase 5, intent-matching half.
 *
 * Deliberately its OWN file, separate from [PassivePathFinder] — this is
 * only "given some free-text user intent and a list of learned elements,
 * which element are they probably talking about," nothing about graphs,
 * screens, or paths. [PassivePathFinder] is the only caller.
 *
 * Per the Phase 5 design note: reuses the same shape of scoring as
 * [com.junai.app.learning.TriggerMatcher] (dependency-free, no NLP lib)
 * rather than building a separate NLU layer — but the actual matching
 * problem here is different enough to need its own function, not a
 * direct call into TriggerMatcher: TriggerMatcher compares one phrase
 * against a whole other phrase (a trigger), while this compares a
 * short element label (e.g. "Send", "Pay", "Search contacts") against
 * it appearing SOMEWHERE inside a longer free-text sentence (e.g.
 * "paytm khol ke paisa bhejo").
 */
object PassiveIntentMatcher {

    /** Same conservative-by-default spirit as TriggerMatcher.FUZZY_MATCH_THRESHOLD — a wrong guess costs more than a missed one. */
    const val MATCH_THRESHOLD = 0.55

    data class ElementMatch(val element: PassiveElementEntity, val score: Double)

    /**
     * Best-scoring element whose label plausibly appears in [text], or
     * null if nothing clears [MATCH_THRESHOLD] (including when
     * [candidates] is empty). Sensitive elements are never passed in here
     * in the first place — see [PassivePathFinder], not this function's
     * concern to re-check.
     */
    fun findTarget(text: String, candidates: List<PassiveElementEntity>): ElementMatch? {
        val words = normalize(text)
        if (words.isEmpty() || candidates.isEmpty()) return null

        var best: ElementMatch? = null
        for (element in candidates) {
            val label = element.text ?: element.contentDescription ?: continue
            val score = scoreLabelAgainstText(label, words)
            if (best == null || score > best!!.score) {
                best = ElementMatch(element, score)
            }
        }
        return best?.takeIf { it.score >= MATCH_THRESHOLD }
    }

    private fun normalize(text: String): List<String> =
        text.lowercase().trim().split(Regex("\\s+")).filter { it.isNotBlank() }

    /**
     * 1.0 = the label's words all appear in the text, in order, as a
     * contiguous substring. Otherwise scaled down by how much of the
     * label's words are present anywhere in the text (order-independent
     * partial credit), with a small bonus for exact substring containment
     * since that's a much stronger signal than scattered word overlap.
     */
    private fun scoreLabelAgainstText(label: String, textWords: List<String>): Double {
        val labelWords = normalize(label)
        if (labelWords.isEmpty()) return 0.0

        val textJoined = textWords.joinToString(" ")
        val labelJoined = labelWords.joinToString(" ")
        if (textJoined.contains(labelJoined)) return 1.0

        val textWordSet = textWords.toSet()
        val overlap = labelWords.count { it in textWordSet }
        return overlap.toDouble() / labelWords.size
    }
}
