package com.junai.app.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface MemoryDao {

    @Insert
    suspend fun insert(memory: MemoryEntity): Long

    @Update
    suspend fun update(memory: MemoryEntity)

    @Query("SELECT * FROM memory_items WHERE id = :id")
    suspend fun getById(id: Int): MemoryEntity?

    @Query("SELECT * FROM memory_items WHERE memoryType = :type ORDER BY timestamp DESC")
    suspend fun getByType(type: String): List<MemoryEntity>

    @Query("SELECT * FROM memory_items WHERE memoryType = 'SHORT_TERM' ORDER BY timestamp DESC")
    suspend fun getShortTerm(): List<MemoryEntity>

    @Query("SELECT * FROM memory_items WHERE memoryType = 'LONG_TERM' ORDER BY importance DESC")
    suspend fun getLongTerm(): List<MemoryEntity>

    @Query("SELECT * FROM memory_items WHERE memoryType = 'WORKING' ORDER BY lastAccessed DESC")
    suspend fun getWorking(): List<MemoryEntity>

    @Query("SELECT * FROM memory_items WHERE memoryType = 'EPISODIC' ORDER BY timestamp DESC")
    suspend fun getEpisodic(): List<MemoryEntity>

    // Candidates for SHORT_TERM -> LONG_TERM promotion
    @Query("SELECT * FROM memory_items WHERE memoryType = 'SHORT_TERM' AND importance >= :threshold")
    suspend fun getPromotionCandidates(threshold: Float): List<MemoryEntity>

    // Candidates for forgetting (low importance, old, rarely accessed)
    @Query("SELECT * FROM memory_items WHERE memoryType = 'SHORT_TERM' AND importance < :threshold AND timestamp < :olderThan")
    suspend fun getForgettingCandidates(threshold: Float, olderThan: Long): List<MemoryEntity>

    @Query("SELECT * FROM memory_items WHERE category = :category ORDER BY importance DESC")
    suspend fun getByCategory(category: String): List<MemoryEntity>

    @Query("SELECT * FROM memory_items WHERE tags LIKE '%' || :tag || '%'")
    suspend fun getByTag(tag: String): List<MemoryEntity>

    @Query("UPDATE memory_items SET memoryType = :newType WHERE id = :id")
    suspend fun changeType(id: Int, newType: String)

    @Query("UPDATE memory_items SET accessCount = accessCount + 1, lastAccessed = :now WHERE id = :id")
    suspend fun recordAccess(id: Int, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM memory_items WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("SELECT COUNT(*) FROM memory_items WHERE memoryType = :type")
    suspend fun countByType(type: String): Int
}
