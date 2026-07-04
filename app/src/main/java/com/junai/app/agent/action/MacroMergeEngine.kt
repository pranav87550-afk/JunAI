package com.junai.app.agent.action

import com.junai.app.learning.RecordedStep

/**
 * IMPROVEMENT (multi-demo merge): a SINGLE demonstration has no way to tell
 * "the user meant to do this" apart from "the OS/screen glitched and this
 * got captured by accident" (e.g. ActionEngine's retry-duplicate bug, a
 * stray back-navigation, a mistimed tap while the screen was still
 * settling). Every fix so far has patched a specific KNOWN glitch after the
 * fact; this instead makes the recorder structurally resistant to unknown
 * ones: ask for the same task 2 (sometimes 3) times, and only keep the
 * steps that show up in every single demo. A one-off mistake essentially
 * never repeats identically across independent demonstrations, so this
 * filters noise without needing to know in advance what kind of noise it
 * will be.
 */
object MacroMergeEngine {

    data class MergeResult(
        val steps: List<RecordedStep>,
        val keptCount: Int,
        val discardedCount: Int,
        val overlapRatio: Float,
        // true => the 2 demos disagreed too much (or a typed value had no
        // clear majority) to safely finalize; caller should ask for one
        // more demo and re-merge with all 3 before saving anything.
        val needsTieBreaker: Boolean
    )

    /** Identity used to decide "is this the same real-world step" across demos — deliberately ignores bounds (can drift a few px between runs) and typedText (compared/voted on separately below). */
    private fun key(step: RecordedStep): String =
        "${step.actionType}|${step.packageName}|${step.resourceId ?: step.text ?: step.contentDescription ?: step.className}"

    /**
     * Longest-common-subsequence alignment between two step lists, by
     * canonical [key]. Using LCS (not a plain index-by-index compare)
     * means a step that's simply shifted by one position in one of the
     * demos (e.g. an extra stray tap before it) still matches correctly,
     * instead of falsely breaking alignment for everything after it.
     */
    private fun lcsPairs(a: List<RecordedStep>, b: List<RecordedStep>): List<Pair<Int, Int>> {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return emptyList()
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (key(a[i]) == key(b[j])) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val pairs = mutableListOf<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                key(a[i]) == key(b[j]) -> { pairs.add(i to j); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return pairs
    }

    /**
     * Merges 2 or 3 demonstrations of the same task into one clean macro.
     * The longest demo is used as the ordering backbone (most likely to be
     * the uninterrupted full sequence, rather than a run where a step got
     * dropped because the screen hadn't loaded yet). A backbone step
     * survives only if a matching step is found in EVERY other demo.
     */
    fun merge(demos: List<List<RecordedStep>>): MergeResult {
        require(demos.isNotEmpty()) { "need at least 1 demo" }
        if (demos.size == 1) {
            return MergeResult(demos[0], demos[0].size, 0, 1f, needsTieBreaker = false)
        }

        val backbone = demos.maxByOrNull { it.size }!!
        val others = demos.filter { it !== backbone }

        val matchedInAll = BooleanArray(backbone.size) { true }
        // backbone index -> typedText values seen for that step across the other demos
        val typedTextVotes = HashMap<Int, MutableList<String>>()

        for (other in others) {
            val pairs = lcsPairs(backbone, other)
            val matchedBackboneIdx = pairs.map { it.first }.toHashSet()
            for (idx in backbone.indices) {
                if (idx !in matchedBackboneIdx) matchedInAll[idx] = false
            }
            for ((bi, oi) in pairs) {
                other[oi].typedText?.let { t ->
                    typedTextVotes.getOrPut(bi) { mutableListOf() }.add(t)
                }
            }
        }

        val kept = mutableListOf<RecordedStep>()
        var splitVote = false
        for (idx in backbone.indices) {
            if (!matchedInAll[idx]) continue
            var step = backbone[idx]
            if (step.actionType == "TYPE") {
                val votes = (typedTextVotes[idx] ?: mutableListOf()).also { it.add(step.typedText ?: "") }
                val counts = votes.groupingBy { it }.eachCount()
                val topCount = counts.values.maxOrNull() ?: 0
                val winners = counts.filterValues { it == topCount }.keys
                if (winners.size > 1 && demos.size == 2) {
                    // 2 demos disagree on what was typed here with no
                    // majority — don't guess, flag for a tie-breaker demo.
                    splitVote = true
                } else {
                    val winner = winners.firstOrNull()
                    if (winner != null && winner.isNotBlank() && winner != step.typedText) {
                        step = step.copy(typedText = winner)
                    }
                }
            }
            kept.add(step)
        }

        val discarded = backbone.size - kept.size
        val overlap = if (backbone.isEmpty()) 1f else kept.size.toFloat() / backbone.size
        // Less than 60% of the longest demo survived intersection => the
        // 2 demos disagreed too much to trust silently; ask for a 3rd.
        val needsTieBreaker = demos.size == 2 && (overlap < 0.6f || splitVote)

        return MergeResult(kept, kept.size, discarded, overlap, needsTieBreaker)
    }
}
