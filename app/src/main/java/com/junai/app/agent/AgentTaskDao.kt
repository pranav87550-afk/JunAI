package com.junai.app.agent

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * AgentTaskDao — Room DAO for [AgentTaskEntity].
 *
 * Moved into its own file (previously lived inside MultiStepTaskManager.kt)
 * for the same reason as AgentTaskEntity: AppDatabase.kt imports it as
 * `com.junai.app.agent.AgentTaskDao`, and Room's annotation processor
 * generates a concrete `AgentTaskDao_Impl` class per @Dao-annotated file —
 * it needs this interface to live in its own compilation unit.
 *
 * MultiStepTaskManager is still the only caller of this DAO.
 */
@Dao
interface AgentTaskDao {
    @Insert
    suspend fun insert(task: AgentTaskEntity): Long

    @Update
    suspend fun update(task: AgentTaskEntity)

    @Query("SELECT * FROM agent_tasks WHERE id = :id")
    suspend fun getById(id: Int): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks WHERE status = 'PAUSED' ORDER BY timestamp DESC LIMIT 1")
    suspend fun getMostRecentPaused(): AgentTaskEntity?

    // BUGFIX (Phase 1c): AgentEngine.isTaskRunning() is an in-memory,
    // per-instance check (currentTaskId on whatever AgentEngine object
    // ChatIntentHandler owns for MainActivity) — LearningCenterActivity is
    // a separate Activity with no reference to that instance, so it had no
    // way to know "is a live agent task running right now" before starting
    // a recording. This table is the actual DB-backed source of truth
    // (RUNNING/PAUSED/etc are all persisted here), so it's reachable from
    // any Activity in the app, unlike the in-memory field. The recency
    // window (timestamp within :sinceMillis) guards against a task that
    // crashed mid-flight and never transitioned out of RUNNING — without
    // it, one stuck row would block recording forever instead of just
    // during an actual in-progress task.
    @Query("SELECT EXISTS(SELECT 1 FROM agent_tasks WHERE status = 'RUNNING' AND timestamp > :sinceMillis)")
    suspend fun hasRecentRunningTask(sinceMillis: Long): Boolean

    @Query("SELECT * FROM agent_tasks ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<AgentTaskEntity>

    @Query("DELETE FROM agent_tasks WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("DELETE FROM agent_tasks WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)
}
