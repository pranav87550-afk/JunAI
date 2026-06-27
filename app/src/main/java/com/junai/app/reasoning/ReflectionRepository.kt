package com.junai.app.reasoning

import android.content.Context
import com.junai.app.AppDatabase
import com.junai.app.LearningRepository
import com.junai.app.memory.KnowledgeGraphRepository
import com.junai.app.memory.SemanticMemoryRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ReflectionRepository — Gathers real numbers from existing repositories
 * (LearningRepository's stats/failures, SemanticMemoryRepository's fact
 * count, KnowledgeGraphRepository's edge count), computes deltas against
 * the PREVIOUS reflection, and feeds them into ReflectionEngine.
 *
 * Runs at most once per calendar day (checked via date string, not a timer).
 */
class ReflectionRepository(
    context: Context,
    private val learningRepo: LearningRepository,
    private val semanticMemoryRepo: SemanticMemoryRepository,
    private val knowledgeGraphRepo: KnowledgeGraphRepository
) {
    private val dao = AppDatabase.getInstance(context).reflectionDao()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun todayKey(): String = dateFormat.format(Date())

    /** Generates and stores today's reflection if one doesn't already exist for today. */
    suspend fun runDailyReflectionIfNeeded() {
        val today = todayKey()
        if (dao.getByDate(today) != null) return  // already reflected today

        val previous = dao.getLatest()
        val sinceTimestamp = previous?.timestamp ?: 0L

        // Only failures logged SINCE the last reflection count as "new" for today
        val newFailures = learningRepo.getFailureLog()
            .filter { it.timestamp >= sinceTimestamp }
            .map { ReflectionEngine.FailureSnapshot(it.question, it.failureReason) }

        val currentFactsCount = semanticMemoryRepo.getAllFacts().size
        val currentEdgesCount = knowledgeGraphRepo.getAllEdges().size
        val stats = learningRepo.getStats()
        val currentTotalQueries = stats?.totalQueries ?: 0
        val currentFailedQueries = stats?.failedQueries ?: 0

        val newFacts = (currentFactsCount - (previous?.factsCountSnapshot ?: 0)).coerceAtLeast(0)
        val newEdges = (currentEdgesCount - (previous?.edgesCountSnapshot ?: 0)).coerceAtLeast(0)
        val previousCorrect = (previous?.totalQueriesSnapshot ?: 0) - (previous?.failedQueriesSnapshot ?: 0)
        val currentCorrect = currentTotalQueries - currentFailedQueries
        val newCorrect = (currentCorrect - previousCorrect).coerceAtLeast(0)

        val input = ReflectionEngine.ReflectionInput(
            newFactsLearned = newFacts,
            newRelationsLearned = newEdges,
            questionsAnsweredCorrectly = newCorrect,
            failures = newFailures
        )

        val result = ReflectionEngine.generate(input)

        dao.insert(
            ReflectionEntity(
                date = today,
                learnedSummary = result.learnedSummary,
                failureSummary = result.failureSummary,
                patternsSummary = result.patternsSummary,
                improvementSuggestion = result.improvementSuggestion,
                factsCountSnapshot = currentFactsCount,
                edgesCountSnapshot = currentEdgesCount,
                totalQueriesSnapshot = currentTotalQueries,
                failedQueriesSnapshot = currentFailedQueries
            )
        )
    }

    suspend fun getLatest(): ReflectionEntity? = dao.getLatest()

    /** Human-readable version for chat replies. Null if no reflection exists yet. */
    suspend fun formatLatestForChat(): String? {
        val latest = dao.getLatest() ?: return null
        return "\uD83D\uDCDD Reflection (${latest.date}):\n" +
                "• Learned: ${latest.learnedSummary}\n" +
                "• Failures: ${latest.failureSummary}\n" +
                "• Patterns: ${latest.patternsSummary}\n" +
                "• Improvement: ${latest.improvementSuggestion}"
    }
}
