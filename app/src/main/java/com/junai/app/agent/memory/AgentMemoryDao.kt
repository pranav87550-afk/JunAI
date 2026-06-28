package com.junai.app.agent.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface AgentMemoryDao {

    @Insert
    suspend fun insert(memory: AgentMemoryEntity): Long

    @Update
    suspend fun update(memory: AgentMemoryEntity)

    @Query("SELECT * FROM agent_memory WHERE id = :id")
    suspend fun getById(id: Int): AgentMemoryEntity?

    @Query("SELECT * FROM agent_memory ORDER BY timestamp DESC")
    suspend fun getAll(): List<AgentMemoryEntity>

    @Query("SELECT * FROM agent_memory WHERE wasSuccessful = 1 ORDER BY timestamp DESC")
    suspend fun getSuccessful(): List<AgentMemoryEntity>

    @Query("SELECT * FROM agent_memory WHERE wasSuccessful = 0 ORDER BY timestamp DESC")
    suspend fun getFailed(): List<AgentMemoryEntity>

    // Fuzzy match against past goals — lets AgentEngine reuse a workflow
    // that succeeded for a similar request before.
    @Query("SELECT * FROM agent_memory WHERE goalText LIKE '%' || :keyword || '%' ORDER BY timestamp DESC")
    suspend fun getByGoalKeyword(keyword: String): List<AgentMemoryEntity>

    @Query("SELECT * FROM agent_memory WHERE goalText LIKE '%' || :keyword || '%' AND wasSuccessful = 1 ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastSuccessfulFor(keyword: String): AgentMemoryEntity?

    @Query("SELECT * FROM agent_memory ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<AgentMemoryEntity>

    @Query("DELETE FROM agent_memory WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("DELETE FROM agent_memory WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)

    @Query("SELECT COUNT(*) FROM agent_memory")
    suspend fun count(): Int
}
