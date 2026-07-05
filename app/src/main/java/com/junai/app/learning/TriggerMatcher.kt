package com.junai.app.learning

/**
 * TriggerMatcher — Phase 3 (confidence-aware trigger matching).
 *
 * findByTrigger() in RecordedMacroDao requires an exact normalized-string
 * match. This is the fallback for when that misses: compute a similarity
 * score against every stored triggerPhrase and, if the best one clears
 * FUZZY_MATCH_THRESHOLD, the caller asks the user to confirm before
 * replaying — never replays silently off a fuzzy guess.
 *
 * Deliberately dependency-free (no NLP library) per the phase's own
 * ground rule — Levenshtein edit-distance normalized by the longer
 * string's length is simple, has no external deps, and works well for
 * short command-style phrases (typos, one word off, etc).
 */
object TriggerMatcher {

    /**
     * Minimum similarity (0.0–1.0) before we even suggest a fuzzy match.
     * Named constant so it's easy to tune later without hunting through
     * call sites — start conservative (high bar) since a wrong suggestion
     * is more annoying than an occasional missed one; the user can always
     * just retry with the exact phrase.
     */
    const val FUZZY_MATCH_THRESHOLD = 0.72

    /**
     * Words/short phrases that count as "yes, go ahead" when replying to a
     * fuzzy-match confirmation ("Ye lagta hai '<phrase>' — chalayu?").
     * Deliberately a plain whitelist, not a classifier — matches
     * exactly or as the leading word(s) of the reply (e.g. "haan kar do"),
     * so a reply like "haanji lekin ruk jao" would need to actually say
     * one of these as its lead-in; anything else is treated as NOT a yes
     * and the suggestion is simply dropped rather than guessed at.
     */
    private val AFFIRMATIVE_WORDS = listOf(
        "haan", "han", "haa", "ha", "haanji", "ji haan",
        "yes", "yep", "yup", "yeah", "sure",
        "ok", "okay", "theek hai", "thik hai", "sahi hai",
        "chalao", "chala do", "kar do", "kardo"
    )

    data class Match(val macro: RecordedMacroEntity, val score: Double)

    /**
     * Best-scoring candidate above FUZZY_MATCH_THRESHOLD, or null if
     * nothing clears the bar (including when candidates is empty).
     */
    fun bestMatch(input: String, candidates: List<RecordedMacroEntity>): Match? {
        val normalizedInput = input.lowercase().trim()
        if (normalizedInput.isEmpty() || candidates.isEmpty()) return null

        var best: Match? = null
        for (macro in candidates) {
            val score = similarity(normalizedInput, macro.triggerPhrase)
            if (best == null || score > best!!.score) {
                best = Match(macro, score)
            }
        }
        return best?.takeIf { it.score >= FUZZY_MATCH_THRESHOLD }
    }

    /** True if `text` is a recognized "yes, go ahead" reply — see AFFIRMATIVE_WORDS doc above. */
    fun isAffirmative(text: String): Boolean {
        val normalized = text.lowercase().trim().trimEnd('.', '!', '?')
        return AFFIRMATIVE_WORDS.any { word ->
            normalized == word || normalized.startsWith("$word ")
        }
    }

    /** 1.0 = identical, 0.0 = completely different, based on normalized Levenshtein distance. */
    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        val distance = levenshtein(a, b)
        return 1.0 - (distance.toDouble() / maxLen)
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j - 1], dp[i - 1][j], dp[i][j - 1])
                }
            }
        }
        return dp[a.length][b.length]
    }
}
