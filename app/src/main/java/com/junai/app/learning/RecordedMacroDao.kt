package com.junai.app.learning

import androidx.room.*

@Dao
interface RecordedMacroDao {

    @Query("SELECT * FROM recorded_macros ORDER BY createdAt DESC")
    suspend fun getAll(): List<RecordedMacroEntity>

    @Query("SELECT * FROM recorded_macros WHERE triggerPhrase = :phrase LIMIT 1")
    suspend fun findByTrigger(phrase: String): RecordedMacroEntity?

    @Insert
    suspend fun insert(macro: RecordedMacroEntity): Long

    @Query("DELETE FROM recorded_macros WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("UPDATE recorded_macros SET lastUsedAt = :timestamp, timesReplayed = timesReplayed + 1 WHERE id = :id")
    suspend fun markReplayed(id: Int, timestamp: Long)

    @Query("SELECT * FROM recorded_macros WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): RecordedMacroEntity?

    // Used by "dobara demonstrate karo" (Execute tab redo) — replaces an
    // existing macro's steps in place, keeping its id/trigger/usage stats,
    // instead of inserting a duplicate row for the same trigger phrase.
    @Query("UPDATE recorded_macros SET stepsJson = :stepsJson, stepCount = :stepCount WHERE id = :id")
    suspend fun updateSteps(id: Int, stepsJson: String, stepCount: Int)
}
