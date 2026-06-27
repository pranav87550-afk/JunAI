package com.junai.app.reasoning

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ReflectionEntity — One day's self-reflection log.
 *
 * Snapshot fields (factsCountSnapshot, edgesCountSnapshot, etc.) store the
 * CUMULATIVE totals at the time this reflection ran — not deltas. The delta
 * ("what's new since yesterday") is computed by ReflectionRepository by
 * comparing today's totals against the PREVIOUS reflection's snapshot.
 * This avoids needing new timestamp-range queries on every existing table.
 */
@Entity(tableName = "reflection_logs")
data class ReflectionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val date: String,                  // yyyy-MM-dd
    val learnedSummary: String,
    val failureSummary: String,
    val patternsSummary: String,
    val improvementSuggestion: String,
    val factsCountSnapshot: Int = 0,
    val edgesCountSnapshot: Int = 0,
    val totalQueriesSnapshot: Int = 0,
    val failedQueriesSnapshot: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)
