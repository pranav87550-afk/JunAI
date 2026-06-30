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

    @Query("SELECT * FROM agent_tasks ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<AgentTaskEntity>

    @Query("DELETE FROM agent_tasks WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("DELETE FROM agent_tasks WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)
}
