package com.junai.app.learning

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * PHASE 4: per-step replay outcome log.
 *
 * Deliberately lightweight — one row per step attempt during a macro
 * replay, not a general-purpose event/analytics table. The whole point is
 * answering two very specific questions later (from Learning Center or a
 * future "why did this macro break" flow):
 *   1. Which step in a given macro tends to fail, and how?
 *   2. Which match strategy (id/text/description/bounds) is actually
 *      winning in practice — useful signal for tuning findBestMatchingNode's
 *      scoring weights down the line, without guessing.
 *
 * Not indexed/joined against RecordedMacroEntity via a foreign key on
 * purpose — macroId is stored as a plain Int so a step outcome survives
 * even if the macro itself gets deleted later re-recorded with a new id;
 * losing that history isn't worth the cascade-delete complexity here.
 */
@Entity(tableName = "step_outcomes")
data class StepOutcomeEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val macroId: Int,
    val stepIndex: Int,
    val actionType: String,       // TAP, LONG_PRESS, TYPE, SWIPE — mirrors RecordedStep.actionType
    val success: Boolean,
    val matchedVia: String?,      // "id" | "text" | "description" | "bounds" | null (no node found at all)
    val usedAlternate: Boolean = false, // true if this succeeded on the second-best candidate after the top one failed
    val failureReason: String?,   // e.g. "no_node_found", "action_failed", "node_stale" — null when success
    val timestamp: Long
)

@Dao
interface StepOutcomeDao {

    @Insert
    suspend fun insert(outcome: StepOutcomeEntity): Long

    /** Full history for one macro, oldest first — for a future "step N keeps failing" view. */
    @Query("SELECT * FROM step_outcomes WHERE macroId = :macroId ORDER BY timestamp ASC")
    suspend fun getForMacro(macroId: Int): List<StepOutcomeEntity>

    /** Most recent failures across all macros, newest first — capped since this is for a quick glance, not a report. */
    @Query("SELECT * FROM step_outcomes WHERE success = 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentFailures(limit: Int = 50): List<StepOutcomeEntity>

    /** Housekeeping — this table grows one row per step per replay, so LearningCenterActivity (or similar) can periodically trim it. */
    @Query("DELETE FROM step_outcomes WHERE timestamp < :olderThan")
    suspend fun pruneOlderThan(olderThan: Long)
}
